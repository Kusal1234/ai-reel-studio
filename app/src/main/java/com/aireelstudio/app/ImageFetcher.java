package com.aireelstudio.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Gets one image per scene: Gemini image model -> Pollinations (free) -> generated placeholder. */
final class ImageFetcher {
    static final String MODE_AUTO = "Auto (Gemini → Pollinations → Placeholder)";
    static final String MODE_GEMINI = "Gemini only";
    static final String MODE_POLL = "Pollinations only (free, no key)";
    static final String MODE_PLACEHOLDER = "Placeholder only (no AI images)";
    static final String GEMINI_IMAGE_MODEL = "gemini-2.5-flash-image";

    String lastError = "";

    /** Returns true when an AI image was saved to out. */
    boolean fetch(String mode, String key, String prompt, File out, int seed) {
        if (mode.equals(MODE_PLACEHOLDER)) return false;
        boolean tryGemini = (mode.equals(MODE_AUTO) || mode.equals(MODE_GEMINI)) && key != null && !key.isEmpty();
        boolean tryPoll = mode.equals(MODE_AUTO) || mode.equals(MODE_POLL);
        if (tryGemini) {
            try {
                byte[] img = gemini(key, prompt);
                if (valid(img)) { write(out, img); return true; }
                lastError = "Gemini returned no image";
            } catch (Exception e) { lastError = "Gemini: " + e.getMessage(); }
        }
        if (tryPoll) {
            try {
                byte[] img = pollinations(prompt, seed);
                if (valid(img)) { write(out, img); return true; }
                lastError = "Pollinations returned invalid image";
            } catch (Exception e) { lastError = "Pollinations: " + e.getMessage(); }
        }
        return false;
    }

    private static boolean valid(byte[] b) {
        if (b == null || b.length < 1000) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    private static void write(File f, byte[] b) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) { os.write(b); }
    }

    private byte[] gemini(String key, String prompt) throws Exception {
        try {
            return geminiCall(key, prompt, true, false);
        } catch (RetryableException e) {
            Thread.sleep(10000);
            return geminiCall(key, prompt, true, false);
        } catch (BadRequestException e) {
            return geminiCall(key, prompt, false, false);
        }
    }

    private static class RetryableException extends Exception { RetryableException(String m) { super(m); } }
    private static class BadRequestException extends Exception { BadRequestException(String m) { super(m); } }

    private byte[] geminiCall(String key, String prompt, boolean aspect, boolean unused) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + GEMINI_IMAGE_MODEL + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(25000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("x-goog-api-key", key);
        JSONObject part = new JSONObject(); part.put("text", prompt);
        JSONObject content = new JSONObject(); content.put("parts", new JSONArray().put(part));
        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(content));
        if (aspect) {
            JSONObject ic = new JSONObject(); ic.put("aspectRatio", "9:16");
            JSONObject gc = new JSONObject(); gc.put("imageConfig", ic);
            body.put("generationConfig", gc);
        }
        try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = readAll(is);
        c.disconnect();
        if (code == 429) throw new RetryableException("rate limit (HTTP 429)");
        if (code == 400 && aspect) throw new BadRequestException("HTTP 400");
        if (code < 200 || code >= 300) {
            String msg = "HTTP " + code;
            try {
                JSONObject err = new JSONObject(text).optJSONObject("error");
                if (err != null) msg = err.optString("message", msg);
            } catch (Exception ignored) { }
            throw new Exception(msg);
        }
        JSONArray cands = new JSONObject(text).optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return null;
        JSONObject cont = cands.getJSONObject(0).optJSONObject("content");
        JSONArray parts = cont == null ? null : cont.optJSONArray("parts");
        if (parts == null) return null;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            JSONObject inl = p.optJSONObject("inlineData");
            if (inl == null) inl = p.optJSONObject("inline_data");
            if (inl != null) {
                String data = inl.optString("data", "");
                if (!data.isEmpty()) return Base64.decode(data, Base64.DEFAULT);
            }
        }
        return null;
    }

    private byte[] pollinations(String prompt, int seed) throws Exception {
        String p = prompt.length() > 700 ? prompt.substring(0, 700) : prompt;
        String enc = URLEncoder.encode(p, "UTF-8").replace("+", "%20");
        URL url = new URL("https://image.pollinations.ai/prompt/" + enc
                + "?width=720&height=1280&nologo=true&seed=" + (seed + 1));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(25000);
        c.setReadTimeout(120000);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("HTTP " + code); }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        c.disconnect();
        return bos.toByteArray();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    /** Draws a simple gradient card with the scene text so the pipeline still completes without AI images. */
    static void placeholder(int index, String text, File out) throws Exception {
        int w = ReelPipeline.W, h = ReelPipeline.H;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float hue = (index * 47) % 360;
        int c1 = Color.HSVToColor(new float[]{hue, 0.55f, 0.45f});
        int c2 = Color.HSVToColor(new float[]{(hue + 40) % 360, 0.65f, 0.18f});
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w, h, c1, c2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.argb(230, 255, 255, 255));
        tp.setTextSize(46);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        String t = text.length() > 260 ? text.substring(0, 260) + "..." : text;
        StaticLayout l = StaticLayout.Builder.obtain(t, 0, t.length(), tp, w - 120)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
        c.save();
        c.translate(60, (h - l.getHeight()) / 2f);
        l.draw(c);
        c.restore();
        try (OutputStream os = new FileOutputStream(out)) { bmp.compress(Bitmap.CompressFormat.JPEG, 90, os); }
        bmp.recycle();
    }
}
