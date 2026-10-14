package dev.wearbrdgeadb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object WifiFileProtocol {
    const val CHUNK_SIZE = 64 * 1024
    const val DATA = 1
    const val END = 2
    const val RESULT = 3

    data class Record(val type: Int, val payload: ByteArray)

    fun authenticateServer(input: DataInputStream, output: DataOutputStream, key: ByteArray, id: String) {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        output.write(challenge)
        output.flush()
        val received = ByteArray(32)
        input.readFully(received)
        val expected = hmac(key, challenge + id.toByteArray(Charsets.UTF_8))
        val accepted = MessageDigest.isEqual(received, expected)
        output.writeByte(if (accepted) 1 else 0)
        output.flush()
        if (!accepted) throw SecurityException("Wi-Fi transfer authentication failed")
    }

    fun authenticateClient(input: DataInputStream, output: DataOutputStream, key: ByteArray, id: String) {
        val challenge = ByteArray(32)
        input.readFully(challenge)
        output.write(hmac(key, challenge + id.toByteArray(Charsets.UTF_8)))
        output.flush()
        if (input.readUnsignedByte() != 1) throw SecurityException("Wi-Fi transfer authentication rejected")
    }

    fun writer(output: DataOutputStream, key: ByteArray, id: String) = RecordWriter(output, key, id)
    fun reader(input: DataInputStream, key: ByteArray, id: String) = RecordReader(input, key, id)

    private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(bytes)
    }

    private fun aad(id: String, sequence: Long): ByteArray =
        id.toByteArray(Charsets.UTF_8) + ByteBuffer.allocate(8).putLong(sequence).array()

    class RecordWriter internal constructor(private val output: DataOutputStream, key: ByteArray, private val id: String) {
        private val secret = SecretKeySpec(key, "AES")
        private var sequence = 0L

        fun write(type: Int, payload: ByteArray = byteArrayOf()) {
            require(payload.size <= CHUNK_SIZE)
            val plain = ByteArray(payload.size + 1)
            plain[0] = type.toByte()
            payload.copyInto(plain, 1)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(id, sequence++))
            val encrypted = cipher.doFinal(plain)
            output.writeInt(encrypted.size)
            output.write(nonce)
            output.write(encrypted)
            output.flush()
        }
    }

    class RecordReader internal constructor(private val input: DataInputStream, key: ByteArray, private val id: String) {
        private val secret = SecretKeySpec(key, "AES")
        private var sequence = 0L

        fun read(): Record {
            val length = try { input.readInt() } catch (e: EOFException) { throw e }
            if (length !in 17..(CHUNK_SIZE + 17)) throw SecurityException("Invalid encrypted record length")
            val nonce = ByteArray(12)
            input.readFully(nonce)
            val encrypted = ByteArray(length)
            input.readFully(encrypted)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(id, sequence++))
            val plain = cipher.doFinal(encrypted)
            if (plain.isEmpty()) throw SecurityException("Empty encrypted record")
            return Record(plain[0].toInt() and 0xff, plain.copyOfRange(1, plain.size))
        }
    }
}
