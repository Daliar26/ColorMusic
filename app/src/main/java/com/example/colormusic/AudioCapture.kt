package com.example.colormusic

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection

/**
 * Захват звука.
 * SYSTEM  — звук других приложений (Android 10+, только у приложений, разрешивших захват).
 * MICROPHONE — запасной режим: микрофон слышит звук из динамика.
 * Аудио не записывается на диск: буфер сразу уходит в анализатор.
 */
class AudioCapture(
    private val projection: MediaProjection?,
    private val mode: AudioSourceMode,
) {
    companion object {
        const val RATE = 44100
    }

    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(): Result<Unit> = runCatching {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, AudioAnalyzer.SIZE * 2 * 4)

        val rec = if (mode == AudioSourceMode.SYSTEM) {
            val p = requireNotNull(projection) { "нет разрешения MediaProjection" }
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufBytes,
            )
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord не инициализирован")
        }
        rec.startRecording()
        record = rec
    }

    /** Блокирующее чтение. Возвращает число прочитанных сэмплов или отрицательное значение при ошибке. */
    fun read(buf: ShortArray): Int = record?.read(buf, 0, buf.size) ?: -1

    fun stop() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}
