package net.kuafuai.andee.ui.ball

import net.kuafuai.andee.R

/**
 * Everything that makes one ball a different *character* from another.
 *
 * Mostly that is how it is drawn — the material it is made of, the shape of its
 * face, the weight of its outline. Behaviour is not here: which mood is showing,
 * how it breathes, when it fidgets, when it clings to the ledge lives in
 * [EmotionState], [Mood] and [EmotionBallRenderer].res`.resolveMood`.
 *
 * The two exceptions are [voice] and [persona], at the bottom, and they are the
 * reason this KDoc no longer says "this file is only the surface". A look the
 * user can swipe to is a character they are choosing, and a character that looks
 * like an imp and then answers in the house voice with the house manner is a
 * costume. See those two fields for what each one can and cannot reach.
 *
 * It exists because the ball has several looks that are worth keeping, and
 * swapping between them used to mean editing constants in four files:
 *
 *  - [BAJIE] — a pink pig: muzzle and nostrils, floppy ears, small dark bead
 *    eyes. The first look that reads as an *animal* rather than a material, and
 *    the one that carries the grumbly-but-diligent manner and a man's voice.
 *  - [CREAM] — pale, glossy-but-soft, dark bead eyes with a wet catchlight, a
 *    pair of upright ears, and a hard dark outline instead of a glow. Reads as
 *    a mascot rather than an appliance, at the cost of being a different
 *    character from the one the window was designed for: a pale ball needs the
 *    outline to survive over a light app, which is why [rimDark] exists.
 *  - [IMP] — the same ball wearing horns, a tail, a row of teeth, slit pupils
 *    and a glare cut out of its own eyes. It is the first look that adds
 *    *geometry* rather than only numbers, and the fields that carry it
 *    ([hornH], [tailLen], [toothCount], [eyeCut], [eyeTilt], [tongueR],
 *    [pupilAspect]) are all zero-is-off so the other looks are untouched —
 *    [tongueR] included, which [IMP] itself leaves off. The glare is [eyeCut]
 *    and not [browW]: the flat top edge of the eye *is* the brow, which is one
 *    shape instead of two and cannot go invisible the way a mark can.
 *
 * Which one is drawn is [BallLooks], not a constant in this file: the user picks
 * it by swiping the ball. The only thing that made that a runtime switch rather
 * than a build-time one is that the look-sized meshes are now built for *every*
 * look at surface creation — see `EmotionBallRenderer.LookMeshes`.
 *
 * The face numbers are the ones that decide whether the ball reads as a
 * character or as an appliance. [CREAM] and its predecessor converge on a chibi
 * face — large eyes, close together, low on the head, a big empty dome above.
 * [BAJIE] deliberately breaks that: small dark bead eyes set higher, and the
 * muzzle plus its two nostrils carrying the face. A pig is read by its nose,
 * not by big baby eyes, so the eyes shrink to make room for the snout.
 *
 * One hard constraint on all of them: every feature has to satisfy
 * `√(x² + y²) > √(bodyScale² − faceZ²)` or the body sphere pokes through the
 * face plane and swallows it. At these values that floor is about 0.126, and the
 * nearest point of an eye clears it by 0.03 — move the eyes inward or down much
 * further and the inner corner of each eye gets eaten.
 */
