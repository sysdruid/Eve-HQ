package eve.hq.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TokenDecryptionFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** AES-256-GCM for tokens at rest. Stored form: `v1:` + base64(nonce + ciphertext + tag). */
class TokenCipher(key: ByteArray) {
    private val secretKey = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        }
        return PREFIX + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.toByteArray()))
    }

    fun decrypt(stored: String): String {
        try {
            require(isEncrypted(stored)) { "not an encrypted value" }
            val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, NONCE_BYTES)))
            }
            return String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES))
        } catch (e: GeneralSecurityException) {
            throw TokenDecryptionFailed("could not decrypt a stored token (wrong key or damaged data)", e)
        } catch (e: IllegalArgumentException) {
            throw TokenDecryptionFailed("could not decrypt a stored token (${e.message})", e)
        } catch (e: IndexOutOfBoundsException) {
            throw TokenDecryptionFailed("could not decrypt a stored token (too short)", e)
        }
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        const val KEY_FILE = "token.key"
        private const val PREFIX = "v1:"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        fun loadOrCreate(dir: Path): TokenCipher {
            val file = dir.resolve(KEY_FILE)
            if (Files.exists(file)) {
                val key = Files.readAllBytes(file)
                if (key.size != KEY_BYTES) {
                    throw TokenDecryptionFailed("$file is damaged: expected $KEY_BYTES bytes, found ${key.size}. Restore it from a backup; stored tokens cannot be read without it.")
                }
                return TokenCipher(key)
            }
            val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
            Files.createDirectories(dir)
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.write(file, key)
            return TokenCipher(key)
        }
    }
}
