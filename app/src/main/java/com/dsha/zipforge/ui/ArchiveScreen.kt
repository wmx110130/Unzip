package com.dsha.zipforge.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.dsha.zipforge.R
import com.dsha.zipforge.archive.ArchiveBadPassword
import com.dsha.zipforge.archive.ArchiveEngines
import com.dsha.zipforge.archive.ArchivePasswordRequired
import com.dsha.zipforge.archive.ArchiveReader
import com.dsha.zipforge.core.ArchiveEntryInfo
import com.dsha.zipforge.core.ArchiveRequest
import com.dsha.zipforge.core.ConflictPolicy
import com.dsha.zipforge.core.ExtractPlan
import com.dsha.zipforge.core.Fmt
import com.dsha.zipforge.data.AppSettings
import com.dsha.zipforge.io.UriArchiveSource
import com.dsha.zipforge.task.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TreeItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val entry: ArchiveEntryInfo?,
    val count: Int = 0,
)

private sealed interface LoadResult {
    data class Ok(val reader: ArchiveReader) : LoadResult
    data object NeedPassword : LoadResult
    data object BadPassword : LoadResult
    data class Fail(val message: String) : LoadResult
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    request: ArchiveRequest,
    settings: AppSettings,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reader by remember { mutableStateOf<ArchiveReader?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }

    var currentDir by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectMode by remember { mutableStateOf(false) }

    var showExtractDialog by remember { mutableStateOf(false) }

    LaunchedEffect(attempt, request.uri) {
        loading = true
        loadError = null
        reader?.close()
        reader = null
        val result = withContext(Dispatchers.IO) {
            try {
                val source = UriArchiveSource.from(context, request.uri)
                LoadResult.Ok(ArchiveEngines.open(source, password))
            } catch (e: ArchivePasswordRequired) {
                if (password.isNullOrEmpty()) LoadResult.NeedPassword else LoadResult.BadPassword
            } catch (e: ArchiveBadPassword) {
                LoadResult.BadPassword
            } catch (e: Throwable) {
                LoadResult.Fail(e.message ?: context.getString(R.string.common_error))
            }
        }
        loading = false
        when (result) {
            is LoadResult.Ok -> {
                reader = result.reader
                passwordError = false
                showPasswordDialog = false
            }
            LoadResult.NeedPassword -> {
                showPasswordDialog = true
                passwordError = false
            }
            LoadResult.BadPassword -> {
                showPasswordDialog = true
                passwordError = true
            }
            is LoadResult.Fail -> loadError = result.message
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            reader?.close()
            reader = null
        }
    }

    BackHandler(enabled = currentDir.isNotEmpty()) {
        currentDir = currentDir.substringBeforeLast('/', "")
    }
    BackHandler(enabled = selectMode) {
        selectMode = false
        selection = emptySet()
    }

