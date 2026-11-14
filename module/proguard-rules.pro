# Called from native code (jni/main.cpp) by name.
-keep class io.github.misaka20002.gestureswitch.Entry {
    public static void start(java.lang.String, boolean);
}
# Keep readable names in logcat stack traces from system_server.
-dontobfuscate
