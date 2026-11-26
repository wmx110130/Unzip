package com.dsha.zipforge.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dsha.zipforge.core.ConflictPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.zipForgeStore by preferencesDataStore(name = "zipforge")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val conflict: ConflictPolicy = ConflictPolicy.ASK,
    val defaultTargetUri: String? = null,
    val defaultTargetName: String? = null,
)

data class RecentEntry(
    val uri: String,
    val name: String,
    val size: Long,
    val format: String,
    val openedAt: Long,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val conflict = stringPreferencesKey("conflict")
        val targetUri = stringPreferencesKey("target_uri")
        val targetName = stringPreferencesKey("target_name")
    }

    val settings: Flow<AppSettings> = context.zipForgeStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                theme = runCatching { ThemeMode.valueOf(prefs[Keys.theme] ?: ThemeMode.SYSTEM.name) }
                    .getOrDefault(ThemeMode.SYSTEM),
                conflict = runCatching { ConflictPolicy.valueOf(prefs[Keys.conflict] ?: ConflictPolicy.ASK.name) }
                    .getOrDefault(ConflictPolicy.ASK),
                defaultTargetUri = prefs[Keys.targetUri],
                defaultTargetName = prefs[Keys.targetName],
            )
        }

    suspend fun setTheme(mode: ThemeMode) {
        context.zipForgeStore.edit { it[Keys.theme] = mode.name }
    }

    suspend fun setConflict(policy: ConflictPolicy) {
        context.zipForgeStore.edit { it[Keys.conflict] = policy.name }
    }

    suspend fun setDefaultTarget(uri: String?, name: String?) {
        context.zipForgeStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(Keys.targetUri)
                prefs.remove(Keys.targetName)
            } else {
                prefs[Keys.targetUri] = uri
                prefs[Keys.targetName] = name ?: ""
            }
        }
    }
}

class RecentRepository(private val context: Context) {

    private val key = stringPreferencesKey("recent_archives_json")

    val recents: Flow<List<RecentEntry>> = context.zipForgeStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> parse(prefs[key]) }

    suspend fun add(entry: RecentEntry) {
        context.zipForgeStore.edit { prefs ->
            val list = parse(prefs[key]).toMutableList()
            list.removeAll { it.uri == entry.uri }
            list.add(0, entry)
            while (list.size > 12) list.removeAt(list.size - 1)
            prefs[key] = toJson(list)
        }
    }

    suspend fun clear() {
        context.zipForgeStore.edit { it.remove(key) }
    }

    private fun parse(raw: String?): List<RecentEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val out = ArrayList<RecentEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                out.add(
                    RecentEntry(
                        uri = obj.optString("uri"),
                        name = obj.optString("name"),
                        size = obj.optLong("size"),
                        format = obj.optString("format"),
                        openedAt = obj.optLong("openedAt"),
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun toJson(list: List<RecentEntry>): String {
        val array = JSONArray()
        for (e in list) {
            array.put(
                JSONObject().apply {
                    put("uri", e.uri)
                    put("name", e.name)
                    put("size", e.size)
                    put("format", e.format)
                    put("openedAt", e.openedAt)
                }
            )
        }
        return array.toString()
    }
}