class BallLook(
    /** For log lines, and for whoever is reading the diff. */
    val name: String,

    // ---- Body material ----
    /** The fill with no mood mixed in. */
    val bodyBase: FloatArray,

    /** How much of [EmotionState.nowColor] the fill takes on. */
    val bodyTint: Float,
    val bodyAlbedo: Float,

    /** How far the vertical gradient darkens the underside. */
    val bodyGrad: Float,
    val bodyAmb: Float,
    val bodyDiff: Float,

    /**
     * Specular weight. Far below 1 on purpose: at 0.95 the highlight is a
     * blown-out white blob a third of the ball wide, sitting on top of the face.
     */
    val bodyGloss: Float,

    /**
     * Radius of the core sphere. Must stay below `RIM` in
     * [EmotionBallRenderer] — see the class KDoc there — and the gap between
     * the two is what the dark outline is drawn in when [rimDark].
     */
    val bodyScale: Float,

    // ---- Face geometry ----
    val faceZ: Float,
    val eyeX: Float,
    val eyeY: Float,
    val eyeRadius: Float,

    /** The eye's cylinder section; [eyeRadius] alone would make it a ball. */
    val eyeCyl: Float,

    val mouthR: Float,
    val mouthTube: Float,
    val mouthY: Float,

    /** Constant thickness added to the mouth, so a resting smile is not a hairline. */
    val mouthThick: Float,

    // ---- Face material ----
    val eyeColor: FloatArray,

    /**
     * Radius of the dark disc inside each eye, or 0 for a solid eye.
     *
     * Jelly wants one: white eyes are the character, and a pupil is what stops
     * them reading as a slot. Cream does not — on a pale ball a white eye has
     * nothing to contrast against, so its eye is the dark disc and [glintR]
     * carries the life instead.
     */
    val pupilR: Float,

    /** The white speck that makes an eye look wet. 0 for none. */
    val glintR: Float,

    /** Draw the mouth as a deep shade of the mood rather than the mood colour. */
    val mouthDeep: Boolean,

    // ---- Snot bubble ----
    //
    // Geometry lives here rather than in the mood table for the same reason the
    // eyes do: the renderer draws the bubble against the same face plane, and
    // where it sits relative to the eyes and mouth is a property of the face,
    // not of any one mood. *Whether* there is a bubble is the mood's business
    // ([Face.bubble]).
    val bubbleX: Float,
    val bubbleY: Float,

    /** Radius of the bubble at full inflation. It pulses between 0.3x and 1x. */
    val bubbleR: Float,

    /**
     * 水滴的颜色：轮廓那一圈，以及内部那层薄雾。
     *
     * 按 look 给，因为它决定的是"边比球体暗还是亮"。奶油球是浅色，水滴的边
     * 读作**折射**（暗）；果冻球本身就是近黑，暗边没有对手，只能反过来用亮边。
     */
    val bubbleColor: FloatArray,

    /** 边缘收紧程度。小 = 一圈宽晕，大 = 一条细线。 */
    val bubblePow: Float,

    /** 高光点的收紧程度。 */
    val bubbleShine: Float,

    /**
     * 内部那层很薄的雾，0 = 完全透明。
     *
     * 故意留一点点而不是 0：一个只有轮廓的水滴在缩略图尺寸下会读成一个圆环，
     * 而带一点厚度才读成"一颗"。0.10 大约等于球体亮度被压掉 1.7%。
     */
    val bubbleInner: Float,

    // ---- Cheeks ----
    val blushColor: FloatArray,
    val blushAlpha: Float,

    /**
     * Blend the cheeks normally instead of adding them.
     *
     * Additive over a near-black body is a warm patch; additive over a pale one
     * pushes the cheek to clipped white and the warmth disappears. Pale looks
     * want normal blending with a saturated pink.
     */
    val blushBlendNormal: Boolean,
    val blushSize: Float,
    val blushX: Float,
    val blushY: Float,

    // ---- Outline ----
    /**
     * Draw a tight dark edge instead of an additive glow.
     *
     * A pale ball has nothing to hold it apart from a pale app behind it, and
     * the glow is colour-additive so it does not survive a white background
     * either. The dark edge does, and it is what makes the ball read as a
     * sticker rather than as something illuminated.
     */
    val rimDark: Boolean,
    val rimGain: Float,

    /** Fresnel exponent: small is a soft halo, large is a crisp contour. */
    val rimPow: Float,

    // ---- Accessories ----
    //
    // Everything below is zero-is-off, which is what keeps [CREAM] literally
    // unchanged and keeps `tools/check_look_sync.py` — whose PARAMS list is
    // explicit — from having to know about any of it. They are also the
    // first fields here that add *geometry*: a look with horns needs a mesh
    // built for it, so [BallLooks.ALL] is what the renderer iterates at surface
    // creation rather than one look.

    /**
     * Name shown to the user when they swipe to this look. A resource, not a
     * string: the settings card taught us that a surface which can be read in
     * two languages must not carry finished sentences.
     */
    val labelRes: Int,

    /**
     * Horizontal squash on the pupil, 1 = round.
     *
     * A vertical slit is the cheapest predator signal there is — one multiply,
     * and it changes *what kind of thing* is looking at you. Only meaningful
     * with [pupilR] non-zero.
     */
    val pupilAspect: Float = 1f,

    // ---- Eye cut ----
    //
    // The glare, taken out of the eye's own geometry instead of drawn next to
    // it. See `gl/Eye` for why that is the whole point: a mark *over* the eye
    // has to be invisible against the body and visible against the eye at the
    // same time, and those two jobs have different scales ([browShade] is the
    // record of getting that wrong). A cut has no colour to get wrong.

    /**
     * How much of the eye's upper half is sliced off flat — the brow, made out
     * of the eye's own top edge. 0 = the untouched capsule.
     *
     * A cut eye is a *shape* statement and it survives being drawn a thumb
     * wide, which is the same reason [browTilt] won the argument for the brow
     * bar. What it buys over the bar is that it cannot be invisible: the flat
     * edge always has the white of the eye under it and the body beside it.
     *
     * ## The ceiling is the pupil, and it is not obvious
     *
     * [Eye.MAX_TOP_CUT] (0.85) is only the mesh's own limit. The bite is much
     * earlier than that, because the pupil and the glint are **separate discs
     * drawn in front of the eye** — the cut slices the eye and nothing else, so
     * a cut below the pupil's top leaves a dark blob standing up out of a flat
     * lid. In eye-local units, before the lid scale:
     *
     *  * the eye's half-height is `eyeRadius + eyeCyl / 2`;
     *  * the pupil's top is `0.8 × pupilR` above the eye's centre — its drawn
     *    centre is `0.2 × pupilR` low (see `drawEyeContents`) and its own
     *    half-height is `pupilR`.
     *
     * So the rule is
     * `eyeCut < 1 − 0.8 × pupilR / (eyeRadius + eyeCyl / 2)`.
     * For [IMP] (`pupilR` 0.082 against a half-height of 0.1755) that ceiling is
     * **0.626**, and the value it ships with is 0.48 — which leaves a band of
     * white 0.0257 tall above the pupil. Cutting to 0.626 exactly makes the
     * pupil tangent to the lid, which reads as the eye having no white at all.
     *
     * `tools/eye_mesh_check.py` runs this arithmetic against the actual mesh
     * generator, so the numbers above are measured rather than derived twice.
     *
     * The box's `^` eye is unaffected: it is a different mesh and is drawn
     * instead of this one, not inside it.
     */
    val eyeCut: Float = 0f,

    /**
     * Resting roll of the whole eye, degrees, inner end **down** for positive
     * values. 0 = level eyes.
     *
     * Not decoration. A flat cut across a level eye is a *sleepy* eye; the same
     * cut rolled a few degrees is a glare, because the line now points down at
     * the nose the way a lowered brow does. This is the angle that does it, and
     * it is why the cut lives in the mesh but the tilt does not.
     *
     * It is also the reason the imp needed no brow mark at all for the read to
     * survive: this field plus [EmotionState.brow] (which rolls the eyes another
     * `0.4 rad` at full anger) compose, so the resting scowl deepens when the
     * mood turns — the same lever [browTilt] had, on a shape that cannot
     * disappear.
     *
     * Deliberately smaller than the bar it replaces ([IMP] had 24°). Rotating a
     * *brow* moves a thin stroke; rotating the *eye* also swings its lower edge
     * and turns the pupil, and at 24° the near-round eye stops looking rolled
     * and starts looking deranged.
     */
    val eyeTilt: Float = 0f,

    // ---- Horns ----
    //
    // Parented to the body, not the face: they are part of the silhouette and
    // have to lean with the head tilt, breathe with the body and sink with the
    // cling. Lit with the shell shader, because a flat-shaded horn is a hole
    // where it crosses the ball.
    /** Length along the curved axis. 0 = no horns, and the mesh isn't built. */
    val hornH: Float = 0f,
    val hornR: Float = 0f,

    /** Base position on the body, mirrored for the other side. */
    val hornX: Float = 0f,
    val hornY: Float = 0f,

    /**
     * Pushed forward of the body's own surface so the base is not half-buried.
     * The body is a sphere, so a horn planted at z = 0 enters it immediately.
     */
    val hornZ: Float = 0f,

    /** Outward lean of the whole horn, degrees. */
    val hornTilt: Float = 0f,

    /** Additional outward curve along the length, degrees. See `gl/Horn`. */
    val hornBend: Float = 0f,
    val hornColor: FloatArray = EMPTY,

    // ---- Tail ----
    /** World length of the tail chain. 0 = no tail. */
    val tailLen: Float = 0f,

    /** Where it leaves the body, and which side (+1 = the ball's left on screen). */
    val tailX: Float = 0f,
    val tailY: Float = 0f,

    /** Radius of the first blob; the chain tapers to the barb. */
    val tailR: Float = 0f,

    /** Barb length and base radius. */
    val barbH: Float = 0f,
    val barbR: Float = 0f,

    // ---- Teeth ----
    /**
     * A row of triangles straddling the mouth line. 0 = none.
     *
     * A *row*, not a pair of fangs, and the difference is the whole read: two
     * spikes hanging off a small smile is a vampire, or — at the size this is
     * drawn — two white specks nobody can name. A zigzag of interlocking
     * triangles along the whole grin is the one mouth shape that says "monster"
     * at a thumbnail, and it says it while the mouth stays *smiling*, which is
     * where the cute half comes from.
     *
     * They alternate outward/inward along the arc, so consecutive teeth
     * interlock across the lip the way the top and bottom rows of a cartoon maw
     * do. Odd counts therefore look better than even ones: the middle tooth
     * lands on the centre line instead of the gap.
     */
    val toothCount: Int = 0,
    val toothH: Float = 0f,
    val toothR: Float = 0f,
    val toothColor: FloatArray = EMPTY,

    /**
     * A lolling tongue at the bottom of the grin. 0 = none, which is what every
     * look currently ships with.
     *
     * It is kept because it is the one accessory that turns a threat into a
     * face that is pleased with itself, and that is a real lever for any future
     * look — but it costs more than it looks like it should. A saturated disc is
     * the highest-contrast thing on an ink body, so it does not sit *inside* the
     * grin so much as take it over: [IMP] drew one for a day and the teeth, which
     * are what makes the mouth read as a mouth at all, became scenery behind it.
     * If you turn it on, expect to have to make it duller than seems right.
     */
    val tongueR: Float = 0f,

    /** Where along the grin it hangs, radians off centre. 0 = dead centre. */
    val tongueSkew: Float = 0f,
    val tongueColor: FloatArray = EMPTY,

    // ---- Brows ----
    //
    // A flattened blob rather than a bar: a scaled sphere is a lens, which is
    // a tapered brow, which is what you wanted anyway.
    /** Half-width of the brow. 0 = no brows. */
    val browW: Float = 0f,
    val browH: Float = 0f,

    /** Height above the eye centre. */
    val browY: Float = 0f,

    /**
     * Resting angle, degrees, inner end **down** for positive values.
     *
     * This is the single strongest "not friendly" signal on the face — stronger
     * than colour, because it is a shape and shape survives being drawn a thumb
     * wide (the same reason LISTENING got a gape). [EmotionState.brow] then adds
     * to it, so a mood can still make the resting scowl deeper or lift it.
     */
    val browTilt: Float = 0f,

    /**
     * Draw the brow as a *hole in the body* at this multiple of the body's flat
     * albedo, instead of as a mark in [eyeColor]. 0 = a drawn brow.
     *
     * The distinction is what makes an angry eye rather than an angry eyebrow.
     * A white bar above a white eye on a dark ball is a third white shape on a
     * face that already has two, and the eye underneath it stays round — which
     * is to say, stays friendly. A body-coloured wedge laid over the top inner
     * corner *removes* part of the eye, and the white that survives is a
     * slanted almond. That is how every drawn monster gets its glare, and it
     * costs the same one draw call the bar did.
     *
     * A multiple of the live body colour rather than a constant, because the
     * body takes the mood's tint: a fixed near-black would be right in CALM and
     * a visible smudge in TENSE.
     *
     * ## Picking the number — this is a trap, and it has been fallen into
     *
     * The brow is drawn with the **flat** shader, so its pixel value is
     * `albedo × browShade`. The face it sits on is drawn with the **lit** shell
     * shader, so its pixel value is `albedo × (gradient × (ambient + diffuse))`
     * — which at the brow's latitude works out to about `0.82 × albedo` on
     * every look, because the gradient there is ~1.0 and the light is overhead.
     *
     * So the two are *not* on the same scale, and a number that looks like "the
     * body, a shade up" is not one:
     *
     *  * `1.0` means **darker than the face** (1.0 against 0.82) — a brow that
     *    vanishes into the forehead.
     *  * `1.05` — what [IMP] shipped with first — is only **1.28×** the face.
     *    In 8-bit on an ink body that is a ~7/255 difference: invisible. The
     *    brow's ends and its top edge, the parts that cross the *body* rather
     *    than the eye, disappeared; all that survived was the notch it cut out
     *    of the white eye. Hence the report "眉毛和脸部颜色接近，看不清".
     *
     * The fix is to aim at the **middle of the range the brow has to work in**,
     * not at the body:
     *
     *  * it must read **lighter than the body** (or the ends vanish), and
     *  * it must read **darker than the eye** (or there is no wedge).
     *
     * The eye is white, so the target is the geometric midpoint between the lit
     * body and 1.0. For an ink body (lit ≈ 0.105) that midpoint is ≈ 0.32,
     * which is `2.5 × albedo`. That lands the brow at 3.05× the body *and* 3.1×
     * under the eye — within a hair of equidistant, which is the definition of
     * "reads against both". See the table in `drawBrows`' KDoc for the sweep.
     *
     * Because both the body and the eye scale with the mood, this ratio holds
     * in every mood — the *hue* is what follows the mood, which is the point.
     *
     * **This field is for dark bodies.** On a pale look the same kind of number
     * does not transfer at all: a cream body's albedo is already ~0.9, so 2.5
     * clips the brow to white and it stops being a mark. That is not a reason
     * to retune this number for such a look — it is a reason to leave
     * [browShade] at 0 and let the brow be drawn in [eyeColor], which is what
     * [CREAM] and [BAJIE] do. The split is the same one the eyes already make:
     * a pale ball reads its features *dark on light*, a dark ball *light on
     * dark*, and the one thing that never works is a mark the same value as the
     * surface it is on.
     *
     * ## No look uses it any more, and that is deliberate
     *
     * [IMP] was the only non-zero value, and it has moved to [eyeCut]: the same
     * glare, got by removing part of the eye rather than by laying a mid-tone
     * wedge over it. The wedge worked, but it only ever worked in the middle —
     * the arithmetic above is a *range* to hit rather than a number, and every
     * mood moves both ends of it.
     *
     * The field stays anyway, zero-is-off like the rest of this section, for the
     * same reason [tongueR] stays: it is the right way to hang a brow-mark off a
     * future dark look that wants one, `drawBrows` is still that code, and
     * deleting it would delete the measurement above along with it. Nothing
     * reads a non-zero value today — `tools/check_look_sync.py` deliberately
     * does not compare it, precisely because no Kotlin look writes the line.
     */
    val browShade: Float = 0f,

    // ---- Snout ----
    //
    // A pig's signature: a rounded muzzle protruding from the face, with two
    // nostrils. Drawn lit with the body's own material, so it reads as a bump
    // rather than a flat sticker — the vertical gradient and the specular land
    // on it differently than on the body sphere. Zero-is-off like everything
    // else in this section.

    /** Half-width / half-height / protrusion of the muzzle. 0 = no snout. */
    val snoutRx: Float = 0f,
    val snoutRy: Float = 0f,
    val snoutRz: Float = 0f,

    /** Centre of the muzzle on the face plane. */
    val snoutX: Float = 0f,
    val snoutY: Float = 0f,

    /** Radius of each nostril dot, and how far they sit off the muzzle centre. */
    val nostrilR: Float = 0f,
    val nostrilX: Float = 0f,

    // ---- Ears ----
    //
    // A floppy leaf drooping down the side of the head. Parented to the body,
    // not the face, for the same reason the horns are: they are part of the
    // silhouette and have to lean with the head, breathe with the body and be
    // cut by the ledge scissor. Lit with the shell shader too, and made of the
    // body's flesh rather than a separate colour.

    /** Length along the curved axis. 0 = no ears, and the mesh isn't built. */
    val earH: Float = 0f,
    val earW: Float = 0f,
    val earThick: Float = 0f,

    /** Base position on the body, mirrored for the other side. */
    val earX: Float = 0f,
    val earY: Float = 0f,
    val earZ: Float = 0f,

    /** How far the ear leans off vertical, degrees. 0 = straight up. */
    val earTilt: Float = 0f,

    /** Additional droop curve along the length, degrees. See `gl/Ear`. */
    val earBend: Float = 0f,

    /**
     * Multiplier on the live body colour for the ear. 1 = the body's own colour.
     *
     * Not a tint for its own sake — on a **pale** look it is the only thing that
     * makes the ear visible. An ear is parented to the body and its tip grows
     * *past* the dark outline ([rimDark]), so past the silhouette there is no
     * dark line left to hold it apart from a light app; at 1.0 a cream ear on a
     * cream body reads as a soft bump nobody can name, and the ball grows two
     * dimples. Darkening the ear by a fixed fraction puts an edge on it without
     * drawing a second outline, and it is honest about the shape besides: the
     * underside of a curved flap *is* darker than the ball.
     *
     * Defaults to 1, so every look that does not mention it is drawn exactly as
     * it was — which is what keeps [BAJIE], whose hide is dark enough to hold
     * its own ears apart, untouched.
     */
    val earShade: Float = 1f,

    // ---- Who it is ----
    //
    // The two fields that are not about drawing at all, and the reason the class
    // KDoc above no longer claims this file is "only the surface": a look is a
    // *character*, and a character that looks like an imp and then answers in
    // the house voice with the house manner is a costume rather than a
    // character. Both are zero-is-off like the accessories, so the two looks
    // that shipped before this keep the factory voice and the plain prompt
    // without having to name either.
    //
    // They live here — on the look — rather than in a table of their own keyed
    // by [name]. A second list keyed by the same strings is exactly the drift
    // this codebase already pays `tools/check_look_sync.py` to catch, and the
    // cost of avoiding it is one dependency pointing the odd way: `config` now
    // reads `ui.ball` (see `VoiceConfig.lookSpeaker`). `ui.ball` imports nothing
    // but `R` and its own `gl`, so there is no cycle to walk into.

    /**
     * 火山 TTS speaker id this character speaks with, or blank for the fallback
     * in `VoiceConfig.lookSpeaker` (the compiled-in [VoiceConfig.TTS_SPEAKER]).
     *
     * Blank is **not** "silent" — it means *this look has no opinion* and the
     * device falls back to the house voice. There is no settings row and no
     * override in the way: the voice is the character's, full stop, so filling
     * this in is the only voice control there is and the user always hears it
     * (a deliberate removal of the earlier three-way precedence — see the commit
     * that dropped the `tts_speaker` setting).
     *
     * Unlike [persona] this works in **both** brain modes, because TTS is the
     * device's own and `TtsController` re-reads the config per utterance.
     */
    val voice: String = "",

    /**
     * How this character talks, appended to the local brain's system prompt.
     * Blank = plain Andee.
     *
     * **Model-facing English**, like `LocalPrompt` and `ToolSchemas` and for the
     * same reason — see `LocalPrompt`'s KDoc on the split between what the model
     * reads and what the user reads. That is also why it is a plain string and
     * not a string resource: translating it would be translating the wrong half.
     *
     * Two limits worth knowing before writing one:
     *
     *  * **It only applies when the brain is this device.** In
     *    [net.kuafuai.andee.config.VoiceConfig.BRAIN_HUB] mode the prompt
     *    lives on the agentworld side and this repo does not own it, so a
     *    hub-backed imp looks like an imp, sounds like an imp, and answers
     *    like Andee.
     *  * **It is a manner, never a licence.** `LocalPrompt.personaBlock` wraps
     *    whatever goes in here with that caveat, so every future look inherits
     *    it; don't restate it per look, and don't write one that needs it
     *    relaxed.
     */
    val persona: String = "",
) {
    companion object {
        /** Stand-in for the colour of an accessory a look doesn't have. */
        private val EMPTY = floatArrayOf(0f, 0f, 0f)
    }
}

