package com.dsha.zipforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsha.zipforge.core.ArchiveFormat
import com.dsha.zipforge.core.FormatFamily

fun iconForFormat(format: ArchiveFormat): ImageVector = when (format.family) {
    FormatFamily.ZIP -> AppIcons.Archive
    FormatFamily.SEVEN_Z, FormatFamily.RAR -> AppIcons.Archive
    FormatFamily.TAR -> AppIcons.Folder
    else -> AppIcons.File
}

fun iconForFile(name: String, isDirectory: Boolean = false): ImageVector {
    if (isDirectory) return AppIcons.Folder
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> AppIcons.Image
        "mp4", "mkv", "avi", "mov", "webm", "m4v" -> AppIcons.Movie
        "mp3", "flac", "wav", "ogg", "m4a", "aac" -> AppIcons.MusicNote
        "apk", "aab" -> AppIcons.Android
        "pdf" -> AppIcons.PictureAsPdf
        "kt", "java", "js", "ts", "py", "c", "cpp", "h", "json", "xml", "html", "css", "sh" -> AppIcons.Code
        "csv", "xls", "xlsx" -> AppIcons.TableChart
        "txt", "md", "log", "ini", "conf" -> AppIcons.Description
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "zst" -> AppIcons.Archive
        else -> AppIcons.File
    }
}

@Composable
fun FormatBadge(format: ArchiveFormat, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = format.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyHint(
    icon: ImageVector,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = icon, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(background, RoundedCornerShape(percent = 30)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
fun HeroGradient(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val brush = Brush.linearGradient(
        listOf(scheme.primary, scheme.secondary),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(brush, RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
fun RowCell(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
fun SpacerH(width: androidx.compose.ui.unit.Dp) {
    Spacer(Modifier.width(width))
}
