package com.dsha.zipforge.core

/**
 * 压缩格式家族，决定用哪套编解码器。
 */
enum class FormatFamily {
    ZIP, SEVEN_Z, RAR, TAR, GZIP, BZIP2, XZ, ZSTD, LZ4, BROTLI, UNKNOWN
}

/**
 * 应用支持的压缩/归档格式。
 */
enum class ArchiveFormat(
    val label: String,
    val family: FormatFamily,
    val extensions: List<String>,
    val canRead: Boolean,
    val canWrite: Boolean,
    /** 只能对单个文件进行压缩（gz/bz2/xz/zst 等流式格式） */
    val singleFileOnly: Boolean = false,
) {
    ZIP("ZIP", FormatFamily.ZIP, listOf("zip", "zipx", "jar", "apk", "epub", "xpi"), true, true),
    SEVEN_Z("7Z", FormatFamily.SEVEN_Z, listOf("7z"), true, true),
    RAR("RAR", FormatFamily.RAR, listOf("rar"), true, false),
    TAR("TAR", FormatFamily.TAR, listOf("tar"), true, true),
    TAR_GZ("TAR.GZ", FormatFamily.TAR, listOf("tar.gz", "tgz"), true, true),
    TAR_BZ2("TAR.BZ2", FormatFamily.TAR, listOf("tar.bz2", "tbz2", "tbz"), true, true),
    TAR_XZ("TAR.XZ", FormatFamily.TAR, listOf("tar.xz", "txz"), true, true),
    TAR_ZST("TAR.ZST", FormatFamily.TAR, listOf("tar.zst", "tzst"), true, true),
    GZ("GZ", FormatFamily.GZIP, listOf("gz", "gzip"), true, true, true),
    BZ2("BZ2", FormatFamily.BZIP2, listOf("bz2", "bzip2"), true, true, true),
    XZ("XZ", FormatFamily.XZ, listOf("xz"), true, true, true),
    ZST("ZST", FormatFamily.ZSTD, listOf("zst", "zstd"), true, true, true),
    LZ4("LZ4", FormatFamily.LZ4, listOf("lz4"), true, false, true),
    BROTLI("BROTLI", FormatFamily.BROTLI, listOf("br", "brotli"), true, false, true),
    UNKNOWN("未知", FormatFamily.UNKNOWN, emptyList(), false, false);

    val isTar: Boolean get() = family == FormatFamily.TAR
    val isSingleStream: Boolean get() = singleFileOnly
}

object Formats {

    /** 解压时可识别的格式（用于界面展示） */
    val readableFormats: List<ArchiveFormat>
        get() = ArchiveFormat.entries.filter { it.canRead && it != ArchiveFormat.UNKNOWN }

    /** 压缩时可选择的格式 */
    val writableFormats: List<ArchiveFormat>
        get() = ArchiveFormat.entries.filter { it.canWrite && it != ArchiveFormat.UNKNOWN }

    /** 根据文件名推断格式 */
    fun detectByName(name: String): ArchiveFormat {
        val lower = name.lowercase()
        // 先匹配复合后缀，例如 .tar.gz
        val sorted = ArchiveFormat.entries
            .filter { it != ArchiveFormat.UNKNOWN }
            .flatMap { f -> f.extensions.map { ext -> ext to f } }
            .sortedByDescending { it.first.length }
        for ((ext, format) in sorted) {
            if (lower.endsWith(".$ext")) return format
        }
        return ArchiveFormat.UNKNOWN
    }

    /** 根据文件头魔数推断（比扩展名可靠） */
    fun sniff(header: ByteArray, fallbackName: String): ArchiveFormat {
        if (header.size >= 4) {
            val b0 = header[0].toInt() and 0xFF
            val b1 = header[1].toInt() and 0xFF
            val b2 = header[2].toInt() and 0xFF
            val b3 = header[3].toInt() and 0xFF
            when {
                b0 == 0x50 && b1 == 0x4B && (b2 == 3 || b2 == 5 || b2 == 7) -> return ArchiveFormat.ZIP
                b0 == 0x37 && b1 == 0x7A && b2 == 0xBC && b3 == 0xAF -> return ArchiveFormat.SEVEN_Z
                b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21 -> return ArchiveFormat.RAR
                b0 == 0x1F && b1 == 0x8B -> return gzipVariant(fallbackName)
                b0 == 0x42 && b1 == 0x5A && b2 == 0x68 -> ArchiveFormat.BZ2
                b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58 -> ArchiveFormat.XZ
                b0 == 0x28 && b1 == 0xB5 && b2 == 0x2F && b3 == 0xFD -> ArchiveFormat.ZST
                b0 == 0x04 && b1 == 0x22 && b2 == 0x4D && b3 == 0x18 -> ArchiveFormat.LZ4
            }
        }
        if (header.size >= 262) {
            val ustar = String(header, 257, 5, Charsets.US_ASCII)
            if (ustar == "ustar") return ArchiveFormat.TAR
        }
        // brotli 没有魔数，只能看扩展名
        return detectByName(fallbackName)
    }

    private fun gzipVariant(name: String): ArchiveFormat {
        val lower = name.lowercase()
        return if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) ArchiveFormat.TAR_GZ
        else ArchiveFormat.GZ
    }

    /**
     * 该格式通常对应的文件扩展名（用于生成默认文件名）。
     */
    fun defaultExtension(format: ArchiveFormat): String = when (format) {
        ArchiveFormat.ZIP -> "zip"
        ArchiveFormat.SEVEN_Z -> "7z"
        ArchiveFormat.TAR -> "tar"
        ArchiveFormat.TAR_GZ -> "tar.gz"
        ArchiveFormat.TAR_BZ2 -> "tar.bz2"
        ArchiveFormat.TAR_XZ -> "tar.xz"
        ArchiveFormat.TAR_ZST -> "tar.zst"
        ArchiveFormat.GZ -> "gz"
        ArchiveFormat.BZ2 -> "bz2"
        ArchiveFormat.XZ -> "xz"
        ArchiveFormat.ZST -> "zst"
        ArchiveFormat.LZ4 -> "lz4"
        ArchiveFormat.BROTLI -> "br"
        else -> "bin"
    }

    fun mimeType(format: ArchiveFormat): String = when (format) {
        ArchiveFormat.ZIP -> "application/zip"
        ArchiveFormat.SEVEN_Z -> "application/x-7z-compressed"
        ArchiveFormat.RAR -> "application/x-rar-compressed"
        ArchiveFormat.TAR -> "application/x-tar"
        ArchiveFormat.TAR_GZ, ArchiveFormat.GZ -> "application/gzip"
        ArchiveFormat.TAR_BZ2, ArchiveFormat.BZ2 -> "application/x-bzip2"
        ArchiveFormat.TAR_XZ, ArchiveFormat.XZ -> "application/x-xz"
        ArchiveFormat.TAR_ZST, ArchiveFormat.ZST -> "application/zstd"
        ArchiveFormat.LZ4 -> "application/x-lz4"
        ArchiveFormat.BROTLI -> "application/x-brotli"
        else -> "application/octet-stream"
    }

    /** 解压单个条目时需要写入的 MIME（部分 provider 依赖它决定打开方式） */
    fun entryMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/x-rar-compressed"
            "apk" -> "application/vnd.android.package-archive"
            "epub" -> "application/epub+zip"
            else -> "application/octet-stream"
        }
    }
}
