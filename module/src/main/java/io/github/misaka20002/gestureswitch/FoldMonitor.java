package io.github.misaka20002.gestureswitch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.util.Log;
import android.view.InputDevice;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

/**
 * Event-driven fold detection. Two independent signals:
 *
 * <ol>
 *   <li>DeviceStateManager callback (primary). The callback interface changed in Android 15
 *       (onStateChanged(int) became onDeviceStateChanged(DeviceState)), so it is implemented
 *       with a reflective Proxy that handles both.</li>
 *   <li>The hardware lid switch via InputManagerInternal (fallback, e.g. stock Android 13/14
 *       ROMs whose config_foldedDeviceStates is empty).</li>
 * </ol>
 */
final class FoldMonitor {
    private static final String DSM_CALLBACK =
            "android.hardware.devicestate.DeviceStateManager$DeviceStateCallback";
    private static final String DEVICE_STATE = "android.hardware.devicestate.DeviceState";
    private static final int SW_LID = 0; // linux/input-event-codes.h
    // InputManagerInternal moved packages in Android 14.
    private static final String[] INPUT_MANAGER_INTERNAL = {
            "com.android.server.input.InputManagerInternal",
            "android.hardware.input.InputManagerInternal",
    };

    private FoldMonitor() {}

    static boolean register(Context ctx, Handler handler, Config cfg, Controller controller) {
        boolean any = false;
        try {
            any |= registerDeviceState(ctx, handler, cfg, controller);
        } catch (Throwable t) {
            Log.w(Entry.TAG, "DeviceStateManager unavailable", t);
        }
        try {
            any |= registerLidSwitch(handler, controller);
        } catch (Throwable t) {
            Log.w(Entry.TAG, "lid switch unavailable", t);
        }
        if (!any) Log.e(Entry.TAG, "no fold signal available on this device");
        return any;
    }