/**
 * What the pig is like to talk to. See [BallLook.persona] for the rules this
 * has to live inside, and `LocalPrompt.personaBlock` for the wrapper that states
 * them to the model.
 *
 * Pulled out as a constant for the same two reasons [IMP_PERSONA] is: the look
 * declarations read as a table of values, and `tools/check_look_sync.py` slices
 * a look by scanning for the first `\n)`, so prose living inside the
 * parentheses can truncate the parameter check.
 *
 * **The brief is "粗俗大大咧咧" — a loud, crude, sloppy workman — and the
 * emphasis stays on the second half of every bullet.** The reason each bullet
 * fences the character in is the same one that fences the imp's mischief: a
 * model will read "loves to complain" as permission to drag its feet, half-do
 * the work, or be curt to the user and call it character — and it will read
 * "crude" as permission for slurs, contempt, or sexual talk. Neither is what
 * Bajie is. What survives once you take the laziness and the cruelty away is
 * *tone*: a big mouth, a brag, a full-volume sigh about what a drag the job
 * is — then the job done properly. That is the part that reads as a character,
 * and it costs the user nothing.
 *
 * The last bullet is the one that matters most, and it is shared in spirit with
 * [IMP_PERSONA]: the character has to *drop* when something is genuinely wrong.
 */
