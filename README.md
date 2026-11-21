# gesture_switch

A Zygisk module for flip phones (built for the **Motorola Razr**) that switches the
system navigation mode with the fold state:

| Fold state | Navigation (default) |
|---|---|
| Folded (cover screen) | 3-button |
| Open (main screen) | Gestures |

It runs inside `system_server`, listens for fold-state events (no polling), and enables
the matching AOSP navigation-bar overlay, exactly like
`cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.<mode>`.

## Requirements

- Android 13 to 16 (arm64)
- **KernelSU + [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)**, or Magisk v27.0+ with Zygisk enabled

## Install

1. Flash `gesture_switch-<version>-release.zip` from the KernelSU (or Magisk) manager.
2. Reboot to activate a staged installation. An already active installation can also
   start through NeoZygisk late-load.
3. Check that it is working:

   ```sh
   adb logcat -s GestureSwitch
   ```

   You should see `starting`, `DeviceStateManager callback registered` and/or
   `lid switch callback registered`, then a `device state … -> folded/open` line and
   `switch to threebutton/gestural` each time you fold or open the phone.

### NeoZygisk late-load

With a NeoZygisk build containing [late-load support](https://github.com/JingMatrix/NeoZygisk/pull/174),
the provider runs the module's Zygisk callbacks in a live `system_server` during
KernelSU late-load. Look for `ready (late=true)` in `adb logcat -s GestureSwitch`.
The module registers one listener set per process. If this process already loaded
an older version of Gesture Switch, restart Android once to load the updated code.

## Configuration

- **KernelSU:** Open the module's WebUI from the Gesture Switch card. Choose a mode for each
  posture, set the debounce delay in milliseconds and tap **保存设置** (Save). The page offers
  modes that the current user can switch to.
- **Magisk 28.0+:** Tap **Action** on the Gesture Switch card to switch between
  three-button folded / gestural open and gestural folded / three-button open. The console
  shows the saved selection.
- **Manual:** Edit `/data/adb/modules/gesture_switch/config.prop` for custom state IDs,
  debounce, or mode settings on older managers.

The WebUI and Action apply navigation modes and debounce changes to the running module.
If you edit the file manually, apply those same settings with:

```sh
adb shell "su -c 'sh /data/adb/modules/gesture_switch/settings.sh apply'"
```

Changes to `folded_states` take effect on the next module start. The configuration is
kept across manager-installed updates.

| Key | Default | Meaning |
|---|---|---|
| `folded_mode` | `threebutton` | Mode when folded: `gestural`, `threebutton` or `twobutton` |
| `open_mode` | `gestural` | Mode when open |
| `folded_states` | empty (auto) | Comma-separated device state ids that count as folded |
| `debounce_ms` | `400` | Stable-state delay in milliseconds, from `0` to `5000` |

### How folded is detected

In order of priority:

1. `folded_states` from `config.prop`, if set.
2. Android 15+: the device state's "outer display is primary" property.
3. The framework's `config_foldedDeviceStates` list.
4. Otherwise the hardware lid switch.

If the log says `fold state unknown`, find your device's state ids and set
`folded_states`:

```sh
adb shell cmd device_state print-states
adb shell dumpsys device_state      # run while folded, then while open
```

## Testing on a new device

Before relying on the module, check by hand that the pieces exist:

```sh
# Both overlays must be listed (and not marked immutable)
adb shell cmd overlay list android | grep navbar

# Switch by hand while folded: is a usable 3-button bar shown on the cover screen?
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
```

## Uninstalling / recovery

- When the module is removed, it switches back to `open_mode` (gestures by default) on
  the next boot. You can always pick a mode in
  **Settings → System → Gestures → System navigation**, or run the
  `cmd overlay enable-exclusive …` command above.
- The module runs inside `system_server`. If the phone ever fails to boot after
  installing it, boot into **safe mode**, which disables all modules, then remove it:
  - **KernelSU:** right after the first boot logo, *press and release* volume down more
    than three times (holding it does nothing).
  - **Magisk:** hold volume down during boot.
  - Or, from recovery or `adb shell` as root, create
    `/data/adb/modules/gesture_switch/disable` (or delete that directory).

## Building

### Requirements

- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK with **platform `android-37.0`**
- Android **NDK r30 (`30.0.16248370`)**, pinned in [module/build.gradle.kts](module/build.gradle.kts).
  The bundled `libcxx` requires clang 21, so older NDKs fail to compile it.
  Install it with Android Studio's SDK Manager or `sdkmanager "ndk;30.0.16248370"`.

### Clone and build

```sh
git clone --recursive https://github.com/misaka-20002/gesture_switch.git
cd gesture_switch
./gradlew build          # Windows: gradlew.bat build
```

Point Gradle at your SDK with `ANDROID_HOME` or a `local.properties` file
(`sdk.dir=...`, not committed). The flashable modules are written to
`module/build/outputs/magisk/`:

| File | Contents |
|---|---|
| `gesture_switch-<version>-release.zip` | release build |
| `gesture_switch-<version>-debug.zip` | debug build |

`./gradlew zipRelease` builds just the release zip. Module id, name, version and
description are set at the top of [module/build.gradle.kts](module/build.gradle.kts).

### How it works

```
module/
├── jni/main.cpp          Native Zygisk loader: in system_server, reads classes.dex and
│                         config.prop from the module dir, loads the dex with
│                         InMemoryDexClassLoader and calls Entry.start(). Unloads itself.
├── src/main/java/…/      Java code that runs in system_server
│   ├── Entry.java        Start-up: waits for boot, gets the system context
│   ├── FoldMonitor.java  DeviceStateManager + lid switch listeners, fold classifier
│   ├── Controller.java   Combines signals, debounces, picks the mode
│   └── NavSwitcher.java  Enables the navbar overlay via IOverlayManager
├── magisk/               Files copied into the module zip (module.prop, customize.sh,
│                         config.prop, installer stub)
└── build.gradle.kts      Builds the APK (only a container for classes.dex and the
                          native libraries) and the zip<Variant> packaging tasks
```

Hidden framework APIs are used only through reflection, so the same dex works on
Android 13 to 16. Everything that runs in `system_server` catches all exceptions and
only logs them, because an uncaught exception there would crash the phone into a
boot loop.

## Credits

Based on [topjohnwu/zygisk-module-sample](https://github.com/topjohnwu/zygisk-module-sample).
The Zygisk API header and the original sample code are released under 0BSD.
