package com.example.colormusic

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

data class ServiceStatus(
    val running: Boolean = false,
    val hasSignal: Boolean = false,
    val lightConnected: Boolean = false,
    val text: String = "Остановлено",
)

/**
 * Foreground-сервис: держит захват звука, анализ и отправку команд лампе.
 * Работает, пока пользователь не нажмёт «Стоп» (в приложении или в уведомлении).
 */
class ColorMusicService : Service() {

    companion object {
        const val ACTION_START = "com.example.colormusic.START"
        const val ACTION_STOP = "com.example.colormusic.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        private const val CHANNEL_ID = "colormusic"
        private const val NOTIF_ID = 1
        private const val TYPE_MICROPHONE = 0x80   // FOREGROUND_SERVICE_TYPE_MICROPHONE

        /** Настройки, которые сервис читает на каждом кадре (меняются вживую из UI). */
        val liveSettings = MutableStateFlow(AppSettings())
        val status = MutableStateFlow(ServiceStatus())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipeline: Job? = null
    private var capture: AudioCapture? = null
    private var sender: LightCommandSender? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> {
                stopPipeline()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun start(intent: Intent) {
        if (pipeline != null) return
        val s = liveSettings.value
        val systemMode = s.source == AudioSourceMode.SYSTEM

        // Сначала переходим в foreground (требование Android 14 для MediaProjection/микрофона)
        startForeground(
            NOTIF_ID,
            buildNotification(),
            if (systemMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else TYPE_MICROPHONE,
        )
        status.value = ServiceStatus(running = true, text = "Запуск...")

        var proj: MediaProjection? = null
        if (systemMode) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
            if (code != Activity.RESULT_OK || data == null) {
                fail("Нет разрешения на захват звука. Нажмите «Старт» и разрешите доступ.")
                return
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data)
            if (Build.VERSION.SDK_INT >= 34) {
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopPipeline()
                        stopSelf()
                    }
                }, null)
            }
            proj = p
        }

        val cap = AudioCapture(proj, s.source)
        cap.start().onFailure {
            proj?.stop()
            val hint = if (systemMode) {
                "Приложение-источник может запрещать захват. Переключите источник на «Микрофон»."
            } else {
                "Проверьте доступ к микрофону."
            }
            fail("Не удалось начать захват: ${it.message}. $hint")
            return
        }

        capture = cap
        projection = proj
        val ctl = HomeAssistantLightController(s.haUrl, s.haToken, s.entityId)
        val snd = LightCommandSender(ctl, scope) { ok ->
            status.value = status.value.copy(lightConnected = ok)
        }
        sender = snd
        snd.start()
        status.value = status.value.copy(running = true, text = "Захват звука...")
        pipeline = scope.launch { loop(cap) }
    }

    private suspend fun CoroutineScope.loop(cap: AudioCapture) {
        val analyzer = AudioAnalyzer()
        val mapper = ColorMapper()
        val buf = ShortArray(AudioAnalyzer.SIZE)
        var lastFrame = System.nanoTime()
        var lastSignal = 0L
        var lastUi = 0L

        while (isActive) {
            val n = cap.read(buf)
            if (n <= 0) {
                delay(20)
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val s = liveSettings.value
            val f = analyzer.analyze(buf, n, s.sensitivity, now)
            if (!f.silent) lastSignal = now
            val hasSignal = now - lastSignal < 2500

            val nano = System.nanoTime()
            val dt = ((nano - lastFrame) / 1e9f).coerceIn(0.001f, 0.2f)
            lastFrame = nano

            sender?.offer(mapper.map(f, s, dt))

            if (now - lastUi > 400) {
                lastUi = now
                status.value = status.value.copy(
                    hasSignal = hasSignal,
                    text = when {
                        hasSignal -> "Цветомузыка работает"
                        s.source == AudioSourceMode.SYSTEM ->
                            "Нет звука. Возможно, приложение запрещает захват. Попробуйте «Микрофон»."
                        else -> "Тишина: лампочка в спокойном режиме"
                    },
                )
            }
        }
    }

    private fun fail(msg: String) {
        releaseResources()
        status.value = ServiceStatus(text = msg)
        stopSelf()
    }

    private fun stopPipeline() {
        releaseResources()
        status.value = ServiceStatus(text = "Остановлено")
    }

    private fun releaseResources() {
        pipeline?.cancel()
        pipeline = null
        capture?.stop()
        capture = null
        sender?.stop()
        sender = null
        projection?.stop()
        projection = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Цветомузыка", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ColorMusicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Цветомузыка активна")
            .setContentText("Анализ звука и управление лампочкой")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Стоп", stopIntent)
            .build()
    }

    override fun onDestroy() {
        releaseResources()
        scope.cancel()
        status.value = ServiceStatus()
        super.onDestroy()
    }
}
