package io.github.misaka20002.gestureswitch;

import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Switches the navigation mode by enabling one of the AOSP navbar overlays exclusively in its
 * category, exactly like `cmd overlay enable-exclusive --category`. SystemUI then updates
 * Settings.Secure.navigation_mode itself.
 */
final class NavSwitcher {
    private static final String PREFIX = "com.android.internal.systemui.navbar.";
    // Gesture navigation with a custom back sensitivity counts as gestural (Android 13/14).
    private static final String[] GESTURAL_VARIANTS = {
            "gestural", "gestural_wide_back", "gestural_extra_wide_back", "gestural_narrow_back",
    };

    private Object oms;
    private Method getOverlayInfo;
    private Method setEnabledExclusiveInCategory;

    boolean apply(String mode, int user) throws Exception {
        // OverlayManagerService allows SYSTEM_UID callers; make sure that is the uid it sees.
        long token = Binder.clearCallingIdentity();
        try {
            ensureService();
            String target = PREFIX + mode;
            Object info = getOverlayInfo.invoke(oms, target, user);
            if ((info != null && isEnabled(info))
                    || ("gestural".equals(mode) && anyGesturalEnabled(user))) {
                Log.i(Entry.TAG, mode + " already active for user " + user);
                return true;
            }
            if (info == null) {
                Log.w(Entry.TAG, target + " is not installed for user " + user);
                return false;
            }
            if (!isMutable(info)) {
                Log.w(Entry.TAG, target + " is not mutable");
                return false;
            }
            boolean ok = (Boolean) setEnabledExclusiveInCategory.invoke(oms, target, user);
            Log.i(Entry.TAG, "switch to " + mode + " for user " + user + (ok ? ": ok" : ": FAILED"));
            return ok;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void ensureService() throws Exception {
        if (oms != null) return;
        IBinder binder = Reflect.getService("overlay");
        if (binder == null) throw new IllegalStateException("overlay service not found");
        Class<?> stub = Class.forName("android.content.om.IOverlayManager$Stub");
        Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> iface = Class.forName("android.content.om.IOverlayManager");
        getOverlayInfo = iface.getMethod("getOverlayInfo", String.class, int.class);
        setEnabledExclusiveInCategory = iface.getMethod("setEnabledExclusiveInCategory", String.class, int.class);
        oms = service;
    }

    private boolean anyGesturalEnabled(int user) throws Exception {
        for (String variant : GESTURAL_VARIANTS) {
            Object info = getOverlayInfo.invoke(oms, PREFIX + variant, user);
            if (info != null && isEnabled(info)) return true;
        }
        return false;
    }

    private static boolean isEnabled(Object info) throws Exception {
        return (Boolean) info.getClass().getMethod("isEnabled").invoke(info);
    }

    private static boolean isMutable(Object info) {
        try {
            return (Boolean) info.getClass().getMethod("isMutable").invoke(info);
        } catch (Throwable ignored) {
            // older releases expose it as a field
        }
        try {
            return info.getClass().getField("isMutable").getBoolean(info);
        } catch (Throwable ignored) {
            return true; // unknown: let OverlayManagerService decide
        }
    }
}
