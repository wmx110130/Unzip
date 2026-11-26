package com.dsha.zipforge.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {

    fun size(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        if (gb < 1024) return String.format(Locale.US, "%.2f GB", gb)
        return String.format(Locale.US, "%.2f TB", gb / 1024.0)
    }

    fun height(bytes: Long): String = size(bytes)

    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "—"
        return "${size(bytesPerSecond)}/s"
    }

    fun duration(millis: Long): String {
        if (millis <= 0) return "0 秒"
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "$h 小时 $m 分"
            m > 0 -> "$m 分 $s 秒"
            else -> "$s 秒"
        }
    }

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    fun date(millis: Long?): String {
        if (millis == null || millis <= 0) return "—"
        return dateFormat.get().format(Date(millis))
    }

    fun shortDate(millis: Long?): String {
        if (millis == null || millis <= 0) return ""
        val now = System.currentTimeMillis()
        val fmt = if (now - millis < 365L * 24 * 3600 * 1000) "MM-dd HH:mm" else "yyyy-MM-dd"
        return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(millis))
    }
}

/**
 * 清洗压缩包内的路径，防止 Zip Slip（../ 逃逸）以及非法文件名。
 * 返回 null 表示该条目应当被跳过。
 */
fun sanitizeEntryPath(raw: String): String? {
    var p = raw.replace('\\', '/').trim()
    if (p.isEmpty()) return null
    // 去掉盘符
    if (p.length > 1 && p[1] == ':') p = p.substring(2)
    p = p.trimStart('/')
    val parts = ArrayList<String>()
    for (segment in p.split('/')) {
        val s = segment.trim()
        if (s.isEmpty() || s == "." || s == "..") continue
        val cleaned = buildString {
            for (ch in s) {
                when {
                    ch.code < 0x20 -> Unit
                    ch in "\\/:*?\"<>|" -> append('_')
                    else -> append(ch)
                }
            }
        }.trim().trimEnd('.')
        if (cleaned.isEmpty()) continue
        parts.add(if (cleaned.length > 180) cleaned.take(180) else cleaned)
    }
    if (parts.isEmpty()) return null
    return parts.joinToString("/")
}

/** 生成不重复的文件名，例如 "a (1).txt" */
fun uniqueName(existing: Set<String>, desired: String): String {
    if (desired !in existing) return desired
    val dot = desired.lastIndexOf('.')
    val base = if (dot > 0) desired.substring(0, dot) else desired
    val ext = if (dot > 0) desired.substring(dot) else ""
    var i = 1
    while (true) {
        val candidate = "$base ($i)$ext"
        if (candidate !in existing) return candidate
        i++
        if (i > 9999) return "$base-${System.currentTimeMillis()}$ext"
    }
}
