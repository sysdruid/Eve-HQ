package eve.hq.data

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TokenCipherTest {
    private fun newKey() = ByteArray(32).also(SecureRandom()::nextBytes)

    @Test
    fun `a value decrypts to what was encrypted`() {
        val cipher = TokenCipher(newKey())

        assertEquals("a-refresh-token", cipher.decrypt(cipher.encrypt("a-refresh-token")))
    }

    @Test
    fun `the stored form is marked, hides the value and differs on every encryption`() {
        val cipher = TokenCipher(newKey())

        val first = cipher.encrypt("a-refresh-token")
        val second = cipher.encrypt("a-refresh-token")

        assertTrue(cipher.isEncrypted(first))
        assertFalse("a-refresh-token" in first, first)
        assertNotEquals(first, second)
    }

    @Test
    fun `a plain value is not mistaken for an encrypted one`() {
        val cipher = TokenCipher(newKey())

        assertFalse(cipher.isEncrypted("eyJhbGciOiJSUzI1NiJ9.e30.c2ln"))
        assertFalse(cipher.isEncrypted("Zm9vYmFy"))
    }

    @Test
    fun `a tampered value is rejected`() {
        val cipher = TokenCipher(newKey())
        val stored = cipher.encrypt("a-refresh-token")
        val tampered = stored.dropLast(4) + if (stored.endsWith("AAAA")) "BBBB" else "AAAA"

        assertFailsWith<TokenDecryptionFailed> { cipher.decrypt(tampered) }
    }

    @Test
    fun `a value encrypted with another key is rejected`() {
        val stored = TokenCipher(newKey()).encrypt("a-refresh-token")

        assertFailsWith<TokenDecryptionFailed> { TokenCipher(newKey()).decrypt(stored) }
    }

    @Test
    fun `the key file is created once with owner-only access and reused`(@TempDir dir: Path) {
        val first = TokenCipher.loadOrCreate(dir)
        val second = TokenCipher.loadOrCreate(dir)

        val keyFile = dir.resolve(TokenCipher.KEY_FILE)
        assertEquals(32L, Files.size(keyFile))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(keyFile),
        )
        assertEquals("secret", second.decrypt(first.encrypt("secret")))
    }

    @Test
    fun `a damaged key file is reported, not replaced`(@TempDir dir: Path) {
        Files.write(dir.resolve(TokenCipher.KEY_FILE), ByteArray(5))

        val failure = assertFailsWith<TokenDecryptionFailed> { TokenCipher.loadOrCreate(dir) }

        assertTrue(dir.resolve(TokenCipher.KEY_FILE).toString() in failure.message!!, failure.message)
        assertEquals(5L, Files.size(dir.resolve(TokenCipher.KEY_FILE)))
    }
}
