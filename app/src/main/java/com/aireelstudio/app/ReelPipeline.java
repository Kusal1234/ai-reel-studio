package com.aireelstudio.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Runs the whole reel creation: images -> voice -> music -> video -> subtitles -> MP4 export. */
final class ReelPipeline {
    static final int W = 720, H = 1280, FPS = 30;

    static final class Cancelled extends RuntimeException { }

    interface Listener {
        void status(String s);
        void done(Uri uri, String where, String notes);
        void fail(String msg);
    }

    private final Context ctx;
    private final ReelData reel;
    private final String apiKey, imageMode;
    private final int videoMode; // 0 = free camera motion, 1 = AI image->video, 2 = AI text->video
    private final Locale locale;
    private final Listener l;
    private volatile boolean cancelled;

    ReelPipeline(Context ctx, ReelData reel, String apiKey, String imageMode, int videoMode, Locale locale, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.reel = reel; this.apiKey = apiKey; this.imageMode = imageMode; this.videoMode = videoMode; this.locale = locale; this.l = l;
    }

    void cancel() { cancelled = true; }

    void run() {
        File dir = new File(ctx.getCacheDir(), "reel_" + System.currentTimeMillis());
        try {
            dir.mkdirs();
            StringBuilder notes = new StringBuilder();
            List<ReelData.Scene> scenes = reel.scenes;
            int n = scenes.size();
            if (n == 0) throw new IllegalStateException("No scenes. Generate a story first.");

            // 1. Images (+ optional AI video clips)
            File[] imgs = new File[n];
            File[] clips = new File[n];
            ImageFetcher fetcher = new ImageFetcher();
            VideoFetcher vf = new VideoFetcher();
            int placeholders = 0, clipFails = 0;
            for (int i = 0; i < n; i++) {
                check();
                ReelData.Scene s = scenes.get(i);
                String visual = s.imagePrompt.isEmpty() ? s.visual : s.imagePrompt;
                String chars = reel.characterSheet.isEmpty() ? "" : "Characters (keep identical appearance): " + reel.characterSheet + " ";
                String imgPrompt = "Vertical 9:16 cinematic photo-realistic image. " + chars + "Scene: " + visual;
                String vidPrompt = "Vertical 9:16 cinematic video, smooth natural motion. " + chars + "Scene: " + visual
                        + (s.camera.isEmpty() ? "" : " Camera: " + s.camera + ".")
                        + " No text, subtitles or captions in the video.";
                imgs[i] = new File(dir, "scene" + i + ".img");
                File clipFile = new File(dir, "clip" + i + ".mp4");

                boolean haveClip = false;
                if (videoMode == 2) {
                    l.status("🎞 AI video " + (i + 1) + "/" + n + " (විනාඩියක් පමණ යයි) ...");
                    haveClip = vf.fetch(apiKey, null, vidPrompt, clipFile);
                }
                if (videoMode == 2 && haveClip) {
                    ImageFetcher.placeholder(i, s.visual.isEmpty() ? s.narration : s.visual, imgs[i]); // fallback still
                } else {
                    l.status("🖼 Images " + (i + 1) + "/" + n + " ...");
                    if (!fetcher.fetch(imageMode, apiKey, imgPrompt, imgs[i], i)) {
                        ImageFetcher.placeholder(i, s.visual.isEmpty() ? s.narration : s.visual, imgs[i]);
                        placeholders++;
                    }
                    if (videoMode == 1) {
                        check();
                        l.status("🎞 AI video " + (i + 1) + "/" + n + " (විනාඩියක් පමණ යයි) ...");
                        haveClip = vf.fetch(apiKey, imgs[i], vidPrompt, clipFile);
                    }
                }
                if (haveClip) clips[i] = clipFile; else if (videoMode != 0) clipFails++;
            }
            if (placeholders > 0) {
                notes.append(placeholders).append("/").append(n).append(" scenes used placeholder images");
                if (!fetcher.lastError.isEmpty()) notes.append(" (").append(fetcher.lastError).append(")");
                notes.append(". ");
            }
            if (clipFails > 0) {
                notes.append(clipFails).append("/").append(n).append(" scenes used camera-motion instead of AI video (")
                     .append(vf.lastError).append("). ");
            }

            // 2. Voice-over
            check();
            l.status("🎙 Voice-over ...");
            short[][] voice = new short[n][];
            VoiceSynth vs = new VoiceSynth();
            boolean voiceOk = false;
            try {
                voiceOk = vs.init(ctx, locale);
                if (voiceOk) {
                    for (int i = 0; i < n; i++) {
                        check();
                        l.status("🎙 Voice-over " + (i + 1) + "/" + n + " ...");
                        File w = new File(dir, "v" + i + ".wav");
                        if (vs.synth(scenes.get(i).narration, w)) voice[i] = WavUtil.readMono44k(w);
                    }
                } else {
                    notes.append("No voice-over: ").append(vs.lastError)
                         .append(" (Settings > Text-to-speech to install a voice). ");
                }
            } finally { vs.shutdown(); }

            // 3. Timeline
            double[] start = new double[n], dur = new double[n], vStart = new double[n], vDur = new double[n];
            double t = 0;
            for (int i = 0; i < n; i++) {
                start[i] = t;
                vStart[i] = 0.15;
                if (voice[i] != null && voice[i].length > 0) {
                    vDur[i] = voice[i].length / (double) WavUtil.RATE;
                    dur[i] = Math.max(2.5, vDur[i] + 0.15 + 0.5);
                } else {
                    dur[i] = Math.max(3, scenes.get(i).seconds);
                    vDur[i] = Math.max(1, dur[i] - 0.7);
                }
                t += dur[i];
            }
            double total = t;
            int totalFrames = (int) Math.round(total * FPS);

            // 4. Audio mix (voice + generated ambient music)
            check();
            l.status("🎵 Mixing audio ...");
            int totalSamples = (int) (total * WavUtil.RATE) + WavUtil.RATE / 2;
            short[] music = MusicSynth.make(totalSamples, voiceOk ? 0.10 : 0.22);
            int[] mix = new int[totalSamples];
            for (int i = 0; i < totalSamples; i++) mix[i] = music[i];
            for (int i = 0; i < n; i++) {
                if (voice[i] == null) continue;
                int off = (int) ((start[i] + vStart[i]) * WavUtil.RATE);
                for (int k = 0; k < voice[i].length && off + k < totalSamples; k++) mix[off + k] += voice[i][k];
            }
            short[] pcm = new short[totalSamples];
            int fade = WavUtil.RATE * 3 / 2;
            for (int i = 0; i < totalSamples; i++) {
                double g = 1.0;
                int fromEnd = totalSamples - i;
                if (fromEnd < fade) g = (double) fromEnd / fade;
                int v = (int) (mix[i] * g);
                pcm[i] = (short) Math.max(-32768, Math.min(32767, v));
            }

            // 5. Video
            ReelPainter painter = new ReelPainter(reel, imgs, clips, start, dur, vStart, vDur);
            File video = new File(dir, "video.mp4");
            try {
                ReelRenderer.encodeVideo(video, W, H, FPS, totalFrames, painter,
                        p -> l.status("🎬 Rendering video " + p + "% ..."), () -> cancelled);
            } finally { painter.release(); }

            // 6. Audio encode + merge
            check();
            l.status("📦 Finishing MP4 ...");
            File audio = new File(dir, "audio.m4a");
            ReelRenderer.encodeAac(pcm, audio, () -> cancelled);
            File finalFile = new File(dir, "reel.mp4");
            ReelRenderer.merge(video, audio, finalFile);

            // 7. Save to gallery
            String name = "AIReel_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            Object[] saved = save(finalFile, name);
            l.done((Uri) saved[0], (String) saved[1], notes.toString().trim());
        } catch (Cancelled c) {
            l.fail("Cancelled");
        } catch (Throwable e) {
            l.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            deleteRecursive(dir);
        }
    }

    private void check() { if (cancelled) throw new Cancelled(); }

    private Object[] save(File src, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AI Reel Studio");
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("Could not create gallery entry");
            try (OutputStream os = r.openOutputStream(uri); InputStream in = new FileInputStream(src)) {
                copy(in, os);
            }
            cv.clear();
            cv.put(MediaStore.Video.Media.IS_PENDING, 0);
            r.update(uri, cv, null, null);
            return new Object[]{uri, "Movies/AI Reel Studio/" + name};
        } else {
            File dest = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), name);
            try (OutputStream os = new FileOutputStream(dest); InputStream in = new FileInputStream(src)) {
                copy(in, os);
            }
            return new Object[]{null, dest.getAbsolutePath()};
        }
    }

    private static void copy(InputStream in, OutputStream os) throws Exception {
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
