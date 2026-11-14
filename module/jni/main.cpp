/* gesture_switch: native Zygisk loader.
 *
 * Apps:          nothing is done; the library asks to be unloaded.
 * system_server: classes.dex and config.prop are read from the module directory in
 *                preServerSpecialize (the only time the module dir is reachable; all fds
 *                must be closed before returning), then in postServerSpecialize the dex is
 *                loaded with InMemoryDexClassLoader and Entry.start(config, late) is called.
 *                All real work happens in Java; no JNI natives or hooks are installed, so
 *                the library is dlclose'd afterwards.
 *
 * Anything that throws here would crash system_server (= bootloop), so every JNI call is
 * followed by an exception check and failures only log.
 *
 * Based on the Zygisk module sample by John "topjohnwu" Wu (0BSD).
 */

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#include "zygisk.hpp"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

#define LOG_TAG "GestureSwitch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static constexpr const char *kDexName = "classes.dex";
static constexpr const char *kConfigName = "config.prop";
static constexpr const char *kEntryClass = "io.github.misaka20002.gestureswitch.Entry";
// NeoZygisk sets this runtime flag when injecting into an already running system_server.
static constexpr jint kLateInjectFlag = 1 << 30;
static constexpr size_t kMaxFileSize = 16 * 1024 * 1024;

struct Buffer {
    char *data = nullptr;
    size_t size = 0;

    void reset() {
        free(data);
        data = nullptr;
        size = 0;
    }
};

// Read a whole file relative to dirfd into a NUL-terminated heap buffer. The fd is always closed.
// Returns nullptr on success, otherwise a description of what failed.
static const char *read_file_at(int dirfd, const char *name, Buffer &out) {
    int fd = openat(dirfd, name, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return strerror(errno);

    const char *error = nullptr;
    struct stat st{};
    if (fstat(fd, &st) != 0) {
        error = strerror(errno);
    } else if (st.st_size <= 0) {
        error = "empty file";
    } else if (static_cast<size_t>(st.st_size) > kMaxFileSize) {
        error = "file too large";
    } else {
        size_t size = static_cast<size_t>(st.st_size);
        auto *buf = static_cast<char *>(malloc(size + 1));
        if (!buf) {
            error = "out of memory";
        } else {
            size_t done = 0;
            while (done < size) {
                ssize_t n = TEMP_FAILURE_RETRY(read(fd, buf + done, size - done));
                if (n < 0) error = strerror(errno);
                if (n <= 0) break;
                done += static_cast<size_t>(n);
            }
            if (done == size) {
                buf[size] = '\0';
                out.data = buf;
                out.size = size;
            } else {
                if (!error) error = "short read";
                free(buf);
            }
        }
    }
    close(fd);
    return error;
}

// NewStringUTF needs valid modified UTF-8 (CheckJNI aborts otherwise); the config is plain
// ASCII, so replace anything else.
static void sanitize_ascii(Buffer &buf) {
    for (size_t i = 0; i < buf.size; ++i) {
        auto c = static_cast<unsigned char>(buf.data[i]);
        if (c >= 0x80 || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) buf.data[i] = '?';
    }
}

class GestureSwitch : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *) override {
        // Nothing to do in app processes.
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

    void preServerSpecialize(ServerSpecializeArgs *args) override {
        late = (args->runtime_flags & kLateInjectFlag) != 0;

        int dirfd = api->getModuleDir();
        if (dirfd < 0) {
            LOGW("getModuleDir failed");
            return;
        }
        if (const char *error = read_file_at(dirfd, kDexName, dex)) {
            LOGW("cannot read %s: %s", kDexName, error);
        }
        if (!read_file_at(dirfd, kConfigName, config)) {  // optional, defaults otherwise
            sanitize_ascii(config);
        }
        close(dirfd);
    }

