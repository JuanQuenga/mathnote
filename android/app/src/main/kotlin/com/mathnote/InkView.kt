package com.mathnote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Stores normalized points and pressure so ink survives rotation and page reopening. */
class InkView(context: Context) : View(context) {
    data class Dot(val x: Float, val y: Float, val pressure: Float)
    data class Stroke(val color: Int, val size: Float, val dots: MutableList<Dot> = mutableListOf())

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkStrokes = mutableListOf<Stroke>()
    private val undoHistory = mutableListOf<String>()
    private val redoHistory = mutableListOf<String>()
    private var active: Stroke? = null
    private var activePointer = -1
    private var inkColor = Color.rgb(25, 35, 55)
    private var erasing = false
    private var fingerWriting = false
    private var eraseChanged = false
    private var strokeEraser = false
    private val density = resources.displayMetrics.density
    private var listener: (() -> Unit)? = null
    private var annotations = JSONArray()
    private var committedInk: Bitmap? = null
    private var inkDirty = true

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setBackgroundColor(Color.WHITE)
    }

    fun setListener(value: () -> Unit) { listener = value }
    fun setColor(value: Int) { inkColor = value; erasing = false }
    fun setEraser() { erasing = true }
    fun setFingerWriting(value: Boolean) { fingerWriting = value }
    fun hasInk() = inkStrokes.isNotEmpty()
    fun clearHistory() { undoHistory.clear(); redoHistory.clear() }
    fun setAnnotations(marks: JSONArray?) { annotations = marks ?: JSONArray(); invalidate() }
    fun strokes(): List<Stroke> = inkStrokes

    fun load(value: List<Stroke>) {
        inkStrokes.clear()
        inkStrokes.addAll(value)
        active = null
        clearHistory()
        inkDirty = true
        invalidate()
    }

    private fun checkpoint() {
        undoHistory.add(InkCodec.encode(inkStrokes))
        if (undoHistory.size > 30) undoHistory.removeAt(0)
        redoHistory.clear()
    }

    fun undo() {
        if (undoHistory.isEmpty()) return
        redoHistory.add(InkCodec.encode(inkStrokes))
        restore(undoHistory.removeAt(undoHistory.lastIndex))
    }

    fun redo() {
        if (redoHistory.isEmpty()) return
        undoHistory.add(InkCodec.encode(inkStrokes))
        restore(redoHistory.removeAt(redoHistory.lastIndex))
    }

    private fun restore(encoded: String) {
        inkStrokes.clear()
        inkStrokes.addAll(InkCodec.decode(encoded))
        inkDirty = true
        invalidate()
        listener?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val cached = committedInk
        if (cached == null || cached.width != width || cached.height != height || inkDirty) {
            cached?.recycle()
            committedInk = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val base = Canvas(requireNotNull(committedInk))
            drawPage(base)
            inkStrokes.forEach { drawStroke(base, it, width, height) }
            inkDirty = false
        }
        canvas.drawBitmap(requireNotNull(committedInk), 0f, 0f, null)
        active?.let { drawStroke(canvas, it, width, height) }
        drawAnnotations(canvas)
    }

    private fun drawPage(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        paint.color = 0xffe8edf2.toInt()
        paint.strokeWidth = density
        for (i in 1 until 20) {
            val y = height * i / 20f
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        committedInk?.recycle()
        committedInk = null
    }

    private fun drawAnnotations(canvas: Canvas) {
        val mark = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until annotations.length()) {
            val item = annotations.optJSONObject(i) ?: continue
            val points = item.optJSONArray("points") ?: continue
            if (points.length() != 2) continue
            val start = points.optJSONArray(0) ?: continue
            val end = points.optJSONArray(1) ?: continue
            val x1 = start.optDouble(0).toFloat() * width
            val y1 = start.optDouble(1).toFloat() * height
            val x2 = end.optDouble(0).toFloat() * width
            val y2 = end.optDouble(1).toFloat() * height
            mark.strokeCap = Paint.Cap.ROUND
            when (item.optString("kind")) {
                "highlight" -> {
                    mark.color = 0x77ffd54f
                    mark.strokeWidth = 23f * density
                    canvas.drawLine(x1, y1, x2, y2, mark)
                }
                "underline", "arrow" -> {
                    mark.color = 0xffdb4054.toInt()
                    mark.strokeWidth = 3f * density
                    canvas.drawLine(x1, y1, x2, y2, mark)
                    if (item.optString("kind") == "arrow") {
                        val angle = atan2(y2 - y1, x2 - x1)
                        val length = 13f * density
                        canvas.drawLine(x2, y2, x2 - length * cos(angle - .55f), y2 - length * sin(angle - .55f), mark)
                        canvas.drawLine(x2, y2, x2 - length * cos(angle + .55f), y2 - length * sin(angle + .55f), mark)
                    }
                }
            }
            val label = item.optString("text")
            if (label.isNotEmpty()) {
                mark.color = 0xffa52137.toInt()
                mark.textSize = 16f * density
                mark.strokeWidth = 1f
                canvas.drawText(label.take(60), min(x2, width - 60f * density), max(20f * density, y2 - 8f * density), mark)
            }
        }
    }

    private fun addSample(event: MotionEvent, pointerIndex: Int, historyIndex: Int) {
        val x = (if (historyIndex < 0) event.getX(pointerIndex) else event.getHistoricalX(pointerIndex, historyIndex))
            .div(max(1, width)).coerceIn(0f, 1f)
        val y = (if (historyIndex < 0) event.getY(pointerIndex) else event.getHistoricalY(pointerIndex, historyIndex))
            .div(max(1, height)).coerceIn(0f, 1f)
        val pressure = if (historyIndex < 0) event.getPressure(pointerIndex) else event.getHistoricalPressure(pointerIndex, historyIndex)
        val drawing = active
        if (drawing != null) {
            drawing.dots.add(Dot(x, y, pressure.coerceIn(.35f, 1.7f)))
        } else if (strokeEraser) {
            val px = x * width
            val py = y * height
            val radius = 17f * density
            val iterator = inkStrokes.listIterator(inkStrokes.size)
            while (iterator.hasPrevious()) {
                if (near(iterator.previous(), px, py, radius)) {
                    iterator.remove()
                    eraseChanged = true
                    inkDirty = true
                }
            }
        }
    }

    private fun near(stroke: Stroke, x: Float, y: Float, radius: Float): Boolean {
        for (i in stroke.dots.indices) {
            val a = stroke.dots[i]
            val b = stroke.dots[min(i + 1, stroke.dots.lastIndex)]
            val ax = a.x * width; val ay = a.y * height
            val bx = b.x * width; val by = b.y * height
            val dx = bx - ax; val dy = by - ay
            val t = (((x - ax) * dx + (y - ay) * dy) / max(1f, dx * dx + dy * dy)).coerceIn(0f, 1f)
            if (hypot(x - ax - t * dx, y - ay - t * dy) <= radius) return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        if (action == MotionEvent.ACTION_DOWN) {
            val type = event.getToolType(index)
            if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER &&
                !(fingerWriting && type == MotionEvent.TOOL_TYPE_FINGER)) return true
            activePointer = event.getPointerId(index)
            eraseChanged = false
            strokeEraser = erasing || type == MotionEvent.TOOL_TYPE_ERASER
            checkpoint()
            if (!strokeEraser) active = Stroke(inkColor, 4.4f)
            addSample(event, index, -1)
            invalidate()
            return true
        }
        if (activePointer < 0) return true
        if (action == MotionEvent.ACTION_CANCEL ||
            (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(index) == activePointer &&
             event.flags and MotionEvent.FLAG_CANCELED != 0)) {
            // Android marks unintended palm input by canceling the pointer stream.
            restore(undoHistory.removeAt(undoHistory.lastIndex))
            active = null
            activePointer = -1
            return true
        }
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            val pointerIndex = event.findPointerIndex(activePointer)
            if (pointerIndex >= 0) {
                for (i in 0 until event.historySize) addSample(event, pointerIndex, i)
                addSample(event, pointerIndex, -1)
            }
            val finished = action == MotionEvent.ACTION_UP ||
                (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(index) == activePointer)
            if (finished) {
                active?.takeIf { it.dots.isNotEmpty() }?.let { inkStrokes.add(it); inkDirty = true }
                if (active != null || eraseChanged) listener?.invoke()
                else if (undoHistory.isNotEmpty()) undoHistory.removeAt(undoHistory.lastIndex)
                active = null
                activePointer = -1
            }
            invalidate()
            return true
        }
        return true
    }

    companion object {
        fun drawStroke(canvas: Canvas, stroke: Stroke, width: Int, height: Int) {
            if (stroke.dots.isEmpty()) return
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val scale = width / 1600f
            if (stroke.dots.size == 1) {
                val d = stroke.dots[0]
                p.style = Paint.Style.FILL
                canvas.drawCircle(d.x * width, d.y * height, max(1f, stroke.size * d.pressure * scale / 2f), p)
                return
            }
            for (i in 1 until stroke.dots.size) {
                val a = stroke.dots[i - 1]
                val b = stroke.dots[i]
                p.strokeWidth = max(1f, stroke.size * (a.pressure + b.pressure) * .5f * scale)
                canvas.drawLine(a.x * width, a.y * height, b.x * width, b.y * height, p)
            }
        }
    }
}
