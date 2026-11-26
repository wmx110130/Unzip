package com.dsha.zipforge.archive

import android.content.Context
import com.dsha.zipforge.core.ArchiveFormat
import com.dsha.zipforge.core.FileToAdd
import io.airlift.compress.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Date

/**
 * 压缩引擎：把一组文件写入指定的输出流/文件。
 */
object ArchiveCompressor {

    const val BUFFER_SIZE = 128 * 1024

    class CancelledException : RuntimeException("任务已取消")

    private class Tracker(
        private val onBytes: (Long, String) -> Unit,
        private val isCancelled: () -> Boolean,
    ) {
        var done = 0L
            private set

        fun copy(context: Context, file: FileToAdd, write: (ByteArray, Int, Int) -> Unit) {
            val resolver = context.contentResolver
            val input = resolver.openInputStream(file.uri)
                ?: throw IOException("无法读取 ${file.relativePath}")
            input.use { src ->
                val buffer = ByteArray(BUFFER_SIZE)
                var lastReport = 0L
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val read = src.read(buffer)
                    if (read < 0) break
                    write(buffer, 0, read)
                    done += read
                    if (done - lastReport >= 512 * 1024) {
                        lastReport = done
                        onBytes(done, file.relativePath)
                    }
                }
            }
            onBytes(done, file.relativePath)
        }
    }

    /**
     * 把文件列表压缩到 sink。调用方负责关闭 sink。
     * 7Z 由于需要随机访问，请使用 [compressToSevenZ]。
     */
    fun compress(
        context: Context,
        format: ArchiveFormat,
        level: Int,
        files: List<FileToAdd>,
        sink: OutputStream,
        onBytes: (Long, String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val tracker = Tracker(onBytes, isCancelled)
        when (format) {
            ArchiveFormat.ZIP -> writeZip(context, files, level, sink, tracker)
            ArchiveFormat.TAR -> writeTar(context, format, files, level, sink, tracker)
            ArchiveFormat.TAR_GZ -> writeTar(context, format, files, level, sink, tracker)
            ArchiveFormat.TAR_BZ2 -> writeTar(context, format, files, level, sink, tracker)
            ArchiveFormat.TAR_XZ -> writeTar(context, format, files, level, sink, tracker)
            ArchiveFormat.TAR_ZST -> writeTar(context, format, files, level, sink, tracker)
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ, ArchiveFormat.ZST ->
                writeSingleStream(context, format, files, level, sink, tracker)
            else -> throw IOException("不支持写出 ${format.label}")
        }
    }

    private fun writeZip(
        context: Context,
        files: List<FileToAdd>,
        level: Int,
        sink: OutputStream,
        tracker: Tracker,
    ) {
        val zos = ZipArchiveOutputStream(sink)
        zos.setEncoding("UTF-8")
        zos.setLevel(level.coerceIn(0, 9))
        zos.setUseZip64(Zip64Mode.AsNeeded)
        try {
            for (file in files) {
                val entry = ZipArchiveEntry(file.relativePath)
                entry.time = file.lastModified
                if (file.size >= 0) entry.size = file.size
                zos.putArchiveEntry(entry)
                tracker.copy(context, file) { b, o, l -> zos.write(b, o, l) }
                zos.closeArchiveEntry()
            }
            zos.finish()
            zos.flush()
        } finally {
            // 不关闭 sink，交由调用方处理
        }
    }

    private fun writeTar(
        context: Context,
        format: ArchiveFormat,
        files: List<FileToAdd>,
        level: Int,
        sink: OutputStream,
        tracker: Tracker,
    ) {
        var gzip: GzipCompressorOutputStream? = null
        var bzip2: BZip2CompressorOutputStream? = null
        var xz: XZCompressorOutputStream? = null
        var zstd: ZstdOutputStream? = null

        val compressed: OutputStream = when (format) {
            ArchiveFormat.TAR_GZ -> {
                val params = GzipParameters().apply { compressionLevel = level.coerceIn(0, 9) }
                GzipCompressorOutputStream(sink, params).also { gzip = it }
            }
            ArchiveFormat.TAR_BZ2 -> {
                BZip2CompressorOutputStream(sink, level.coerceIn(1, 9)).also { bzip2 = it }
            }
            ArchiveFormat.TAR_XZ -> {
                XZCompressorOutputStream(sink, level.coerceIn(0, 9)).also { xz = it }
            }
            ArchiveFormat.TAR_ZST -> {
                ZstdOutputStream(sink).also { zstd = it }
            }
            else -> sink
        }

        val tos = TarArchiveOutputStream(compressed)
        tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        try {
            for (file in files) {
                val entry = TarArchiveEntry(file.relativePath)
                entry.size = if (file.size >= 0) file.size else 0L
                entry.modTime = Date(file.lastModified)
                tos.putArchiveEntry(entry)
                tracker.copy(context, file) { b, o, l -> tos.write(b, o, l) }
                tos.closeArchiveEntry()
            }
        } finally {
            // close() 会写入 TAR 结束块，并结束底层压缩流（GZIP/BZIP2/XZ 的 trailer、ZSTD 的结束帧）。
            // 注意：GZIP/BZIP2 显式 finish() 后再 close() 会重复写 trailer 甚至 NPE，所以只能调用 close()。
            runCatching { tos.close() }
        }
    }

    private fun writeSingleStream(
        context: Context,
        format: ArchiveFormat,
        files: List<FileToAdd>,
        level: Int,
        sink: OutputStream,
        tracker: Tracker,
    ) {
        if (files.isEmpty()) throw IOException("没有可压缩的文件")
        val file = files.first()
        when (format) {
            ArchiveFormat.GZ -> {
                val params = GzipParameters().apply {
                    compressionLevel = level.coerceIn(0, 9)
                    fileName = file.relativePath.substringAfterLast('/')
                }
                GzipCompressorOutputStream(sink, params).use { out ->
                    tracker.copy(context, file) { b, o, l -> out.write(b, o, l) }
                }
            }
            ArchiveFormat.BZ2 -> {
                BZip2CompressorOutputStream(sink, level.coerceIn(1, 9)).use { out ->
                    tracker.copy(context, file) { b, o, l -> out.write(b, o, l) }
                }
            }
            ArchiveFormat.XZ -> {
                XZCompressorOutputStream(sink, level.coerceIn(0, 9)).use { out ->
                    tracker.copy(context, file) { b, o, l -> out.write(b, o, l) }
                }
            }
            ArchiveFormat.ZST -> {
                val out = ZstdOutputStream(sink)
                tracker.copy(context, file) { b, o, l -> out.write(b, o, l) }
                out.close()
            }
            else -> throw IOException("不支持写出 ${format.label}")
        }
    }

    /**
     * 写入 7Z（需要随机访问，因此先写临时文件）。
     */
    fun compressToSevenZ(
        context: Context,
        files: List<FileToAdd>,
        level: Int,
        target: File,
        onBytes: (Long, String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val tracker = Tracker(onBytes, isCancelled)
        val out = SevenZOutputFile(target)
        try {
            out.setContentCompression(SevenZMethod.LZMA2)
            for (file in files) {
                val entry = SevenZArchiveEntry()
                entry.name = file.relativePath
                entry.size = if (file.size >= 0) file.size else 0L
                entry.lastModifiedDate = Date(file.lastModified)
                entry.setHasStream(true)
                out.putArchiveEntry(entry)
                tracker.copy(context, file) { b, o, l -> out.write(b, o, l) }
                out.closeArchiveEntry()
            }
            out.finish()
        } finally {
            runCatching { out.close() }
        }
    }
}
