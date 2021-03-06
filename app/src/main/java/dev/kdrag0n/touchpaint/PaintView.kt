package dev.kdrag0n.touchpaint

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

const val MAX_FINGERS = 10

class PaintView(context: Context, attrs: AttributeSet) : View(context, attrs)
{
    var mode = PaintMode.PAINT
        set(value) {
            field = value

            removeCallbacks(clearRunnable)
            clearCanvas()
        }

    // 0 = on next stroke, -1 = never, * = delay in ms
    var paintClearDelay = 0L

    // Finger tracking
    private var fingers = 0
    private val lastPoint = Array(MAX_FINGERS) { PointF(-1f, -1f) }
    private val fingerDown = Array(MAX_FINGERS) { false }
    var showEventPoints = false
        set(value) {
            field = value
            invalidate()
        }

    // Rendering
    private lateinit var bitmap: Bitmap
    private var bufCanvas: Canvas? = null
    private val eventPoints = mutableListOf<PointF>()
    private var fillDown = false
    private val clearRunnable = Runnable {
        clearCanvas()
    }
    private val bgPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val fgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val brushPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.default_brush_size)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val highlightPointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.default_brush_size) * 1.5f
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }
    private val boxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.follow_box_size)
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }

    // Event rate measurement
    private var eventsReceived = 0
    private var lastToast: Toast? = null
    private val eventRateRunnable = Runnable {
        lastToast?.cancel()
        lastToast = null

        val toast = Toast.makeText(context, "Touch event rate: $eventsReceived Hz", Toast.LENGTH_SHORT)
        toast.show()
        lastToast = toast

        kickEventRate()
    }
    var measureEventRate = false
        set(value) {
            field = value
            if (!value) {
                stopEventRate()
            }
        }
    var batchEvents = false

    fun setBrushSizePx(px: Float) {
        brushPaint.strokeWidth = px
        highlightPointPaint.strokeWidth = px * 1.5f
    }

    fun getBrushSizeDp(): Float {
        return if (brushPaint.strokeWidth == 1f) {
            -1f
        } else {
            resources.pxToDp(brushPaint.strokeWidth)
        }
    }

    fun setBrushSize(dp: Float) {
        setBrushSizePx(resources.dpToPx(dp))
    }

    fun clearCanvas() {
        fillDown = false
        bufCanvas?.drawPaint(bgPaint)
        eventPoints.clear()

        lastPoint.forEach {
            it.x = -1f
            it.y = -1f
        }

        invalidate()
    }

    private fun kickEventRate() {
        eventsReceived = 0

        if (measureEventRate) {
            postDelayed(eventRateRunnable, 1000)
        }
    }

    private fun stopEventRate() {
        removeCallbacks(eventRateRunnable)
    }

    private fun fingerDown(slot: Int) {
        if (fingerDown[slot]) {
            return
        }

        fingerDown[slot] = true
        fingers++
        if (fingers == 1) {
            when (mode) {
                PaintMode.PAINT -> {
                    if (paintClearDelay > 0)
                        removeCallbacks(clearRunnable)
                    else if (paintClearDelay == 0L)
                        clearCanvas()
                }
                PaintMode.FILL -> {
                    removeCallbacks(clearRunnable)
                    fillDown = true
                }
                else -> {}
            }

            kickEventRate()
        }
    }

    private fun fingerMove(slot: Int, x: Float, y: Float, isSystemEvent: Boolean) {
        if (!fingerDown[slot]) {
            return
        }

        if (mode == PaintMode.PAINT) {
            if (lastPoint[slot].x == -1f && lastPoint[slot].y == -1f) {
                bufCanvas?.drawPoint(x, y, brushPaint)
            } else {
                bufCanvas?.drawLine(x, y, lastPoint[slot].x, lastPoint[slot].y, brushPaint)
            }
        }

        lastPoint[slot].x = x
        lastPoint[slot].y = y

        if (isSystemEvent && showEventPoints) {
            eventPoints.add(PointF(x, y))
        }
    }

    private fun fingerUp(slot: Int) {
        if (!fingerDown[slot]) {
            return
        }

        lastPoint[slot].x = -1f
        lastPoint[slot].y = -1f
        fingerDown[slot] = false
        fingers--

        if (fingers == 0) {
            when (mode) {
                PaintMode.PAINT -> if (paintClearDelay > 0) {
                    postDelayed(clearRunnable, paintClearDelay)
                }
                PaintMode.FILL -> postDelayed(clearRunnable, 250)
                else -> {}
            }

            stopEventRate()
        }
    }

    private fun allFingersUp() {
        for (i in 0 until MAX_FINGERS) {
            fingerUp(i)
        }
    }

    override fun onDraw(canvas: Canvas?) {
        when (mode) {
            PaintMode.PAINT -> canvas!!.drawBitmap(bitmap, 0f, 0f, null)
            PaintMode.FILL -> canvas!!.drawPaint(if (fillDown) fgPaint else bgPaint)
            PaintMode.FOLLOW -> {
                canvas!!.drawPaint(bgPaint)

                for (point in lastPoint) {
                    if (point.x != -1f && point.y != -1f) {
                        canvas.drawPoint(point.x, point.y, boxPaint)
                    }
                }
            }
            PaintMode.BLANK -> {
                canvas!!.drawPaint(bgPaint)
                invalidate()
            }
        }

        if (showEventPoints) {
            for (point in eventPoints) {
                canvas.drawPoint(point.x, point.y, highlightPointPaint)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        bufCanvas = Canvas(bitmap)
        clearCanvas()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event!!.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDown(0)
                fingerMove(0, event.x, event.y, true)

                if (!batchEvents) {
                    // Unbuffered dispatch only affects a single gesture (until the next ACTION_UP),
                    // so we need to request it on every ACTION_DOWN event
                    rootView.requestUnbufferedDispatch(event)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val slot = event.actionIndex

                fingerDown(slot)
                fingerMove(slot, event.getX(slot), event.getY(slot), true)
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount) {
                    val slot = event.getPointerId(p)

                    for (h in 0 until event.historySize) {
                        fingerMove(slot, event.getHistoricalX(p, h), event.getHistoricalY(p, h), false)
                    }

                    fingerMove(slot, event.getX(p), event.getY(p), true)
                }

                if (measureEventRate) {
                    eventsReceived++
                }
            }
            MotionEvent.ACTION_POINTER_UP -> fingerUp(event.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> allFingersUp()
        }

        invalidate()
        return true
    }
}