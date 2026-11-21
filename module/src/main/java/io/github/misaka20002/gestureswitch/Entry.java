package io.github.misaka20002.gestureswitch;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Called once from the native Zygisk loader inside system_server (see jni/main.cpp).
 *
 * <p>On the normal boot path this runs on system_server's main thread before
 * SystemServer.main(), when no services exist yet, so it only starts a background
 * thread and returns. Any exception escaping into system_server would crash it, so
 * everything is wrapped in catch-all handlers that only log.
 */
public final class Entry {
    static final String TAG = "GestureSwitch";
    private static final String LOADED_KEY = "gesture_switch.loaded";
    private static final int SERVICE_CHECK_INTERVAL_MS = 500;
    private static final int SERVICE_CHECK_ATTEMPTS = 61;
    // Intent.ACTION_USER_SWITCHED is @hide.
    private static final String ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static final String ACTION_UPDATE_SETTINGS =
            "io.github.misaka20002.gestureswitch.UPDATE_SETTINGS";

    private Entry() {}

    public static void start(String config, boolean late) {
        boolean claimed = false;
        try {
            // A second injection (e.g. NeoZygisk late-load) gets a new class loader, so a static
            // field would not survive; system properties are process-global.
            if (System.getProperties().putIfAbsent(LOADED_KEY, "1") != null) {
                Log.w(TAG, "already running in this process, skipping");
                return;
            }
            claimed = true;
            final Config cfg = Config.parse(config);
            Log.i(TAG, "starting (late=" + late + ") " + cfg);

            HandlerThread thread = new HandlerThread("gesture_switch") {
                @Override
                protected void onLooperPrepared() {
                    boolean registered = false;
                    try {
                        registered = init(cfg, late, new Handler(getLooper()));
                    } catch (Throwable t) {
                        Log.e(TAG, "init failed", t);
                    } finally {
                        if (!registered) {
                            quit();
                            System.clearProperty(LOADED_KEY);
                        }
                    }
                }
            };
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((th, t) -> Log.e(TAG, "uncaught in " + th.getName(), t));
            thread.start();
        } catch (Throwable t) {
            if (claimed) System.clearProperty(LOADED_KEY);
            Log.e(TAG, "start failed", t);
        }
    }

    private static boolean init(Config cfg, boolean late, Handler handler) throws Exception {
        if (!late) waitForBootCompleted();
        Context ctx = waitForServices();
        if (ctx == null) {
            Log.e(TAG, "system services unavailable (late=" + late + ")");
            return false;
        }

        Controller controller = new Controller(cfg, new NavSwitcher(), handler);
        if (!FoldMonitor.register(ctx, handler, cfg, controller)) return false;
        registerUserSwitch(ctx, handler, controller);
        registerSettings(ctx, handler, controller);
        Log.i(TAG, "ready (late=" + late + ")");
        return true;
    }

    private static Context waitForServices() throws Exception {
        // A soft reboot can leave sys.boot_completed set while the new framework starts.
        // Check the services on both the regular startup and late-load paths.
        for (int attempt = 0; attempt < SERVICE_CHECK_ATTEMPTS; ++attempt) {
            if (Reflect.getService("activity") != null && Reflect.getService("overlay") != null
                    && Reflect.getService("input") != null) {
                Context ctx = Reflect.systemContext();
                if (ctx != null) return ctx;
            }
            if (attempt + 1 < SERVICE_CHECK_ATTEMPTS) Thread.sleep(SERVICE_CHECK_INTERVAL_MS);
        }
        return null;
    }

    // On a cold boot, wait until Android has finished starting before changing overlays.
    private static void waitForBootCompleted() throws Exception {
        while (!"1".equals(Reflect.getSystemProperty("sys.boot_completed"))) {
            Thread.sleep(1000);
        }
    }

    private static void registerUserSwitch(Context ctx, Handler handler, Controller controller) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "user switched, re-applying");
                controller.schedule();
            }
        };
        try {
            ctx.registerReceiver(receiver, new IntentFilter(ACTION_USER_SWITCHED), null, handler);
        } catch (Throwable t) {
            Log.w(TAG, "cannot listen for user switches", t);
        }
    }

    private static void registerSettings(Context ctx, Handler handler, Controller controller) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_UPDATE_SETTINGS.equals(intent.getAction()) || !isOrderedBroadcast()) return;
                try {
                    String folded = intent.getStringExtra("folded_mode");
                    String opened = intent.getStringExtra("open_mode");
                    int debounce = intent.getIntExtra("debounce_ms", -1);
                    if (!Config.isMode(folded) || !Config.isMode(opened)
                            || debounce < 0 || debounce > 5000) return;
                    controller.updateSettings(folded, opened, debounce);
                    Log.i(TAG, "settings updated: folded=" + folded + ", open=" + opened
                            + ", debounceMs=" + debounce);
                    setResultCode(Activity.RESULT_OK);
                    setResultData("gesture_switch.settings_updated");
                } catch (Throwable t) {
                    Log.w(TAG, "settings update failed", t);
                }
            }
        };
        try {
            // The root manager can reach this receiver. Ordinary apps cannot send to it.
            ctx.registerReceiver(receiver, new IntentFilter(ACTION_UPDATE_SETTINGS),
                    null, handler, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "cannot listen for settings updates", t);
        }
    }
}
