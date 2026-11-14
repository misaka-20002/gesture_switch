package io.github.misaka20002.gestureswitch;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

/** Hidden framework APIs, accessed reflectively so one dex works on Android 13 to 16. */
final class Reflect {
    private Reflect() {}

    static String getSystemProperty(String key) throws Exception {
        return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class).invoke(null, key, "");
    }

    static Context systemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            Log.w(Entry.TAG, "cannot get system context", t);
            return null;
        }
    }

    static IBinder getService(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    static int currentUser() {
        try {
            return (Integer) Class.forName("android.app.ActivityManager")
                    .getMethod("getCurrentUser").invoke(null);
        } catch (Throwable t) {
            Log.w(Entry.TAG, "getCurrentUser failed, assuming user 0", t);
            return 0;
        }
    }
}
