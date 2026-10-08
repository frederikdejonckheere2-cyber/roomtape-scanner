package app.roomtape.scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Draws the measuring lines, corner dots and the aiming cross on top of the camera image. */
class OverlayView(ctx: Context) : View(ctx) {

    enum class Kind { CURRENT, PREVIEW, SAVED, HEIGHT }

    class Seg(val a: PointF, val b: PointF, val label: String?, val kind: Kind)

    private var segs: List<Seg> = emptyList()
    private var dots: List<PointF> = emptyList()
    private var aimOk = false

    private val density = resources.displayMetrics.density
    private val tape = Color.rgb(242, 183, 5)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tape }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 23, 34, 58) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    fun set(s: List<Seg>, d: List<PointF>, ok: Boolean) {
        synchronized(this) {
            segs = s
            dots = d
            aimOk = ok
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s: List<Seg>
        val d: List<PointF>
        val ok: Boolean
        synchronized(this) { s = segs; d = dots; ok = aimOk }

        for (seg in s) {
            when (seg.kind) {
                Kind.CURRENT -> { linePaint.color = tape; linePaint.strokeWidth = 4f * density; linePaint.pathEffect = null }
                Kind.PREVIEW -> { linePaint.color = Color.WHITE; linePaint.strokeWidth = 2.5f * density; linePaint.pathEffect = DashPathEffect(floatArrayOf(14f * density, 9f * density), 0f) }
                Kind.SAVED -> { linePaint.color = Color.argb(150, 255, 255, 255); linePaint.strokeWidth = 2f * density; linePaint.pathEffect = null }
                Kind.HEIGHT -> { linePaint.color = Color.rgb(134, 162, 255); linePaint.strokeWidth = 4f * density; linePaint.pathEffect = null }
            }
            canvas.drawLine(seg.a.x, seg.a.y, seg.b.x, seg.b.y, linePaint)
        }
        for (p in d) canvas.drawCircle(p.x, p.y, 6f * density, dotPaint)
        for (seg in s) {
            val text = seg.label ?: continue
            val mx = (seg.a.x + seg.b.x) / 2f
            val my = (seg.a.y + seg.b.y) / 2f
            val w = labelText.measureText(text) / 2f + 8f * density
            val h = 13f * density
            canvas.drawRoundRect(RectF(mx - w, my - h, mx + w, my + h * 0.7f), 6f * density, 6f * density, labelBg)
            canvas.drawText(text, mx, my + 4f * density, labelText)
        }

        val cx = width / 2f
        val cy = height / 2f
        val r = 16f * density
        reticle.color = if (ok) tape else Color.argb(170, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, reticle)
        canvas.drawLine(cx - r * 1.6f, cy, cx - r * 0.5f, cy, reticle)
        canvas.drawLine(cx + r * 0.5f, cy, cx + r * 1.6f, cy, reticle)
        canvas.drawLine(cx, cy - r * 1.6f, cx, cy - r * 0.5f, reticle)
        canvas.drawLine(cx, cy + r * 0.5f, cx, cy + r * 1.6f, reticle)
    }
}