    void postServerSpecialize(const ServerSpecializeArgs *) override {
        if (dex.data) {
            loadDex();
        }
        dex.reset();
        config.reset();
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
    Buffer dex;
    Buffer config;
    bool late = false;

    // Returns true if no exception is pending; otherwise logs and clears it.
    bool ok(const char *what) {
        if (!env->ExceptionCheck()) return true;
        jthrowable error = env->ExceptionOccurred();
        env->ExceptionClear();
        logThrowable(what, error);
        if (error) env->DeleteLocalRef(error);
        return false;
    }

    // ExceptionDescribe() is useless here: on the normal boot path System.err still points to
    // /dev/null. Log the stack trace through android.util.Log instead.
    void logThrowable(const char *what, jthrowable error) {
        jstring trace = nullptr;
        jclass log = error ? env->FindClass("android/util/Log") : nullptr;
        if (log && !env->ExceptionCheck()) {
            jmethodID get = env->GetStaticMethodID(log, "getStackTraceString", "(Ljava/lang/Throwable;)Ljava/lang/String;");
            if (get && !env->ExceptionCheck()) {
                trace = static_cast<jstring>(env->CallStaticObjectMethod(log, get, error));
            }
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            trace = nullptr;
        }
        const char *chars = trace ? env->GetStringUTFChars(trace, nullptr) : nullptr;
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (chars) {
            LOGW("%s: %s", what, chars);
            env->ReleaseStringUTFChars(trace, chars);
        } else {
            LOGW("%s: exception", what);
        }
        if (trace) env->DeleteLocalRef(trace);
        if (log) env->DeleteLocalRef(log);
    }

    // The system_server class loader (services.jar) is created in native SpecializeCommon before
    // the post-fork hooks run; using it as parent lets the Java side reach com.android.server.*.
    jobject parentClassLoader() {
        jobject loader = nullptr;
        jclass zygoteInit = env->FindClass("com/android/internal/os/ZygoteInit");
        if (ok("find ZygoteInit") && zygoteInit) {
            jmethodID get = env->GetStaticMethodID(zygoteInit, "getOrCreateSystemServerClassLoader", "()Ljava/lang/ClassLoader;");
            if (ok("lookup getOrCreateSystemServerClassLoader") && get) {
                loader = env->CallStaticObjectMethod(zygoteInit, get);
                if (!ok("getOrCreateSystemServerClassLoader")) loader = nullptr;
            }
            env->DeleteLocalRef(zygoteInit);
        }
        if (loader) return loader;

        jclass classLoader = env->FindClass("java/lang/ClassLoader");
        if (ok("find ClassLoader") && classLoader) {
            jmethodID get = env->GetStaticMethodID(classLoader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
            if (ok("lookup getSystemClassLoader") && get) {
                loader = env->CallStaticObjectMethod(classLoader, get);
                if (!ok("getSystemClassLoader")) loader = nullptr;
            }
            env->DeleteLocalRef(classLoader);
        }
        return loader;
    }

    void loadDex() {
        jobject parent = parentClassLoader();
        if (!parent) {
            LOGW("no parent class loader");
            return;
        }

        jclass imdcl = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        jmethodID ctor = (ok("find InMemoryDexClassLoader") && imdcl)
                ? env->GetMethodID(imdcl, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V") : nullptr;
        jobject buffer = (ok("lookup InMemoryDexClassLoader.<init>") && ctor)
                ? env->NewDirectByteBuffer(dex.data, static_cast<jlong>(dex.size)) : nullptr;
        // The dex bytes are copied by ART, so the buffer can be freed afterwards.
        jobject loader = (ok("NewDirectByteBuffer") && buffer) ? env->NewObject(imdcl, ctor, buffer, parent) : nullptr;
        if (!ok("create InMemoryDexClassLoader") || !loader) {
            LOGW("failed to load %s", kDexName);
        } else {
            jclass classLoader = env->GetObjectClass(loader);
            jmethodID loadClass = env->GetMethodID(classLoader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
            jstring name = (ok("lookup loadClass") && loadClass) ? env->NewStringUTF(kEntryClass) : nullptr;
            auto entry = (ok("alloc class name") && name)
                    ? static_cast<jclass>(env->CallObjectMethod(loader, loadClass, name)) : nullptr;
            if (ok("load entry class") && entry) {
                jmethodID start = env->GetStaticMethodID(entry, "start", "(Ljava/lang/String;Z)V");
                jstring cfg = (ok("lookup Entry.start") && start)
                        ? env->NewStringUTF(config.data ? config.data : "") : nullptr;
                if (ok("alloc config") && cfg) {
                    env->CallStaticVoidMethod(entry, start, cfg, static_cast<jboolean>(late));
                    if (ok("Entry.start")) LOGI("loaded (late=%d)", late);
                    env->DeleteLocalRef(cfg);
                }
                env->DeleteLocalRef(entry);
            }
            if (name) env->DeleteLocalRef(name);
            env->DeleteLocalRef(classLoader);
            env->DeleteLocalRef(loader);
        }
        if (buffer) env->DeleteLocalRef(buffer);
        if (imdcl) env->DeleteLocalRef(imdcl);
        env->DeleteLocalRef(parent);
    }
};

REGISTER_ZYGISK_MODULE(GestureSwitch)
