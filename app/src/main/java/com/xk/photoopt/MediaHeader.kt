package com.xk.photoopt

import java.io.File

object MediaHeader {
    /** A short header read; extensions are only a fallback for formats not identified here. */
    fun imageFormat(file: File): String? = file.inputStream().use { input ->
        val bytes = ByteArray(256)
        var size = 0
        while (size < bytes.size) { val n = input.read(bytes, size, bytes.size - size); if (n < 0) break; size += n }
        val brands = if (size >= 16 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") {
            val boxSize = java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
            val limit = minOf(size.toLong(), boxSize).toInt()
            listOf(String(bytes, 8, 4, Charsets.US_ASCII)) + (16 until limit - 3 step 4).map { String(bytes, it, 4, Charsets.US_ASCII) }
        } else emptyList()
        when {
            size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
            size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "png"
            size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
            brands.any { it in setOf("avif", "avis") } -> "avif"
            brands.any { it in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs") } -> "heic"
            else -> null
        }
    }
}
