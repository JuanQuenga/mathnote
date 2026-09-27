package com.mathnote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Stores normalized points, including per-sample pressure, so rotation keeps the ink. */
public final class InkView extends View {
    public static final class Dot {
        float x, y, pressure;
        Dot(float x, float y, float pressure) {
            this.x = x; this.y = y; this.pressure = pressure;
        }
    }
    public static final class Stroke {
        int color;
        float size;
        final ArrayList<Dot> dots = new ArrayList<>();
        Stroke(int color, float size) { this.color = color; this.size = size; }
    }
    public interface Listener { void onEdit(); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private final ArrayList<String> undo = new ArrayList<>(), redo = new ArrayList<>();
    private Stroke active;
    private int activePointer = -1, inkColor = Color.rgb(25, 35, 55);
    private boolean erasing, fingerWriting, eraseChanged, strokeEraser;
    private float density;
    private Listener listener;
    private JSONArray annotations = new JSONArray();

    public InkView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setBackgroundColor(Color.WHITE);
    }
    public void setListener(Listener value) { listener = value; }
    public void setColor(int value) { inkColor = value; erasing = false; }
    public void setEraser() { erasing = true; }
    public void setFingerWriting(boolean value) { fingerWriting = value; }
    public boolean hasInk() { return !strokes.isEmpty(); }
    public void clearHistory() { undo.clear(); redo.clear(); }
    public void setAnnotations(JSONArray marks) { annotations = marks == null ? new JSONArray() : marks; invalidate(); }
    public ArrayList<Stroke> strokes() { return strokes; }

