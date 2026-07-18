package com.badgerride.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.badgerride.R
import com.badgerride.Sample
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The ride's power + heart-rate lines over moving time, with the dashed ERG target.
 * Power scales 0..300 W (expanding in 150 W steps if exceeded), heart rate is fixed
 * to the design's 80..180 bpm band.
 */
class PowerHrChart @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: List<Sample> = emptyList()
    private var drawnCount = -1
    private var targetW = 0
    private var showTarget = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val textColor = context.getColor(R.color.text)
    private val accent = context.getColor(R.color.accent)
    private val hrColor = context.getColor(R.color.hr_line)

    private val gridPaint = Paint().apply {
        color = textColor; strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor; alpha = 140; textSize = sp(8f)
        typeface = resources.getFont(R.font.lora)
        fontFeatureSettings = "'tnum'"
    }
    private val hrLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hrColor; alpha = 204; textSize = sp(8f)
        typeface = resources.getFont(R.font.lora)
        fontFeatureSettings = "'tnum'"
    }
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = dp(1.6f)
        strokeJoin = Paint.Join.ROUND
    }
    private val hrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hrColor; style = Paint.Style.STROKE; strokeWidth = dp(1.4f)
        strokeJoin = Paint.Join.ROUND
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
        alpha = 230
    }

    private val powerPath = Path()
    private val hrPath = Path()

    fun update(samples: List<Sample>, targetW: Int, showTarget: Boolean) {
        val dirty = samples.size != drawnCount || targetW != this.targetW || showTarget != this.showTarget
        this.samples = samples
        this.targetW = targetW
        this.showTarget = showTarget
        if (dirty) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawnCount = samples.size
        val padL = dp(26f); val padR = dp(30f); val padT = dp(6f); val padB = dp(16f)
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0 || h <= 0) return

        val maxSample = samples.maxOfOrNull { it.power } ?: 0
        val powerTop = max(300, ceil(maxSample / 150.0).toInt() * 150)
        val durSec = max(samples.size, 300)

        fun x(sec: Int) = padL + w * sec / durSec.toFloat()
        fun yP(p: Int) = padT + h * (1f - p / powerTop.toFloat())
        fun yH(hr: Int) = padT + h * (1f - (hr.coerceIn(80, 180) - 80) / 100f)

        // grid: baseline strong, mid and top faint
        gridPaint.alpha = 51
        canvas.drawLine(padL, yP(0), padL + w, yP(0), gridPaint)
        gridPaint.alpha = 26
        canvas.drawLine(padL, yP(powerTop / 2), padL + w, yP(powerTop / 2), gridPaint)
        canvas.drawLine(padL, yP(powerTop), padL + w, yP(powerTop), gridPaint)

        // power axis labels (left, right-aligned)
        labelPaint.textAlign = Paint.Align.RIGHT
        val lx = padL - dp(4f)
        canvas.drawText("0", lx, yP(0) + dp(3f), labelPaint)
        canvas.drawText((powerTop / 2).toString(), lx, yP(powerTop / 2) + dp(3f), labelPaint)
        canvas.drawText(powerTop.toString(), lx, yP(powerTop) + dp(3f), labelPaint)

        // heart-rate axis labels (right)
        hrLabelPaint.textAlign = Paint.Align.LEFT
        val rx = padL + w + dp(4f)
        canvas.drawText("80", rx, yH(80) + dp(3f), hrLabelPaint)
        canvas.drawText("130", rx, yH(130) + dp(3f), hrLabelPaint)
        canvas.drawText("180", rx, yH(180) + dp(3f), hrLabelPaint)

        // time labels: 0, quarters, "N min"
        labelPaint.textAlign = Paint.Align.CENTER
        val mins = durSec / 60.0
        val ty = height - dp(3f)
        canvas.drawText("0", x(0), ty, labelPaint)
        canvas.drawText((mins * 0.25).roundToInt().toString(), x(durSec / 4), ty, labelPaint)
        canvas.drawText((mins * 0.5).roundToInt().toString(), x(durSec / 2), ty, labelPaint)
        canvas.drawText((mins * 0.75).roundToInt().toString(), x(durSec * 3 / 4), ty, labelPaint)
        canvas.drawText("${mins.roundToInt()} min", x(durSec), ty, labelPaint)

        if (showTarget && targetW in 1..powerTop) {
            val yt = yP(targetW)
            canvas.drawLine(padL, yt, padL + w, yt, targetPaint)
        }

        if (samples.isEmpty()) return
        val stride = max(1, samples.size / max(1, w.toInt()))
        powerPath.rewind()
        hrPath.rewind()
        var hrStarted = false
        var i = 0
        while (i < samples.size) {
            val s = samples[i]
            val sx = x(i)
            if (i == 0) powerPath.moveTo(sx, yP(s.power.coerceIn(0, powerTop)))
            else powerPath.lineTo(sx, yP(s.power.coerceIn(0, powerTop)))
            if (s.hr > 0) {
                if (!hrStarted) { hrPath.moveTo(sx, yH(s.hr)); hrStarted = true }
                else hrPath.lineTo(sx, yH(s.hr))
            }
            i += stride
        }
        canvas.drawPath(powerPath, powerPaint)
        if (hrStarted) canvas.drawPath(hrPath, hrPaint)
    }
}
