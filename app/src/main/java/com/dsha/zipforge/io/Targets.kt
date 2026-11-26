package com.dsha.zipforge.io

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dsha.zipforge.core.ConflictPolicy
import com.dsha.zipforge.core.Formats
import com.dsha.zipforge.core.uniqueName
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * 解压目标：可以是 SAF 目录，也可以是应用内/真实目录。
 */
interface ExtractionTarget {
    val displayName: String

    fun createDirectory(relPath: String)

    /**
     * 处理重名。返回最终要写入的相对路径；null 表示跳过该条目。
     */
    fun resolveConflict(relPath: String, policy: ConflictPolicy): String?

    fun openOutput(relPath: String): OutputStream

    fun onEntryDone(relPath: String, modified: Long?)
}

class LocalDirTarget(
    private val root: File,
    override val displayName: String = root.name,
) : ExtractionTarget {
    init {
        if (!root.exists()) root.mkdirs()
    }

    override fun createDirectory(relPath: String) {
        File(root, relPath).mkdirs()
    }

    override fun resolveConflict(relPath: String, policy: ConflictPolicy): String? {
        val file = File(root, relPath)
        if (!file.exists()) return relPath
        if (file.isDirectory) return relPath
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.RENAME -> {
                val parentRel = relPath.substringBeforeLast('/', "")
                val name = relPath.substringAfterLast('/')
                val siblings = File(root, parentRel).list()?.toSet() ?: emptySet()
                val newName = uniqueName(siblings, name)
                if (parentRel.isEmpty()) newName else "$parentRel/$newName"
            }
            else -> relPath
        }
    }

    override fun openOutput(relPath: String): OutputStream {
        val file = File(root, relPath)
        file.parentFile?.mkdirs()
        return file.outputStream().buffered(128 * 1024)
    }

    override fun onEntryDone(relPath: String, modified: Long?) {
        if (modified != null && modified > 0) {
            runCatching { File(root, relPath).setLastModified(modified) }
        }
    }
}

class SafTreeTarget(
    private val context: Context,
    treeUri: Uri,
    private val rootName: String,
) : ExtractionTarget {

    private val resolver get() = context.contentResolver
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("无法访问所选目录")

    override val displayName: String = rootName.ifEmpty { root.name ?: "文档目录" }

    private val dirDocs = HashMap<String, DocumentFile>()
    private val childMaps = HashMap<String, HashMap<String, DocumentFile>>()

    private fun dirDoc(rel: String): DocumentFile {
        if (rel.isEmpty()) return root
        dirDocs[rel]?.let { return it }
        val parentRel = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val parent = dirDoc(parentRel)
        val map = childrenOf(parentRel)
        val existing = map[name]
        val doc = when {
            existing != null && existing.isDirectory -> existing
            existing != null -> {
                map.remove(name)
                existing.delete()
                parent.createDirectory(name) ?: throw IOException("无法创建目录 $rel")
            }
            else -> parent.createDirectory(name) ?: throw IOException("无法创建目录 $rel")
        }
        map[name] = doc
        dirDocs[rel] = doc
        return doc
    }

    private fun childrenOf(rel: String): HashMap<String, DocumentFile> {
        childMaps[rel]?.let { return it }
        val map = HashMap<String, DocumentFile>()
        val dir = if (rel.isEmpty()) root else dirDoc(rel)
        runCatching {
            dir.listFiles().forEach { f ->
                val name = f.name ?: return@forEach
                map[name] = f
            }
        }
        childMaps[rel] = map
        return map
    }

    override fun createDirectory(relPath: String) {
        if (relPath.isNotEmpty()) dirDoc(relPath)
    }

    override fun resolveConflict(relPath: String, policy: ConflictPolicy): String? {
        val parentRel = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val map = childrenOf(parentRel)
        val existing = map[name] ?: return relPath
        if (existing.isDirectory) return relPath
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.RENAME -> {
                val newName = uniqueName(map.keys, name)
                if (parentRel.isEmpty()) newName else "$parentRel/$newName"
            }
            else -> {
                runCatching { existing.delete() }
                map.remove(name)
                relPath
            }
        }
    }

    override fun openOutput(relPath: String): OutputStream {
        val parentRel = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/')
        val parent = dirDoc(parentRel)
        val map = childrenOf(parentRel)
        val existing = map[name]
        val doc = if (existing != null && !existing.isDirectory) existing
        else parent.createFile(Formats.entryMime(name), name)
            ?: throw IOException("无法创建文件 $relPath")
        map[name] = doc
        return resolver.openOutputStream(doc.uri, "wt")
            ?: throw IOException("无法写入文件 $relPath")
    }

    override fun onEntryDone(relPath: String, modified: Long?) = Unit
}

/** 读取 DocumentFile 树的辅助函数 */
object DocumentTree {

    fun listChildren(context: Context, uri: Uri): List<DocumentFile> {
        val doc = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        return runCatching { doc.listFiles().toList() }.getOrDefault(emptyList())
    }

    /** 递归展开目录，返回 (相对路径, Uri, 大小, 修改时间) */
    fun walk(
        context: Context,
        root: DocumentFile,
        basePath: String = "",
        limit: Int = 200_000,
    ): List<Triple<DocumentFile, String, Long>> {
        val out = ArrayList<Triple<DocumentFile, String, Long>>()
        fun visit(dir: DocumentFile, prefix: String) {
            if (out.size > limit) return
            val children = runCatching { dir.listFiles().toList() }.getOrDefault(emptyList())
            for (child in children) {
                val name = child.name ?: continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    visit(child, rel)
                } else {
                    out.add(Triple(child, rel, child.length()))
                }
            }
        }
        visit(root, basePath)
        return out
    }
}
