package io.github.misaka20002.gestureswitch;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Combines the fold signals and applies the navigation mode, debounced.
 * All methods run on the module's handler thread.
 */
final class Controller {
    private final Config cfg;
    private final NavSwitcher nav;
    private final Handler handler;
    private final Runnable apply = safe("apply", () -> {
        pendingSince = -1;
        apply();
    });
    private long pendingSince = -1;

    /** Folded according to DeviceStateManager, or null if unknown. Preferred when known. */
    private Boolean deviceStateFolded;
    /** Folded according to the hardware lid switch, or null if unknown. */
    private Boolean lidFolded;
    /** Fold state last applied per user id. */
    private final Map<Integer, Boolean> lastApplied = new HashMap<>();

    Controller(Config cfg, NavSwitcher nav, Handler handler) {
        this.cfg = cfg;
        this.nav = nav;
        this.handler = handler;
    }

    void onDeviceState(int id, Boolean folded, String how) {
        Log.i(Entry.TAG, "device state " + id + " -> " + describe(folded) + " (" + how + ")");
        deviceStateFolded = folded;
        schedule();
    }

    void onLid(boolean open) {
        Log.i(Entry.TAG, "lid " + (open ? "open" : "closed"));
        lidFolded = !open;
        schedule();
    }

    /** Re-evaluate after the state has been stable for debounceMs (fold animations flap). */
    void schedule() {
        pendingSince = SystemClock.uptimeMillis();
        reschedule();
    }

    private void reschedule() {
        handler.removeCallbacks(apply);
        handler.postAtTime(apply, pendingSince + cfg.debounceMs);
    }

    void updateSettings(String foldedMode, String openMode, int debounceMs) {
        boolean foldedChanged = !cfg.foldedMode.equals(foldedMode);
        boolean openChanged = !cfg.openMode.equals(openMode);
        boolean debounceChanged = cfg.debounceMs != debounceMs;
        cfg.foldedMode = foldedMode;
        cfg.openMode = openMode;
        cfg.debounceMs = debounceMs;

        if (foldedChanged || openChanged) {
            lastApplied.values().removeIf(folded -> folded ? foldedChanged : openChanged);
        }
        if (pendingSince >= 0) {
            if (debounceChanged) reschedule();
        } else {
            Boolean folded = currentFolded();
            if (folded != null && (folded ? foldedChanged : openChanged)) {
                handler.removeCallbacks(apply);
                handler.post(apply);
            }
        }
    }

    private Boolean currentFolded() {
        return deviceStateFolded != null ? deviceStateFolded : lidFolded;
    }

    private void apply() throws Exception {
        Boolean folded = currentFolded();
        if (folded == null) {
            Log.w(Entry.TAG, "fold state unknown; set folded_states in config.prop");
            return;
        }
        int user = Reflect.currentUser();
        // Only act on real folded/open transitions (per user). Other device-state changes,
        // e.g. half-open, must not override a mode the user picked by hand in Settings.
        if (folded.equals(lastApplied.get(user))) return;
        if (nav.apply(folded ? cfg.foldedMode : cfg.openMode, user)) {
            lastApplied.put(user, folded);
        }
    }

    private static String describe(Boolean folded) {
        return folded == null ? "unknown" : folded ? "folded" : "open";
    }

    /** Wraps a task so that nothing can throw into the handler thread (= system_server). */
    static Runnable safe(String name, ThrowingRunnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.e(Entry.TAG, name + " failed", t);
            }
        };
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
