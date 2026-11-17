package com.nuvio.app.features.auth

/**
 * Minimal QR code encoder (byte mode, error correction level M, versions 1-10).
 *
 * Enough for device-login URLs (up to 213 bytes) and dependency-free so it runs on every
 * platform. Adapted from Project Nayuki's QR Code generator (MIT License).
 *
 * Returns the module grid as `[y][x]` (true = dark), or null if the text is too long.
 */
internal object QrCodeEncoder {
    private const val MAX_VERSION = 10
    private const val FORMAT_BITS_M = 0

    // Index = version; level M only.
    private val eccCodewordsPerBlock = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val numErrorCorrectionBlocks = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.encodeToByteArray()
        val version = (1..MAX_VERSION).firstOrNull { ver ->
            4 + charCountBits(ver) + bytes.size * 8 <= numDataCodewords(ver) * 8
        } ?: return null
        return Symbol(version, encodeData(bytes, version)).modules
    }

    private fun charCountBits(version: Int) = if (version <= 9) 8 else 16

    private fun numRawDataModules(ver: Int): Int {
        var result = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val numAlign = ver / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (ver >= 7) result -= 36
        }
        return result
    }

    private fun numDataCodewords(ver: Int) =
        numRawDataModules(ver) / 8 - eccCodewordsPerBlock[ver] * numErrorCorrectionBlocks[ver]

    private fun encodeData(bytes: ByteArray, version: Int): IntArray {
        val bits = ArrayList<Boolean>()
        fun append(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits += (value ushr i) and 1 != 0
        }
        append(0b0100, 4)
        append(bytes.size, charCountBits(version))
        bytes.forEach { append(it.toInt() and 0xFF, 8) }

        val capacityBits = numDataCodewords(version) * 8
        append(0, minOf(4, capacityBits - bits.size))
        append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacityBits) {
            append(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }

        val data = IntArray(bits.size / 8)
        bits.forEachIndexed { i, bit -> if (bit) data[i ushr 3] = data[i ushr 3] or (1 shl (7 - (i and 7))) }
        return addEccAndInterleave(data, version)
    }

    private fun addEccAndInterleave(data: IntArray, version: Int): IntArray {
        val numBlocks = numErrorCorrectionBlocks[version]
        val blockEccLen = eccCodewordsPerBlock[version]
        val rawCodewords = numRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks
        val divisor = reedSolomonDivisor(blockEccLen)

        var k = 0
        val blocks = Array(numBlocks) { i ->
            val datLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            val block = dat.copyOf(shortBlockLen + 1)
            val ecc = reedSolomonRemainder(dat, divisor)
            ecc.copyInto(block, block.size - blockEccLen)
            block
        }

        val result = IntArray(rawCodewords)
        var r = 0
        for (i in blocks[0].indices) {
            for (j in blocks.indices) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[r++] = blocks[j][i]
                }
            }
        }
        return result
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in result.indices) {
                result[j] = gfMultiply(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            result.copyInto(result, 0, 1, result.size)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
        }
        return result
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private class Symbol(private val version: Int, codewords: IntArray) {
        private val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        private val isFunction = Array(size) { BooleanArray(size) }

        init {
            drawFunctionPatterns()
            drawCodewords(codewords)
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormatBits(mask)
                val penalty = penaltyScore()
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    bestMask = mask
                }
                applyMask(mask) // XOR again to undo.
            }
            applyMask(bestMask)
            drawFormatBits(bestMask)
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val positions = alignmentPositions()
            val last = positions.size - 1
            for (i in positions.indices) {
                for (j in positions.indices) {
                    val corner = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                    if (!corner) drawAlignment(positions[i], positions[j])
                }
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun drawFinder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, dist != 2 && dist != 4)
            }
        }

        private fun drawAlignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2) {
                set(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }

        private fun drawFormatBits(mask: Int) {
            val data = FORMAT_BITS_M shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = version shl 12 or rem
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, bit)
                set(b, a, bit)
            }
        }

        private fun drawCodewords(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y][x] && i < data.size * 8) {
                            modules[y][x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
            }
        }

        /** Simplified penalty (runs, 2x2 blocks, dark balance); any mask decodes correctly. */
        private fun penaltyScore(): Int {
            var penalty = 0
            for (line in 0 until size) {
                var rowColor = false
                var rowRun = 0
                var colColor = false
                var colRun = 0
                for (k in 0 until size) {
                    val r = modules[line][k]
                    if (k == 0 || r != rowColor) { rowColor = r; rowRun = 1 } else if (++rowRun == 5) penalty += 3 else if (rowRun > 5) penalty++
                    val c = modules[k][line]
                    if (k == 0 || c != colColor) { colColor = c; colRun = 1 } else if (++colRun == 5) penalty += 3 else if (colRun > 5) penalty++
                }
            }
            var dark = 0
            for (y in 0 until size) for (x in 0 until size) {
                if (modules[y][x]) dark++
                if (x < size - 1 && y < size - 1) {
                    val c = modules[y][x]
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) penalty += 3
                }
            }
            val total = size * size
            val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            return penalty + k * 10
        }
    }
}
