package com.dsha.zipforge.archive

import com.dsha.zipforge.core.ArchiveEntryInfo
import com.dsha.zipforge.core.ArchiveFormat
import com.dsha.zipforge.core.ArchiveMeta
import com.dsha.zipforge.core.Formats
import com.dsha.zipforge.io.ArchiveSource
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.brotli.dec.BrotliInputStream
import io.airlift.compress.zstd.ZstdInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class ArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 需要密码才能继续 */
class ArchivePasswordRequired(message: String = "该压缩包已加密") : IOException(message)

/** 密码错误 */
class ArchiveBadPassword(message: String = "密码错误") : IOException(message)

/** 用户取消任务 */
class ArchiveCancelledException : RuntimeException("任务已取消")

/**
 * 统一的压缩包读取接口。
 */
interface ArchiveReader : Closeable {
    val format: ArchiveFormat
    val meta: ArchiveMeta
    val entries: List<ArchiveEntryInfo>

    /** 打开某个条目的数据流（zip/7z/rar 可随机访问）。调用方负责关闭。 */
    fun openEntry(entry: ArchiveEntryInfo): InputStream

    /**
     * 批量解压。默认实现逐个打开；Tar 等顺序格式会覆盖为一遍扫描。
     * consumer 负责关闭传入的流。
     */
    fun extractSelected(selected: Set<Int>, consumer: (ArchiveEntryInfo, InputStream) -> Unit) {
        for (e in entries) {
            if (e.isDirectory) continue
            if (e.id !in selected) continue
            openEntry(e).use { input -> consumer(e, input) }
        }
    }
}

internal fun normalizeEntryPath(raw: String, isDirectory: Boolean): String {
    var p = raw.replace('\\', '/').trim()
    while (p.startsWith("./")) p = p.substring(2)
    p = p.trimStart('/')
    if (isDirectory) p = p.trimEnd('/')
    return p
}

internal fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/')

internal fun stripCompressSuffix(name: String): String {
    val lower = name.lowercase()
    val suffixes = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tgz", ".tbz2", ".tbz", ".txz", ".tzst", ".gz", ".bz2", ".xz", ".zst", ".lz4", ".br", ".gzip", ".bzip2")
    for (s in suffixes) {
        if (lower.endsWith(s) && name.length > s.length) return name.dropLast(s.length)
    }
    return name
}

/**
 * ZIP 读取器。
 */
class ZipArchiveReader(
    private val zip: ZipFile,
    private val source: ArchiveSource,
    override val format: ArchiveFormat = ArchiveFormat.ZIP,
) : ArchiveReader {

    private val entryById = LinkedHashMap<Int, org.apache.commons.compress.archivers.zip.ZipArchiveEntry>()
    override val entries: List<ArchiveEntryInfo>
    override val meta: ArchiveMeta

    init {
        val list = ArrayList<ArchiveEntryInfo>()
        var id = 0
        val en = zip.entries
        while (en.hasMoreElements()) {
            val ze = en.nextElement()
            val dir = ze.isDirectory
            val path = normalizeEntryPath(ze.name, dir)
            if (path.isEmpty()) continue
            val info = ArchiveEntryInfo(
                id = id,
                path = path,
                isDirectory = dir,
                size = if (dir) 0L else ze.size,
                compressedSize = if (dir) 0L else ze.compressedSize,
                modified = ze.time.takeIf { it > 0 },
                encrypted = runCatching { ze.generalPurposeBit.usesEncryption() }.getOrDefault(false),
            )
            entryById[id] = ze
            list.add(info)
            id++
        }
        entries = list
        var total = 0L
        var comp = 0L
        var files = 0
        for (e in list) {
            if (!e.isDirectory) {
                if (e.size > 0) total += e.size
                if (e.compressedSize > 0) comp += e.compressedSize
                files++
            }
        }
        meta = ArchiveMeta(
            format = format,
            entryCount = list.size,
            fileCount = files,
            totalSize = total,
            totalCompressed = if (comp > 0) comp else source.size,
            encrypted = list.any { it.encrypted },
            comment = null,
        )
    }

    override fun openEntry(entry: ArchiveEntryInfo): InputStream {
        val ze = entryById[entry.id] ?: throw IOException("条目不存在")
        return try {
            zip.getInputStream(ze)
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw ArchivePasswordRequired()
        } catch (e: Exception) {
            throw ArchiveException("无法读取 ${entry.name}：${e.message}", e)
        }
    }

    override fun close() {
        runCatching { zip.close() }
        source.close()
    }
}

