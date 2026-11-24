package com.aireelstudio.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.view.WindowManager;
import java.util.Locale;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AI Reel Studio v1.0 - story, images, voice-over, subtitles and 9:16 MP4 export. */
public class MainActivity extends Activity {
    private static final String VM_FREE = "Camera motion on images (free)";
    private static final String VM_IMG2VID = "AI Image → Video (Gemini Omni, paid)";
    private static final String VM_TXT2VID = "AI Text → Video (Gemini Omni, paid)";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final String FALLBACK_MODEL = "gemini-flash-latest";

    private final int bg = Color.rgb(18, 18, 32);
    private final int accent = Color.rgb(146, 119, 255);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText topic, apiKey, modelName, extra, result;
    private Spinner language, duration, style;
    private Button generate;
    private TextView status;
    private ProgressBar progress;

    // Stage data kept for later versions (images / voice / video use these).
    private String lastCaptionBlock = "";
    private String lastRawJson = "";
    private volatile ReelData lastReel;
    private Spinner imageSource, videoMode;
    private Button createReel, cancelReel, openReel;
    private TextView reelStatus;
    private ReelPipeline pipeline;
    private Uri lastVideoUri;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(page);

        // Android 15+ draws edge-to-edge: keep content clear of the system bars.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("AI REEL STUDIO");
        title.setTextColor(Color.WHITE); title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Version 1.0  •  Full Reel Creator");
        subtitle.setTextColor(Color.LTGRAY); subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(18)); page.addView(subtitle);

        header(page, "01  REEL TOPIC");
        topic = edit("උදා: He Bought His Mother a House, But Forgot to Visit Her", 2);
        page.addView(topic, match());

        header(page, "02  OUTPUT LANGUAGE");
        language = spinner(new String[]{"English", "French", "Portuguese", "Spanish", "German", "Sinhala"});
        page.addView(language, match());

        header(page, "03  REEL LENGTH");
        duration = spinner(new String[]{"30 seconds", "45 seconds", "60 seconds", "90 seconds"});
        page.addView(duration, match());

        header(page, "04  STORY STYLE");
        style = spinner(new String[]{"Emotional story", "Motivational", "Educational", "Inspirational", "Documentary"});
        page.addView(style, match());

        header(page, "05  GEMINI API KEY");
        apiKey = edit("Paste your Google AI Studio API key here", 1);
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(apiKey, match());

        TextView keyNote = new TextView(this);
        keyNote.setText("API key එක App එක තුළ save නොවේ. Request එකට පමණක් භාවිත වේ. Key එක වෙනත් අයට share කරන්න එපා.");
        keyNote.setTextColor(Color.LTGRAY); keyNote.setTextSize(12);
        keyNote.setPadding(0, dp(6), 0, 0); page.addView(keyNote);

        header(page, "06  AI MODEL");
        modelName = edit("Model name", 1);
        modelName.setSingleLine(true);
        modelName.setText(DEFAULT_MODEL);
        page.addView(modelName, match());

        header(page, "07  OPTIONAL DETAILS");
        extra = edit("කතාවේ ඇතුළත් විය යුතු විශේෂ කරුණි (අවශ්ය නම්)", 2);
        page.addView(extra, match());

        generate = new Button(this);
        generate.setText("✨ GENERATE STORY + SCENES");
        generate.setAllCaps(false);
        generate.setTextColor(Color.WHITE);
        generate.setBackgroundTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams buttonLp = match();
        buttonLp.topMargin = dp(18); page.addView(generate, buttonLp);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY); status.setTextSize(13);
        status.setPadding(0, dp(12), 0, dp(8)); page.addView(status);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        progress.setVisibility(View.GONE);
        page.addView(progress, new LinearLayout.LayoutParams(dp(36), dp(36)));

        header(page, "08  GENERATED SCRIPT");
        result = edit("Generated title, caption, hashtags, story and scene prompts will appear here...", 12);
        result.setGravity(Gravity.TOP);
        result.setKeyListener(null); // read-only; text stays selectable/copyable
        result.setTextIsSelectable(true);
        page.addView(result, match());

        Button copy = new Button(this);
        copy.setText("COPY FULL SCRIPT"); copy.setAllCaps(false);
        copy.setOnClickListener(v -> copyText("AI Reel Script", result.getText().toString()));
        page.addView(copy, match());

        Button copyCaption = new Button(this);
        copyCaption.setText("COPY CAPTION + HASHTAGS"); copyCaption.setAllCaps(false);
        copyCaption.setOnClickListener(v -> {
            if (lastCaptionBlock.isEmpty()) {
                Toast.makeText(this, "Generate first", Toast.LENGTH_SHORT).show();
            } else {
                copyText("Caption", lastCaptionBlock);
            }
        });
        page.addView(copyCaption, match());

        header(page, "09  CREATE REEL (MP4)");
        imageSource = spinner(new String[]{ImageFetcher.MODE_AUTO, ImageFetcher.MODE_GEMINI,
                ImageFetcher.MODE_POLL, ImageFetcher.MODE_PLACEHOLDER});
        page.addView(imageSource, match());

        header(page, "10  VIDEO MODE");
        videoMode = spinner(new String[]{VM_FREE, VM_IMG2VID, VM_TXT2VID});
        page.addView(videoMode, match());
        TextView vidNote = new TextView(this);
        vidNote.setText("AI video = Gemini Omni Flash (Google ගෙවන සේවාවක්, නොමිලේ tier නැත; ≈ $0.10 / තත්පරයට). Billing enabled API key එකක් අවශ්‍යයි. Fail වුණොත් scene එක නොමිලේ camera-motion වලට මාරු වේ.");
        vidNote.setTextColor(Color.LTGRAY); vidNote.setTextSize(12);
        vidNote.setPadding(0, dp(6), 0, 0); page.addView(vidNote);

        TextView imgNote = new TextView(this);
        imgNote.setText("Images: Gemini image model (free-tier limits ඇත) හෝ Pollinations.ai (නොමිලේ, key අවශ්‍ය නැත). Voice: phone එකේ built-in TTS. Reel එක හදන ගමන් screen එක on තියලා app එක open තියන්න.");
        imgNote.setTextColor(Color.LTGRAY); imgNote.setTextSize(12);
        imgNote.setPadding(0, dp(6), 0, 0); page.addView(imgNote);

        createReel = new Button(this);
        createReel.setText("\uD83C\uDFAC CREATE REEL (MP4)");
        createReel.setAllCaps(false);
        createReel.setTextColor(Color.WHITE);
        createReel.setBackgroundTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams crLp = match();
        crLp.topMargin = dp(12); page.addView(createReel, crLp);

        cancelReel = new Button(this);
        cancelReel.setText("CANCEL"); cancelReel.setAllCaps(false);
        cancelReel.setEnabled(false);
        page.addView(cancelReel, match());

        reelStatus = new TextView(this);
        reelStatus.setTextColor(Color.LTGRAY); reelStatus.setTextSize(13);
        reelStatus.setPadding(0, dp(10), 0, dp(6)); page.addView(reelStatus);

        openReel = new Button(this);
        openReel.setText("\u25B6 OPEN / PLAY REEL"); openReel.setAllCaps(false);
        openReel.setEnabled(false);
        page.addView(openReel, match());

        createReel.setOnClickListener(v -> startReel());
        cancelReel.setOnClickListener(v -> { if (pipeline != null) { pipeline.cancel(); reelStatus.setText("Cancelling..."); } });
        openReel.setOnClickListener(v -> {
            if (lastVideoUri == null) return;
            try {
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(lastVideoUri, "video/mp4");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(it);
            } catch (Exception e) {
                Toast.makeText(this, "No video player found. Open it from your Gallery.", Toast.LENGTH_LONG).show();
            }
        });

        TextView next = new TextView(this);
        next.setText("Pipeline: Script → AI Images → Voice-over → Camera motion + Subtitles + Music → 9:16 MP4 (720x1280).");
        next.setTextColor(Color.LTGRAY); next.setTextSize(12);
        next.setPadding(0, dp(16), 0, 0); page.addView(next);

        generate.setOnClickListener(v -> generateStory());
        setContentView(scroll);
    }

    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void generateStory() {
        final String t = topic.getText().toString().trim();
        final String key = apiKey.getText().toString().trim();
        String m = modelName.getText().toString().trim();
        final String model = m.isEmpty() ? DEFAULT_MODEL : m;
        if (t.isEmpty()) { topic.setError("Topic එකක් ඇතුළත් කරන්න"); return; }
        if (key.isEmpty()) { apiKey.setError("Gemini API key එක ඇතුළත් කරන්න"); return; }

        final String lang = language.getSelectedItem().toString();
        final String len = duration.getSelectedItem().toString();
        final String st = style.getSelectedItem().toString();
        final String details = extra.getText().toString().trim();

        String prompt = "You are a professional short-form vertical video scriptwriter. "
                + "Create a compelling " + st + " reel based on this topic: " + t + ". "
                + "Write all narration, the title, the caption and the hashtags in " + lang + ". "
                + "Target duration: " + len + ". "
                + (details.isEmpty() ? "" : "Additional details: " + details + ". ")
                + "Keep the story a creative fiction; do not invent factual claims. "
                + "Return ONLY valid JSON (no markdown) with exactly this structure: "
                + "{\"title\":string,\"hook\":string,\"voiceover\":string,"
                + "\"caption\":string,\"hashtags\":[string,...8 to 12 items],"
                + "\"character_sheet\":string (detailed fixed appearance of every recurring character, in English),"
                + "\"scenes\":[{\"number\":int,\"seconds\":int,\"narration\":string,\"visual\":string,"
                + "\"image_prompt\":string (English, 9:16 vertical, must restate each character's fixed appearance),"
                + "\"broll\":string,\"camera\":string,\"transition\":string}],"
                + "\"closing_line\":string}. "
                + "Use 6 to 10 scenes whose seconds add up to the target duration.";

        generate.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Story එක නිර්මාණය කරමින්... Internet සම්බන්ධතාව පරීක්ෂා කරන්න.");
        executor.execute(() -> {
            try {
                String raw;
                try {
                    raw = callGemini(key, model, prompt);
                } catch (ModelNotFoundException e) {
                    runOnUiThread(() -> status.setText("Model '" + model + "' නැහැ. " + FALLBACK_MODEL + " උත්සාහ කරමින්..."));
                    raw = callGemini(key, FALLBACK_MODEL, prompt);
                }
                final String[] shown = render(raw);
                lastRawJson = raw;
                lastCaptionBlock = shown[1];
                try { lastReel = ReelData.parse(raw); } catch (Exception pe) { lastReel = null; }
                runOnUiThread(() -> {
                    result.setText(shown[0]);
                    status.setText("සාර්ථකයි! Script, caption සහ Scene prompts සූදානම්.");
                    generate.setEnabled(true);
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                final String msg = String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    status.setText("දෝෂයක්: " + msg);
                    generate.setEnabled(true);
                    progress.setVisibility(View.GONE);
                });
            }
        });
    }

    /** Turns the model's JSON into readable text. Returns {fullText, captionBlock}. Falls back to raw text. */
    private String[] render(String raw) {
        try {
            ReelData d = ReelData.parse(raw);
            return new String[]{d.toDisplayText(), d.captionBlock()};
        } catch (Exception e) {
            return new String[]{raw, ""};
        }
    }

    private Locale localeFor(String lang) {
        switch (lang) {
            case "French": return Locale.FRANCE;
            case "Portuguese": return new Locale("pt", "BR");
            case "Spanish": return new Locale("es", "ES");
            case "German": return Locale.GERMANY;
            case "Sinhala": return new Locale("si", "LK");
            default: return Locale.US;
        }
    }

    private void startReel() {
        final ReelData d = lastReel;
        if (d == null || d.scenes.isEmpty()) {
            Toast.makeText(this, "පළමුව Story + Scenes generate කරන්න", Toast.LENGTH_LONG).show();
            return;
        }
        final String key = apiKey.getText().toString().trim();
        final String mode = imageSource.getSelectedItem().toString();
        if (key.isEmpty() && mode.equals(ImageFetcher.MODE_GEMINI)) { apiKey.setError("Gemini API key අවශ්‍යයි"); return; }
        final Locale loc = localeFor(language.getSelectedItem().toString());
        final String vm = videoMode.getSelectedItem().toString();
        final int vmode = vm.equals(VM_IMG2VID) ? 1 : vm.equals(VM_TXT2VID) ? 2 : 0;
        if (vmode != 0 && key.isEmpty()) { apiKey.setError("AI video සඳහා Gemini API key අවශ්‍යයි"); return; }
        if (vmode != 0) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("AI Video – ගෙවන සේවාවක්")
                .setMessage("Gemini Omni video generation නොමිලේ නොවේ (≈ $0.10 / තත්පරයක්, scenes "
                        + d.scenes.size() + "ක්). ඔබේ Google API account එකට බිල් වේ. දිගටම යන්නද?")
                .setPositiveButton("Continue", (dlg, w) -> launchReel(d, key, mode, vmode, loc))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        launchReel(d, key, mode, vmode, loc);
    }

    private void launchReel(final ReelData d, final String key, final String mode, final int vmode, final Locale loc) {
        createReel.setEnabled(false); generate.setEnabled(false); openReel.setEnabled(false);
        cancelReel.setEnabled(true);
        progress.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        reelStatus.setText("Starting...");

        pipeline = new ReelPipeline(this, d, key, mode, vmode, loc, new ReelPipeline.Listener() {
            @Override public void status(String s) { runOnUiThread(() -> reelStatus.setText(s)); }
            @Override public void done(Uri uri, String where, String notes) {
                runOnUiThread(() -> {
                    lastVideoUri = uri;
                    reelStatus.setText("✅ Reel සූදානම්! " + where + (notes.isEmpty() ? "" : "\n⚠ " + notes));
                    reelFinished(uri != null);
                });
            }
            @Override public void fail(String msg) {
                runOnUiThread(() -> {
                    reelStatus.setText("දෝෂයක්: " + msg);
                    reelFinished(false);
                });
            }
        });
        final ReelPipeline pl = pipeline;
        executor.execute(pl::run);
    }

    private void reelFinished(boolean canOpen) {
        createReel.setEnabled(true); generate.setEnabled(true);
        cancelReel.setEnabled(false);
        openReel.setEnabled(canOpen);
        progress.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static class ModelNotFoundException extends Exception {
        ModelNotFoundException(String m) { super(m); }
    }

    private String callGemini(String key, String model, String prompt) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(25000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("x-goog-api-key", key);

        JSONObject part = new JSONObject(); part.put("text", prompt);
        JSONArray parts = new JSONArray(); parts.put(part);
        JSONObject content = new JSONObject(); content.put("parts", parts);
        JSONArray contents = new JSONArray(); contents.put(content);
        JSONObject gen = new JSONObject(); gen.put("responseMimeType", "application/json");
        JSONObject body = new JSONObject();
        body.put("contents", contents);
        body.put("generationConfig", gen);

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = "";
        if (is != null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            text = sb.toString();
        }
        c.disconnect();

        if (code < 200 || code >= 300) {
            String msg = "HTTP " + code;
            try {
                JSONObject err = new JSONObject(text).optJSONObject("error");
                if (err != null) msg = err.optString("message", msg);
            } catch (Exception ignored) { }
            if (code == 404) throw new ModelNotFoundException(msg);
            throw new Exception(msg);
        }

        JSONObject data = new JSONObject(text);
        JSONArray candidates = data.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new Exception("AI response එක හිස් (blocked වී ඇති).");
        }
        JSONObject cont = candidates.getJSONObject(0).optJSONObject("content");
        JSONArray outParts = cont == null ? null : cont.optJSONArray("parts");
        StringBuilder out = new StringBuilder();
        if (outParts != null) {
            for (int i = 0; i < outParts.length(); i++) out.append(outParts.getJSONObject(i).optString("text", ""));
        }
        if (out.length() == 0) throw new Exception("AI response එක හිස්.");
        return out.toString();
    }

    private void header(LinearLayout p, String s) {
        TextView h = new TextView(this); h.setText(s); h.setTextColor(accent);
        h.setTextSize(13); h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        h.setPadding(0, dp(18), 0, dp(7)); p.addView(h);
    }

    private EditText edit(String hint, int lines) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY); e.setTextSize(14); e.setMinLines(lines);
        e.setPadding(dp(12), dp(9), dp(12), dp(9));
        e.setBackgroundTintList(ColorStateList.valueOf(accent));
        return e;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items));
        return s;
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
