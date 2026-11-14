package io.github.misaka20002.gestureswitch;

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
    // Intent.ACTION_USER_SWITCHED is @hide.
    private static final String ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED";

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
                        registered = init(cfg, new Handler(getLooper()));
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

    private static boolean init(Config cfg, Handler handler) throws Exception {
        waitForBootCompleted();

        Context ctx = Reflect.systemContext();
        if (ctx == null) {
            Log.e(TAG, "no system context, giving up");
            return false;
        }

        Controller controller = new Controller(cfg, new NavSwitcher(), handler);
        if (!FoldMonitor.register(ctx, handler, cfg, controller)) return false;
        registerUserSwitch(ctx, handler, controller);
        return true;
    }

    // Boot is the only time we poll: once sys.boot_completed=1, SystemUI, the launcher and the
    // overlay service are up and the current user is unlocked.
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
}
