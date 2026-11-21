package com.example.colormusic

import com.example.colormusic.Palette
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** Итоговое желаемое состояние лампы. */
data class Rgb(val r: Int, val g: Int, val b: Int)
data class LightState(val rgb: Rgb, val brightness: Int, val on: Boolean)

/**
 * Превращает признаки звука в цвет и яркость.
 * громкость → яркость, бас → насыщенность, середина → оттенок,
 * высокие → скорость смены цвета, центроид → сдвиг оттенка.
 * Все значения сглаживаются экспоненциально; в safeMode ограничивается скорость изменений.
 */
class ColorMapper {
    private var hue = 0f
    private var drift = 0f
    private var sat = 0.6f
    private var level = 0f
    private var pulse = 0f

    fun map(f: AudioFeatures, s: AppSettings, dt: Float): LightState {
        val safe = s.safeMode
        if (f.beat && !safe) pulse = 1f
        pulse = max(0f, pulse - dt * 3f)
        if (!f.silent) drift = (drift + dt * s.speed * (0.5f + f.high) * 60f) % 360f

        val minL = s.minPercent / 100f
        val maxL = max(s.maxPercent, s.minPercent) / 100f
        val targetLevel = if (f.silent) minL
        else (minL + (maxL - minL) * (0.8f * f.rms + 0.2f * pulse)).coerceIn(0f, 1f)
        val targetSat = if (f.silent) 0.15f else 0.5f + 0.5f * f.low
        val targetHue = when (s.palette) {
            Palette.RAINBOW -> (drift + f.centroid * 120f + f.mid * 60f) % 360f
            Palette.WARM -> f.centroid * 50f
            Palette.COOL -> 180f + f.centroid * 80f
            Palette.CUSTOM -> s.customHue + (f.centroid - 0.5f) * 40f
        }

        val tau = max(s.transitionMs / 1000f, 0.05f).let { if (safe) max(it, 0.4f) else it }
        val a = (1.0 - exp(-dt / tau.toDouble())).toFloat()
        hue = stepHue(hue, targetHue, a, if (safe) 40f * dt else 360f)
        sat += (targetSat - sat) * a
        val dl = (targetLevel - level) * a
        level = (level + if (safe) dl.coerceIn(-0.5f * dt, 0.5f * dt) else dl).coerceIn(0f, 1f)

        return LightState(
            rgb = hsvToRgb(hue, sat.coerceIn(0f, 1f), 1f),
            brightness = (level * 255).roundToInt(),
            on = level > 0.02f,
        )
    }

    /** Поворот оттенка по кратчайшей дуге круга. */
    private fun stepHue(from: Float, to: Float, a: Float, maxStep: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        val step = (diff * a).coerceIn(-maxStep, maxStep)
        return ((from + step) % 360f + 360f) % 360f
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Rgb {
        val c = v * s
        val hp = (h / 60f) % 6f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r, g, b) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        return Rgb(
            ((r + m) * 255).roundToInt(),
            ((g + m) * 255).roundToInt(),
            ((b + m) * 255).roundToInt(),
        )
    }
}
