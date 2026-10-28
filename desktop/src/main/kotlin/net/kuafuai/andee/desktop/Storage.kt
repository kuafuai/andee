package net.kuafuai.andee.desktop

import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A secret kept in the user's login Keychain, read and written with the
 * system's own `security` tool — so the key never sits in a file next to the
 * data it protects, the same promise the phone's Keystore makes.
 */
object Keychain {

    private const val TAG = "Keychain"
    private const val SERVICE = "net.kuafuai.andee.mac"

    /** The stored value for [account], or null when there is none. */
    fun get(account: String): String? {
        val p = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w")
            .redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return if (p.waitFor() == 0 && out.isNotEmpty()) out else null
    }

    fun put(account: String, value: String): Boolean {
        val p = ProcessBuilder(
            "/usr/bin/security", "add-generic-password", "-U", "-s", SERVICE, "-a", account, "-w", value,
        ).redirectErrorStream(true).start()
        val err = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0
        if (!ok) Log.e(TAG, "could not write $account: ${err.trim()}")
        return ok
    }

    fun delete(account: String) {
        ProcessBuilder("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account)
            .redirectErrorStream(true).start().waitFor()
    }

    /** A 256-bit AES key under [account], created on first use. Falls back to file storage if Keychain is unavailable. */
    fun aesKey(account: String, fallbackFile: File? = null): ByteArray? {
        // Try Keychain first
        get(account)?.let { return runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.getEncoder().encodeToString(k)

        // Try to save to Keychain
        if (put(account, encoded)) return k

        // Keychain failed, fall back to file
        if (fallbackFile != null) {
            Log.w(TAG, "Keychain unavailable for $account, using file fallback")
            return runCatching {
                if (fallbackFile.exists()) {
                    Base64.getDecoder().decode(fallbackFile.readText().trim())
                } else {
                    fallbackFile.parentFile?.mkdirs()
                    fallbackFile.writeText(encoded)
                    k
                }
            }.getOrElse {
                Log.e(TAG, "file fallback failed for $account", it)
                null
            }
        }

        return null
    }
}

/**
 * The notebook's blob on disk, AES/GCM with the key in the Keychain:
 * `base64(iv || ciphertext)`, the same construction the phone uses.
 *
 * A blob that will not decrypt reads as null and is logged loudly — an
 * unreadable notebook and an empty one look the same from the outside.
 */
class EncryptedFileStore(private val file: File, private val keyAccount: String) : NotebookStore {

    private val keyFile = File(file.parentFile, ".${keyAccount}.key")
    private val key: ByteArray? by lazy { Keychain.aesKey(keyAccount, keyFile) }

    override fun read(): String? {
        if (!file.exists()) return null
        val k = key ?: return null
        return runCatching {
            val raw = Base64.getDecoder().decode(file.readText().trim())
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, raw, 0, IV))
            String(c.doFinal(raw, IV, raw.size - IV), Charsets.UTF_8)
        }.getOrElse {
            Log.e(TAG, "${file.name}: decrypt failed — unreadable on this install", it)
            null
        }
    }

    override fun write(plain: String): Boolean {
        val k = key ?: return false
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"))
            val blob = Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(blob)
            check(tmp.renameTo(file)) { "rename failed" }
            true
        }.getOrElse {
            Log.e(TAG, "${file.name}: write failed", it)
            false
        }
    }

    override fun wipe() {
        file.delete()
    }

    private companion object {
        const val TAG = "Store"
        const val IV = 12
    }
}