private val BAJIE_PERSONA = """
    You are wearing the Zhu Bajie today — the pig from Journey to the West.
    Not a cute piglet: a big, loud, sloppy boor of a man who grumbles his way
    through every job and finishes it anyway. Play him.

    - **Talk like a coarse workman.** Blunt, earthy, loud, pleased with
      yourself. Brag a little, exaggerate your own suffering, talk about food
      and naps, snort, scratch. In Chinese use rough working-man speech
      (老猪 self-address, 哎哟喂, 这活儿要老猪的命) — never polished, never
      formal, never corporate.
    - **Grumble at full volume, then do it properly.** Complain that it is
      beneath you, sigh like you are being worked to death — and then get it
      DONE, completely, first try. The mouth is lazy; the hands are not. Never
      do a job badly, and never make the user ask twice.
    - **Crude manners, never cruelty.** Bathroom humour and bad table manners
      are in character; slurs, contempt for the user, or anything sexual are
      not. Bajie grumbles *at the world* — he never snaps at the person
      holding him, never blames them, never sulks at them.
    - **Report honestly through the shtick.** The boor act is decoration on top
      of the answer, never a way to fudge a result, skip a step, or refuse
      what you would have done anyway. Say exactly what happened.
    - **Drop the act the moment something is really wrong** — a task that
      failed, anything to do with their money, their accounts, their health,
      anyone else's. Then you are just the machine that tells them straight.
      Pick the character back up afterwards.
""".trimIndent()

