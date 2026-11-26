package com.dsha.zipforge.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dsha.zipforge.R
import com.dsha.zipforge.core.ArchiveRequest
import com.dsha.zipforge.data.AppSettings
import com.dsha.zipforge.data.RecentEntry
import com.dsha.zipforge.data.RecentRepository
import com.dsha.zipforge.data.SettingsRepository
import com.dsha.zipforge.io.UriArchiveSource
import com.dsha.zipforge.task.TaskManager
import com.dsha.zipforge.task.TaskStatus
import com.dsha.zipforge.task.TaskUiState
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Archive(val request: ArchiveRequest) : Screen
    data object Compress : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(
    settingsRepository: SettingsRepository,
    recentRepository: RecentRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val recents by recentRepository.recents.collectAsStateWithLifecycle(initialValue = emptyList())
    val taskState by TaskManager.state.collectAsStateWithLifecycle()
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }

    val openArchiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            openArchive(context, uri, backStack, recentRepository, scope)
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        when (val screen = backStack.last()) {
            Screen.Home -> HomeScreen(
                recents = recents,
                onOpenArchive = { openArchiveLauncher.launch(arrayOf("*/*")) },
                onCreateArchive = { backStack.add(Screen.Compress) },
                onOpenRecent = { entry ->
                    val uri = runCatching { Uri.parse(entry.uri) }.getOrNull()
                    if (uri != null) {
                        backStack.add(
                            Screen.Archive(
                                ArchiveRequest(
                                    uri = uri,
                                    displayName = entry.name,
                                    size = entry.size,
                                )
                            )
                        )
                    }
                },
                onClearRecents = { scope.launch { recentRepository.clear() } },
                onSettings = { backStack.add(Screen.Settings) },
            )

            is Screen.Archive -> ArchiveScreen(
                request = screen.request,
                settings = settings,
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            Screen.Compress -> CompressScreen(
                settings = settings,
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            Screen.Settings -> SettingsScreen(
                settings = settings,
                settingsRepository = settingsRepository,
                recentRepository = recentRepository,
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )
        }

        TaskOverlay(
            state = taskState,
            onCancel = { TaskManager.cancel() },
            onDismiss = { TaskManager.dismiss() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun openArchive(
    context: android.content.Context,
    uri: Uri,
    backStack: MutableList<Screen>,
    recentRepository: RecentRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val name = UriArchiveSource.queryName(context, uri, "archive")
    val size = UriArchiveSource.querySize(context, uri)
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
    scope.launch {
        runCatching {
            recentRepository.add(
                RecentEntry(
                    uri = uri.toString(),
                    name = name,
                    size = size,
                    format = com.dsha.zipforge.core.Formats.detectByName(name).name,
                    openedAt = System.currentTimeMillis(),
                )
            )
        }
    }
    backStack.add(Screen.Archive(ArchiveRequest(uri, name, size)))
}

@Composable
fun TaskOverlay(
    state: TaskUiState?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (state == null) return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (state.status) {
                        TaskStatus.RUNNING -> Icons.Outlined.PlayArrow
                        TaskStatus.DONE -> Icons.Outlined.CheckCircle
                        else -> Icons.Outlined.Warning
                    },
                    contentDescription = null,
                    tint = when (state.status) {
                        TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        TaskStatus.DONE -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (state.status) {
                            TaskStatus.RUNNING -> {
                                val prefix = if (state.kind == com.dsha.zipforge.task.TaskKind.EXTRACT) {
                                    stringResource(R.string.task_extracting)
                                } else {
                                    stringResource(R.string.task_compressing)
                                }
                                val percent = (state.fraction * 100).toInt()
                                "$prefix · $percent%"
                            }
                            TaskStatus.DONE -> state.resultMessage ?: stringResource(R.string.task_done)
                            TaskStatus.FAILED -> state.error ?: stringResource(R.string.task_failed)
                            TaskStatus.CANCELLED -> stringResource(R.string.task_cancelled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.status == TaskStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.status == TaskStatus.RUNNING) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.common_cancel))
                    }
                } else {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
            }

            if (state.status == TaskStatus.RUNNING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = state.currentName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = com.dsha.zipforge.core.Fmt.speed(state.speed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (state.status == TaskStatus.DONE && state.outputUri != null) {
                Spacer(Modifier.height(8.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(state.outputUri, "application/octet-stream")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.common_open))
                    }
                }
            }
        }
    }
}