    public void load(ArrayList<Stroke> value) {
        strokes.clear(); strokes.addAll(value); active = null; clearHistory(); invalidate();
    }
    private void checkpoint() {
        undo.add(InkCodec.encode(strokes));
        if (undo.size() > 30) undo.remove(0);
        redo.clear();
    }
    public void undo() {
        if (undo.isEmpty()) return;
        redo.add(InkCodec.encode(strokes));
        restore(undo.remove(undo.size() - 1));
    }
    public void redo() {
        if (redo.isEmpty()) return;
        undo.add(InkCodec.encode(strokes));
        restore(redo.remove(redo.size() - 1));
    }
    private void restore(String encoded) {
        strokes.clear(); strokes.addAll(InkCodec.decode(encoded)); invalidate(); edited();
    }
    private void edited() { if (listener != null) listener.onEdit(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);
        paint.setColor(0xffe8edf2); paint.setStrokeWidth(density);
        for (int i = 1; i < 20; i++) {
            float y = getHeight() * i / 20f;
            canvas.drawLine(0, y, getWidth(), y, paint);
        }
        for (Stroke stroke : strokes) drawStroke(canvas, stroke, getWidth(), getHeight());
        if (active != null) drawStroke(canvas, active, getWidth(), getHeight());
        drawAnnotations(canvas);
    }
    private void drawAnnotations(Canvas canvas) {
        Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < annotations.length(); i++) {
            JSONObject item = annotations.optJSONObject(i);
            if (item == null) continue;
            JSONArray points = item.optJSONArray("points");
            if (points == null || points.length() != 2) continue;
            JSONArray start = points.optJSONArray(0), end = points.optJSONArray(1);
            if (start == null || end == null) continue;
            float x1 = (float) start.optDouble(0) * getWidth(), y1 = (float) start.optDouble(1) * getHeight();
            float x2 = (float) end.optDouble(0) * getWidth(), y2 = (float) end.optDouble(1) * getHeight();
            String kind = item.optString("kind");
            mark.setStrokeCap(Paint.Cap.ROUND);
            if (kind.equals("highlight")) {
                mark.setColor(0x77ffd54f); mark.setStrokeWidth(23 * density);
                canvas.drawLine(x1, y1, x2, y2, mark);
            } else if (kind.equals("underline") || kind.equals("arrow")) {
                mark.setColor(0xffdb4054); mark.setStrokeWidth(3 * density);
                canvas.drawLine(x1, y1, x2, y2, mark);
                if (kind.equals("arrow")) {
                    float angle = (float) Math.atan2(y2 - y1, x2 - x1);
                    float length = 13 * density;
                    canvas.drawLine(x2, y2, x2 - length * (float) Math.cos(angle - .55),
                            y2 - length * (float) Math.sin(angle - .55), mark);
                    canvas.drawLine(x2, y2, x2 - length * (float) Math.cos(angle + .55),
                            y2 - length * (float) Math.sin(angle + .55), mark);
                }
            }
            String label = item.optString("text");
            if (!label.isEmpty()) {
                mark.setColor(0xffa52137); mark.setTextSize(16 * density); mark.setStrokeWidth(1);
                canvas.drawText(label.length() > 60 ? label.substring(0, 60) : label,
                        Math.min(x2, getWidth() - 60 * density), Math.max(20 * density, y2 - 8 * density), mark);
            }
        }
    }
    public static void drawStroke(Canvas canvas, Stroke stroke, int width, int height) {
        if (stroke.dots.isEmpty()) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(stroke.color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        float scale = width / 1600f;
        if (stroke.dots.size() == 1) {
            Dot d = stroke.dots.get(0);
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(d.x * width, d.y * height, Math.max(1f, stroke.size * d.pressure * scale / 2f), p);
            return;
        }
        for (int i = 1; i < stroke.dots.size(); i++) {
            Dot a = stroke.dots.get(i - 1), b = stroke.dots.get(i);
            p.setStrokeWidth(Math.max(1f, stroke.size * (a.pressure + b.pressure) * 0.5f * scale));
            canvas.drawLine(a.x * width, a.y * height, b.x * width, b.y * height, p);
        }
    }

    private void addSample(MotionEvent event, int pointerIndex, int historyIndex) {
        float x = historyIndex < 0 ? event.getX(pointerIndex) : event.getHistoricalX(pointerIndex, historyIndex);
        float y = historyIndex < 0 ? event.getY(pointerIndex) : event.getHistoricalY(pointerIndex, historyIndex);
        float pressure = historyIndex < 0 ? event.getPressure(pointerIndex) : event.getHistoricalPressure(pointerIndex, historyIndex);
        x = Math.max(0, Math.min(1, x / Math.max(1, getWidth())));
        y = Math.max(0, Math.min(1, y / Math.max(1, getHeight())));
        if (active != null) {
            active.dots.add(new Dot(x, y, Math.max(0.35f, Math.min(1.7f, pressure))));
        } else if (strokeEraser) {
            float px = x * getWidth(), py = y * getHeight();
            float radius = 17 * density;
            for (int i = strokes.size() - 1; i >= 0; i--) {
                if (near(strokes.get(i), px, py, radius)) {
                    strokes.remove(i); eraseChanged = true;
                }
            }
        }
    }
    private boolean near(Stroke stroke, float x, float y, float radius) {
        for (int i = 0; i < stroke.dots.size(); i++) {
            Dot a = stroke.dots.get(i);
            Dot b = stroke.dots.get(Math.min(i + 1, stroke.dots.size() - 1));
            float ax = a.x * getWidth(), ay = a.y * getHeight();
            float bx = b.x * getWidth(), by = b.y * getHeight();
            float dx = bx - ax, dy = by - ay;
            float t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / Math.max(1, dx * dx + dy * dy)));
            if (Math.hypot(x - ax - t * dx, y - ay - t * dy) <= radius) return true;
        }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            int type = event.getToolType(index);
            if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER &&
                    !(fingerWriting && type == MotionEvent.TOOL_TYPE_FINGER)) return true;
            activePointer = event.getPointerId(index);
            eraseChanged = false;
            strokeEraser = erasing || type == MotionEvent.TOOL_TYPE_ERASER;
            checkpoint();
            if (!strokeEraser) active = new Stroke(inkColor, 4.4f);
            addSample(event, index, -1); invalidate();
            return true;
        }
        if (activePointer < 0) return true;
        if (action == MotionEvent.ACTION_CANCEL ||
                (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(index) == activePointer &&
                 (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0)) {
            // Android marks unintended palm input by canceling the current pointer stream.
            restore(undo.remove(undo.size() - 1));
            active = null; activePointer = -1; return true;
        }
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int pointerIndex = event.findPointerIndex(activePointer);
            if (pointerIndex >= 0) {
                for (int i = 0; i < event.getHistorySize(); i++) addSample(event, pointerIndex, i);
                addSample(event, pointerIndex, -1);
            }
            boolean finished = action == MotionEvent.ACTION_UP ||
                    (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(index) == activePointer);
            if (finished) {
                if (active != null && !active.dots.isEmpty()) strokes.add(active);
                if (active != null || eraseChanged) edited();
                else if (!undo.isEmpty()) undo.remove(undo.size() - 1);
                active = null; activePointer = -1;
            }
            invalidate(); return true;
        }
        return true;
    }
}
