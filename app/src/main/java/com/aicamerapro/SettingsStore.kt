package com.aicamerapro

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_camera_pro_settings")

class SettingsStore(private val ctx: Context) {
    private val kSave = booleanPreferencesKey("save_both")
    private val kMode = intPreferencesKey("startup_mode")
    private val kSize = intPreferencesKey("size_idx")
    private val kAspect = booleanPreferencesKey("aspect_169")
    private val kFlash = intPreferencesKey("flash")
    private val kBlur = intPreferencesKey("blur")
    private val kStrength = intPreferencesKey("strength")

    suspend fun load(): AppSettings {
        val p = ctx.dataStore.data.first()
        val d = AppSettings()
        return AppSettings(
            saveBoth = p[kSave] ?: d.saveBoth,
            startupMode = p[kMode] ?: d.startupMode,
            sizeIdx = p[kSize] ?: d.sizeIdx,
            aspect169 = p[kAspect] ?: d.aspect169,
            flash = p[kFlash] ?: d.flash,
            blur = p[kBlur] ?: d.blur,
            strengthIdx = p[kStrength] ?: d.strengthIdx
        )
    }

    suspend fun save(s: AppSettings) {
        ctx.dataStore.edit {
            it[kSave] = s.saveBoth
            it[kMode] = s.startupMode
            it[kSize] = s.sizeIdx
            it[kAspect] = s.aspect169
            it[kFlash] = s.flash
            it[kBlur] = s.blur
            it[kStrength] = s.strengthIdx
        }
    }
}
