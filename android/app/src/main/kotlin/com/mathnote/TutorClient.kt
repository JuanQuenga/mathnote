package com.mathnote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TutorClient {
    fun image(strokes: List<InkView.Stroke>): String {
        val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        strokes.forEach { InkView.drawStroke(canvas, it, 1600, 1000) }
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    fun post(server: String, token: String, path: String, payload: JSONObject): JSONObject {
        val connection = connect(server, path, token)
        connection.requestMethod = "POST"
        connection.connectTimeout = 7000
        connection.readTimeout = 210000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val data = payload.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(data.size)
        return try {
            connection.outputStream.use { it.write(data) }
            response(connection)
        } finally {
            connection.disconnect()
        }
    }

    fun annotations(server: String, token: String, pageId: String): JSONObject {
        val path = "/annotations?page_id=" + URLEncoder.encode(pageId, "UTF-8")
        val connection = connect(server, path, token)
        connection.connectTimeout = 7000
        connection.readTimeout = 10000
        return try { response(connection) } finally { connection.disconnect() }
    }

    /** Check reachability and the device token without uploading a page image. */
    fun probe(server: String, token: String) {
        val health = connect(server, "/health", "")
        health.connectTimeout = 7000
        health.readTimeout = 7000
        try {
            check(response(health).optBoolean("ok")) { "This address is not a MathNote server" }
        } finally {
            health.disconnect()
        }
        val connection = connect(server,
            "/annotations?page_id=00000000-0000-0000-0000-000000000000", token)
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        try {
            when (val status = connection.responseCode) {
                200, 404 -> Unit // A missing probe page still proves authentication succeeded.
                401 -> throw IllegalStateException("Device token was rejected. Scan a fresh QR.")
                else -> throw IllegalStateException("Server returned HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(server: String, path: String, token: String): HttpURLConnection {
        require(server.startsWith("http://") || server.startsWith("https://")) {
            "Server address must start with http:// or https://"
        }
        val url = URL(server.trimEnd('/') + path)
        return (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            setRequestProperty("X-Device-Token", token)
        }
    }

    private fun response(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val response = stream.use { String(it.readBytes(), Charsets.UTF_8) }
        val result = JSONObject(response)
        if (status >= 400) throw IllegalStateException(result.optString("error", "Server HTTP $status"))
        return result
    }

    fun audio(file: File): String {
        require(file.length() <= 5 * 1024 * 1024) { "Recording is too large" }
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }
}
