package net.kuafuai.andee.config

/**
 * What both bodies say and do when this build is behind. The version service decides whether there is an update;
 * this decides whether to *believe* it, and where to send the user.
 */
object Upgrade {

    /**
     * True only when [latest] is strictly newer than [current], compared number by number (`0.7.10` > `0.7.9`).
     * A build ahead of the release — a development build, or one installed before the service was updated — is
     * never told to "upgrade" to an older one. Anything that does not parse as numbers is not newer: a reminder
     * nobody can act on is worse than none.
     */
    fun newer(latest: String, current: String): Boolean {
        val a = parts(latest) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int>? =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+').split('.')
            .map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    /** Where the upgrade button goes: CodeFlying's Chinese site for a Chinese interface, the international one otherwise. */
    fun url(lang: String): String = if (lang.startsWith("zh")) "https://www.codeflying.net" else "https://www.codeflying.app"
}
