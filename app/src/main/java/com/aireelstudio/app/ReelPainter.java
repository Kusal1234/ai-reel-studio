package com.aireelstudio.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Draws each video frame: Ken-Burns camera motion, crossfade transitions, title and subtitles. */
final class ReelPainter implements ReelRenderer.Painter {
    private static final double XFADE = 0.4;
    private static final int MODE_IN = 0, MODE_OUT = 1, MODE_PAN_L = 2, MODE_PAN_R = 3;

    private final int W = ReelPipeline.W, H = ReelPipeline.H, FPS = ReelPipeline.FPS;
    private final int n;
    private final File[] images;
    private final File[] clips;
    private final MediaMetadataRetriever[] mmr;
    private final long[] clipDurUs, vKey;
    private final Bitmap[] vFrame;
    private final double[] start, dur, voiceStart, voiceDur;
    private final int[] mode;
    private final boolean[] cut;
    private final String[][] chunks;
    private final String title;
    private final Bitmap[] cache;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, StaticLayout> layouts = new HashMap<>();

    ReelPainter(ReelData reel, File[] images, File[] clips, double[] start, double[] dur, double[] voiceStart, double[] voiceDur) {
        this.n = reel.scenes.size();
        this.images = images;
        this.clips = clips;
        this.mmr = new MediaMetadataRetriever[reel.scenes.size()];
        this.clipDurUs = new long[reel.scenes.size()];
        this.vKey = new long[reel.scenes.size()];
        this.vFrame = new Bitmap[reel.scenes.size()];
        this.start = start; this.dur = dur; this.voiceStart = voiceStart; this.voiceDur = voiceDur;
        this.title = reel.title;
        this.cache = new Bitmap[n];
        this.mode = new int[n];
        this.cut = new boolean[n];
        this.chunks = new String[n][];
        for (int i = 0; i < n; i++) {
            ReelData.Scene s = reel.scenes.get(i);
            String cam = s.camera.toLowerCase();
            if (cam.contains("zoom out") || cam.contains("pull")) mode[i] = MODE_OUT;
            else if (cam.contains("pan left")) mode[i] = MODE_PAN_L;
            else if (cam.contains("pan right") || cam.contains("pan")) mode[i] = MODE_PAN_R;
            else mode[i] = MODE_IN;
            String tr = s.transition.toLowerCase();
            cut[i] = tr.contains("hard cut") || tr.equals("cut") || tr.startsWith("cut");
            chunks[i] = split(s.narration);
        }
        boxPaint.setColor(Color.argb(150, 0, 0, 0));
        subPaint.setColor(Color.WHITE);
        subPaint.setTextSize(50);
        subPaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(58);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private static String[] split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int words = 0;
        for (String w : text.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
            words++;
            boolean punct = w.endsWith(".") || w.endsWith("!") || w.endsWith("?") || w.endsWith(",");
            if (words >= 7 || (words >= 3 && punct)) { out.add(cur.toString()); cur.setLength(0); words = 0; }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    @Override public void paint(Canvas c, int frame) {
        double t = (double) frame / FPS;
        int i = n - 1;
        for (int k = 0; k < n; k++) if (t < start[k] + dur[k]) { i = k; break; }
        double local = t - start[i];
        double p = Math.max(0, Math.min(1, local / dur[i]));

        c.drawColor(Color.BLACK);
        drawScene(c, i, p, local, 255);
        double left = dur[i] - local;
        if (i + 1 < n && !cut[i] && left < XFADE) {
            drawScene(c, i + 1, 0, 0.0, (int) (255 * (1 - left / XFADE)));
        }
        drawSubtitle(c, i, local);
        if (t < 2.6 && title != null && !title.isEmpty()) {
            int a = (int) (255 * Math.min(1, Math.min(t / 0.4, (2.6 - t) / 0.4)));
            drawText(c, title, titlePaint, 140, Math.max(0, a), true);
        }
    }

    private void drawScene(Canvas c, int i, double p, double localT, int alpha) {
        boolean video = clips[i] != null;
        Bitmap b = video ? videoFrame(i, localT) : bitmap(i);
        if (clips[i] == null) video = false; // clip failed to decode: fell back to image
        float bw = b.getWidth(), bh = b.getHeight();
        float base = Math.max(W / bw, H / bh);
        double e = p * p * (3 - 2 * p); // ease in-out
        float z;
        switch (video ? -1 : mode[i]) {
            case MODE_OUT: z = (float) (1.12 - 0.12 * e); break;
            case MODE_PAN_L: case MODE_PAN_R: z = 1.12f; break;
            case -1: z = 1f; break;
            default: z = (float) (1.0 + 0.12 * e);
        }
        float scale = base * z;
        float dx = (W - bw * scale) / 2f;
        float dy = (H - bh * scale) / 2f;
        if (!video && (mode[i] == MODE_PAN_L || mode[i] == MODE_PAN_R)) {
            float slack = Math.max(0, bw * scale - W);
            float off = (float) ((e - 0.5) * slack);
            dx += (mode[i] == MODE_PAN_L) ? -off : off;
        }
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        bmpPaint.setAlpha(alpha);
        c.drawBitmap(b, m, bmpPaint);
    }

    private void drawSubtitle(Canvas c, int i, double local) {
        String[] ch = chunks[i];
        if (ch.length == 0) return;
        double vs = voiceStart[i], vd = voiceDur[i];
        if (local < vs || local > vs + vd + 0.3) return;
        int idx = (int) Math.min(ch.length - 1, Math.max(0, (local - vs) / vd * ch.length));
        drawText(c, ch[idx], subPaint, H - 330, 255, false);
    }

    private void drawText(Canvas c, String text, TextPaint paint, int topY, int alpha, boolean center) {
        StaticLayout l = layouts.get(text);
        if (l == null) {
            l = StaticLayout.Builder.obtain(text, 0, text.length(), paint, W - 120)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            if (layouts.size() > 200) layouts.clear();
            layouts.put(text, l);
        }
        c.save();
        c.translate(60, topY);
        boxPaint.setAlpha(alpha * 150 / 255);
        c.drawRoundRect(new RectF(-24, -14, W - 120 + 24, l.getHeight() + 14), 24, 24, boxPaint);
        if (alpha < 255) c.saveLayerAlpha(0, 0, W, l.getHeight() + 20, alpha);
        l.draw(c);
        if (alpha < 255) c.restore();
        c.restore();
    }

    private Bitmap videoFrame(int i, double localT) {
        try {
            if (mmr[i] == null) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(clips[i].getAbsolutePath());
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                clipDurUs[i] = (d == null ? 0 : Long.parseLong(d)) * 1000L;
                mmr[i] = r;
            }
            long us = (long) (Math.max(0, localT) * 1_000_000d);
            us = Math.min(us, Math.max(0, clipDurUs[i] - 100_000)); // hold last frame if scene is longer than clip
            long key = us / (1_000_000L / FPS);
            if (vFrame[i] != null && vKey[i] == key) return vFrame[i];
            Bitmap f = mmr[i].getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
            if (f == null) {
                if (vFrame[i] != null) return vFrame[i];
                throw new IllegalStateException("no frame");
            }
            if (vFrame[i] != null) vFrame[i].recycle();
            vFrame[i] = f;
            vKey[i] = key;
            return f;
        } catch (Exception e) {
            clips[i] = null; // fall back to the still image + camera motion
            return bitmap(i);
        }
    }

    void release() {
        for (int i = 0; i < n; i++) {
            if (mmr[i] != null) { try { mmr[i].release(); } catch (Exception ignored) { } mmr[i] = null; }
            if (vFrame[i] != null) { vFrame[i].recycle(); vFrame[i] = null; }
        }
    }

    private Bitmap bitmap(int i) {
        for (int j = 0; j < i - 1; j++) {
            if (cache[j] != null) { cache[j].recycle(); cache[j] = null; }
        }
        if (cache[i] == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(images[i].getAbsolutePath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 2048) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(images[i].getAbsolutePath(), o);
            if (b == null) {
                b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.rgb(30, 28, 50));
            }
            cache[i] = b;
        }
        return cache[i];
    }
}
