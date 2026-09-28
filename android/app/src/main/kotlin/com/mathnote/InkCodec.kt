package com.mathnote

import org.json.JSONArray
import org.json.JSONObject

/** The persisted JSON format is shared with the original Java client. */
object InkCodec {
    fun encode(strokes: List<InkView.Stroke>): String {
        val all = JSONArray()
        strokes.forEach { stroke ->
            val dots = JSONArray()
            stroke.dots.forEach { dot -> dots.put(JSONArray().put(dot.x).put(dot.y).put(dot.pressure)) }
            all.put(JSONObject().put("color", stroke.color).put("size", stroke.size).put("dots", dots))
        }
        return all.toString()
    }

    fun decode(text: String): MutableList<InkView.Stroke> = try {
        val all = JSONArray(text)
        MutableList(all.length()) { index ->
            val item = all.getJSONObject(index)
            val dots = item.getJSONArray("dots")
            InkView.Stroke(item.getInt("color"), item.getDouble("size").toFloat(),
                MutableList(dots.length()) { pointIndex ->
                    val point = dots.getJSONArray(pointIndex)
                    InkView.Dot(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.getDouble(2).toFloat())
                })
        }
    } catch (_: Exception) {
        mutableListOf()
    }
}
