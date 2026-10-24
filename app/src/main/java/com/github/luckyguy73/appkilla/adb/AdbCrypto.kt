package com.github.luckyguy73.appkilla.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Handles RSA 2048 key pair generation, persistence, token signing,
 * and ADB public key structure formatting required by the ADB auth protocol.
 */
object AdbCrypto {

    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048
    private const val PRIVATE_KEY_FILE = "adb_key.priv"
    private const val PUBLIC_KEY_FILE = "adb_key.pub"

    @Synchronized
    fun getOrCreateKeyPair(context: Context): KeyPair {
        val privFile = File(context.filesDir, PRIVATE_KEY_FILE)
        val pubFile = File(context.filesDir, PUBLIC_KEY_FILE)

        return if (privFile.exists() && pubFile.exists()) {
            try {
                val privBytes = FileInputStream(privFile).use { it.readBytes() }
                val pubBytes = FileInputStream(pubFile).use { it.readBytes() }

                val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
                val privKeySpec = PKCS8EncodedKeySpec(privBytes)
                val pubKeySpec = X509EncodedKeySpec(pubBytes)

                val privateKey = keyFactory.generatePrivate(privKeySpec)
                val publicKey = keyFactory.generatePublic(pubKeySpec)

                KeyPair(publicKey, privateKey)
            } catch (_: Exception) {
                generateAndSaveKeyPair(privFile, pubFile)
            }
        } else {
            generateAndSaveKeyPair(privFile, pubFile)
        }
    }

    private fun generateAndSaveKeyPair(
        privFile: File,
        pubFile: File
    ): KeyPair {
        val keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        keyGen.initialize(KEY_SIZE)
        val keyPair = keyGen.generateKeyPair()

        FileOutputStream(privFile).use { it.write(keyPair.private.encoded) }
        FileOutputStream(pubFile).use { it.write(keyPair.public.encoded) }

        return keyPair
    }

    /**
     * Signs the ADB authentication token using RSA private key.
     * ADB protocol requires SHA1withRSA signature over the token challenge.
     */
    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(token)
        return signature.sign()
    }

    /**
     * Formats the RSA public key into standard ADB public key format (524 bytes)
     * + null-terminated user info string.
     */
    fun getAdbPublicKeyPayload(publicKey: PublicKey): ByteArray {
        val rsaPublicKey = publicKey as RSAPublicKey
        val modulus = rsaPublicKey.modulus
        val exponent = rsaPublicKey.publicExponent

        val numWords = KEY_SIZE / 32 // 64 words for 2048-bit key

        // Calculate n0inv = -1 / N[0] mod 2^32
        val r32 = BigInteger.valueOf(2).pow(32)
        val n0 = BigInteger.valueOf(
            modulus.and(BigInteger.valueOf(0xFFFFFFFFL)).toLong()
        )
        val n0inv = n0.modInverse(r32).negate().add(r32).toLong() and 0xFFFFFFFFL

        // Calculate R^2 mod N
        val r = BigInteger.valueOf(2).pow(KEY_SIZE)
        val r2 = r.multiply(r).mod(modulus)

        val buffer = ByteBuffer.allocate(524)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(numWords)
        buffer.putInt(n0inv.toInt())

        // Add modulus in little endian 32-bit words
        writeBigIntegerWords(buffer, modulus, numWords)

        // Add R^2 in little endian 32-bit words
        writeBigIntegerWords(buffer, r2, numWords)

        buffer.putInt(exponent.toInt())

        val binaryKey = buffer.array()
        val base64Key = Base64.encodeToString(binaryKey, Base64.NO_WRAP)
        val comment = " appkilla@localhost\u0000"

        return (base64Key + comment).toByteArray(Charsets.UTF_8)
    }

    private fun writeBigIntegerWords(buffer: ByteBuffer, value: BigInteger, numWords: Int) {
        val rawBytes = value.toByteArray()
        val padded = ByteArray(numWords * 4)

        val srcPos = if (rawBytes.size > padded.size) rawBytes.size - padded.size else 0
        val length = minOf(rawBytes.size, padded.size)
        val destPos = padded.size - length
        System.arraycopy(rawBytes, srcPos, padded, destPos, length)

        for (i in numWords - 1 downTo 0) {
            val byteOffset = (numWords - 1 - i) * 4
            val word = ((padded[byteOffset].toInt() and 0xFF) shl 24) or
                    ((padded[byteOffset + 1].toInt() and 0xFF) shl 16) or
                    ((padded[byteOffset + 2].toInt() and 0xFF) shl 8) or
                    (padded[byteOffset + 3].toInt() and 0xFF)
            buffer.putInt(word)
        }
    }
}
