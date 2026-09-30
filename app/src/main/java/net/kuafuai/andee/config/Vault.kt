package net.kuafuai.andee.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's own credentials, so the ball can log in as them.
 *
 * One entry per account — a label the brain can search for ("飞书"), the
 * identifiers it may read, and the secrets it may only *use*. The split is the
 * whole design: a secret never crosses the WebSocket. The brain asks the
 * device to type it (`device.vault.fill`) and gets back a length, not a value.
 * See the `fill_secret` description in ToolSchemas.
 *
 * An entry can also hold a bank card, and it is the same mechanism rather than
 * a second one: the number, the expiry and the CVV are three more names in
 * [Entry.secrets], typed and never returned. What it is *not* is a way to pay
 * on a native payment sheet — those keypads are the app's own and are not a
 * focused text field, so what this reaches is a checkout form or the one-time
 * card-binding page. See the `fill_secret` description for the same warning in
 * the words the brain reads.
 *
 * Storage is a JSON array encrypted with an AES/GCM key held in the Android
 * Keystore — hand-rolled rather than via androidx.security because Jetpack
 * Security Crypto is deprecated and this needs no dependency at all. The key
 * material never leaves the secure hardware and dies with the app, so an
 * uninstall is a wipe.
 *
 * Deliberately NOT in `voice_prefs`/[VoiceConfig]: that file is a flat
 * `Map<String, String>` filtered through `ALLOWED_KEYS`, which a list of
 * records cannot enter, and it is written in plaintext.
 */
object Vault {

    private const val TAG = "Body"
    private const val PREFS = "vault_prefs"
    private const val KEY_BLOB = "entries"
    private const val KEY_ALIAS = "body_vault_key"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    data class Entry(
        val id: String,
        val label: String,
        val phone: String = "",
        val email: String = "",
        val username: String = "",
        val note: String = "",
        val password: String = "",
        /**
         * A bank card, for the checkout form that wants one typed in.
         *
         * Three of the four are secrets, on the same rule as [password]: the
         * brain never needs to *reason* about a card number, an expiry or a
         * CVV, it only needs them in a field — so it never holds them. The
         * holder's name is the user's own name and is readable, because a form
         * that asks for it beside the number is asking for something the brain
         * may legitimately have to match against what is already on screen.
         */
        val cardNumber: String = "",
        val cardExpiry: String = "",
        val cardCvv: String = "",
        val cardHolder: String = "",
    ) {
        /** Field names the brain is allowed to read in the clear. */
        fun readable(): Map<String, String> = mapOf(
            "phone" to phone,
            "email" to email,
            "username" to username,
            "note" to note,
            "card_holder" to cardHolder,
            // Already the safe form of a number that is never readable, which
            // is why [masked] lets it through untouched. Without it two cards
            // are two identical rows and the brain has to guess which one the
            // user meant — the one question it must never answer by guessing.
            "card_last4" to cardNumber.filter(Char::isDigit).takeLast(4),
        ).filterValues { it.isNotBlank() }

        /**
         * [readable], as the brain and the card list are allowed to see it.
         *
         * Here rather than at the two call sites because `card_last4` is the
         * one field that must *not* be masked — it is four characters, so
         * [mask] would return `****` and throw away the only thing that
         * distinguishes one card from another.
         */
        fun masked(): Map<String, String> = readable().mapValues { (k, v) ->
            if (k == "card_last4") v else mask(v)
        }

        /** Field names the brain may only ask the device to type. */
        fun secrets(): List<String> = buildList {
            if (password.isNotBlank()) add("password")
            if (cardNumber.isNotBlank()) add("card_number")
            if (cardExpiry.isNotBlank()) add("card_expiry")
            if (cardCvv.isNotBlank()) add("card_cvv")
        }

        /**
         * The value behind a [secrets] name, for the one caller allowed to
         * have it: the device's own typing path.
         *
         * Null for anything not a secret of this entry, so the dispatcher can
         * tell "no such secret" from "saved but empty" without a when-chain of
         * its own that could drift out of step with [secrets].
         */
        fun secretValue(field: String): String? = when (field) {
            "password" -> password
            "card_number" -> cardNumber
            "card_expiry" -> cardExpiry
            "card_cvv" -> cardCvv
            else -> null
        }?.ifBlank { null }
    }

