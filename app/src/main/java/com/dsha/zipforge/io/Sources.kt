package com.dsha.zipforge.io

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * 统一的压缩包读取来源，支持随机访问（ZIP / 7Z / RAR 需要）。
 */
interface ArchiveSource : Closeable {
    val displayName: String
    val size: Long

    /** 打开一个独立的随机访问通道，调用方负责关闭。 */
    fun openChannel(): SeekableByteChannel

    /** 打开顺序读取流，调用方负责关闭。 */
    fun openStream(): InputStream

    /** 如果底层是真实文件，返回它（RAR 解析收益最大）。 */
    fun localFile(): File?

    override fun close()
}

class FileArchiveSource(
    private val file: File,
    override val displayName: String = file.name,
) : ArchiveSource {
    override val size: Long get() = file.length()

    override fun openChannel(): SeekableByteChannel =
        FileChannel.open(file.toPath(), StandardOpenOption.READ)

    override fun openStream(): InputStream = FileInputStream(file)

    override fun localFile(): File = file

    override fun close() = Unit
}

class UriArchiveSource(
    private val context: Context,
    private val uri: Uri,
    override val displayName: String,
    override val size: Long,
) : ArchiveSource {

    private val resolver get() = context.contentResolver

    override fun openChannel(): SeekableByteChannel {
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("无法打开文件描述符")
        val stream = FileInputStream(pfd.fileDescriptor)
        val channel = stream.channel
        return object : SeekableByteChannel {
            override fun read(dst: ByteBuffer): Int = channel.read(dst)
            override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
            override fun position(): Long = channel.position()
            override fun position(newPosition: Long): SeekableByteChannel {
                channel.position(newPosition)
                return this
            }
            override fun size(): Long = channel.size()
            override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
            override fun isOpen(): Boolean = channel.isOpen
            override fun close() {
                runCatching { channel.close() }
                runCatching { stream.close() }
                runCatching { pfd.close() }
            }
        }
    }

    override fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("无法读取文件")

    /**
     * 对于本地文件 provider，尝试通过 /proc/self/fd 暴露真实文件；否则返回 null。
     */
    override fun localFile(): File? {
        return try {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
            val real = File("/proc/self/fd/${pfd.fd}")
            if (real.exists() && real.canRead()) {
                // 把 fd 的生命周期绑定到 source 上
                openedFds.add(pfd)
                real
            } else {
                pfd.close()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private val openedFds = ArrayList<ParcelFileDescriptor>()

    override fun close() {
        openedFds.forEach { runCatching { it.close() } }
        openedFds.clear()
    }

    companion object {
        fun querySize(context: Context, uri: Uri): Long {
            return runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                    } ?: -1L
            }.getOrDefault(-1L)
        }

        fun queryName(context: Context, uri: Uri, fallback: String): String {
            return runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull() ?: uri.lastPathSegment ?: fallback
        }

        fun from(context: Context, uri: Uri): UriArchiveSource {
            val name = queryName(context, uri, "archive")
            val size = querySize(context, uri)
            return UriArchiveSource(context, uri, name, size)
        }
    }
}

/**
 * 如果来源无法随机访问，就把内容复制到应用缓存里，返回临时文件。
 */
suspend fun ArchiveSource.ensureLocalFile(context: Context, onProgress: (Long) -> Unit = {}): File {
    localFile()?.let { return it }
    val cacheDir = File(context.cacheDir, "archives")
    if (!cacheDir.exists()) cacheDir.mkdirs()
    val safeName = displayName.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(80)
    val temp = File(cacheDir, "source-$safeName")
    var done = 0L
    openStream().use { input ->
        temp.outputStream().buffered(64 * 1024).use { output ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
    }
    return temp
}
