package net.kuafuai.andee.ui

import android.content.Context
import net.kuafuai.andee.config.Notebook

/**
 * The small amount of state the first-run story has to survive outside trips.
 *
 * Permission steps leave this Activity for system Settings. Some ROMs destroy
 * our task while the user is there, so the current page cannot live only in a
 * View. The actual permission/configuration state is never copied here: it is
 * re-read from Android and [net.kuafuai.andee.config.VoiceConfig] every time.
 * This file only remembers which part of the story the user had reached.
 */
object OnboardingState {
    private const val PREFS = "onboarding"
    private const val VERSION = 1
    private const val KEY_COMPLETE_VERSION = "complete_version"
    private const val KEY_PAGE = "page"
    private const val KEY_ASSISTANT_NAME = "assistant_name"
    private const val KEY_USER_NAME = "user_name"

    enum class Page {
        PRELUDE,
        INTRO,
        SHOPPING,
        CUSTOMER,
        SUBSCRIPTIONS,
        PERMISSIONS,
        MODEL,
        MEET,
        READY,
    }

    fun isComplete(context: Context): Boolean =
        prefs(context).getInt(KEY_COMPLETE_VERSION, 0) >= VERSION

    fun page(context: Context): Page {
        val raw = prefs(context).getString(KEY_PAGE, null)
        return runCatching { Page.valueOf(raw.orEmpty()) }.getOrDefault(Page.PRELUDE)
    }

    fun setPage(context: Context, page: Page) {
        prefs(context).edit().putString(KEY_PAGE, page.name).apply()
    }

    fun assistantName(context: Context): String =
        prefs(context).getString(KEY_ASSISTANT_NAME, "Andee").orEmpty().ifBlank { "Andee" }

    fun userName(context: Context): String =
        prefs(context).getString(KEY_USER_NAME, "").orEmpty()

    /**
     * The names go where the rest of the device reads them, in the encrypted
     * notebook: Andee's own name on its self ([net.kuafuai.andee.config.Selfhood.setName]),
     * the user's on their owner record in the people it knows
     * ([net.kuafuai.andee.config.People.setOwnerName]). Both ride on every user
     * message from there, so the brain actually uses them. The preferences are
     * kept only so this UI paints instantly without opening the notebook.
     */
    fun saveNames(context: Context, assistantName: String, userName: String) {
        val assistant = assistantName.trim().ifEmpty { "Andee" }
        val user = userName.trim()
        prefs(context).edit()
            .putString(KEY_ASSISTANT_NAME, assistant)
            .putString(KEY_USER_NAME, user)
            .apply()

        runCatching { Notebook.selfhood(context).setName(assistant) }
            .onFailure { android.util.Log.w("Onboarding", "could not save Andee's name", it) }
        runCatching { Notebook.people(context).setOwnerName(user) }
            .onFailure { android.util.Log.w("Onboarding", "could not save the user's name", it) }
    }

    fun complete(context: Context) {
        prefs(context).edit()
            .putInt(KEY_COMPLETE_VERSION, VERSION)
            .putString(KEY_PAGE, Page.READY.name)
            .apply()
    }

    /** Factory reset means the next launch is a first launch again. */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
