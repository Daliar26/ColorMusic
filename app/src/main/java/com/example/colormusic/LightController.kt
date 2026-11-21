package com.example.colormusic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Абстракция над лампочкой. Сейчас реализация через Home Assistant,
 * который управляет Tuya/Smart Life лампочкой. Можно добавить другую реализацию
 * (локальный протокол Tuya и т.п.), не меняя остальной код.
 */
interface LightController {
    val connected: Boolean
    suspend fun connect(): Boolean
    suspend fun send(state: LightState): Boolean
}

class HomeAssistantLightController(
    baseUrl: String,
    private val token: String,
    private val entityId: String,
) : LightController {

    private val base = baseUrl.trim().trimEnd('/')
    private val jsonType = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var up = false
    override val connected: Boolean get() = up

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        up = runCatching {
            val req = Request.Builder()
                .url("$base/api/")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        up
    }

    override suspend fun send(state: LightState): Boolean = withContext(Dispatchers.IO) {
        val (service, body) = if (!state.on) {
            "turn_off" to JSONObject().put("entity_id", entityId)
        } else {
            "turn_on" to JSONObject()
                .put("entity_id", entityId)
                .put("rgb_color", JSONArray(listOf(state.rgb.r, state.rgb.g, state.rgb.b)))
                .put("brightness", state.brightness.coerceIn(1, 255))
                .put("transition", 0.3)
        }
        val req = Request.Builder()
            .url("$base/api/services/light/$service")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val ok = runCatching { http.newCall(req).execute().use { it.isSuccessful } }
            .getOrDefault(false)
        up = ok
        ok
    }
}

/**
 * Отправляет лампе не чаще maxPerSecond команд в секунду.
 * offer() только перезаписывает "последнее состояние", устаревшие кадры отбрасываются.
 * При ошибке переподключается с паузой 2 с и не роняет приложение.
 */
class LightCommandSender(
    private val controller: LightController,
    private val scope: CoroutineScope,
    private val maxPerSecond: Float = 6f,
    private val onConnection: (Boolean) -> Unit,
) {
    @Volatile private var latest: LightState? = null
    private var lastSent: LightState? = null
    private var job: Job? = null

    fun offer(state: LightState) {
        latest = state
    }

    fun start() {
        job = scope.launch {
            val intervalMs = (1000f / maxPerSecond).toLong()
            onConnection(controller.connect())
            while (isActive) {
                val s = latest
                if (s != null && s != lastSent) {
                    if (controller.send(s)) {
                        lastSent = s
                        onConnection(true)
                    } else {
                        onConnection(false)
                        delay(2000)
                        onConnection(controller.connect())
                        continue
                    }
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/** Тест для кнопки «Проверить лампочку»: красный → зелёный → синий → белый. */
suspend fun testLight(s: AppSettings): String {
    val ctl = HomeAssistantLightController(s.haUrl, s.haToken, s.entityId)
    if (!ctl.connect()) return "Нет связи с Home Assistant: проверьте адрес, токен и Wi-Fi."
    val steps = listOf(Rgb(255, 0, 0), Rgb(0, 255, 0), Rgb(0, 0, 255), Rgb(255, 255, 255))
    for (c in steps) {
        if (!ctl.send(LightState(c, 255, true))) return "Команда не дошла до лампочки."
        delay(700)
    }
    return "Тест пройден: красный, зелёный, синий, белый."
}
