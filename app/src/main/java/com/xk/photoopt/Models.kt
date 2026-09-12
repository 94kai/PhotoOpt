package com.xk.photoopt

import java.io.File
import java.util.Locale

const val MARKER = "PhotoOpt:v1"

enum class Quality(val title: String, val caption: String, val edge: Int, val jpeg: Int, val videoEdge: Int, val bitrate: Int) {
    ORIGINAL("保留像素", "原分辨率 · 温和压缩", 0, 85, 1920, 6_000_000),
    BALANCED("均衡", "长边 2560 · 日常够清晰", 2560, 82, 1920, 4_000_000),
    COMPACT("更省空间", "长边 1920 · 适合手机看", 1920, 76, 1280, 1_000_000)
}

data class MediaEntry(
    val source: String,
    val root: String,
    val relative: String,
    val size: Long,
    val modified: Long,
    val width: Int = 0,
    val height: Int = 0,
    val kind: String = "图片",
    val reason: String? = null,
    val taken: String? = null,
    val hasGps: Boolean = false,
    val motionOffset: Long = 0,
    val durationMs: Long = 0,
    val metadataRead: Boolean = true,
    val vivoId: String? = null,
    val vivoPartner: String? = null,
    val copyOriginal: Boolean = false,
    val primaryOnly: Boolean = false,
    val stillOnly: Boolean = false,
    val outputExists: Boolean = false,
    val forceStaticAllowed: Boolean = false,
    val forcedStatic: Boolean = false,
    val sourceFormat: String? = null
) {
    val name: String get() = File(source).name
    val eligible: Boolean get() = reason == null
    fun destination(prefix: String): File {
        val directory = File(root)
        val original = File(File(directory.parentFile, prefix + directory.name), relative)
        if (copyOriginal) {
            val actual = sourceFormat
            val matches = original.extension.lowercase() == actual || (actual == "jpg" && original.extension.equals("jpeg", true))
            return if (actual != null && !matches) File(original.parentFile, original.nameWithoutExtension + "." + actual) else original
        }
        return if ((sourceFormat ?: File(source).extension.lowercase()) in MediaFormats.videos) File(original.parentFile, original.nameWithoutExtension + ".mp4") else if (original.extension.lowercase() !in setOf("jpg", "jpeg")) File(original.parentFile, original.nameWithoutExtension + ".jpg") else original
    }
}

data class Outcome(val source: String, val output: String, val state: String, val detail: String,
                   val before: Long, val after: Long = 0, val alreadyExists: Boolean = false)

data class BatchState(val running: Boolean = false, val completed: Int = 0, val total: Int = 0,
                      val current: String = "", val results: List<Outcome> = emptyList(),
                      val error: String? = null, val cancelled: Boolean = false, val cleanupNote: String? = null,
                      val id: String = "", val startedAt: Long = 0, val endedAt: Long = 0,
                      val profile: String = "", val prefix: String = "") {
    val saved: Long get() = results.filter { it.state == "完成" }.sumOf { if (it.detail.startsWith("原样复制")) 0L else it.before - it.after }
    val outputBytes: Long get() = results.filter { it.state == "完成" }.sumOf { it.after }
}

fun bytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
    value >= 1024 -> String.format(Locale.US, "%.0f KB", value / 1024.0)
    else -> "$value B"
}