/**
 * 7Z 读取器。
 */
class SevenZArchiveReader(
    private val sevenZ: SevenZFile,
    private val source: ArchiveSource,
) : ArchiveReader {

    private val entryById = LinkedHashMap<Int, org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry>()
    override val entries: List<ArchiveEntryInfo>
    override val meta: ArchiveMeta
    override val format: ArchiveFormat = ArchiveFormat.SEVEN_Z

    init {
        val list = ArrayList<ArchiveEntryInfo>()
        var id = 0
        for (e in sevenZ.entries) {
            val dir = e.isDirectory
            val path = normalizeEntryPath(e.name, dir)
            if (path.isEmpty()) continue
            val info = ArchiveEntryInfo(
                id = id,
                path = path,
                isDirectory = dir,
                size = if (dir) 0L else e.size,
                compressedSize = 0L,
                modified = runCatching { e.lastModifiedTime?.toMillis() }.getOrNull(),
                encrypted = false,
            )
            entryById[id] = e
            list.add(info)
            id++
        }
        entries = list
        var total = 0L
        var comp = 0L
        var files = 0
        for (e in list) {
            if (!e.isDirectory) {
                if (e.size > 0) total += e.size
                files++
                comp += if (e.compressedSize > 0) e.compressedSize else 0
            }
        }
        meta = ArchiveMeta(
            format = format,
            entryCount = list.size,
            fileCount = files,
            totalSize = total,
            totalCompressed = if (comp > 0) comp else source.size,
            solid = runCatching { detectSolid(sevenZ) }.getOrDefault(false),
            encrypted = runCatching { detectEncrypted(sevenZ) }.getOrDefault(false),
        )
    }

    override fun openEntry(entry: ArchiveEntryInfo): InputStream {
        val e = entryById[entry.id] ?: throw IOException("条目不存在")
        return try {
            sevenZ.getInputStream(e) ?: throw IOException("条目为空")
        } catch (e2: org.apache.commons.compress.PasswordRequiredException) {
            throw ArchivePasswordRequired()
        } catch (e2: Exception) {
            val msg = e2.message ?: ""
            if (msg.contains("password", true) || msg.contains("encrypted", true)) {
                throw ArchiveBadPassword()
            }
            throw ArchiveException("无法读取 ${entry.name}：$msg", e2)
        }
    }

    override fun close() {
        runCatching { sevenZ.close() }
        source.close()
    }

    private fun detectEncrypted(file: SevenZFile): Boolean {
        for (e in file.entries) {
            if (e.isDirectory) continue
            for (m in e.contentMethods) {
                if (m.method == org.apache.commons.compress.archivers.sevenz.SevenZMethod.AES256SHA256) return true
            }
        }
        return false
    }

    private fun detectSolid(file: SevenZFile): Boolean {
        // 启发式：多个条目共享相同 folder 时视为固实，commons-compress 没有直接接口
        return false
    }
}

/**
 * TAR 及其压缩变体（tar.gz / tar.bz2 / tar.xz / tar.zst）。
 */
