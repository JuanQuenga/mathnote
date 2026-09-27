package com.mathnote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public final class InkCodec {
    private InkCodec() {}
    public static String encode(ArrayList<InkView.Stroke> strokes) {
        JSONArray all = new JSONArray();
        try {
            for (InkView.Stroke s : strokes) {
                JSONObject item = new JSONObject();
                item.put("color", s.color); item.put("size", s.size);
                JSONArray dots = new JSONArray();
                for (InkView.Dot d : s.dots) {
                    JSONArray point = new JSONArray();
                    point.put(d.x); point.put(d.y); point.put(d.pressure); dots.put(point);
                }
                item.put("dots", dots); all.put(item);
            }
        } catch (Exception error) { throw new IllegalStateException(error); }
        return all.toString();
    }
    public static ArrayList<InkView.Stroke> decode(String text) {
        ArrayList<InkView.Stroke> result = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(text);
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.getJSONObject(i);
                InkView.Stroke s = new InkView.Stroke(item.getInt("color"), (float) item.getDouble("size"));
                JSONArray dots = item.getJSONArray("dots");
                for (int j = 0; j < dots.length(); j++) {
                    JSONArray p = dots.getJSONArray(j);
                    s.dots.add(new InkView.Dot((float) p.getDouble(0), (float) p.getDouble(1), (float) p.getDouble(2)));
                }
                result.add(s);
            }
        } catch (Exception ignored) { return new ArrayList<>(); }
        return result;
    }
}
