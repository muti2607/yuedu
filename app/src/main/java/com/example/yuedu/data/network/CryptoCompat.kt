package com.example.yuedu.data.network

import java.util.Base64

object CryptoCompat {

    private val SBOX = intArrayOf(
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
    )

    private val RCON = intArrayOf(
        0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36
    )

    fun encryptAes(plaintext: String): String {
        val utf8Bytes = "book@book.html".toByteArray(Charsets.UTF_8)
        val hexString = utf8Bytes.joinToString("") { "%02x".format(it) }

        val iv = hexString.substring(0, 16).toByteArray(Charsets.UTF_8)
        val key = hexString.substring(16).toByteArray(Charsets.UTF_8)

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val blockSize = 16
        val paddingLen = blockSize - (plaintextBytes.size % blockSize)
        val paddedInput = ByteArray(plaintextBytes.size + paddingLen)
        System.arraycopy(plaintextBytes, 0, paddedInput, 0, plaintextBytes.size)
        for (i in plaintextBytes.size until paddedInput.size) {
            paddedInput[i] = paddingLen.toByte()
        }

        val expandedKey = keyExpansion(key)

        var prevBlock = iv
        val output = ByteArray(paddedInput.size)
        for (offset in paddedInput.indices step 16) {
            var state = IntArray(4)
            for (i in 0 until 16) {
                val byteVal = (paddedInput[offset + i].toInt() and 0xFF) xor (prevBlock[i].toInt() and 0xFF)
                val wordIdx = i / 4
                val byteIdx = 3 - (i % 4)
                state[wordIdx] = state[wordIdx] or (byteVal shl (8 * byteIdx))
            }
            state = aesEncryptBlock(state, expandedKey)
            val encrypted = ByteArray(16)
            for (i in 0 until 4) {
                encrypted[i * 4] = (state[i] ushr 24).toByte()
                encrypted[i * 4 + 1] = (state[i] ushr 16).toByte()
                encrypted[i * 4 + 2] = (state[i] ushr 8).toByte()
                encrypted[i * 4 + 3] = state[i].toByte()
            }
            System.arraycopy(encrypted, 0, output, offset, 16)
            prevBlock = encrypted
        }

        return Base64.getEncoder().encodeToString(output)
    }

    private fun keyExpansion(key: ByteArray): IntArray {
        val keySize = key.size / 4
        val expandedKeySize = (keySize + 6 + 1) * 4
        val expandedKey = IntArray(expandedKeySize)

        for (i in 0 until keySize) {
            expandedKey[i] = (key[i * 4].toInt() and 0xFF shl 24) or
                    (key[i * 4 + 1].toInt() and 0xFF shl 16) or
                    (key[i * 4 + 2].toInt() and 0xFF shl 8) or
                    (key[i * 4 + 3].toInt() and 0xFF)
        }

        for (i in keySize until expandedKeySize) {
            var temp = expandedKey[i - 1]
            if (i % keySize == 0) {
                temp = subWord(rotWord(temp)) xor (RCON[i / keySize] shl 24)
            }
            expandedKey[i] = expandedKey[i - keySize] xor temp
        }

        return expandedKey
    }

    private fun subWord(word: Int): Int {
        return (SBOX[word ushr 24 and 0xFF] shl 24) or
                (SBOX[word ushr 16 and 0xFF] shl 16) or
                (SBOX[word ushr 8 and 0xFF] shl 8) or
                (SBOX[word and 0xFF])
    }

    private fun rotWord(word: Int): Int {
        return (word shl 8) or (word ushr 24)
    }

    private fun aesEncryptBlock(state: IntArray, expandedKey: IntArray): IntArray {
        var s = state.copyOf()
        val keySize = expandedKey.size / 4 - 1
        val nRounds = keySize - 6

        addRoundKey(s, expandedKey, 0)

        for (round in 1 until nRounds) {
            subBytes(s)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, expandedKey, round)
        }

        subBytes(s)
        shiftRows(s)
        addRoundKey(s, expandedKey, nRounds)

        return s
    }

    private fun addRoundKey(state: IntArray, expandedKey: IntArray, round: Int) {
        for (i in 0 until 4) {
            state[i] = state[i] xor expandedKey[round * 4 + i]
        }
    }

    private fun subBytes(state: IntArray) {
        for (i in 0 until 4) {
            state[i] = subWord(state[i])
        }
    }

    private fun shiftRows(state: IntArray) {
        val s = IntArray(4)
        for (i in 0 until 4) {
            s[i] = state[i]
        }
        for (i in 0 until 4) {
            state[i] = (s[i] and 0xFF000000.toInt()) or
                    (s[(i + 1) % 4] and 0x00FF0000) or
                    (s[(i + 2) % 4] and 0x0000FF00) or
                    (s[(i + 3) % 4] and 0x000000FF)
        }
    }

    private fun mixColumns(state: IntArray) {
        for (i in 0 until 4) {
            val a = IntArray(4)
            for (j in 0 until 4) {
                a[j] = state[j] ushr (24 - 8 * i) and 0xFF
            }
            val b = IntArray(4)
            b[0] = gmul(0x02, a[0]) xor gmul(0x03, a[1]) xor a[2] xor a[3]
            b[1] = a[0] xor gmul(0x02, a[1]) xor gmul(0x03, a[2]) xor a[3]
            b[2] = a[0] xor a[1] xor gmul(0x02, a[2]) xor gmul(0x03, a[3])
            b[3] = gmul(0x03, a[0]) xor a[1] xor a[2] xor gmul(0x02, a[3])
            for (j in 0 until 4) {
                val shift = 24 - 8 * i
                state[j] = (state[j] and (0xFF shl shift).inv()) or (b[j] shl shift)
            }
        }
    }

    private fun gmul(a: Int, b: Int): Int {
        var result = 0
        var aa = a
        var bb = b
        for (i in 0 until 8) {
            if (bb and 1 != 0) result = result xor aa
            val carry = aa and 0x80
            aa = (aa shl 1) and 0xFF
            if (carry != 0) aa = aa xor 0x1b
            bb = bb shr 1
        }
        return result
    }
}