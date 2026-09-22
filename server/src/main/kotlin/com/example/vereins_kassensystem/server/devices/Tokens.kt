package com.example.vereins_kassensystem.server.devices

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Gerätetoken und Kopplungscodes (Spezifikation 5.2).
 *
 * Ein Gerätetoken ist `vd_dev_` plus Base64 aus Geräte-ID (16 Byte) und Geheimnis
 * (32 Byte Zufall). Die ID steckt im Token, damit der Server das Gerät nachschlagen kann,
 * ohne den Hash zu kennen — Argon2id ist gesalzen, danach lässt sich nicht suchen.
 */
object Tokens {

    private const val PREFIX = "vd_dev_"
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** Buchstaben und Ziffern ohne 0/O und 1/I — wird am Tresen vorgelesen. */
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    class DeviceToken(val deviceId: UUID, val secret: ByteArray)

    fun newDeviceToken(deviceId: UUID): String {
        val secret = ByteArray(32).also(random::nextBytes)
        val bytes = ByteBuffer.allocate(48)
            .putLong(deviceId.mostSignificantBits)
            .putLong(deviceId.leastSignificantBits)
            .put(secret)
            .array()
        return PREFIX + encoder.encodeToString(bytes)
    }

    fun parseDeviceToken(token: String): DeviceToken? {
        if (!token.startsWith(PREFIX)) return null
        val bytes = try {
            decoder.decode(token.removePrefix(PREFIX))
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size != 48) return null
        val buffer = ByteBuffer.wrap(bytes)
        val id = UUID(buffer.getLong(), buffer.getLong())
        return DeviceToken(id, bytes.copyOfRange(16, 48))
    }

    /** "8K4M-2QX9" */
    fun newPairingCode(): String {
        val chars = CharArray(8) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
        return String(chars, 0, 4) + "-" + String(chars, 4, 4)
    }

    /** Was jemand eintippt, tolerant: Kleinbuchstaben, Leerzeichen, fehlender Bindestrich. */
    fun normalizePairingCode(input: String): String =
        input.uppercase().filter { it in CODE_ALPHABET }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/**
 * Argon2id im PHC-Format, `$argon2id$v=19$m=19456,t=2,p=1$<salz>$<hash>`.
 *
 * Die Parameter sind das OWASP-Minimum. Die Geheimnisse haben 256 Bit Zufall — die Härte
 * schützt einen kopierten Datenbankauszug, nicht gegen Raten, und darf deshalb so gewählt
 * sein, dass ein Raspberry Pi jede Anfrage in Millisekunden prüft.
 */
object Argon2 {

    private const val MEMORY_KB = 19_456
    private const val ITERATIONS = 2
    private const val LANES = 1
    private const val HASH_BYTES = 32
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    fun hash(secret: ByteArray): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(secret, salt, MEMORY_KB, ITERATIONS, LANES)
        return "\$argon2id\$v=19\$m=$MEMORY_KB,t=$ITERATIONS,p=$LANES\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    fun verify(secret: ByteArray, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        val memory = params["m"] ?: return false
        val iterations = params["t"] ?: return false
        val lanes = params["p"] ?: return false
        val salt = decoder.decode(parts[4])
        val expected = decoder.decode(parts[5])
        val actual = derive(secret, salt, memory, iterations, lanes)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(secret: ByteArray, salt: ByteArray, memoryKb: Int, iterations: Int, lanes: Int): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(lanes)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        return ByteArray(HASH_BYTES).also { generator.generateBytes(secret, it) }
    }
}
