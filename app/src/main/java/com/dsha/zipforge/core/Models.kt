package com.dsha.zipforge.core

import android.net.Uri

/**
 * 压缩包内的一个条目。
 */
data class ArchiveEntryInfo(
    val id: Int,
    /** 规范化后的相对路径，目录以 / 结尾（保持原始结构） */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val modified: Long?,
    val encrypted: Boolean,
) {
    val name: String
        get() {
            val p = path.trimEnd('/')
            return p.substringAfterLast('/').ifEmpty { path }
        }

    val parentPath: String
        get() {
            val p = path.trimEnd('/')
            return if (p.contains('/')) p.substringBeforeLast('/') else ""
        }

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

data class ArchiveMeta(
    val format: ArchiveFormat,
    val entryCount: Int,
    val fileCount: Int,
    val totalSize: Long,
    val totalCompressed: Long,
    val solid: Boolean = false,
    val encrypted: Boolean = false,
    val comment: String? = null,
) {
    val ratioText: String
        get() = if (totalSize <= 0L) "—"
        else {
            val ratio = (totalCompressed.toDouble() / totalSize.toDouble() * 100.0)
            if (ratio.isFinite()) String.format("%.1f%%", ratio.coerceIn(0.0, 100.0)) else "—"
        }
}

/** 打开一个压缩包的请求 */
data class ArchiveRequest(
    val uri: Uri,
    val displayName: String,
    val size: Long,
)

/** 解压时遇到重名文件怎么办 */
enum class ConflictPolicy { ASK, OVERWRITE, SKIP, RENAME }

/** 压缩源的抽象：可以是文件，也可以是文件夹 */
data class CompressSource(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val childCount: Int = 0,
)

/** 压缩前展开出来的一个待写入文件 */
data class FileToAdd(
    val relativePath: String,
    val uri: Uri,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean = false,
)

data class CompressPlan(
    val archiveName: String,
    val format: ArchiveFormat,
    val level: Int,
    val files: List<FileToAdd>,
    val totalBytes: Long,
    val saveTree: Uri?,
    val saveUri: Uri,
)

data class ExtractPlan(
    val request: ArchiveRequest,
    val password: String?,
    val entries: List<ArchiveEntryInfo>,
    val targetUri: Uri,
    val targetName: String,
    val conflictPolicy: ConflictPolicy,
)
