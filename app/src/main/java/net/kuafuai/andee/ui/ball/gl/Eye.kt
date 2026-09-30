package net.kuafuai.andee.ui.ball.gl

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * An eye whose **top is cut off flat** — a [Capsule] with the top sliced away.
 *
 * The point is that the flat edge *is* the brow. On this ball a white eye on a
 * dark body already carries the maximum contrast the screen can show, so an
 * angry eye does not need a second shape drawn beside it at all: cut the eye's
 * own top at an angle and the top edge reads as a brow that has been pulled
 * down over the eye. That is the classic drawn-villain eye, and it costs one
 * mesh instead of one mesh plus one mark plus a colour argument about whether
 * that mark can be seen.
 *
 * It also removes a trap. Anything drawn *over* the eye to cut it has to be
 * invisible against the body at the same time as it is visible against the eye
 * — two contradictory jobs, because the body is lit and a flat draw is not (see
 * [BallLook.browShade], whose first value was wrong by exactly that). A cut in
 * the geometry has no colour to get wrong.
 *
 * Built like [Capsule] — rings along Y, bottom pole first, with a cylindrical
 * band in the middle — except that the sweep stops at the cut and the last ring
 * is closed by a flat disc instead of a cap.
 *
 * ## The angle is not in here
 *
 * A flat cut horizontally across a round eye is a *sleepy* eye. What makes it
 * read as a brow is that the line is **slanted**, and that comes from the
 * caller rolling the whole eye (`BallLook.eyeTilt`, plus the mood's own
 * `brow`). Rotating the mesh in the draw call rather than shearing it here
 * keeps this generator a shape and the look table a pose, and it means the cut
 * angle survives being animated.
 *
 * @param radius half-width of the eye.
 * @param cylinderHeight the straight section, as in [Capsule].
 * @param topCut how much of the eye's upper half is removed, as a fraction of
 *   the half-height: 0 = the untouched capsule, 0.5 = the top edge sits halfway
 *   between the centre and the natural top. Clamped to `0..`[MAX_TOP_CUT].
 * @param capSegs rings across each hemisphere.
 * @param radialSegs vertices around each ring.
 */
object Eye {
    /**
     * The most of the eye's upper half [build] will remove.
     *
     * This is a **design** bound, not a geometric one — at 0.85 the surviving
     * aperture is still a valid mesh (the cut sits at 15% of the half-height,
     * above the centre line). Past it the flat edge drops below the eye's own
     * centre and the shape stops reading as a lidded eye at all.
     *
     * It is deliberately *not* the bound that matters most in practice. The
     * tighter one is whatever is drawn *inside* the eye: the cut only slices
     * the eye's own geometry, and a pupil is a separate disc drawn in front of
     * it, so a cut below the pupil's top leaves a dark blob standing up out of
     * a flat lid. That ceiling depends on [BallLook.pupilR], so it lives on
     * [BallLook.eyeCut] where the pupil is in scope.
     */
    const val MAX_TOP_CUT = 0.85f

