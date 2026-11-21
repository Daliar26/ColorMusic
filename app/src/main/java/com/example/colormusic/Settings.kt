package com.example.colormusic

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Palette { RAINBOW, WARM, COOL, CUSTOM }
enum class AudioSourceMode { SYSTEM, MICROPHONE }

data class AppSettings(
    val haUrl: String = "http://192.168.1.10:8123",
    val haToken: String = "",
    val entityId: String = "light.tuya_bulb",
    val source: AudioSourceMode = AudioSourceMode.SYSTEM,
    val palette: Palette = Palette.RAINBOW,
    val sensitivity: Float = 1f,     // 0.5 .. 2.0
    val minPercent: Int = 5,         // минимальная яркость, %
    val maxPercent: Int = 100,       // максимальная яркость, %
    val speed: Float = 1f,           // скорость смены цвета 0.2 .. 2.0
    val transitionMs: Int = 400,     // время сглаживания
    val customHue: Float = 200f,     // оттенок для палитры CUSTOM
    val safeMode: Boolean = true,    // мягкие переходы, без вспышек
)

/** Все настройки, включая токен, лежат в зашифрованных SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "colormusic_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            haUrl = prefs.getString("haUrl", d.haUrl) ?: d.haUrl,
            haToken = prefs.getString("haToken", d.haToken) ?: d.haToken,
            entityId = prefs.getString("entityId", d.entityId) ?: d.entityId,
            source = runCatching {
                AudioSourceMode.valueOf(prefs.getString("source", d.source.name)!!)
            }.getOrDefault(d.source),
            palette = runCatching {
                Palette.valueOf(prefs.getString("palette", d.palette.name)!!)
            }.getOrDefault(d.palette),
            sensitivity = prefs.getFloat("sensitivity", d.sensitivity),
            minPercent = prefs.getInt("minPercent", d.minPercent),
            maxPercent = prefs.getInt("maxPercent", d.maxPercent),
            speed = prefs.getFloat("speed", d.speed),
            transitionMs = prefs.getInt("transitionMs", d.transitionMs),
            customHue = prefs.getFloat("customHue", d.customHue),
            safeMode = prefs.getBoolean("safeMode", d.safeMode),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("haUrl", s.haUrl)
            .putString("haToken", s.haToken)
            .putString("entityId", s.entityId)
            .putString("source", s.source.name)
            .putString("palette", s.palette.name)
            .putFloat("sensitivity", s.sensitivity)
            .putInt("minPercent", s.minPercent)
            .putInt("maxPercent", s.maxPercent)
            .putFloat("speed", s.speed)
            .putInt("transitionMs", s.transitionMs)
            .putFloat("customHue", s.customHue)
            .putBoolean("safeMode", s.safeMode)
            .apply()
    }
}
