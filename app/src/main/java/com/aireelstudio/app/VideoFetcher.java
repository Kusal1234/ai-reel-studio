package com.aireelstudio.app;

import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * AI video clips via Gemini Omni Flash (Interactions API). PAID: no free tier (about $0.10 per second at 720p).
 * Image -> Video when an image file is given, Text -> Video when image is null.
 */
final class VideoFetcher {
    static final String MODEL = "gemini-omni-1.1-flash";
    String lastError = "";

    boolean fetch(String key, File image, String prompt, File out) {
        try {
            JSONArray input = new JSONArray();
            if (image != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(image.getAbsolutePath(), o);
                String mime = o.outMimeType != null ? o.outMimeType : "image/jpeg";
                input.put(new JSONObject().put("type", "image")
                        .put("data", Base64.encodeToString(readFile(image), Base64.NO_WRAP))
                        .put("mime_type", mime));
            }
            input.put(new JSONObject().put("type", "text").put("text", prompt));
            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            body.put("input", input);
            body.put("response_format", new JSONObject()
                    .put("type", "video").put("aspect_ratio", "9:16").put("resolution", "720p"));

            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/interactions");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(30000);
            c.setReadTimeout(420000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("x-goog-api-key", key);
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            byte[] resp = is == null ? new byte[0] : readStream(is);
            c.disconnect();
            String text = new String(resp, StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                String msg = "HTTP " + code;
                try {
                    JSONObject err = new JSONObject(text).optJSONObject("error");
                    if (err != null) msg = err.optString("message", msg);
                } catch (Exception ignored) { }
                lastError = "Video: " + msg;
                return false;
            }
            String b64 = findVideoData(new JSONObject(text));
            if (b64 == null) { lastError = "Video: response had no video data"; return false; }
            byte[] mp4 = Base64.decode(b64, Base64.DEFAULT);
            try (OutputStream os = new FileOutputStream(out)) { os.write(mp4); }
            return out.length() > 1000;
        } catch (Exception e) {
            lastError = "Video: " + e.getMessage();
            return false;
        }
    }

    /** Finds base64 data of any object that looks like {mime_type: "video/...", data: "..."} anywhere in the JSON. */
    private static String findVideoData(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String mime = o.optString("mime_type", o.optString("mimeType", ""));
            if (mime.startsWith("video/")) {
                String d = o.optString("data", "");
                if (!d.isEmpty()) return d;
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                Object v = o.opt(it.next());
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    String r = findVideoData(v);
                    if (r != null) return r;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findVideoData(a.opt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return readStream(in); }
    }

    private static byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