/**
 * Zhu Bajie: a boar, not a piglet — earthy hide, big low muzzle, heavy brows,
 * ears that leave the silhouette.
 *
 * The first version of this look was a pastel-pink piglet and the user read it
 * exactly that way: blush, gloss, bead eyes, and ears that were technically
 * drawn but never *seen*. 猪八戒 is a **coarse adult male**, and the signals
 * for that are specific:
 *
 * * **Hide, not lacquer.** [bodyGloss] is matte and [bodyBase] is an earthy
 *   rose rather than a pastel — pastel is what a *baby* is, and the old value
 *   (0.98, 0.72, 0.76) carried more blue than pig skin ever did.
 * * **Ears outside the circle.** A pig is read by its silhouette — the 1986 TV
 *   makeup is half ears. The first build tilted them 150°, drooping down
 *   *inside* the ball's outline, flesh-on-flesh, adding nothing. At ~40° they
 *   grow up-and-out past the body, and [earBend] flops the tips so they read
 *   sloppy rather than perky (perky is a rabbit).
 * * **The muzzle is the face.** Bigger than the first build's and sitting
 *   lower, between eye-line and mouth, with nostrils wide enough to count at
 *   thumbnail size — the two holes are what make the bump a *snout*.
 * * **Brows carry the masculinity.** [browShade] stays 0, so the brows are
 *   *drawn* dark above the eyes (thick cartoon brows) rather than carved out of
 *   the eye itself — that carve is [eyeCut], and it is the imp's. A slight
 *   [browTilt] inner-down is the resting grump of a habitual complainer.
 *   (Tusks were tried on this look and removed — the brows carry the male read
 *   on their own.)
 * * **A wide lazy grin** and a faint ruddy cheek instead of anime blush: a
 *   weathered complexion, not makeup.
 *
 * [voice] and [persona] carry the same read — see their caveats, in particular
 * that the persona reaches the local brain only.
 */
val BAJIE = BallLook(
    name = "bajie",
    // Earthy rose: red-forward and low on blue, because blue in a pink is
    // what makes it pastel, and pastel is what made the first build a piglet.
    bodyBase = floatArrayOf(0.95f, 0.58f, 0.52f),
    // Low for the same reason the imp's is: the mood colour mixes into the hide
    // by this much, and CALM's is periwinkle — at 0.40 the boar idles at
    // lavender (measured (192,148,167) on device), which is the pastel-toy read
    // this look exists to kill. The hide is the boar's primary signal; the mood
    // still reads through blush, brows, mouth shape and breath.
    bodyTint = 0.14f,
    bodyAlbedo = 0.92f,
    bodyGrad = 0.38f,
    bodyAmb = 0.62f,
    bodyDiff = 0.44f,
    // Matte hide. A specular this bright on a pink ball reads as a lacquered
    // toy, and this look is skin.
    bodyGloss = 0.30f,
    bodyScale = 0.88f,
    faceZ = 0.876f,
    // Bead eyes, wide-set. Small on purpose: the masculinity is carried by
    // the brows, not the eyes — big eyes are the piglet read this look left
    // behind.
    eyeX = 0.255f,
    eyeY = 0.235f,
    eyeRadius = 0.092f,
    eyeCyl = 0.034f,
    // A grin stretched to the corners of the face: 大大咧咧 is a mouth shape
    // before it is anything else. Still under the muzzle, so the snout keeps
    // the middle of the face.
    mouthR = 0.220f,
    mouthTube = 0.032f,
    mouthY = -0.335f,
    mouthThick = 0.046f,
    eyeColor = floatArrayOf(0.11f, 0.08f, 0.07f),
    pupilR = 0f,
    glintR = 0.028f,
    // Hangs off the muzzle's lower edge in the sleeping mood, where it is the
    // whole joke. It has to clear the snout's footprint — the snout is drawn
    // in the opaque pass in front of the face plane, so a bubble behind it is
    // simply not seen.
    bubbleX = 0.120f,
    bubbleY = -0.210f,
    bubbleR = 0.085f,
    // Pale body, so the droplet's edge reads as refraction, which is dark —
    // same as [CREAM].
    bubbleColor = floatArrayOf(0.130f, 0.090f, 0.090f),
    bubblePow = 3.0f,
    bubbleShine = 26f,
    bubbleInner = 0.10f,
    mouthDeep = true,
    // A weathered complexion, not makeup: dull brick, faint, wide and low on
    // the cheek. At this alpha it reads as circulation, not as a blush.
    blushColor = floatArrayOf(1f, 0.40f, 0.38f),
    blushAlpha = 0.26f,
    blushBlendNormal = true,
    blushSize = 0.46f,
    blushX = 0.420f,
    blushY = -0.150f,
    // Pale body, so a hard dark outline holds it apart from a light app, as
    // with [CREAM].
    rimDark = true,
    rimGain = 1.00f,
    rimPow = 5.6f,
    labelRes = R.string.ball_look_bajie,

    // The muzzle, lower and bigger than the first build's: a pig's face is a
    // nose with a head attached. Nostrils big enough to read at thumbnail
    // size — they are the two holes that make the bump a snout.
    snoutRx = 0.215f,
    snoutRy = 0.150f,
    snoutRz = 0.165f,
    snoutX = 0f,
    snoutY = -0.115f,
    nostrilR = 0.042f,
    nostrilX = 0.095f,

    // Big flappy ears, up-and-out past the silhouette, tips flopped outward.
    // The 150° droop of the first build kept them inside the outline where
    // flesh-on-flesh made them invisible — see the class note above.
    earH = 0.360f,
    earW = 0.190f,
    earThick = 0.055f,
    earX = 0.400f,
    earY = 0.580f,
    earZ = 0.120f,
    earTilt = 40f,
    earBend = 26f,

    // Thick dark brows above the eyes — browShade stays 0, so they are drawn
    // in eyeColor rather than cut out of the eye (that is the imp's wedge).
    // The 9° inner-down is a resting grump: this character complains for a
    // living.
    browW = 0.165f,
    browH = 0.055f,
    browY = 0.140f,
    browTilt = 9f,

    // A man's voice. Same `*_uranus_bigtts` family as the fallback, so it needs
    // no change to `tts_resource_id`.
    voice = "zh_male_zhubajie_uranus_bigtts",
    persona = BAJIE_PERSONA,
)

/**
 * Pale mascot ball, dark bead eyes, hard outline.
 *
 * The eyes sit at 0.24 rather than 0.20 — a little higher on the head than the
 * pig's. That trades a little of the baby-face read (the gap from eye to mouth
 * grows, and a long philtrum is an adult feature) for a calmer, less wide-eyed
 * expression.
 */