    fun entries(context: Context): List<Entry> {
        val blob = prefs(context).getString(KEY_BLOB, null) ?: return emptyList()
        val json = decrypt(blob) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    phone = o.optString("phone"),
                    email = o.optString("email"),
                    username = o.optString("username"),
                    note = o.optString("note"),
                    password = o.optString("password"),
                    cardNumber = o.optString("card_number"),
                    cardExpiry = o.optString("card_expiry"),
                    cardCvv = o.optString("card_cvv"),
                    cardHolder = o.optString("card_holder"),
                )
            }.filter { it.id.isNotBlank() }
        }.getOrElse {
            Log.e(TAG, "vault: entries unreadable", it)
            emptyList()
        }
    }

    /** Insert or replace by [Entry.id]. */
    fun put(context: Context, entry: Entry) {
        val next = entries(context).filterNot { it.id == entry.id } + entry
        write(context, next)
    }

    fun remove(context: Context, id: String) {
        write(context, entries(context).filterNot { it.id == id })
    }

    /**
     * Find by id or by label. The brain only ever holds the word the user
     * used ("飞书"), so matching on the label is the primary path; case and
     * surrounding space are ignored because that word arrives via an LLM.
     */
    fun find(context: Context, needle: String): Entry? {
        val n = needle.trim().lowercase()
        if (n.isEmpty()) return null
        val all = entries(context)
        return all.firstOrNull { it.id.lowercase() == n }
            ?: all.firstOrNull { it.label.trim().lowercase() == n }
            ?: all.firstOrNull { it.label.trim().lowercase().contains(n) }
    }

    fun newId(): String = "v${System.currentTimeMillis().toString(36)}"

    /**
     * `138****1111` — enough for the brain to confirm it has the right
     * account without pulling every identifier into its context.
     */
    fun mask(value: String): String {
        if (value.isBlank()) return ""
        val at = value.indexOf('@')
        if (at > 0) {
            val name = value.take(at)
            val head = name.take(2)
            return "$head${"*".repeat(maxOf(1, name.length - 2))}${value.substring(at)}"
        }
        if (value.length <= 4) return "*".repeat(value.length)
        if (value.length <= 7) return "${value.take(2)}${"*".repeat(value.length - 2)}"
        return "${value.take(3)}${"*".repeat(value.length - 7)}${value.takeLast(4)}"
    }

    /**
     * Erase every credential. The one caller allowed to lose this on purpose
     * (恢复出厂设置).
     *
     * No cache to invalidate, unlike [Notebook]: [entries] reads straight from
     * prefs every time. `commit()` rather than `apply()` because the caller
     * reports success and the device may lose power a moment later.
     */
    fun wipe(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun write(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("label", e.label)
                    .put("phone", e.phone)
                    .put("email", e.email)
                    .put("username", e.username)
                    .put("note", e.note)
                    .put("password", e.password)
                    .put("card_number", e.cardNumber)
                    .put("card_expiry", e.cardExpiry)
                    .put("card_cvv", e.cardCvv)
                    .put("card_holder", e.cardHolder)
            )
        }
        val blob = encrypt(arr.toString())
        if (blob == null) {
            // Silently keeping the old contents would let the user close the
            // form believing a password was saved.
            Log.e(TAG, "vault: encrypt failed, nothing written")
            return
        }
        prefs(context).edit().putString(KEY_BLOB, blob).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- crypto ----------------------------------------------------

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No setUserAuthenticationRequired: the ball fills a password
                // while the user is watching it work, not while they are at
                // the lock screen, and a biometric prompt mid-task would stop
                // the very automation this exists for.
                .build()
        )
        return gen.generateKey()
    }

    /** `base64(iv || ciphertext)`, or null if the Keystore refused. */
    private fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES),
        )
        String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }.getOrElse {
        // A restored backup or a reset Keystore leaves an undecryptable blob.
        // Say so in the log: from the settings screen it looks identical to
        // never having saved anything.
        Log.e(TAG, "vault: decrypt failed — entries are unreadable on this install", it)
        null
    }
}
