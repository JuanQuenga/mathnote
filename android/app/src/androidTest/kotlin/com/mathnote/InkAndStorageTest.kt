package com.mathnote

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkAndStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun stylusPressurePalmCancelUndoRedoAndReopen() {
        lateinit var ink: InkView
        instrumentation.runOnMainSync {
            ink = InkView(context)
            ink.layout(0, 0, 1600, 1000)
            // A finger should be ignored by default while the S Pen is selected.
            ink.onTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, 100f, 100f, 1f))
            ink.onTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_FINGER, 200f, 200f, 1f))
        }
        assertFalse(ink.hasInk())

        instrumentation.runOnMainSync {
            ink.onTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100f, 100f, .4f))
            ink.onTouchEvent(event(MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_STYLUS, 200f, 200f, .8f))
            ink.onTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 300f, 300f, 1.2f))
        }
        assertEquals(1, ink.strokes().size)
        assertEquals(3, ink.strokes().first().dots.size)
        assertEquals(.4f, ink.strokes().first().dots.first().pressure, .001f)
        assertEquals(1.2f, ink.strokes().first().dots.last().pressure, .001f)

        instrumentation.runOnMainSync { ink.undo() }
        assertFalse(ink.hasInk())
        instrumentation.runOnMainSync { ink.redo() }
        assertTrue(ink.hasInk())
        instrumentation.runOnMainSync {
            ink.onTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 400f, 400f, 1f))
            ink.onTouchEvent(event(MotionEvent.ACTION_CANCEL, MotionEvent.TOOL_TYPE_STYLUS, 450f, 450f, 1f))
        }
        assertEquals(1, ink.strokes().size)

        instrumentation.runOnMainSync {
            ink.setTool(InkView.Tool.HIGHLIGHTER)
            ink.setColor(0xffffd54f.toInt())
            ink.setStrokeWidth(24f)
            ink.onTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 600f, 600f, .4f))
            ink.onTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 700f, 700f, 1.2f))
        }
        assertEquals(InkView.Tool.HIGHLIGHTER, ink.strokes().last().tool)
        assertEquals(24f, ink.strokes().last().size, .001f)

        val original = NoteStore(context)
        val book = original.createBook("Ink test ${System.nanoTime()}")
        val page = book.pages.first()
        original.savePage(page, ink.strokes())
        val reopened = NoteStore(context)
        val savedPage = reopened.books.first { it.id == book.id }.pages.first { it.id == page.id }
        val loaded = reopened.loadPage(savedPage)
        assertEquals(2, loaded.size)
        assertEquals(3, loaded.first().dots.size)
        assertEquals(.4f, loaded.first().dots.first().pressure, .001f)
        assertEquals(InkView.Tool.HIGHLIGHTER, loaded.last().tool)
        assertEquals(24f, loaded.last().size, .001f)
    }

    @Test fun sideButtonErasesOnlyWhileHeldAndReturnsToSelectedPen() {
        lateinit var ink: InkView
        instrumentation.runOnMainSync {
            ink = InkView(context)
            ink.layout(0, 0, 1600, 1000)
            ink.onTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100f, 100f, 1f))
            ink.onTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 300f, 300f, 1f))
            ink.onTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 900f, 700f, 1f))
            ink.onTouchEvent(event(MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_STYLUS, 950f, 750f, 1f))
            ink.onGenericMotionEvent(event(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.TOOL_TYPE_STYLUS,
                200f, 200f, 1f, MotionEvent.BUTTON_STYLUS_PRIMARY))
            ink.onGenericMotionEvent(event(MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.TOOL_TYPE_STYLUS,
                1000f, 800f, 1f))
            ink.onTouchEvent(event(MotionEvent.ACTION_UP, MotionEvent.TOOL_TYPE_STYLUS, 1100f, 850f, 1f))
        }
        assertEquals(InkView.Tool.PEN, ink.selectedTool)
        assertEquals(2, ink.strokes().size)
        assertTrue(ink.strokes().all { it.tool == InkView.Tool.PEN })
        assertTrue(ink.strokes().all { stroke -> stroke.dots.all { it.x > .5f } })
        instrumentation.runOnMainSync { ink.undo() }
        assertEquals(1, ink.strokes().size)
        assertEquals(.0625f, ink.strokes().first().dots.first().x, .001f)
        instrumentation.runOnMainSync { ink.redo() }
        assertEquals(2, ink.strokes().size)
    }

    private fun event(action: Int, tool: Int, x: Float, y: Float, pressure: Float,
                      buttonState: Int = 0): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 1f })
        val now = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, 1, properties, coords, 0, buttonState,
            1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
    }
}
