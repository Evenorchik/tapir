package com.sleeptalk.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Draws a row of vertical bars from an int array of heights (0..100). Used for
 * the live equalizer, the player track, sparklines and per-moment mini-waves.
 * Optionally renders a "played" portion in a brighter colour.
 */
class WaveBarsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bars: IntArray = IntArray(0)
    private var playedFraction = 0f
    private var barColor = 0xFFf0a868.toInt()
    private var playedColor = 0xFFf3b074.toInt()
    private var dimColor = 0x47f0a868
    private var useDimForUnplayed = false
    private var gapDp = 2f
    private var minBarFrac = 0.06f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setBarColor(color: Int) { barColor = color; invalidate() }
    fun setPlayedColors(played: Int, unplayed: Int) {
        playedColor = played; dimColor = unplayed; useDimForUnplayed = true; invalidate()
    }
    fun setGapDp(v: Float) { gapDp = v; invalidate() }

    fun setBars(heights: IntArray, played: Float = 0f) {
        bars = heights
        playedFraction = played.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = bars.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = gapDp * resources.displayMetrics.density
        val radius = 1.5f * resources.displayMetrics.density
        val barW = max(1f, (w - gap * (n - 1)) / n)
        val playedCount = (playedFraction * n).toInt()
        var x = 0f
        for (i in 0 until n) {
            val frac = (bars[i].coerceIn(0, 100) / 100f).coerceAtLeast(minBarFrac)
            val barH = h * frac
            val top = (h - barH) / 2f
            rect.set(x, top, x + barW, top + barH)
            paint.color = when {
                useDimForUnplayed && i >= playedCount -> dimColor
                useDimForUnplayed -> playedColor
                else -> barColor
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
            x += barW + gap
        }
    }
}
