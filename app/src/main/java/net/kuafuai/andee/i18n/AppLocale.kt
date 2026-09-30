package net.kuafuai.andee.i18n

import android.content.Context
import android.content.res.Configuration
import net.kuafuai.andee.config.VoiceConfig
import java.util.Locale

/**
 * Turns "which language did the user pick" into "which Context the UI should be
 * built from". The one door between [VoiceConfig.uiLanguage] and `getString`.
 *
 * **Why this exists at all.** Android ships per-app language
 * (`AppCompatDelegate.setApplicationLocales`) and this app cannot use it as the
 * whole answer, for two independent reasons:
 *
 *  * **Almost no UI here lives in an Activity.** The ball, the card, the
 *    subtitle band, the signboards and the settings sheet are all
 *    `WindowManager`-added overlays owned by [net.kuafuai.andee.ScreenBodyService],
 *    which is a `Service`. `setApplicationLocales` rebuilds Activities; it does
 *    nothing for a window whose Context belonged to a Service, and nothing at
 *    all for views already attached. So the overlays would keep the old language
 *    until something happened to rebuild them.
 *  * **The language is a setting, not the system locale.** [VoiceConfig.uiLanguage]
 *    lets the user pick 中文 on an English tablet, and once they pick, that
 *    choice outranks the system forever. A device whose locale says `en` but
 *    whose preference says `zh` is correct and expected here.
 *
 * So language travels with the Context instead: [wrap] hands back a Context
 * whose locale is the *preference*, and every UI class takes its strings from
 * that. Nothing reads `getString` off a raw Service Context.
 *
 * **The trap this is shaped around.** Getting this wrong does not crash.
 * `getString` on an unwrapped Context silently returns the *system* language —
 * so a Chinese tablet shows a Chinese 取消 button while everything built from
 * the wrapped Context is English, and it reads as "that one button is hardcoded"
 * rather than "that call site missed the wrap". Hence: one entry point, called
 * where the UI object is constructed, and nowhere else.
 *
 * **What [wrap]'s result may and may not be used for.** Strings and inflating
 * views — yes. System services and window management — no. Keep
 * `getSystemService(WINDOW_SERVICE)` and `addView` on the original Context: a
 * configuration Context is a thin wrapper over the base, and handing it to the
 * window manager is asking for a layer-mismatch bug in exchange for nothing.
 * [net.kuafuai.andee.ui.SettingsUi] is the shape to copy — it holds the raw
 * `context` for its window and a wrapped `ctx` for its text.
 *
 * Deliberately not cached. `createConfigurationContext` is cheap, a UI object
 * is built once per open, and a cache keyed on language would hold a Context —
 * and through it a Resources — alive for the process lifetime to save
 * microseconds nobody is waiting on.
 */
object AppLocale {

    /**
     * The locale the UI should be in, from the saved preference.
     *
     * [VoiceConfig.uiLanguage] returns exactly the two tags this app ships
     * (`zh` / `en`) and falls through to the device locale when nothing is
     * saved, so there is no third case to handle here.
     */
    fun localeOf(context: Context): Locale =
        Locale.forLanguageTag(VoiceConfig.uiLanguage(context))

    /**
     * [context] with its locale overridden to the user's choice.
     *
     * Returns [context] unchanged when it is already right, so the common
     * Chinese-device-Chinese-preference case costs nothing.
     */
    fun wrap(context: Context): Context {
        val locale = localeOf(context)
        val base = context.resources.configuration
        // locales[0] rather than the deprecated `locale` field: on API 24+ the
        // primary locale lives in the list, and minSdk here is 30.
        if (base.locales[0] == locale) return context
        val config = Configuration(base)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** One string, in the user's language. For call sites with no Context to keep. */
    fun str(context: Context, id: Int): String = wrap(context).getString(id)

    /** [str] with `%s` / `%d` arguments substituted. */
    fun str(context: Context, id: Int, vararg args: Any): String =
        wrap(context).getString(id, *args)
}
