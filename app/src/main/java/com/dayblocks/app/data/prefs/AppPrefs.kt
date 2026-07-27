package com.dayblocks.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.dayblocks.app.data.model.AppSettings
import com.dayblocks.app.data.model.TimerState
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("dayblocks_prefs")

class AppPrefs(private val context: Context) {

    private val gson = Gson()

    companion object {
        val KEY_TIMER_STATE   = stringPreferencesKey("timer_state")
        val KEY_TASK_PROGRESS = stringPreferencesKey("task_progress")
        val KEY_SETTINGS      = stringPreferencesKey("settings")
        val KEY_LAST_RESET    = stringPreferencesKey("last_reset_date")
        val KEY_BUBBLE_HIDDEN = booleanPreferencesKey("bubble_hidden")
        val KEY_SELECTED_TASKS = stringPreferencesKey("selected_tasks")
    }

    // ── Timer State ──────────────────────────────────────────────────────────
    val timerStateFlow: Flow<TimerState?> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[KEY_TIMER_STATE]?.let {
                runCatching { gson.fromJson(it, TimerState::class.java) }.getOrNull()
            }
        }

    suspend fun saveTimerState(state: TimerState?) {
        context.dataStore.edit { prefs ->
            if (state == null) prefs.remove(KEY_TIMER_STATE)
            else prefs[KEY_TIMER_STATE] = gson.toJson(state)
        }
    }

    // ── Task Progress (paused tasks) ─────────────────────────────────────────
    val taskProgressFlow: Flow<Map<String, Long>> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[KEY_TASK_PROGRESS]?.let {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(it, Map::class.java) as? Map<String, Double>
                }.getOrNull()?.mapValues { (_, v) -> v.toLong() } ?: emptyMap()
            } ?: emptyMap()
        }

    suspend fun saveTaskProgress(progress: Map<String, Long>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TASK_PROGRESS] = gson.toJson(progress)
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────
    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[KEY_SETTINGS]?.let {
                runCatching { gson.fromJson(it, AppSettings::class.java) }.getOrNull()
            } ?: AppSettings()
        }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SETTINGS] = gson.toJson(settings)
        }
    }

    // ── Last Reset Date ───────────────────────────────────────────────────────
    val lastResetDateFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_LAST_RESET] ?: "" }

    suspend fun saveLastResetDate(date: String) {
        context.dataStore.edit { prefs -> prefs[KEY_LAST_RESET] = date }
    }

    // ── Bubble Hidden State ───────────────────────────────────────────────────
    val bubbleHiddenFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[KEY_BUBBLE_HIDDEN] ?: false }

    suspend fun saveBubbleHidden(hidden: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_BUBBLE_HIDDEN] = hidden }
    }

    // ── Selected Tasks for Notification Dashboard ──────────────────────────────

    val selectedTasksFlow: Flow<Map<String, String>> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[KEY_SELECTED_TASKS]?.let {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(it, Map::class.java) as? Map<String, String>
                }.getOrNull() ?: emptyMap()
            } ?: emptyMap()
        }

    suspend fun saveSelectedTasks(selected: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTED_TASKS] = gson.toJson(selected)
        }
    }
}