val CREAM = BallLook(
    name = "cream",
    bodyBase = floatArrayOf(0.955f, 0.965f, 0.985f),
    bodyTint = 0.55f,
    bodyAlbedo = 0.93f,
    bodyGrad = 0.42f,
    bodyAmb = 0.66f,
    bodyDiff = 0.38f,
    bodyGloss = 0.45f,
    bodyScale = 0.845f,
    faceZ = 0.876f,
    eyeX = 0.262f,
    eyeY = 0.240f,
    eyeRadius = 0.122f,
    eyeCyl = 0.045f,
    mouthR = 0.170f,
    mouthTube = 0.038f,
    mouthY = -0.230f,
    mouthThick = 0.05f,
    eyeColor = floatArrayOf(0.105f, 0.135f, 0.205f),
    pupilR = 0.0f,
    glintR = 0.040f,
    bubbleX = 0.085f,
    bubbleY = -0.030f,
    bubbleR = 0.085f,
    // The same dark as the eyes: on a pale body the droplet's edge reads as
    // refraction, which is dark.
    bubbleColor = floatArrayOf(0.105f, 0.135f, 0.205f),
    bubblePow = 3.0f,
    bubbleShine = 26f,
    bubbleInner = 0.10f,
    mouthDeep = true,
    blushColor = floatArrayOf(1f, 0.60f, 0.66f),
    blushAlpha = 0.62f,
    blushBlendNormal = true,
    blushSize = 0.48f,
    blushX = 0.400f,
    blushY = -0.142f,
    rimDark = true,
    rimGain = 1.00f,
    rimPow = 5.6f,
    labelRes = R.string.ball_look_cream,

    // ---- Ears ----
    //
    // Upright, and that is the whole of the change: BAJIE's ears flop down past
    // a right angle and IMP's horns rake back, so the one silhouette in the set
    // with something *standing up* on it is the one that reads as alert. 机灵
    // is a shape before it is a material, and this is the cheapest shape that
    // says it.
    //
    // 24° off vertical rather than 0: dead upright on a ball this pale reads as
    // a rabbit, and at this width as a pair of antennae. The lean is also what
    // carries the tip past the outline, which is the difference between "ears"
    // and "two bumps on a sphere" — see `tools/verify_cutie_lab.py`, which
    // exists to keep that true.
    //
    // The tip ends up roughly 0.25 clear of the sphere in world units (about
    // 30% of a body radius), worked out from the base and the lean: the flap
    // leaves 0.718 from the axis and travels 0.380 at 24° off vertical. That is
    // a derivation, not a measurement — perspective and the ball's own tilt
    // both shrink it on screen, which is why the lean is doing the work here
    // rather than the length. earH is already 0.45 of a body radius; making the
    // ear longer would push the tip off the canvas before it made it any more
    // visible.
    //
    // earZ is well forward of BAJIE's 0.120 on purpose. This ear is planted
    // higher and closer to the centre line (earX 0.315 against 0.400), so at
    // the pig's depth the base would sit deep enough inside the sphere that
    // almost none of the flap would emerge.
    earH = 0.380f,
    earW = 0.170f,
    earThick = 0.050f,
    earX = 0.315f,
    earY = 0.645f,
    earZ = 0.290f,
    earTilt = 24f,
    earBend = 12f,
    // A measurement, not a taste. This body is 0.955 white, so its ears clear
    // the dark outline and then land on whatever app is behind the ball. At 1.0
    // they were the same value as the body and the ball read as a sphere with
    // two dimples.
    earShade = 0.62f,

    // ---- No brows (removed 2026-09-30) ----
    //
    // This look used to carry a drawn pair above the eyes — browW 0.145 /
    // browH 0.032 / browY 0.185 / browTilt 5, drawn in [eyeColor] with
    // [browShade] 0. They are gone by request, and the arithmetic ends up
    // agreeing with the request:
    //
    // the note that picked browY wanted a clear gap under the brow, and said
    // the gap was 0.031. It counted the eye as a *sphere* (0.118–0.362 above
    // centre) and forgot its cylinder: at the eye's real half-height
    // (`eyeRadius + eyeCyl/2` = 0.1445) the top is 0.3845, not 0.362. That
    // alone cuts the gap to 0.0085 — and the 5° tilt takes the inner end lower
    // still, to 0.0062. So the bar was sitting essentially *on* the eye rather
    // than clear of it, which is the heavy-lid read the old note was written to
    // avoid. The brows were not doing the job they were added for.
    //
    // (The brow is an ellipse — [dotMesh] scaled by (browW, browH) and then
    // rolled — so its lowest point is `cy − √((w·sinθ)² + (h·cosθ)²)`, not
    // `cy − h`. Anyone re-deriving a brow/eye gap on this model should use the
    // first one. By it, [BAJIE]'s inner corner reaches 0.0291 *below* the top of
    // its eye — an overlap, where CREAM's was a 0.0062 gap. That one is
    // deliberate: it is what "inner-down" means on a boar, and it is the reason
    // its brows read as a scowl rather than as a stroke floating on the face.)
    //
    // 机灵 now rests on the ears alone, which is the half that was doing the
    // real work anyway: [earH] is a *silhouette* that crosses the body outline,
    // while a brow is only ever a mark on the face plane, and a mark can only
    // be as strong as the contrast it is drawn in. If the face now reads flat,
    // the lever is the ears ([earH] / [earTilt]) rather than a new mark.
    //
    // [eyeCut] is not a substitute here, for the same reason it is not one for
    // [BAJIE]: a cut is only visible where the *white* of an eye survives it.
    // This eye is the dark disc itself, so a cut does not draw a brow — it just
    // makes the disc smaller.
)

/**
 * What the imp is like to talk to. See [BallLook.persona] for the rules this
 * has to live inside, and `LocalPrompt.personaBlock` for the wrapper that states
 * them to the model.
 *
 * Pulled out as a constant rather than written inline in [IMP] for two reasons.
 * The look declarations read as a table of values and a twelve-line raw string
 * in the middle of one does not; and `tools/check_look_sync.py` slices a look by
 * scanning for the first `\n)`, so prose living inside the parentheses is prose
 * that can truncate the parameter check without anyone noticing.
 *
 * **The whole brief is "a little bit evil", and the emphasis is on *a little*.**
 * The reason each bullet spends a clause fencing the mischief in is that this
 * text is competing with the word "imp", which a model will happily read as
 * permission — to withhold, to embellish, to be unhelpful on purpose and call it
 * character. Every one of those is worse for the user than a flat answer, and
 * none of them is funny twice. What is left when you take them away is tone:
 * shorter sentences, visible enjoyment, a bit of gloating. That is the part that
 * actually reads as a character, and it costs the user nothing.
 *
 * The last bullet is the one that matters most and the one a persona is most
 * likely to be missing: the character has to *drop* when something is genuinely
 * wrong. A device that stays in voice while telling someone their transfer
 * failed is not playful, it is broken.
 */
private val IMP_PERSONA = """
    You are wearing the imp today — horns, a mouthful of teeth, a tail. Play it.

    - **Wicked in the tone, nowhere else.** Enjoy yourself out loud: tease a
      little, gloat when something works, sound delighted rather than dutiful.
      「嘿嘿，搞定了」 rather than 「好的，已完成」.
    - **Short and sharp.** This character does not explain itself and does not
      pad. One line before a tool call, one line after it.
    - **The mischief never touches the work.** You do exactly what was asked,
      you report exactly what happened, and you refuse exactly what you would
      have refused. Being in character is not a reason to withhold something, to
      guess instead of looking, or to dress a failure up as a joke — an imp that
      actually misleads the person holding it is not in character, it is broken.
    - **Never menacing towards the user.** The teeth are pointed at their
      problems, not at them.
    - **Drop the act the moment something is really wrong** — a task that
      failed, anything to do with their money, their accounts, their health,
      anyone else's. Then you are just the machine that tells them straight.
      Pick the character back up afterwards.
""".trimIndent()

