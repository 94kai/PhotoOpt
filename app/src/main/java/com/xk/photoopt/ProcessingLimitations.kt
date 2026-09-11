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
    LimitationGroup("格式与内容限制", listOf(
        ProcessingLimitation("01  同目录同名照片 / 视频：疑似实况配对",
            "例如 IMG_001.jpg + IMG_001.mov。先比较同一目录、不含扩展名的文件名，忽略大小写；仅凭同名不能确认实况。其中能确认配对 ID 一致的 vivo JPEG + MP4 实况，按 B 方案成组压缩，保留原尺寸、HDR 增益图及私有配对信息；每次输出使用新的配对 ID。勾选“实况只保留照片”后，仅生成静态主图、不输出配对视频，原始两份文件保留。未知或不一致的配对仍不压缩、不复制。只选其中一个会跳过；同时选择两份或添加整个来源目录，识别后可成组选中。\n\n配对范围：JPG / JPEG / PNG / WebP / HEIC / HEIF 与 MP4 / MOV。同名的两张照片不会因此被判成实况。若只是碰巧同名，可自行区分文件名后重新扫描。"),
        ProcessingLimitation("02  单文件实况图 / 未知附加数据",
            "实况不一定是两个文件，也可能在一张 JPEG 内附带视频。已识别的 Google JPEG 实况默认只压缩静态部分，视频原样保留并校验；勾选“实况只保留照片”后仅输出普通主图，小图不能动。Samsung、其他未知结构或无法定位的附加数据默认跳过。含特殊拍摄速率元数据的 JPEG 也默认跳过。若 JPEG 主图已经成功读取，可在文件页勾选“强制转普通图片”，随后手动勾选这些文件，仅保留静态主图，动态、HDR 增益图及未知附加数据会移除，副本写入 forcedStatic 标记。损坏 JPEG、其他格式和同名输出冲突不会因此被放行。"),
        ProcessingLimitation("03  Apple Live Photo 视频",
            "视频内含 Apple 实况关联标识或 still-image-time 信息时，即使旁边没有同名照片，也会跳过视频。仅凭文件名无法发现所有改过名字的配对，当前不是完整的 Apple 实况识别器。"),
        ProcessingLimitation("04  暂不转码的图片格式",
            "HEIC / HEIF、AVIF、GIF、DNG 会列入扫描结果，但不转码。目录扫描当前只收集 ${MediaFormats.images.joinToString(" / ") { it.uppercase() }}；其他图片格式不会出现在目录扫描清单中。"),
        ProcessingLimitation("05  动图与透明像素",
            "动态 PNG（APNG）和动态 WebP 会跳过，GIF 也不处理。静态 PNG / WebP 转 JPEG 时，若发现透明像素，会停止该文件并报告原因，避免透明背景被填成不正确的颜色；透明像素检查发生在实际处理阶段，扫描 不能提前确定所有情况。"),
        ProcessingLimitation("06  HDR / 多画面 JPEG",
            "HDR / 多画面 JPEG 可仅压缩主图，生成普通 JPEG，保留时间、位置、方向等基础拍摄信息。副本不保留 HDR 增益图、附加画面及相关 XMP / 厂商私有信息，并写入 primaryOnly 标记；原件不变。已确认的 vivo 配对继续走 B 方案，保留已支持的增益图与配对信息。HDR 视频仍不转码。"),
        ProcessingLimitation("07  视频容器、特殊速率与设备限制",
            "当前转码 MP4 / MOV / M4V；扫描到的 MKV / 3GP / AVI / WebM 会跳过，其他扩展名不纳入目录扫描。普通拍摄帧率标记不会直接拦截：拍摄与播放帧率一致（允许 5% 偏差）时支持压缩；两者不一致、无法确认或含慢动作元数据时跳过；剪映 slowMotion=none 等明确关闭的值不会因此跳过。容器可支持不代表内部编码一定能被手机解码；无视频轨道、编码器不支持等问题仍可能在读取或实际转码时失败。")
    )),
    LimitationGroup("文件可支持，但本次不处理", listOf(
        ProcessingLimitation("08  已处理 / 已经很小 / 未选中",
            "已有 PhotoOpt:v1 标记的文件不重复压缩；小于 180 KiB（184,320 字节）的支持格式图片原样复制，并写入 action=copy 标记。复制只改元数据，不重新编码画面。未勾选的文件不处理。vivo 实况照片与视频必须成组选择；关闭“是否处理视频”只影响普通视频的自动勾选，仍可在清单中手动选择可处理的视频。"),
        ProcessingLimitation("09  输出重名：与实况配对不同",
            "① 目标位置或配对文件已有同名正式输出：扫描时整组跳过，不覆盖；“隐藏已处理”默认勾选，关闭可查看。扫描时已确认的这些文件不计入新任务总数或记录；生成途中发现同名输出仍记为跳过。\n② 多个源文件将生成同一个目标：相关文件都跳过，例如 A.png 与 A.jpg 都将输出 A.jpg，或 A.mov 与 A.mp4 都输出 A.mp4。可只添加其中一个文件作为来源，或自行调整命名后重新扫描。\n\n这是输出位置冲突，不是照片与视频的实况配对。"),
        ProcessingLimitation("10  压缩后没有更小",
            "编码后若体积不小于原文件，改为原样复制，保留原扩展名并写入 action=copy 标记。元数据增加可能让副本略大，压缩减少量记 0；记录会注明复制原因。实际大小只有编码后才能知道，扫描 不会估计为确定结果。")
    )),
    LimitationGroup("访问与处理过程中的限制", listOf(
        ProcessingLimitation("11  来源不可访问 / 不纳入扫描",
            "仅支持能解析成本地路径的手机或 SD 卡文件；云文件提供商、存储根目录不作为来源。隐藏子目录、当前输出前缀目录、输出目录和扫描到的符号链接被排除。缺少文件访问或照片位置信息权限时，需要先授权。损坏文件、无法读取的元数据会显示读取失败。"),
        ProcessingLimitation("12  文件变化、内存、空间与校验失败",
            "扫描后原文件大小或修改时间变化时，跳过或停止该文件，请重新扫描。超大图片可能超出内存预算，可改选均衡或更省空间。空间不足、无法创建目录、无法恢复修改时间、编码失败，或时间 / GPS / 标记 / 实况视频校验失败时，不提交该文件的结果，并记录原因。\n\n“可处理”表示通过扫描检查，不保证实际处理必定成功。所有这些情况都不会删除或修改原件。正式处理前会清理本应用的临时残留；扫描只读，不删除任何文件。中断后重新扫描处理即可，不做断点续传。")
    ))
)
