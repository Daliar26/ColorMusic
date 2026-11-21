package com.example.colormusic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioFeatures(
    val rms: Float,        // общая громкость 0..1
    val low: Float,        // басы 0..1
    val mid: Float,        // середина 0..1
    val high: Float,       // верх 0..1
    val centroid: Float,   // "яркость" звука 0..1 (спектральный центроид)
    val beat: Boolean,     // всплеск громкости
    val silent: Boolean,   // практически тишина
)

/**
 * Анализ одного окна звука (1024 сэмпла ≈ 23 мс при 44.1 кГц).
 * RMS, FFT с окном Ханна, три полосы частот, центроид, детектор всплесков,
 * адаптивная нормировка (AGC) и сглаживание attack/release.
 */
class AudioAnalyzer(private val rate: Int = AudioCapture.RATE) {

    companion object {
        const val SIZE = 1024
    }

    private val window = DoubleArray(SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1)) }
    private val re = DoubleArray(SIZE)
    private val im = DoubleArray(SIZE)
    private val mag = DoubleArray(SIZE / 2)
    private val binHz = rate.toDouble() / SIZE

    private val agcRms = Agc(0.005)
    private val agcLow = Agc(0.05)
    private val agcMid = Agc(0.05)
    private val agcHigh = Agc(0.05)

    private var rmsAvg = 0.0
    private var lastBeat = 0L
    private var rmsOut = 0f
    private var lowOut = 0f
    private var midOut = 0f
    private var highOut = 0f
    private var centOut = 0.5f

    fun analyze(pcm: ShortArray, count: Int, sensitivity: Float, nowMs: Long): AudioFeatures {
        var sumSq = 0.0
        for (i in 0 until SIZE) {
            val x = if (i < count) pcm[i] / 32768.0 else 0.0
            sumSq += x * x
            re[i] = x * window[i]
            im[i] = 0.0
        }
        val rawRms = sqrt(sumSq / SIZE)
        val silent = rawRms < 0.002

        fft(re, im)
        for (k in mag.indices) mag[k] = hypot(re[k], im[k])

        val low = band(20.0, 250.0)
        val mid = band(250.0, 2000.0)
        val high = band(2000.0, 12000.0)

        // Спектральный центроид, переводим 200 Гц..4 кГц в диапазон 0..1
        var num = 0.0
        var den = 0.0
        val top = min(mag.size - 1, (12000 / binHz).toInt())
        for (k in 1..top) {
            num += k * binHz * mag[k]
            den += mag[k]
        }
        val hz = if (den > 0) num / den else 0.0
        val centTarget = ((hz - 200) / 3800).coerceIn(0.0, 1.0).toFloat()

        val g = sensitivity
        rmsOut = smooth(rmsOut, if (silent) 0f else (agcRms.norm(rawRms) * g).coerceIn(0f, 1f))
        lowOut = smooth(lowOut, if (silent) 0f else (agcLow.norm(low) * g).coerceIn(0f, 1f))
        midOut = smooth(midOut, if (silent) 0f else (agcMid.norm(mid) * g).coerceIn(0f, 1f))
        highOut = smooth(highOut, if (silent) 0f else (agcHigh.norm(high) * g).coerceIn(0f, 1f))
        centOut += (centTarget - centOut) * 0.1f

        // Всплеск: текущая громкость заметно выше недавнего среднего; пауза между ударами 220 мс
        val threshold = 1.0 + 0.6 / g
        val beat = !silent && rawRms > 0.01 && rawRms > rmsAvg * threshold && nowMs - lastBeat > 220
        if (beat) lastBeat = nowMs
        rmsAvg = rmsAvg * 0.94 + rawRms * 0.06

        return AudioFeatures(rmsOut, lowOut, midOut, highOut, centOut, beat, silent)
    }

    private fun band(fromHz: Double, toHz: Double): Double {
        val a = max(1, (fromHz / binHz).toInt())
        val b = min(mag.size - 1, (toHz / binHz).toInt())
        if (b < a) return 0.0
        var s = 0.0
        for (k in a..b) s += mag[k]
        return s / (b - a + 1)
    }

    /** Быстро нарастает (attack), медленно спадает (release): лампа не мигает хаотично. */
    private fun smooth(prev: Float, x: Float): Float =
        if (x > prev) prev + (x - prev) * 0.6f else prev + (x - prev) * 0.12f

    /** Адаптивная нормировка: пиковое значение медленно "забывается". */
    private class Agc(private val floor: Double) {
        private var peak = floor
        fun norm(x: Double): Float {
            peak = max(max(x, floor), peak * 0.995)
            return (x / peak).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /** Итеративный FFT Кули–Тьюки по основанию 2. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vRe = re[b] * curRe - im[b] * curIm
                    val vIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - vRe; im[b] = im[a] - vIm
                    re[a] = re[a] + vRe; im[a] = im[a] + vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
