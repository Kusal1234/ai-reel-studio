package com.aireelstudio.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Parsed story data produced by Gemini. */
final class ReelData {
    static final class Scene {
        int number, seconds;
        String narration = "", visual = "", imagePrompt = "", broll = "", camera = "", transition = "";
    }

    String title = "", hook = "", caption = "", hashtags = "", characterSheet = "", voiceover = "", closing = "";
    final List<Scene> scenes = new ArrayList<>();

    String captionBlock() { return (caption + "\n\n" + hashtags).trim(); }

    static String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).trim();
        }
        return s;
    }

    static ReelData parse(String raw) throws JSONException {
        JSONObject o = new JSONObject(stripFences(raw));
        ReelData d = new ReelData();
        d.title = o.optString("title");
        d.hook = o.optString("hook");
        d.caption = o.optString("caption");
        d.characterSheet = o.optString("character_sheet");
        d.voiceover = o.optString("voiceover");
        d.closing = o.optString("closing_line");
        StringBuilder hash = new StringBuilder();
        JSONArray tags = o.optJSONArray("hashtags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                String h = tags.optString(i, "").trim();
                if (h.isEmpty()) continue;
                if (!h.startsWith("#")) h = "#" + h.replace(" ", "");
                hash.append(h).append(' ');
            }
        }
        d.hashtags = hash.toString().trim();
        JSONArray sc = o.optJSONArray("scenes");
        if (sc != null) {
            for (int i = 0; i < sc.length(); i++) {
                JSONObject j = sc.getJSONObject(i);
                Scene s = new Scene();
                s.number = j.optInt("number", i + 1);
                s.seconds = j.optInt("seconds", 5);
                s.narration = j.optString("narration");
                s.visual = j.optString("visual");
                s.imagePrompt = j.optString("image_prompt");
                s.broll = j.optString("broll");
                s.camera = j.optString("camera");
                s.transition = j.optString("transition");
                d.scenes.add(s);
            }
        }
        return d;
    }

    String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        sb.append("TITLE\n").append(title).append("\n\n");
        sb.append("HOOK\n").append(hook).append("\n\n");
        sb.append("CAPTION\n").append(captionBlock()).append("\n\n");
        sb.append("CHARACTER SHEET\n").append(characterSheet).append("\n\n");
        sb.append("VOICE-OVER\n").append(voiceover).append("\n\n");
        for (Scene s : scenes) {
            sb.append("SCENE ").append(s.number).append("  (").append(s.seconds).append("s)\n");
            sb.append("Narration: ").append(s.narration).append('\n');
            sb.append("Visual: ").append(s.visual).append('\n');
            sb.append("Image prompt: ").append(s.imagePrompt).append('\n');
            sb.append("B-roll: ").append(s.broll).append('\n');
            sb.append("Camera: ").append(s.camera).append('\n');
            sb.append("Transition: ").append(s.transition).append("\n\n");
        }
        sb.append("CLOSING LINE\n").append(closing);
        return sb.toString();
    }
}