    fun build(
        radius: Float,
        cylinderHeight: Float,
        topCut: Float,
        capSegs: Int = 4,
        radialSegs: Int = 12,
    ): Mesh {
        val half = cylinderHeight / 2f
        val halfHeight = radius + half
        val cut = topCut.coerceIn(0f, MAX_TOP_CUT)
        val yCut = halfHeight * (1f - cut)

        // The rings, as (y, r), bottom pole → cut. Collected into lists rather
        // than pre-counted into arrays: the ring count depends on where the cut
        // lands (on the top hemisphere or on the cylindrical band), and getting
        // that count wrong is the silent typed-array overflow this codebase has
        // been bitten by before — a FloatArray that is one ring short does not
        // throw, it drops the vertices and you get a mesh with a hole in it.
        // The lists cost a few hundred floats at build time and cannot be off
        // by one.
        val ys = ArrayList<Float>(2 * capSegs + 3)
        val rs = ArrayList<Float>(2 * capSegs + 3)

        // Bottom hemisphere: phi π → π/2, i.e. the pole first, ending exactly on
        // the lower equator.
        for (i in 0..capSegs) {
            val phi = PI.toFloat() - (i.toFloat() / capSegs) * (PI.toFloat() / 2f)
            ys.add(radius * cos(phi) - half)
            rs.add(radius * sin(phi))
        }

        if (yCut > half) {
            // Cut through the dome. The lower equator is the ring just added, so
            // add the upper one and let the quads between them be the cylinder
            // wall — exactly how [Capsule] does it — then sweep the dome up to
            // the cut.
            ys.add(half)
            rs.add(radius)
            val phiCut = acos(((yCut - half) / radius).coerceIn(-1f, 1f))
            for (i in 1..capSegs) {
                val phi = (PI.toFloat() / 2f) -
                        (i.toFloat() / capSegs) * (PI.toFloat() / 2f - phiCut)
                ys.add(radius * cos(phi) + half)
                rs.add(radius * sin(phi))
            }
        } else {
            // Cut at or below the upper equator: the band runs straight from the
            // lower equator to the cut and there is no dome left to sweep.
            //
            // The upper equator ring is *skipped*, not added and then followed
            // by the cut. Adding it would put a ring above the one after it,
            // which folds the strip back on itself — and a folded strip is not
            // an error, it is a mesh that renders inside out. This branch is
            // reachable at real values: [IMP]'s eye has a half-height of 0.1755
            // against half 0.0275, so any cut above 0.843 lands here.
            ys.add(yCut)
            rs.add(radius)
        }

        val ringCount = ys.size
        val stride = radialSegs + 1
        // The rings, plus the cut disc's own ring, plus its centre vertex.
        val vertCount = ringCount * stride + stride + 1
        val positions = FloatArray(vertCount * 3)
        val normals = FloatArray(vertCount * 3)
        val uvs = FloatArray(vertCount * 2)

        var p = 0
        var u = 0
        for (i in 0 until ringCount) {
            val y = ys[i]
            val r = rs[i]
            // Which sphere this ring belongs to decides the normal. The equator
            // rings land on the boundaries and both reduce to the radial
            // direction, so the formula is continuous across them.
            val cy = if (y <= -half) -half else if (y >= half) half else 0f
            val onCylinder = y > -half && y < half
            for (j in 0..radialSegs) {
                val theta = (j.toFloat() / radialSegs) * 2f * PI.toFloat()
                val nx = -cos(theta)
                val nz = sin(theta)
                positions[p] = r * nx
                positions[p + 1] = y
                positions[p + 2] = r * nz
                if (onCylinder) {
                    normals[p] = nx
                    normals[p + 1] = 0f
                    normals[p + 2] = nz
                } else {
                    normals[p] = nx * r / radius
                    normals[p + 1] = (y - cy) / radius
                    normals[p + 2] = nz * r / radius
                }
                uvs[u] = j.toFloat() / radialSegs
                uvs[u + 1] = 1f - i.toFloat() / (ringCount - 1).coerceAtLeast(1)
                p += 3; u += 2
            }
        }

        // The cut face, on a ring of its own rather than reusing the sweep's
        // last one. The last ring carries the *dome's* normal, so a disc that
        // inherited it would be a flat lid shaded as though it were still
        // curved — a bright smudge across the top of an eye. The eye is drawn
        // with the flat shader today and never reads a normal, which is exactly
        // why this is worth getting right now rather than after something
        // switches it to the lit one.
        val capRing = ringCount * stride
        val capCenter = capRing + stride
        val rCut = rs[ringCount - 1]
        positions[capCenter * 3] = 0f
        positions[capCenter * 3 + 1] = yCut
        positions[capCenter * 3 + 2] = 0f
        normals[capCenter * 3 + 1] = 1f
        uvs[capCenter * 2] = 0.5f
        uvs[capCenter * 2 + 1] = 1f
        for (j in 0..radialSegs) {
            val theta = (j.toFloat() / radialSegs) * 2f * PI.toFloat()
            val nx = -cos(theta)
            val nz = sin(theta)
            val v = capRing + j
            positions[v * 3] = rCut * nx
            positions[v * 3 + 1] = yCut
            positions[v * 3 + 2] = rCut * nz
            normals[v * 3 + 1] = 1f
            uvs[v * 2] = j.toFloat() / radialSegs
            uvs[v * 2 + 1] = 0f
        }

        val idx = ShortArray((ringCount - 1) * radialSegs * 6 + radialSegs * 3)
        var w = 0
        for (r in 0 until ringCount - 1) {
            for (s in 0 until radialSegs) {
                val a = r * stride + s
                val b = r * stride + s + 1
                val c = (r + 1) * stride + s + 1
                val d = (r + 1) * stride + s
                idx[w++] = a.toShort(); idx[w++] = b.toShort(); idx[w++] = c.toShort()
                idx[w++] = a.toShort(); idx[w++] = c.toShort(); idx[w++] = d.toShort()
            }
        }
        for (s in 0 until radialSegs) {
            idx[w++] = capCenter.toShort()
            idx[w++] = (capRing + s).toShort()
            idx[w++] = (capRing + s + 1).toShort()
        }
        return Mesh(positions, normals, uvs, idx)
    }
}
