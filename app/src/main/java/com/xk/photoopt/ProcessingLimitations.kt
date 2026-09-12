package com.xk.photoopt

/** Shared with the scanner so the documented filename-pair rule is symmetric. */
object MediaFormats {
    val images = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif", "dng")
    val videos = setOf("mp4", "mov", "m4v", "mkv", "3gp", "avi", "webm")
    val pairedImages = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    val pairedVideos = setOf("mp4", "mov")
}

data class ProcessingLimitation(val title: String, val explanation: String)
data class LimitationGroup(val title: String, val items: List<ProcessingLimitation>)

val processingLimitations = listOf(
    LimitationGroup("", listOf(
        ProcessingLimitation("vivo 实况",
            "照片和视频成组压缩，保留配对，生成后仍能动。部分其他厂商的实况暂不支持。"),
        ProcessingLimitation("自动去掉 HDR",
            "照片小图会移除 HDR 增益图，更省空间；不影响已支持实况的动态播放。"),
        ProcessingLimitation("压缩到多大",
            "照片长边最多 ${Quality.COMPACT.edge}，JPEG 质量 ${Quality.COMPACT.jpeg}。普通视频和 vivo 实况视频长边最多 ${Quality.COMPACT.videoEdge}，目标 ${Quality.COMPACT.bitrate / 1_000_000} Mbps，保留音频。部分 Google 单文件实况的视频原样保留。"),
        ProcessingLimitation("兼容模式",
            "HEIC / HEIF 及其他能读取主图的部分不兼容图片，可开启兼容模式转为普通 JPEG。只保留主图，尽量保留拍摄时间、位置，动态、深度和其他附加信息不保留。设备无法解码时仍会跳过或失败。不是所有跳过项都能解决；仍不支持的格式可联系作者适配。"),
        ProcessingLimitation("原件和小图",
            "原件不会自动删除，已有输出不覆盖。小文件或压缩后未变小的文件会复制并写入标记。确认备份与小图后再清理原件，才会释放空间。"),
        ProcessingLimitation("联系作者",
            "微信：xk3440395。请提供手机型号和跳过原因，样本注意保护隐私。")
    ))
)
