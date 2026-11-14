# gesture_switch

A [Magisk](https://github.com/topjohnwu/Magisk) Zygisk module.

Requires **Magisk v27.0+** (Zygisk API v5) with Zygisk enabled.

## Building

### Requirements

- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK with **platform `android-37.0`**
- Android **NDK r30 (`30.0.16248370`)**, pinned in [module/build.gradle.kts](module/build.gradle.kts).
  The bundled `libcxx` requires clang 21, so older NDKs fail to compile it.
  Install it with Android Studio's SDK Manager or
  `sdkmanager "ndk;30.0.16248370"`.

### Clone

The C++ standard library is a git submodule, so clone recursively:

```sh
git clone --recursive https://github.com/misaka-20002/gesture_switch.git
# or, in an existing clone:
git submodule update --init
```

### Build

Point Gradle at your SDK with `ANDROID_HOME` or a `local.properties` file
(`sdk.dir=...`, not committed), then:

```sh
./gradlew build          # Windows: gradlew.bat build
```

The flashable modules are written to `module/build/outputs/magisk/`:

| File | Contents |
|---|---|
| `gesture_switch-<version>-release.zip` | release build |
| `gesture_switch-<version>-debug.zip` | debug build (unoptimized) |

`./gradlew zipRelease` builds just the release zip. Install it from the Magisk
app (Modules → Install from storage) and reboot.

Module id, name, version and description are set at the top of
[module/build.gradle.kts](module/build.gradle.kts) and written into `module.prop`.

### Layout

```
module/
├── build.gradle.kts     Gradle build + zip<Variant> packaging task
├── jni/
│   ├── Android.mk       ndk-build script
│   ├── Application.mk   ABIs, C++ flags, API level
│   ├── main.cpp         module code
│   ├── zygisk.hpp       Zygisk API v5 header (do not modify)
│   └── libcxx/          submodule: lightweight, self-contained libc++
└── magisk/              files copied into the module zip
    ├── module.prop      template, filled in by Gradle
    ├── customize.sh     install-time checks
    └── META-INF/        standard Magisk installer stub
```

## Notes

- Code in `pre[XXX]Specialize` runs in **every** app process and in
  system_server before specialization: keep it fast and never block for long.
- `APP_STL` is `none` on purpose. Use the bundled `libcxx` (as the build does),
  never an NDK STL: module code is injected into zygote-forked processes and must
  stay self-contained. Without libcxx you only have C headers (`<stdlib.h>`, not
  `<cstdlib>`); see the commented alternative in `Android.mk`.
- The Zygisk API is documented in [module/jni/zygisk.hpp](module/jni/zygisk.hpp).

## Credits

Based on [topjohnwu/zygisk-module-sample](https://github.com/topjohnwu/zygisk-module-sample).
The Zygisk API header and the original sample code are released under 0BSD.