    @SuppressLint("WrongConstant") // Context.DEVICE_STATE_SERVICE is @hide
    private static boolean registerDeviceState(Context ctx, Handler handler, Config cfg,
                                               Controller controller) throws Exception {
        Object dsm = ctx.getSystemService("device_state");
        if (dsm == null) {
            Log.w(Entry.TAG, "no device_state service");
            return false;
        }
        int[] configFolded = foldedStatesResource(ctx);
        Classifier classifier = new Classifier(cfg.foldedStates, configFolded);
        Log.i(Entry.TAG, "classifier: " + classifier);

        Class<?> callbackClass = Class.forName(DSM_CALLBACK);
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onStateChanged": // Android 13/14: abstract onStateChanged(int)
                            if (args != null && args.length == 1 && args[0] instanceof Integer) {
                                int id = (Integer) args[0];
                                controller.onDeviceState(id, classifier.classify(id, null), "id");
                            }
                            return null;
                        case "onDeviceStateChanged": // Android 15+: onDeviceStateChanged(DeviceState)
                            if (args != null && args.length == 1 && args[0] != null) {
                                Object state = args[0];
                                int id = (Integer) state.getClass().getMethod("getIdentifier").invoke(state);
                                controller.onDeviceState(id, classifier.classify(id, state), "DeviceState");
                            }
                            return null;
                        case "equals":
                            return args != null && args.length == 1 && proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "GestureSwitch.DeviceStateCallback";
                        default: // onBaseStateChanged, onSupportedStatesChanged, ...
                            return null;
                    }
                });

        Method register = dsm.getClass().getMethod("registerCallback", Executor.class, callbackClass);
        Executor executor = r -> handler.post(Controller.safe("device state callback", r::run));
        // Fires immediately with the current state, then on every change.
        register.invoke(dsm, executor, callback);
        Log.i(Entry.TAG, "DeviceStateManager callback registered");
        return true;
    }

    private static boolean registerLidSwitch(Handler handler, Controller controller) throws Exception {
        // Without an SW_LID switch, InputManagerService reports the lid as closed, which would
        // lock the device into folded mode. Only use the signal if the switch exists.
        if (!hasLidSwitch()) {
            Log.i(Entry.TAG, "no lid switch on this device");
            return false;
        }
        // Our class loader's parent is the system_server class loader, which has services.jar.
        ClassLoader loader = FoldMonitor.class.getClassLoader();
        Class<?> imiClass = null;
        for (String name : INPUT_MANAGER_INTERNAL) {
            try {
                imiClass = Class.forName(name, false, loader);
                break;
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        if (imiClass == null) {
            Log.w(Entry.TAG, "InputManagerInternal not found");
            return false;
        }
        Class<?> localServices = Class.forName("com.android.server.LocalServices", false, loader);
        Object imi = localServices.getMethod("getService", Class.class).invoke(null, imiClass);
        if (imi == null) {
            Log.w(Entry.TAG, "InputManagerInternal not registered");
            return false;
        }
        Class<?> callbackClass = Class.forName(imiClass.getName() + "$LidSwitchCallback", false, loader);
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "notifyLidSwitchChanged": // (long whenNanos, boolean lidOpen)
                            if (args != null && args.length == 2 && args[1] instanceof Boolean) {
                                boolean open = (Boolean) args[1];
                                // Called under InputManagerService's lid switch lock: only post.
                                handler.post(Controller.safe("lid callback", () -> controller.onLid(open)));
                            }
                            return null;
                        case "equals":
                            return args != null && args.length == 1 && proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "GestureSwitch.LidSwitchCallback";
                        default:
                            return null;
                    }
                });
        imiClass.getMethod("registerLidSwitchCallback", callbackClass).invoke(imi, callback);
        Log.i(Entry.TAG, "lid switch callback registered");
        return true;
    }

    /** True if some input device reports an SW_LID state (KEY_STATE_UNKNOWN = -1 otherwise). */
    private static boolean hasLidSwitch() {
        try {
            // In system_server, ServiceManager returns the local InputManagerService object.
            Object ims = Reflect.getService("input");
            if (ims == null) return false;
            Method getSwitchState = ims.getClass().getMethod("getSwitchState", int.class, int.class, int.class);
            int state = (Integer) getSwitchState.invoke(ims, -1, InputDevice.SOURCE_ANY, SW_LID);
            return state >= 0;
        } catch (Throwable t) {
            Log.w(Entry.TAG, "cannot query lid switch", t);
            return false;
        }
    }

    @SuppressLint("DiscouragedApi") // framework resource, no R.* id available
    private static int[] foldedStatesResource(Context ctx) {
        try {
            // The system context's resources include the vendor's framework overlays.
            Resources res = ctx.getResources();
            int id = res.getIdentifier("config_foldedDeviceStates", "array", "android");
            return id == 0 ? new int[0] : res.getIntArray(id);
        } catch (Throwable t) {
            Log.w(Entry.TAG, "cannot read config_foldedDeviceStates", t);
            return new int[0];
        }
    }

    /** Maps a device state to folded (true), open (false) or unknown (null). */
    static final class Classifier {
        private final int[] userFolded;
        private final int[] configFolded;
        private final Integer outerPrimary;
        private final Integer innerPrimary;

        Classifier(int[] userFolded, int[] configFolded) {
            this.userFolded = userFolded;
            this.configFolded = configFolded;
            this.outerPrimary = deviceStateConstant("PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY");
            this.innerPrimary = deviceStateConstant("PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY");
        }

        Boolean classify(int id, Object deviceState) {
            // 1. Explicit list in config.prop wins.
            if (userFolded != null) return contains(userFolded, id);
            // 2. Android 15+: the state says which display is primary.
            if (deviceState != null) {
                if (hasProperty(deviceState, outerPrimary)) return true;
                if (hasProperty(deviceState, innerPrimary)) return false;
            }
            // 3. Framework overlay config (what DeviceStateManager.FoldStateListener uses).
            if (configFolded.length > 0) return contains(configFolded, id);
            // 4. Unknown: fall back to the lid switch.
            return null;
        }

        private static boolean hasProperty(Object state, Integer property) {
            if (property == null) return false;
            try {
                return (Boolean) state.getClass().getMethod("hasProperty", int.class).invoke(state, property);
            } catch (Throwable t) {
                return false;
            }
        }

        private static Integer deviceStateConstant(String name) {
            try {
                return Class.forName(DEVICE_STATE).getField(name).getInt(null);
            } catch (Throwable t) {
                return null; // Android 13/14
            }
        }

        private static boolean contains(int[] array, int value) {
            for (int v : array) {
                if (v == value) return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return "Classifier{user=" + java.util.Arrays.toString(userFolded)
                    + ", config_foldedDeviceStates=" + java.util.Arrays.toString(configFolded)
                    + ", outerPrimary=" + outerPrimary + ", innerPrimary=" + innerPrimary + "}";
        }
    }
}
