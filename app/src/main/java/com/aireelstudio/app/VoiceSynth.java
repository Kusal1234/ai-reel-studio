package com.aireelstudio.app;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Offline voice-over using the phone's built-in Text-To-Speech engine. */
final class VoiceSynth {
    private TextToSpeech tts;
    private volatile CountDownLatch utteranceLatch;
    private volatile boolean utteranceFailed;
    String lastError = "";

    boolean init(Context ctx, Locale locale) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
            ok[0] = (status == TextToSpeech.SUCCESS);
            latch.countDown();
        });
        if (!latch.await(15, TimeUnit.SECONDS) || !ok[0]) { lastError = "No TTS engine"; return false; }
        int r = tts.setLanguage(locale);
        if (r < TextToSpeech.LANG_AVAILABLE) { lastError = "Voice for " + locale.getDisplayLanguage() + " not installed"; return false; }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { CountDownLatch l = utteranceLatch; if (l != null) l.countDown(); }
            @Override public void onError(String id) { utteranceFailed = true; CountDownLatch l = utteranceLatch; if (l != null) l.countDown(); }
        });
        return true;
    }

    boolean synth(String text, File out) throws InterruptedException {
        if (text == null || text.trim().isEmpty()) return false;
        utteranceFailed = false;
        utteranceLatch = new CountDownLatch(1);
        int r = tts.synthesizeToFile(text, new Bundle(), out, "u" + System.nanoTime());
        if (r != TextToSpeech.SUCCESS) { lastError = "synthesizeToFile failed"; return false; }
        boolean done = utteranceLatch.await(90, TimeUnit.SECONDS);
        if (!done || utteranceFailed || !out.exists() || out.length() < 100) { lastError = "TTS synthesis failed"; return false; }
        return true;
    }

    void shutdown() { if (tts != null) { tts.stop(); tts.shutdown(); } }
}
