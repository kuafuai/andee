package net.kuafuai.andee.ui

/** Screen-space hit test, independent of the overlay's resized height. */
internal object HomeSwipeRegion {
    fun contains(rawY: Float, screenBottom: Int, edgeSize: Int): Boolean =
        rawY >= screenBottom - edgeSize && rawY < screenBottom
}