    val loaded = reader

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = {
                    Column {
                        Text(
                            text = request.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (loaded != null) {
                            Text(
                                text = "${loaded.meta.format.label} · ${stringResource(R.string.archive_entries, loaded.meta.entryCount)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (selectMode) {
                        TextButton(onClick = {
                            selectMode = false
                            selection = emptySet()
                        }) {
                            Text(stringResource(R.string.archive_exit_select))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (loaded != null) {
                ExtractBottomBar(
                    selectMode = selectMode,
                    selectedCount = selection.size,
                    onExtractAll = {
                        selectMode = false
                        selection = emptySet()
                        showExtractDialog = true
                    },
                    onExtractSelected = { showExtractDialog = true },
                )
            }
        },
    ) { padding ->
        when {
            loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.archive_loading))
                    }
                }
            }
            loadError != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        EmptyHint(
                            icon = AppIcons.Archive,
                            title = stringResource(R.string.archive_open_failed, ""),
                            description = loadError,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                passwordError = false
                                showPasswordDialog = true
                            }) {
                                Text(stringResource(R.string.archive_retry))
                            }
                            Button(onClick = { attempt++ }) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                }
            }
            loaded != null -> {
                ArchiveContent(
                    reader = loaded,
                    request = request,
                    currentDir = currentDir,
                    onDirChange = { currentDir = it },
                    search = search,
                    onSearchChange = { search = it },
                    selectMode = selectMode,
                    selection = selection,
                    onSelectionChange = { selection = it; selectMode = it.isNotEmpty() },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    if (showPasswordDialog) {
        PasswordDialog(
            isError = passwordError,
            onDismiss = { showPasswordDialog = false },
            onSubmit = { pwd ->
                password = pwd
                showPasswordDialog = false
                attempt++
            },
        )
    }

    if (showExtractDialog && loaded != null) {
        val selectedIds = expandSelection(loaded.entries, selection, selectMode)
        ExtractDialog(
            fileCount = selectedIds.size,
            settings = settings,
            onDismiss = { showExtractDialog = false },
            onConfirm = { targetUri, targetName, policy ->
                showExtractDialog = false
                val entries = loaded.entries.filter { it.id in selectedIds || it.isDirectory }
                val plan = ExtractPlan(
                    request = request,
                    password = password,
                    entries = entries,
                    targetUri = targetUri,
                    targetName = targetName,
                    conflictPolicy = policy,
                )
                val started = TaskManager.startExtraction(context, plan)
                if (started) {
                    selectMode = false
                    selection = emptySet()
                }
            },
        )
    }
}

private fun expandSelection(
    entries: List<ArchiveEntryInfo>,
    selection: Set<Int>,
    selectMode: Boolean,
): Set<Int> {
    if (!selectMode || selection.isEmpty()) {
        return entries.filter { !it.isDirectory }.map { it.id }.toSet()
    }
    val byId = entries.associateBy { it.id }
    val result = HashSet<Int>()
    val prefixes = ArrayList<String>()
    for (id in selection) {
        val entry = byId[id] ?: continue
        if (entry.isDirectory) {
            prefixes.add(entry.path + "/")
        } else {
            result.add(id)
        }
    }
    if (prefixes.isNotEmpty()) {
        for (e in entries) {
            if (e.isDirectory) continue
            if (prefixes.any { e.path.startsWith(it) }) result.add(e.id)
        }
    }
    return result
}

@Composable
private fun ArchiveContent(
    reader: ArchiveReader,
    request: ArchiveRequest,
    currentDir: String,
    onDirChange: (String) -> Unit,
    search: String,
    onSearchChange: (String) -> Unit,
    selectMode: Boolean,
    selection: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = reader.entries
    val listItems = remember(entries, currentDir, search) {
        if (search.isNotBlank()) {
            entries.asSequence()
                .filter { !it.isDirectory && it.path.contains(search, ignoreCase = true) }
                .take(800)
                .map { TreeItem(it.name, it.path, false, it) }
                .toList()
        } else {
            listDirectory(entries, currentDir)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ArchiveHeaderCard(reader)
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.archive_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }

        if (search.isBlank()) {
            item {
                Breadcrumb(currentDir = currentDir, onNavigate = onDirChange)
            }
        }

        if (listItems.isEmpty()) {
            item {
                EmptyHint(
                    icon = AppIcons.FolderOpen,
                    title = if (search.isBlank()) stringResource(R.string.archive_empty_dir)
                    else stringResource(R.string.common_unknown),
                )
            }
        } else {
            items(listItems.size) { index ->
                val item = listItems[index]
                val isSelected = item.entry != null && item.entry.id in selection
                ArchiveRow(
                    item = item,
                    selected = isSelected,
                    selectMode = selectMode,
                    onClick = {
                        if (item.isDirectory) {
                            onDirChange(item.path)
                            onSearchChange("")
                        } else if (selectMode) {
                            val entry = item.entry ?: return@ArchiveRow
                            onSelectionChange(
                                if (entry.id in selection) selection - entry.id else selection + entry.id
                            )
                        } else {
                            val entry = item.entry ?: return@ArchiveRow
                            onSelectionChange(setOf(entry.id))
                        }
                    },
                    onLongClick = {
                        val entry = item.entry ?: return@ArchiveRow
                        onSelectionChange(
                            if (entry.id in selection) selection - entry.id else selection + entry.id
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ArchiveHeaderCard(reader: ArchiveReader) {
    val meta = reader.meta
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatBadge(meta.format)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Fmt.size(meta.totalSize),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (meta.encrypted) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatPill(stringResource(R.string.archive_stat_entries), meta.entryCount.toString())
                StatPill(stringResource(R.string.archive_compressed), Fmt.size(meta.totalCompressed))
                StatPill(stringResource(R.string.archive_ratio_label), meta.ratioText)
            }
        }
    }
}

@Composable
private fun Breadcrumb(currentDir: String, onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.archive_root),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigate("") }.padding(6.dp),
        )
        if (currentDir.isNotEmpty()) {
            val parts = currentDir.split('/')
            var acc = ""
            for (part in parts) {
                acc = if (acc.isEmpty()) part else "$acc/$part"
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val target = acc
                Text(
                    text = part,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigate(target) }.padding(6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveRow(
    item: TreeItem,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 0.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode && !item.isDirectory) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
            } else {
                IconBadge(
                    icon = if (item.isDirectory) AppIcons.Folder else iconForFile(item.name),
                    size = 36.dp,
                    tint = if (item.isDirectory) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary,
                    background = if (item.isDirectory) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                )
                Spacer(Modifier.width(10.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = when {
                    item.isDirectory && item.count > 0 -> stringResource(R.string.common_items, item.count)
                    item.entry != null && item.entry.size >= 0 -> Fmt.size(item.entry.size)
                    else -> ""
                }
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.isDirectory) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExtractBottomBar(
    selectMode: Boolean,
    selectedCount: Int,
    onExtractAll: () -> Unit,
    onExtractSelected: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode) {
                Text(
                    text = stringResource(R.string.archive_select_mode, selectedCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onExtractSelected, enabled = selectedCount > 0) {
                    Icon(AppIcons.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.archive_extract_selected, selectedCount))
                }
            } else {
                Text(
                    text = stringResource(R.string.extract_content_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onExtractAll) {
                    Icon(AppIcons.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.archive_extract_all))
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    isError: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.archive_password_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.archive_password_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.archive_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (isError) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.archive_wrong_password),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotEmpty()) onSubmit(value) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun ExtractDialog(
    fileCount: Int,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onConfirm: (Uri, String, ConflictPolicy) -> Unit,
) {
    val context = LocalContext.current
    var targetUri by remember { mutableStateOf(settings.defaultTargetUri) }
    var targetName by remember { mutableStateOf(settings.defaultTargetName ?: "") }
    var policy by remember {
        mutableStateOf(if (settings.conflict == ConflictPolicy.ASK) ConflictPolicy.OVERWRITE else settings.conflict)
    }

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            targetUri = uri.toString()
            targetName = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() ?: "文件夹"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Unarchive, contentDescription = null) },
        title = { Text(stringResource(R.string.extract_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.extract_confirm, fileCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.extract_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = targetName.ifEmpty { stringResource(R.string.settings_not_set) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickDir.launch(null) }) {
                        Text(stringResource(R.string.extract_choose_dir))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.extract_conflict),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = policy == ConflictPolicy.OVERWRITE,
                        onClick = { policy = ConflictPolicy.OVERWRITE },
                        label = { Text(stringResource(R.string.conflict_overwrite)) },
                    )
                    FilterChip(
                        selected = policy == ConflictPolicy.SKIP,
                        onClick = { policy = ConflictPolicy.SKIP },
                        label = { Text(stringResource(R.string.conflict_skip)) },
                    )
                    FilterChip(
                        selected = policy == ConflictPolicy.RENAME,
                        onClick = { policy = ConflictPolicy.RENAME },
                        label = { Text(stringResource(R.string.conflict_rename)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val uri = targetUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    if (uri != null) onConfirm(uri, targetName, policy)
                },
                enabled = targetUri != null,
            ) {
                Text(stringResource(R.string.extract_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private fun listDirectory(entries: List<ArchiveEntryInfo>, dir: String): List<TreeItem> {
    val prefix = if (dir.isEmpty()) "" else "$dir/"
    val dirCounts = LinkedHashMap<String, Int>()
    val dirSeen = HashSet<String>()
    val files = ArrayList<TreeItem>()
    for (e in entries) {
        if (!e.path.startsWith(prefix)) continue
        val rest = e.path.substring(prefix.length)
        if (rest.isEmpty()) continue
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            val name = rest.substring(0, slash)
            if (dirSeen.add(name)) dirCounts[name] = 0
            dirCounts[name] = (dirCounts[name] ?: 0) + 1
        } else if (e.isDirectory) {
            if (dirSeen.add(rest)) dirCounts[rest] = 0
        } else {
            files.add(TreeItem(e.name, e.path, false, e))
        }
    }
    val result = ArrayList<TreeItem>(dirCounts.size + files.size)
    dirCounts.entries.sortedBy { it.key.lowercase() }.forEach { (name, count) ->
        result.add(TreeItem(name, if (dir.isEmpty()) name else "$dir/$name", true, null, count))
    }
    files.sortBy { it.name.lowercase() }
    result.addAll(files)
    return result
}
