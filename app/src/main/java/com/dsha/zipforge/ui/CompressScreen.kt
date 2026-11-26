package com.dsha.zipforge.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.dsha.zipforge.R
import com.dsha.zipforge.core.ArchiveFormat
import com.dsha.zipforge.core.CompressPlan
import com.dsha.zipforge.core.CompressSource
import com.dsha.zipforge.core.FileToAdd
import com.dsha.zipforge.core.Fmt
import com.dsha.zipforge.core.Formats
import com.dsha.zipforge.data.AppSettings
import com.dsha.zipforge.io.DocumentTree
import com.dsha.zipforge.io.UriArchiveSource
import com.dsha.zipforge.task.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen(
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sources by remember { mutableStateOf<List<CompressSource>>(emptyList()) }
    var files by remember { mutableStateOf<List<FileToAdd>>(emptyList()) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var level by remember { mutableStateOf(6) }
    var name by remember { mutableStateOf("") }
    var saveUri by remember { mutableStateOf<Uri?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val totalBytes = remember(files) { files.sumOf { if (it.size > 0) it.size else 0L } }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            preparing = true
            errorText = null
            val added = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    CompressSource(
                        uri = uri,
                        name = UriArchiveSource.queryName(context, uri, "file"),
                        isDirectory = false,
                        size = UriArchiveSource.querySize(context, uri),
                    )
                }
            }
            sources = sources + added
            files = withContext(Dispatchers.IO) { expandSources(context, sources) }
            if (name.isBlank()) name = suggestName(sources, format)
            preparing = false
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        scope.launch {
            preparing = true
            errorText = null
            val source = withContext(Dispatchers.IO) {
                val doc = DocumentFile.fromTreeUri(context, uri)
                val folderName = doc?.name ?: "folder"
                val count = doc?.let { runCatching { it.listFiles().size }.getOrDefault(0) } ?: 0
                CompressSource(
                    uri = uri,
                    name = folderName,
                    isDirectory = true,
                    childCount = count,
                )
            }
            sources = sources + source
            files = withContext(Dispatchers.IO) { expandSources(context, sources) }
            if (name.isBlank()) name = suggestName(sources, format)
            preparing = false
        }
    }

    fun doStart(uri: Uri, finalName: String) {
        val plan = CompressPlan(
            archiveName = finalName,
            format = format,
            level = level,
            files = files,
            totalBytes = totalBytes,
            saveTree = null,
            saveUri = uri,
        )
        val started = TaskManager.startCompression(context, plan)
        if (started) onBack()
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        saveUri = uri
        val finalName = ensureExtension(name, format)
        doStart(uri, finalName)
    }

    fun validateAndStart() {
        if (files.isEmpty()) {
            errorText = context.getString(R.string.compress_empty)
            return
        }
        if (name.isBlank()) {
            errorText = context.getString(R.string.compress_name_empty)
            return
        }
        if (format.singleFileOnly && files.size > 1) {
            errorText = context.getString(R.string.compress_single_only, format.label)
            return
        }
        val finalName = ensureExtension(name, format)
        val existing = saveUri
        if (existing != null && saveUriName(existing).endsWith(".${Formats.defaultExtension(format)}")) {
            doStart(existing, finalName)
        } else {
            createDocument.launch(finalName)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = { Text(stringResource(R.string.compress_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.compress_total,
                                files.size,
                                Fmt.size(totalBytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { validateAndStart() }, enabled = !preparing) {
                        Text(stringResource(R.string.compress_start))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { pickFiles.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(AppIcons.File, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.compress_pick_files))
                    }
                    Button(
                        onClick = { pickFolder.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(AppIcons.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.compress_pick_folder))
                    }
                }
            }

            if (preparing) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.compress_sources),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (sources.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Text(
                            text = stringResource(R.string.compress_no_sources),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(sources.size) { index ->
                    val source = sources[index]
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBadge(
                                icon = if (source.isDirectory) AppIcons.Folder else AppIcons.File,
                                size = 36.dp,
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (source.isDirectory) {
                                        stringResource(R.string.common_items, source.childCount)
                                    } else {
                                        Fmt.size(source.size)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                sources = sources.filterIndexed { i, _ -> i != index }
                                scope.launch {
                                    preparing = true
                                    files = withContext(Dispatchers.IO) { expandSources(context, sources) }
                                    preparing = false
                                }
                            }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.compress_remove))
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.compress_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }

            item {
                Column {
                    Text(
                        text = stringResource(R.string.compress_format),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Formats.writableFormats.forEach { fmt ->
                            FilterChip(
                                selected = format == fmt,
                                onClick = {
                                    format = fmt
                                    if (name.isNotBlank()) {
                                        name = stripKnownExtension(name) + "." + Formats.defaultExtension(fmt)
                                    }
                                },
                                label = { Text(fmt.label) },
                            )
                        }
                    }
                    if (format.singleFileOnly && files.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.compress_single_only, format.label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.compress_level),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = levelLabel(level),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { level = it.toInt().coerceIn(0, 9) },
                        valueRange = 0f..9f,
                        steps = 8,
                    )
                }
            }

            if (errorText != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            text = errorText ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        AppIcons.Save,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.compress_save_to),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = saveUri?.let { saveUriName(it) } ?: stringResource(R.string.compress_choose_location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun suggestName(sources: List<CompressSource>, format: ArchiveFormat): String {
    val base = when {
        sources.isEmpty() -> "archive"
        sources.size == 1 -> sources.first().name
        else -> sources.first().name + "_等"
    }
    val cleaned = base.substringBeforeLast('.', base).ifEmpty { "archive" }
    return "$cleaned.${Formats.defaultExtension(format)}"
}

private fun stripKnownExtension(name: String): String {
    val lower = name.lowercase()
    val exts = Formats.writableFormats.flatMap { it.extensions }.sortedByDescending { it.length }
    for (e in exts) {
        if (lower.endsWith(".$e")) return name.dropLast(e.length + 1)
    }
    return name
}

private fun ensureExtension(name: String, format: ArchiveFormat): String {
    val ext = Formats.defaultExtension(format)
    return if (name.lowercase().endsWith(".$ext")) name else "${stripKnownExtension(name)}.$ext"
}

private fun levelLabel(level: Int): String = when {
    level <= 2 -> "快速"
    level <= 5 -> "均衡"
    level <= 7 -> "较小"
    else -> "极限"
}

private fun saveUriName(uri: Uri): String = uri.lastPathSegment ?: uri.toString()

private fun expandSources(
    context: android.content.Context,
    sources: List<CompressSource>,
): List<FileToAdd> {
    val out = ArrayList<FileToAdd>()
    for (source in sources) {
        if (source.isDirectory) {
            val doc = DocumentFile.fromTreeUri(context, source.uri) ?: continue
            val rootName = source.name
            DocumentTree.walk(context, doc).forEach { (child, rel, size) ->
                val relPath = if (rootName.isBlank()) rel else "$rootName/$rel"
                out.add(
                    FileToAdd(
                        relativePath = relPath,
                        uri = child.uri,
                        size = size,
                        lastModified = child.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                )
            }
        } else {
            out.add(
                FileToAdd(
                    relativePath = source.name,
                    uri = source.uri,
                    size = if (source.size > 0) source.size else -1,
                    lastModified = System.currentTimeMillis(),
                )
            )
        }
    }
    return out
}
