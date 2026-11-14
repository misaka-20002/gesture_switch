/* Based on the Zygisk module sample by John "topjohnwu" Wu (0BSD).
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
 * AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
 * LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
 * OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 * PERFORMANCE OF THIS SOFTWARE.
 */

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <android/log.h>

#include "zygisk.hpp"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

#define LOG_TAG "GestureSwitch"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Read or write exactly `len` bytes, retrying on EINTR and short transfers.
static bool read_full(int fd, void *buf, size_t len) {
    auto *p = static_cast<char *>(buf);
    while (len > 0) {
        ssize_t n = TEMP_FAILURE_RETRY(read(fd, p, len));
        if (n == 0) errno = ECONNRESET;  // peer closed before sending everything
        if (n <= 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

static bool write_full(int fd, const void *buf, size_t len) {
    auto *p = static_cast<const char *>(buf);
    while (len > 0) {
        // MSG_NOSIGNAL: a peer that already went away must not SIGPIPE the root daemon.
        ssize_t n = TEMP_FAILURE_RETRY(send(fd, p, len, MSG_NOSIGNAL));
        if (n <= 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

class GestureSwitch : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        // Use JNI to fetch our process name
        const char *process = nullptr;
        if (args->nice_name) {
            process = env->GetStringUTFChars(args->nice_name, nullptr);
        }
        preSpecialize(process ? process : "<unknown>");
        if (process) {
            env->ReleaseStringUTFChars(args->nice_name, process);
        }
    }

    void preServerSpecialize(ServerSpecializeArgs *args) override {
        preSpecialize("system_server");
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;

    void preSpecialize(const char *process) {
        // Demonstrate connecting to the companion process:
        // we ask the companion for a random number.
        int fd = api->connectCompanion();
        if (fd < 0) {
            LOGW("process=[%s] connectCompanion failed", process);
        } else {
            // This runs in every app fork and in system_server before specialization,
            // so never block indefinitely on the companion.
            timeval tv{};
            tv.tv_sec = 2;
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

            unsigned r = 0;
            if (read_full(fd, &r, sizeof(r))) {
                LOGD("process=[%s], r=[%u]", process, r);
            } else {
                LOGW("process=[%s] companion read failed: %d", process, errno);
            }
            close(fd);
        }

        // Since we do not hook any functions, we should let Zygisk dlclose ourselves
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }
};

// Runs as root in the Zygisk companion daemon. May be called concurrently on
// multiple threads (see zygisk.hpp), so any shared state must be thread-safe.
static void companion_handler(int client) {
    // Per-request fd: no shared state between concurrent handlers.
    int urandom = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (urandom < 0) {
        LOGW("companion: cannot open /dev/urandom: %d", errno);
        return;  // Returning without a reply tells the client we failed.
    }

    unsigned r = 0;
    bool ok = read_full(urandom, &r, sizeof(r));
    int err = errno;
    close(urandom);
    if (!ok) {
        LOGW("companion: read /dev/urandom failed: %d", err);
        return;
    }
    LOGD("companion r=[%u]", r);
    if (!write_full(client, &r, sizeof(r))) {
        LOGW("companion: reply failed: %d", errno);
    }
}

// Register our module class and the companion handler function
REGISTER_ZYGISK_MODULE(GestureSwitch)
REGISTER_ZYGISK_COMPANION(companion_handler)
