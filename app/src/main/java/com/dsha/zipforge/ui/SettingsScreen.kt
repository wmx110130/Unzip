package com.dsha.zipforge.ui

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.dsha.zipforge.BuildConfig
import com.dsha.zipforge.R
import com.dsha.zipforge.core.ConflictPolicy
import com.dsha.zipforge.core.Formats
import com.dsha.zipforge.data.AppSettings
import com.dsha.zipforge.data.RecentRepository
import com.dsha.zipforge.data.SettingsRepository
import com.dsha.zipforge.data.ThemeMode
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    settingsRepository: SettingsRepository,
    recentRepository: RecentRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cleared by remember { mutableStateOf(false) }

    val pickTargetDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() ?: "文件夹"
            scope.launch { settingsRepository.setDefaultTarget(uri.toString(), name) }
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
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsCard(
                    title = stringResource(R.string.settings_appearance),
                    icon = AppIcons.Palette,
                ) {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeOption(
                            label = stringResource(R.string.theme_system),
                            selected = settings.theme == ThemeMode.SYSTEM,
                            onClick = { scope.launch { settingsRepository.setTheme(ThemeMode.SYSTEM) } },
                        )
                        ThemeOption(
                            label = stringResource(R.string.theme_light),
                            selected = settings.theme == ThemeMode.LIGHT,
                            onClick = { scope.launch { settingsRepository.setTheme(ThemeMode.LIGHT) } },
                        )
                        ThemeOption(
                            label = stringResource(R.string.theme_dark),
                            selected = settings.theme == ThemeMode.DARK,
                            onClick = { scope.launch { settingsRepository.setTheme(ThemeMode.DARK) } },
                        )
                    }
                }
            }

            item {
                SettingsCard(
                    title = stringResource(R.string.settings_extract),
                    icon = AppIcons.Unarchive,
                ) {
                    Text(
                        text = stringResource(R.string.settings_default_dir),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = settings.defaultTargetName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.settings_not_set),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { pickTargetDir.launch(null) }) {
                            Text(stringResource(R.string.common_select))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(R.string.settings_conflict),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ConflictPolicy.entries.forEach { policy ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = settings.conflict == policy,
                                    onClick = { scope.launch { settingsRepository.setConflict(policy) } },
                                    label = { Text(conflictLabel(policy)) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsCard(
                    title = stringResource(R.string.settings_formats),
                    icon = AppIcons.Unarchive,
                ) {
                    Text(
                        text = Formats.readableFormats.joinToString(" · ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "可创建：" + Formats.writableFormats.joinToString(" · ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingsCard(
                    title = stringResource(R.string.settings_about),
                    icon = Icons.Outlined.Info,
                ) {
                    Text(
                        text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.about_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_legal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (cleared) stringResource(R.string.settings_cleared)
                            else stringResource(R.string.settings_clear_recent),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        TextButton(onClick = {
                            scope.launch {
                                recentRepository.clear()
                                cleared = true
                            }
                        }) {
                            Text(stringResource(R.string.home_clear_recent))
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun conflictLabel(policy: ConflictPolicy): String = when (policy) {
    ConflictPolicy.ASK -> stringResource(R.string.conflict_ask)
    ConflictPolicy.OVERWRITE -> stringResource(R.string.conflict_overwrite)
    ConflictPolicy.SKIP -> stringResource(R.string.conflict_skip)
    ConflictPolicy.RENAME -> stringResource(R.string.conflict_rename)
}
