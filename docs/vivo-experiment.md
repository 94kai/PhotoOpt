# vivo 实况试转（2026-09-12）

此次先生成独立样本，未将 vivo 配对加入普通批量压缩。用户需要在 vivo 相册中确认识别、动态播放和画面效果。

样本 `IMG_20260411_121639.jpg + .mp4` 的 JPEG 尾部和 MP4 顶层 `uuid/vivoMediaExtInfo` 带有一致的 `com.android.camera.livephoto` 标识。JPEG 还带有 MPF 辅助图像 / HDR 增益图。只保留同名不足以证明关联数据完整。

- `Pictures/实验-A-照片压缩`：照片保持 4096×3072，JPEG 质量 76；视频音画数据不编码。
- `Pictures/实验-B-照片视频压缩`：照片同 A；视频 H.264，目标 2 Mbps，保持 1440×1080 和原播放时间线，音频直接复制。

照片复制原元数据段和尾部数据，并修正 MPF 中随主图大小变化的偏移；视频保留 vivo 私有 UUID 块。每组采用不同的等长关联 ID，避免与原件或另一组混配。原件未修改。输出文件恢复源修改时间。

实验照片在 JPEG Comment 写入 `PhotoOpt:v1; experimental=...`，B 视频通过 MP4 mdta 写入 PhotoOpt 标记，A 视频另附 PhotoOpt UUID 元数据块。实验标记位置与正式图片的 EXIF UserComment 不同；实验目录请勿加入备份或当成普通来源再次批量压缩。

已通过命令核对照片拍摄时间、GPS、方向和色彩描述一致；B 视频时长约 2.933 秒、音频约 2.939 秒。尚不证明厂商相册能完整恢复实况、封面时刻、HDR 显示或其他私有功能。实验转码使用电脑端 Pillow/FFmpeg，尚未作为手机端 Media3 的兼容性承诺。

## 普通视频误判修复

剪映导出样本 `lv_0_20260426102314.mp4` 约 30 fps，元数据中的 `slowMotion` 值是 `none`。此前只检查词是否出现，导致误判。1.6.8 对明确禁用的 slowMotion 值放行；未知或启用的慢动作、capture.fps 等仍保守跳过。检测缓存版本已更新，旧跳过结果不会继续复用。

参考：[ExifTool Vivo 尾部元数据](https://exiftool.org/TagNames/Trailer.html)、[ExifTool MPF 偏移](https://exiftool.org/TagNames/MPF.html)、[Media3 对慢动作的支持范围](https://developer.android.com/media/media3/transformer/supported-formats)。


## 1.7 手机端接入

用户确认电脑实验 B 能被 vivo 识别，A 未被识别；这不代表已定位 A 失败的原因。1.7 将 B 的结构保留与配对 ID 更新方式接入手机端，视频使用 Media3。真机任务已实际成功生成多组；抽查 `IMG_20260411_121639` 输出 JPEG 4,953,391 字节、MP4 1,567,275 字节，MPF 辅助图仍为 1,948,898 字节，照片拍摄时间、位置与方向一致；视频 1440×1080，时长约 2.933 秒，音频约 2.939 秒，新配对 ID 一致，图片 Comment 与视频 mdta 均有 PhotoOpt 标记。其他厂商/结构仍不保证兼容。
