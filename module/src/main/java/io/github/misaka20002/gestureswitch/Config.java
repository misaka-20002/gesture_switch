package io.github.misaka20002.gestureswitch;

import android.util.Log;

import java.util.Arrays;

/** Settings from config.prop in the module directory (read by the native loader at boot). */
final class Config {
    static final String[] MODES = {"gestural", "threebutton", "twobutton"};

    String foldedMode = "threebutton";
    String openMode = "gestural";
    /** Device state ids treated as folded, or null to auto-detect. */
    int[] foldedStates = null;
    long debounceMs = 400;

    static Config parse(String text) {
        Config cfg = new Config();
        if (text == null) return cfg;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            try {
                switch (key) {
                    case "folded_mode":
                        cfg.foldedMode = modeOrDefault(value, cfg.foldedMode);
                        break;
                    case "open_mode":
                        cfg.openMode = modeOrDefault(value, cfg.openMode);
                        break;
                    case "folded_states":
                        cfg.foldedStates = value.isEmpty() ? null : ids(value);
                        break;
                    case "debounce_ms":
                        cfg.debounceMs = Math.max(0, Math.min(5000, Long.parseLong(value)));
                        break;
                    default:
                        Log.w(Entry.TAG, "unknown config key: " + key);
                }
            } catch (RuntimeException e) {
                Log.w(Entry.TAG, "bad config line: " + line);
            }
        }
        return cfg;
    }

    static boolean isMode(String value) {
        for (String m : MODES) {
            if (m.equals(value)) return true;
        }
        return false;
    }

    private static String modeOrDefault(String value, String fallback) {
        if (isMode(value)) return value;
        Log.w(Entry.TAG, "unknown mode '" + value + "', keeping " + fallback);
        return fallback;
    }

    private static int[] ids(String value) {
        String[] parts = value.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    @Override
    public String toString() {
        return "Config{folded=" + foldedMode + ", open=" + openMode
                + ", foldedStates=" + (foldedStates == null ? "auto" : Arrays.toString(foldedStates))
                + ", debounceMs=" + debounceMs + "}";
    }
}
