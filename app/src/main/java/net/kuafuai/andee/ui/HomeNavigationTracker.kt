package net.kuafuai.andee.ui

/** Tracks app navigation without mistaking IME/system-window events for leaving an app. */
internal class HomeNavigationTracker {
    private var previousApp: String? = null

    fun note(pkg: String, ownPackage: String, homePackage: String?, transient: Boolean): Boolean {
        if (transient) return false
        val previous = previousApp
        previousApp = pkg
        return homePackage != null && pkg == homePackage && previous != null &&
            previous != homePackage && previous != ownPackage
    }
}
