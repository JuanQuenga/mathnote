package com.mathnote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;

public final class TutorClient {
    private TutorClient() {}
    public static String image(ArrayList<InkView.Stroke> strokes) {
        Bitmap bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        for (InkView.Stroke stroke : strokes) InkView.drawStroke(canvas, stroke, 1600, 1000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        bitmap.recycle();
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }
    public static JSONObject post(String server, String token, String path, JSONObject payload) throws Exception {
        if (!server.startsWith("http://") && !server.startsWith("https://"))
            throw new IllegalArgumentException("Server address must start with http:// or https://");
        URL url = new URL(server.replaceAll("/+$", "") + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(7000); connection.setReadTimeout(90000);
        connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Device-Token", token);
        byte[] data = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(data); }
        int status = connection.getResponseCode();
        InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String response;
        try (InputStream input = stream) {
            response = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
        JSONObject object = new JSONObject(response);
        if (status >= 400) throw new IllegalStateException(object.optString("error", "Server HTTP " + status));
        return object;
    }
    public static JSONObject annotations(String server, String token, String pageId) throws Exception {
        URL url = new URL(server.replaceAll("/+$", "") + "/annotations?page_id=" +
                URLEncoder.encode(pageId, "UTF-8"));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(7000); connection.setReadTimeout(10000);
        connection.setRequestProperty("X-Device-Token", token);
        int status = connection.getResponseCode();
        try (InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            JSONObject result = new JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (status >= 400) throw new IllegalStateException(result.optString("error", "HTTP " + status));
            return result;
        } finally { connection.disconnect(); }
    }
    public static String audio(File file) throws Exception {
        if (file.length() > 5 * 1024 * 1024) throw new IllegalArgumentException("Recording is too large");
        try (FileInputStream input = new FileInputStream(file)) {
            return Base64.encodeToString(input.readAllBytes(), Base64.NO_WRAP);
        }
    }
}
