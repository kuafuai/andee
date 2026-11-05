package net.kuafuai.andee.desktop.pet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kuafuai.andee.desktop.Settings

/**
 * The desk pet: a small character that lives on the desktop beside the floating ball.
 *
 * Why a separate window instead of a skin for the ball: the ball *is* the product's face
 * (its taps start and stop listening, its shader draws the mood), and a pet that inherited
 * those gestures would make "pat the cat" a control. The pet gets the gestures that cannot
 * be misread — drag, throw, double-click — and is otherwise a presence that can be
 * switched off.
 *
 * [character] is a [PetLook] id, not an enum: the presets are two, but the user can add
 * their own picture, and an enum cannot grow at runtime.
 */
data class PetConfig(
    val enabled: Boolean = true,
    val character: String = PetLibrary.PRESETS.first().id,
    val scale: Int = DEFAULT_SCALE,
) {
    val look: PetLook get() = PetLibrary.of(character)

    /** The height the window is built from; the width follows the art's aspect. */
    val height: Int get() = look.baseHeight * scale / 100

    companion object {
        const val MIN_SCALE = 60
        const val MAX_SCALE = 180

        /** The three steps the settings card offers, as a percentage of the character's own height. */
        const val DEFAULT_SCALE = 100

        /**
         * What is in force. Unset reads as **off**: a fresh install wears Andee's ball, and the pet is
         * something the user picks in Settings (2026-11-05). Only an exact "on" shows her.
         */
        fun load(): PetConfig = PetConfig(
            enabled = Settings.get("pet").trim().lowercase() == "on",
            character = Settings.get("pet_character").ifBlank { PetLibrary.PRESETS.first().id },
            scale = Settings.get("pet_size").toIntOrNull()?.coerceIn(MIN_SCALE, MAX_SCALE) ?: DEFAULT_SCALE,
        )
    }
}

/**
 * The live config, as Compose state: the settings card writes the file and calls
 * [reload], and the window that reads `config` follows in the same frame. One source, so
 * the card and the window cannot disagree about which pet is standing there.
 */
object Pet {
    var config by mutableStateOf(PetConfig.load())
        private set

    /**
     * Whether the pet is the body now — set by Main.kt, which decides between her and the ball. A flag rather
     * than asking the bridge here: the brain reads the persona on every turn, and loading the native bridge
     * from a test JVM gets it killed.
     */
    @Volatile var isBody: Boolean = false

    /**
     * The voice and the manner in force: the pet's while she is the body, else the ball look's. One place, so
     * the voice, the prompt and what is on screen cannot belong to three different characters.
     */
    fun voice(): String =
        if (isBody) config.look.voice
        else net.kuafuai.andee.ui.ball.BallLooks.byName(Settings.get("ball").ifEmpty { null }).voice

    fun persona(): String =
        if (isBody) config.look.persona
        else net.kuafuai.andee.ui.ball.BallLooks.byName(Settings.get("ball").ifEmpty { null }).persona

    fun reload() {
        config = PetConfig.load()
    }

    /** Hiding from the settings card and from the window's close both land here. */
    fun hide() {
        Settings.save(mapOf("pet" to "off"))
        reload()
    }
}