class TarArchiveReader(
    private val source: ArchiveSource,
    override val format: ArchiveFormat,
) : ArchiveReader {

    private val streamFactory: () -> InputStream = when (format) {
        ArchiveFormat.TAR_GZ -> { { GzipCompressorInputStream(source.openStream(), true) } }
        ArchiveFormat.TAR_BZ2 -> { { BZip2CompressorInputStream(source.openStream(), true) } }
        ArchiveFormat.TAR_XZ -> { { XZCompressorInputStream(source.openStream(), true) } }
        ArchiveFormat.TAR_ZST -> { { ZstdInputStream(source.openStream()) } }
        else -> { { source.openStream() } }
    }

    override val entries: List<ArchiveEntryInfo>
    override val meta: ArchiveMeta

    init {
        val list = ArrayList<ArchiveEntryInfo>()
        var total = 0L
        var files = 0
        var id = 0
        try {
            TarArchiveInputStream(streamFactory()).use { tin ->
                var entry = tin.nextEntry
                while (entry != null) {
                    val dir = entry.isDirectory
                    val path = normalizeEntryPath(entry.name, dir)
                    if (path.isNotEmpty()) {
                        list.add(
                            ArchiveEntryInfo(
                                id = id,
                                path = path,
                                isDirectory = dir,
                                size = if (dir) 0L else entry.size,
                                compressedSize = 0L,
                                modified = entry.lastModifiedDate?.time,
                                encrypted = false,
                            )
                        )
                        id++
                        if (!dir) {
                            if (entry.size > 0) total += entry.size
                            files++
                        }
                    }
                    entry = tin.nextEntry
                }
            }
        } catch (e: Exception) {
            throw ArchiveException("无法解析 TAR：${e.message}", e)
        }
        entries = list
        meta = ArchiveMeta(
            format = format,
            entryCount = list.size,
            fileCount = files,
            totalSize = total,
            totalCompressed = source.size,
        )
    }

    override fun openEntry(entry: ArchiveEntryInfo): InputStream {
        // 顺序格式：单独扫描到目标条目
        val tin = TarArchiveInputStream(streamFactory())
        try {
            var index = 0
            var current = tin.nextEntry
            while (current != null) {
                if (index == entry.id) return tin
                index++
                current = tin.nextEntry
            }
        } catch (e: Exception) {
            runCatching { tin.close() }
            throw ArchiveException("读取 TAR 失败：${e.message}", e)
        }
        runCatching { tin.close() }
        throw IOException("条目不存在")
    }

    override fun extractSelected(selected: Set<Int>, consumer: (ArchiveEntryInfo, InputStream) -> Unit) {
        val wanted = entries.filter { !it.isDirectory && it.id in selected }.associateBy { it.id }
        if (wanted.isEmpty()) return
        TarArchiveInputStream(streamFactory()).use { tin ->
            var index = 0
            var current = tin.nextEntry
            while (current != null) {
                val info = wanted[index]
                if (info != null) {
                    consumer(info, tin)
                }
                index++
                current = tin.nextEntry
            }
        }
    }

    override fun close() {
        source.close()
    }
}

/**
 * 单文件压缩流：gzip / bzip2 / xz / zstd / lz4 / brotli。
 */
class StreamArchiveReader(
    private val source: ArchiveSource,
    override val format: ArchiveFormat,
) : ArchiveReader {

    private val entryName: String = stripCompressSuffix(source.displayName)

    override val entries: List<ArchiveEntryInfo> = listOf(
        ArchiveEntryInfo(
            id = 0,
            path = entryName,
            isDirectory = false,
            size = -1,
            compressedSize = source.size,
            modified = null,
            encrypted = false,
        )
    )

    override val meta: ArchiveMeta = ArchiveMeta(
        format = format,
        entryCount = 1,
        fileCount = 1,
        totalSize = -1,
        totalCompressed = source.size,
    )

    override fun openEntry(entry: ArchiveEntryInfo): InputStream {
        val raw = source.openStream()
        return try {
            when (format) {
                ArchiveFormat.GZ, ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(raw, true)
                ArchiveFormat.BZ2, ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw, true)
                ArchiveFormat.XZ, ArchiveFormat.TAR_XZ -> XZCompressorInputStream(raw, true)
                ArchiveFormat.ZST, ArchiveFormat.TAR_ZST -> ZstdInputStream(raw)
                ArchiveFormat.LZ4 -> FramedLZ4CompressorInputStream(raw, true)
                ArchiveFormat.BROTLI -> BrotliInputStream(raw)
                else -> raw
            }
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw ArchiveException("无法解压：${e.message}", e)
        }
    }

    override fun close() {
        source.close()
    }
}

/**
 * RAR 读取器（基于 junrar，支持 RAR4 / RAR5）。
 */
class RarArchiveReader(
    private val archive: com.github.junrar.Archive,
    private val source: ArchiveSource,
    private val tempFile: File?,
    private val password: String?,
) : ArchiveReader {

    private val headers = archive.fileHeaders
    private val entryById = LinkedHashMap<Int, com.github.junrar.rarfile.FileHeader>()
    override val entries: List<ArchiveEntryInfo>
    override val meta: ArchiveMeta
    override val format: ArchiveFormat = ArchiveFormat.RAR

    init {
        val list = ArrayList<ArchiveEntryInfo>()
        var total = 0L
        var comp = 0L
        var files = 0
        var id = 0
        for (h in headers) {
            val dir = h.isDirectory
            val path = normalizeEntryPath(h.fileName, dir)
            if (path.isEmpty()) continue
            val size = runCatching { h.fullUnpackSize }.getOrDefault(-1L)
            val isEnc = runCatching { h.isEncrypted }.getOrDefault(false)
            val info = ArchiveEntryInfo(
                id = id,
                path = path,
                isDirectory = dir,
                size = if (dir) 0L else size,
                compressedSize = 0L,
                modified = runCatching { h.lastModifiedTime?.toMillis() }.getOrNull(),
                encrypted = isEnc,
            )
            entryById[id] = h
            list.add(info)
            id++
            if (!dir) {
                if (size > 0) total += size
                files++
            }
        }
        entries = list
        meta = ArchiveMeta(
            format = format,
            entryCount = list.size,
            fileCount = files,
            totalSize = total,
            totalCompressed = source.size,
            solid = runCatching { archive.isOldFormat.not() && headers.size > 1 }.getOrDefault(false),
            encrypted = runCatching { archive.isEncrypted }.getOrDefault(false) || list.any { it.encrypted },
        )
    }

    override fun openEntry(entry: ArchiveEntryInfo): InputStream {
        val h = entryById[entry.id] ?: throw IOException("条目不存在")
        return try {
            archive.getInputStream(h)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("password", true) || msg.contains("decrypt", true) || msg.contains("crc", true)) {
                throw ArchiveBadPassword()
            }
            throw ArchiveException("无法读取 ${entry.name}：$msg", e)
        }
    }

    override fun close() {
        runCatching { archive.close() }
        source.close()
        tempFile?.let { t -> runCatching { t.delete() } }
    }
}