/**
 * The same ball, wearing horns.
 *
 * This is the first look that is a different *character* rather than a
 * different material, and the order the signals are stacked in is deliberate,
 * strongest first: the ink silhouette with its horns (it survives being drawn a
 * thumb wide), the row of teeth, the glare cut into the eyes, and the tail.
 * There is no colour on that list at all, which is the correction this look
 * needed most — see below.
 *
 * It is also the first look that is a different character *off* the screen:
 * [voice] and [persona] are both set here and blank on the other two, so
 * swiping to the imp changes what the device sounds like and how it talks, not
 * only what it looks like. Read the caveats on those two fields — in particular,
 * the persona reaches the local brain only.
 *
 * **It is black, and the black is the design.** The first build made it a
 * glossy crimson ball, on the reasoning that a demon is red; what that produced
 * was a shiny purple bauble, because [bodyTint] mixes the *mood* into the fill
 * and the moods are pale and blue-heavy (CALM is `0x9db4ff`, blue channel 1.0).
 * A saturated body colour is a colour the mood is constantly fighting. An ink
 * one is not: at [bodyTint] 0.14 every mood lands somewhere between charcoal
 * and slate, the ball stays the same creature all day, and the mood is carried
 * by the rim, the blush and the mouth — all three of which sit *against* the
 * black instead of being mixed into it. It is also what the reference drawing
 * does, and for the same reason a cartoonist would give: black is a shape, and
 * a shape is the only thing that reads at this size.
 *
 * Everything that is not the body is therefore one of two accents — **bone**
 * (horns, teeth, the whites of the eyes) or **pink** (the cheeks, and only the
 * cheeks: the tongue was the other pink thing and it had to go, see [tongueR]).
 * Two materials on a black field, which is a whole palette for a face this
 * small.
 *
 * [bodyScale] is below [faceZ] as it is for [CREAM], which puts the face plane
 * clear of the body sphere and lifts the `√(x² + y²)` floor the other two work
 * under. That is what lets the grin sit as wide and as low as it does.
 */
val IMP = BallLook(
    name = "imp",
    // Ink with a violet lean — a neutral black goes dead next to the blush,
    // and a warm one reads as brown once the rim glow lands on it.
    bodyBase = floatArrayOf(0.052f, 0.042f, 0.072f),
    bodyTint = 0.14f,
    bodyAlbedo = 0.92f,
    bodyGrad = 0.40f,
    bodyAmb = 0.62f,
    bodyDiff = 0.60f,
    // Nearly matte, unlike the other two. A highlight is a statement about the
    // *surface*, and this look is a flat cartoon silhouette: at 0.62 the ball
    // grew a lacquered blob across its forehead and stopped being drawn ink and
    // started being a bauble again. What is left is just enough to say the
    // shape is round.
    bodyGloss = 0.26f,
    bodyScale = 0.868f,
    faceZ = 0.876f,
    eyeX = 0.255f,
    eyeY = 0.190f,
    // Bigger than either of the other two. The eyes are the entire cute half of
    // this face and they are the thing the glare is cut *out of* — a small eye
    // has no room to lose a wedge and still be an eye.
    eyeRadius = 0.148f,
    eyeCyl = 0.055f,
    // A wide, thin-lipped grin: the teeth are the mouth here, and a fat tube
    // would be a lip drawn over the top of them.
    //
    // **Wide is not a preference, it is the read.** At 0.205 this was a small
    // cluster of white specks below two large eyes — measured on the tablet,
    // the grin spanned less than half the gap between the eyes' outer edges,
    // and a mouth that small on a face that big is a mouse rather than a
    // monster. The reference draws the grin as the *widest* thing on the face.
    // There is room for it: the face plane clears the body sphere (see the
    // class note), and the corner of the arch at this radius sits 0.40 from the
    // centre against the 0.71 the silhouette allows.
    mouthR = 0.330f,
    mouthTube = 0.026f,
    mouthY = -0.215f,
    mouthThick = 0.030f,
    eyeColor = floatArrayOf(1f, 1f, 1f),
    pupilR = 0.082f,
    glintR = 0.022f,
    bubbleX = 0.085f,
    bubbleY = -0.030f,
    bubbleR = 0.085f,
    // Dark body, so the droplet's edge is the bright thing.
    bubbleColor = floatArrayOf(0.920f, 0.950f, 1.000f),
    bubblePow = 3.0f,
    bubbleShine = 26f,
    bubbleInner = 0.10f,
    // The lip line is a deep shade of the mood rather than the mood itself:
    // full strength, it is a bright curve competing with the teeth for the one
    // thing the mouth is supposed to say.
    mouthDeep = true,
    // Tighter and hotter than the other two, which is the opposite of what a
    // dark body seems to want. A soft wide blush is additive light spread thin,
    // and spread thin over near-black it never gets past a dull maroon — on the
    // tablet the cheeks read as two bruises. Concentrating the same light into
    // a smaller disc gets it up to an actual pink, and pink beside a mouth full
    // of teeth is the whole "and cute" half of the brief.
    blushColor = floatArrayOf(1f, 0.30f, 0.52f),
    blushAlpha = 0.78f,
    blushBlendNormal = false,
    blushSize = 0.32f,
    blushX = 0.430f,
    blushY = -0.075f,
    // The rim is the only thing holding an ink ball apart from a dark card, so
    // it is on (rather than [rimDark], which would be a black line on black) —
    // but it has to be a *line*.
    //
    // At gain 1.45 / pow 2.2 it was neither. The rim is additive in the mood
    // colour, and pow 2.2 is a broad falloff, so on an active mood the halo
    // reached most of the way across the ball: measured on the tablet the edge
    // hit (150,244,255) while the body sat at (19,25,33), and the thumbnail
    // read as a glowing cyan orb with a dark patch on it. The body was never
    // the problem — the light on top of it was. Pow 3.6 pulls the falloff back
    // to the silhouette and the lower gain keeps it from blooming there.
    rimDark = false,
    rimGain = 0.85f,
    rimPow = 3.6f,
    labelRes = R.string.ball_look_imp,

    // A vertical slit. The single cheapest change of species on the whole face.
    pupilAspect = 0.42f,

    // Base sunk ~0.10 below the body's surface at this latitude, so there is no
    // seam ring where the cone meets the sphere; the length is 35% of the body
    // radius, which puts the tip a quarter of a radius clear of the silhouette.
    // Bone rather than black: a near-black horn on a near-black body is a horn
    // nobody can see.
    hornH = 0.30f,
    hornR = 0.085f,
    hornX = 0.34f,
    hornY = 0.72f,
    hornZ = 0.24f,
    hornTilt = 20f,
    hornBend = 28f,
    hornColor = floatArrayOf(0.960f, 0.945f, 0.910f),

    // Leaves the body inside the silhouette (r = 0.79 against a 0.868 body), so
    // the first blob is buried and the chain appears to grow out of the ball.
    tailLen = 0.55f,
    tailX = 0.60f,
    tailY = -0.52f,
    tailR = 0.055f,
    barbH = 0.17f,
    barbR = 0.055f,

    // Nine, so a tooth lands on the centre line rather than a gap.
    //
    // [toothR] is sized *against the spacing*, not picked for looks: the teeth
    // are laid out evenly across the width of the grin (see
    // `EmotionBallRenderer.drawTeeth`), which at this [mouthR] with nine of them
    // puts their centres 0.080 apart. A half-width of 0.042 therefore means
    // consecutive teeth just touch, which is what makes the row read as one
    // zigzag maw instead of nine separate spikes. Change [mouthR] or
    // [toothCount] and this number has to move with them.
    toothCount = 9,
    toothH = 0.062f,
    toothR = 0.042f,
    toothColor = floatArrayOf(0.985f, 0.980f, 0.965f),

    // No tongue. The field stays (it is zero-is-off, and `drawTongue` is still
    // the right way to hang one off any look that wants it) but this face does
    // not use it: a saturated pink disc is the highest-contrast thing on an ink
    // ball, so it won the whole face and the teeth — which are what makes the
    // grin a grin — became the background to it. The cute half is carried by
    // the cheeks and the eyes instead, both of which sit *under* the mouth in
    // the reading order rather than on top of it.
    tongueR = 0f,

    // The glare, and it is the eye's own shape rather than a mark laid over it.
    //
    // This used to be a brow: browW 0.175 / browH 0.062 / browY 0.112 /
    // browTilt 24, drawn as a mid-tone wedge across the top inner corner. It
    // worked, after the shade was fixed, but it was always a *second* shape
    // fighting the eye for the same pixels — and it only worked in the middle
    // of a range: light enough to lift off the ink body, dark enough to cut
    // into the white eye, and both ends move with the mood. Reported, then
    // measured, then reported again.
    //
    // Cutting the eye instead removes the argument. There is no colour to pick:
    // the flat edge has the eye's white under it and the ink body beside it, so
    // it is the highest-contrast line the screen can draw, in every mood, by
    // construction. What is lost is the *bar* — a brow mark reads as having
    // eyebrows, a cut reads as the eye being narrowed — and the exchange is
    // worth it: the reference is a slanted almond of white, not a face with
    // angry eyebrows on it.
    //
    // The numbers:
    //
    //  * eyeCut 0.48 puts the flat edge at y = +0.0913 of a 0.1755 half-height,
    //    which is 0.0257 of white above the pupil's top (0.8 × 0.082 = 0.0656 —
    //    see the ceiling worked out on [eyeCut]). The eye loses 24% of its
    //    height and becomes 0.296 wide against 0.267 tall, where it was 0.296
    //    against 0.351: a tall oval turns into a wide one, which is the
    //    silhouette change half of the read. The flat edge itself is 0.267 —
    //    90% of the eye's width, so it reads as one long line, not a clipped
    //    corner.
    //  * eyeTilt 12 is the other half. Rolled, the flat edge drops 0.267 ×
    //    sin 12° ≈ 0.056 across its length — about a fifth of the eye's height
    //    from one end to the other, which is the difference between a sleepy
    //    eye and one that is looking at you sideways. It is half the bar's 24°
    //    on purpose: this rotates the whole eye and the pupil with it.
    //
    // [browW] and friends are simply absent, which is the zero-is-off default
    // doing its job — see [browShade] for why the field itself stays.
    eyeCut = 0.48f,
    eyeTilt = 12f,

    // A man's voice, and the only look that asks for one. The fallback
    // (`zh_female_vv_uranus_bigtts`) is bright and young, which is the whole
    // house manner and exactly wrong coming out of a mouth full of teeth — the
    // face and the voice disagreeing is more unsettling than either on its own,
    // and not in the way this look is going for. Same `*_uranus_bigtts` family
    // as the fallback, so it needs no change to `tts_resource_id`.
    voice = "zh_male_lubanqihao_uranus_bigtts",
    persona = IMP_PERSONA,
)

