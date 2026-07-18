package com.badgerride.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.badgerride.R
import com.badgerride.Sample
import com.badgerride.Zones
import kotlin.math.ceil

/**
 * Time spent in each 5-bpm heart-rate bin, colored by the training zone the bin's
 * center falls into. Mirrors the design's histogram panel.
 */
class ZoneHistogram @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object { const val BIN = 5 }

    private var samples: List<Sample> = emptyList()
    private var maxHr = 185
    private var drawnCount = -1

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val baselinePaint = Paint().apply {
        color = context.getColor(R.color.divider); strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text); alpha = 140
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 7.5f, resources.displayMetrics)
        typeface = resources.getFont(R.font.lora)
        fontFeatureSettings = "'tnum'"
        textAlign = Paint.Align.CENTER
    }
    private val barPath = Path()
    private val barRect = RectF()
    private val barRadii = FloatArray(8)
    // 64 bins of 5 bpm = a 320 bpm span; no human ride needs more.
    private val secs = IntArray(64)

    fun update(samples: List<Sample>, maxHr: Int) {
        val dirty = samples.size != drawnCount || maxHr != this.maxHr
        this.samples = samples
        this.maxHr = maxHr
        if (dirty) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawnCount = samples.size
        val labelStrip = labelPaint.textSize + dp(4f)
        val baseY = height - labelStrip
        if (baseY <= 0) return
        canvas.drawLine(0f, baseY, width.toFloat(), baseY, baselinePaint)

        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (s in samples) {
            if (s.hr <= 0) continue
            if (s.hr < lo) lo = s.hr
            if (s.hr > hi) hi = s.hr
        }
        if (lo > hi) return   // no heart-rate data yet

        val binLo = lo / BIN * BIN
        val binHi = ceil((hi + 1) / BIN.toDouble()).toInt() * BIN
        val n = ((binHi - binLo) / BIN).coerceAtMost(secs.size)
        secs.fill(0, 0, n)
        for (s in samples) {
            if (s.hr <= 0) continue
            secs[((s.hr - binLo) / BIN).coerceIn(0, n - 1)]++
        }
        var maxSec = 1
        for (b in 0 until n) if (secs[b] > maxSec) maxSec = secs[b]

        val gap = dp(2f)
        val bw = (width - gap * (n - 1)) / n
        if (bw <= dp(1f)) return
        val r = dp(2f)
        val labelEvery = ceil(n / 6.0).toInt()

        for (b in 0 until n) {
            val cx = b * (bw + gap)
            val zone = Zones.index(binLo + b * BIN + BIN / 2, maxHr)
            if (secs[b] > 0) {
                val hFrac = secs[b] / maxSec.toFloat()
                val top = (baseY - dp(2f)) * (1f - hFrac) + dp(1f)
                barRect.set(cx + dp(0.5f), top, cx + bw - dp(0.5f), baseY)
                for (k in 0..3) barRadii[k] = r
                barPath.rewind()
                barPath.addRoundRect(barRect, barRadii, Path.Direction.CW)
                fillPaint.color = Zones.tint(Zones.colors[zone])
                strokePaint.color = Zones.colors[zone]
                canvas.drawPath(barPath, fillPaint)
                canvas.drawPath(barPath, strokePaint)
            }
            if (b % labelEvery == 0)
                canvas.drawText((binLo + b * BIN).toString(), cx + bw / 2, height - dp(2f), labelPaint)
        }
    }
}
