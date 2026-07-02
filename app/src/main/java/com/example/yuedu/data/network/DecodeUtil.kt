package com.example.yuedu.data.network

object DecodeUtil {

    private const val CUSTOM_BASE64 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/="

    fun decode(str: String, key: String): String {
        val bytes = customBase64Decode(str)
        return rc4Decrypt(bytes, key)
    }

    private fun customBase64Decode(input: String): ByteArray {
        val result = mutableListOf<Byte>()
        var buffer = 0
        var bitsCollected = 0

        for (ch in input) {
            val value = CUSTOM_BASE64.indexOf(ch)
            if (value < 0 || value >= 64) continue

            buffer = (buffer shl 6) or value
            bitsCollected += 6

            if (bitsCollected >= 8) {
                bitsCollected -= 8
                result.add((buffer shr bitsCollected).toByte())
                buffer = buffer and ((1 shl bitsCollected) - 1)
            }
        }

        return result.toByteArray()
    }

    private fun rc4Decrypt(input: ByteArray, key: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val s = IntArray(256) { it }

        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (keyBytes[i % keyBytes.size].toInt() and 0xFF)) % 256
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }

        var i = 0
        j = 0
        val output = ByteArray(input.size)
        for (k in input.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
            val keystream = s[(s[i] + s[j]) % 256]
            output[k] = (input[k].toInt() xor keystream).toByte()
        }

        return output.toString(Charsets.UTF_8)
    }
}