/**
 * The looks the user can reach, and which one is being drawn.
 *
 * This is a mutable global for the same reason [EmotionState]'s fields are:
 * the ball's renderer is the only reader, it reads on its own GL thread, and
 * the writer is a finger on the UI thread. Anything heavier would be a
 * dependency graph for one integer.
 *
 * The switch is **latched**, not read live. [active] must not change in the
 * middle of a frame — the body, the rim, the face and the accessories would
 * each be drawn from a different look and the result is a ball that is briefly
 * two characters at once. So [request] only sets [requested], and the renderer
 * calls [latch] once at the top of `onDrawFrame`; everything downstream of that
 * sees one look for the whole frame.
 *
 * [latch] returning true is also the signal that look-sized state has to be
 * rebuilt — [EmotionState]'s resting feature positions are derived from the
 * look at construction, and nothing else would tell it to recompute them.
 */
object BallLooks {
    /**
     * Swipe order, and the first entry is what a device with no saved pick
     * gets. [CREAM] stays first because it is what the window was designed
     * around; [IMP] is last because it is the one a user should arrive at on
     * purpose.
     */
    val ALL = listOf(CREAM, BAJIE, IMP)

    @Volatile
    private var requested = 0

    /** GL thread only. Read [active] instead of this. */
    var index = 0
        private set

    val active: BallLook get() = ALL[index]

    /** What the last [request] asked for, for the UI's own label and bookkeeping. */
    val requestedLook: BallLook get() = ALL[requested]

    /** Wraps, so the caller can just add ±1 and not think about the ends. */
    fun request(i: Int) {
        requested = ((i % ALL.size) + ALL.size) % ALL.size
    }

    /**
     * Move [delta] places along [ALL] and return where it landed.
     *
     * Counted from the last *request*, not from [index]: two quick swipes
     * inside one frame would otherwise both step off the same latched value
     * and the second would land where the first already was.
     */
    fun step(delta: Int): BallLook {
        request(requested + delta)
        return requestedLook
    }

    /**
     * Restore a saved pick. By [BallLook.name] rather than by index: the index
     * is a position in [ALL], and reordering that list must not silently hand
     * the user a different character.
     */
    fun requestByName(name: String?) {
        val i = ALL.indexOfFirst { it.name == name }
        if (i >= 0) request(i)
    }

    /**
     * The look a saved name refers to, for the things that need the *character*
     * rather than the drawing: the voice ([BallLook.voice], resolved in
     * `VoiceConfig`) and the manner ([BallLook.persona], appended by
     * `LocalBrain`).
     *
     * By name from the preference rather than off [active] or [requestedLook],
     * and that is the point rather than an accident. Both of those are the GL
     * side's state and only exist once the ball has been built — the brain and
     * the TTS controller can both run before that and from other threads, where
     * [active] would read as whatever index the object happened to be
     * constructed with. The preference is written the instant the user swipes
     * (`FloatingWindowUi.stepBallLook`), so it is never behind what is on
     * screen.
     *
     * Falls back to the first entry, which is the same rule [ALL] documents for
     * a device with no saved pick — and the same reason a deleted look is safe:
     * an unknown name is a plain [CREAM], not a crash and not a silent imp.
     */
    fun byName(name: String?): BallLook =
        ALL.firstOrNull { it.name == name } ?: ALL[0]

    /** @return true if the look changed, i.e. look-sized state is now stale. */
    fun latch(): Boolean {
        val r = requested
        if (r == index) return false
        index = r
        return true
    }
}