/**
 * 引擎入口：探测格式并构建 reader。
 */
object ArchiveEngines {

    fun readHeader(source: ArchiveSource, size: Int = 1024): ByteArray {
        return try {
            source.openStream().use { input ->
                val buf = ByteArray(size)
                var off = 0
                while (off < size) {
                    val read = input.read(buf, off, size - off)
                    if (read < 0) break
                    off += read
                }
                if (off == size) buf else buf.copyOf(off)
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    fun detectFormat(source: ArchiveSource): ArchiveFormat {
        val header = readHeader(source, 1024)
        var format = Formats.sniff(header, source.displayName)
        if (format == ArchiveFormat.UNKNOWN) {
            format = Formats.detectByName(source.displayName)
        }
        return format
    }

    /**
     * 打开压缩包。会阻塞当前线程，请放在 IO 线程调用。
     */
    fun open(source: ArchiveSource, password: String?): ArchiveReader {
        val format = detectFormat(source)
        if (format == ArchiveFormat.UNKNOWN) {
            throw ArchiveException("无法识别的压缩格式")
        }
        return try {
            when (format) {
                ArchiveFormat.ZIP -> {
                    val channel = source.openChannel()
                    // 注意参数顺序：(channel, password, encoding, useUnicodeExtraFields)
                    // 传反会把密码当作字符集名，加密 ZIP 直接抛 UnsupportedCharsetException。
                    val zip = ZipFile(
                        channel,
                        password,
                        StandardCharsets.UTF_8.name(),
                        true,
                    )
                    ZipArchiveReader(zip, source, format)
                }
                ArchiveFormat.SEVEN_Z -> {
                    val channel = source.openChannel()
                    val builder = SevenZFile.builder()
                        .setSeekableByteChannel(channel)
                    if (!password.isNullOrEmpty()) builder.setPassword(password)
                    val sevenZ = builder.get()
                    SevenZArchiveReader(sevenZ, source)
                }
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2,
                ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST -> TarArchiveReader(source, format)
                ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ,
                ArchiveFormat.ZST, ArchiveFormat.LZ4, ArchiveFormat.BROTLI ->
                    StreamArchiveReader(source, format)
                ArchiveFormat.RAR -> openRar(source, password)
                else -> throw ArchiveException("暂不支持 ${format.label}")
            }
        } catch (e: ArchivePasswordRequired) {
            throw e
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw ArchivePasswordRequired()
        } catch (e: ArchiveException) {
            throw e
        } catch (e: Exception) {
            // 7Z 等格式在密码错误时抛普通 IOException / CryptographicException，
            // 这里统一识别成“密码错误”，让界面能重新弹出密码输入框。
            val msg = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
            if (msg.contains("password") || msg.contains("bad password") ||
                msg.contains("encrypted") || msg.contains("crypto") || msg.contains("decrypt")
            ) {
                throw ArchiveBadPassword()
            }
            throw ArchiveException("打开失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun openRar(source: ArchiveSource, password: String?): RarArchiveReader {
        val local = source.localFile()
        return if (local != null) {
            val archive = com.github.junrar.Archive(local, password)
            RarArchiveReader(archive, source, null, password)
        } else {
            // 通过输入流打开：junrar 会内部缓存
            val archive = com.github.junrar.Archive(source.openStream(), password)
            RarArchiveReader(archive, source, null, password)
        }
    }
}
