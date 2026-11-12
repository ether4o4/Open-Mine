// JNI bridge for Open Mine's built-in models.
//  * llama.cpp's server runs in-process on loopback, so LFM3 is reached through the same
//    OpenAI-compatible client as every other model (tools, streaming, cancellation).
//  * Cactus runs Needle 3 for fast tool routing. Its weights stay mapped while loaded.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <clocale>
#include <csignal>
#include <mutex>
#include <string>
#include <vector>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "common.h"
#include "arg.h"
#include "llama.h"
#include "server-stream.h"

#ifdef OPENMINE_HAS_CACTUS
#include "cactus.h"
#endif

int llama_server(common_params & params, int argc, char ** argv);
void llama_server_terminate();

#define TAG "OpenMineAI"

static std::atomic<bool> g_server_running{false};

static std::vector<std::string> strings(JNIEnv * env, jobjectArray array) {
    std::vector<std::string> out;
    const jsize n = env->GetArrayLength(array);
    for (jsize i = 0; i < n; i++) {
        auto value = (jstring) env->GetObjectArrayElement(array, i);
        const char * chars = env->GetStringUTFChars(value, nullptr);
        out.emplace_back(chars);
        env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
    }
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_openmine_ai_NativeAi_serverRun(JNIEnv * env, jclass, jobjectArray args) {
    bool expected = false;
    if (!g_server_running.compare_exchange_strong(expected, true)) return -100;
    std::vector<std::string> values = strings(env, args);
    std::vector<char *> argv;
    for (auto & v : values) argv.push_back(v.data());
    argv.push_back(nullptr);
    const int argc = (int) values.size();

    std::setlocale(LC_NUMERIC, "C");
    signal(SIGPIPE, SIG_IGN);
    common_params params;
    common_init();
    server_stream_session_manager_start();
    int result = 1;
    if (common_params_parse(argc, argv.data(), params, LLAMA_EXAMPLE_SERVER)) {
        llama_backend_init();
        llama_numa_init(params.numa);
        // The embedded variant installs no signal handlers and never downloads models.
        result = llama_server(params, argc, argv.data());
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Invalid server arguments");
    }
    g_server_running = false;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openmine_ai_NativeAi_serverStop(JNIEnv *, jclass) {
    if (g_server_running) llama_server_terminate();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openmine_ai_NativeAi_serverRunning(JNIEnv *, jclass) { return g_server_running.load(); }

#ifdef OPENMINE_HAS_CACTUS
static std::mutex g_cactus;
static void * g_map = nullptr;
static size_t g_map_size = 0;
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openmine_ai_NativeAi_cactusAvailable(JNIEnv *, jclass) {
#ifdef OPENMINE_HAS_CACTUS
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static jstring error_string(JNIEnv * env, const char * message) {
    return env->NewStringUTF((std::string("ERROR: ") + message).c_str());
}

/** Maps the .cact file read-only and loads it. Returns null on success or an error message. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_openmine_ai_NativeAi_cactusLoad(JNIEnv * env, jclass, jstring path_) {
#ifdef OPENMINE_HAS_CACTUS
    std::lock_guard<std::mutex> lock(g_cactus);
    if (g_map) return nullptr;
    const char * path = env->GetStringUTFChars(path_, nullptr);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(path_, path);
    if (fd < 0) return error_string(env, "cannot open Needle weights");
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); return error_string(env, "cannot read Needle weights"); }
    void * map = mmap(nullptr, (size_t) st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return error_string(env, "cannot map Needle weights");
    if (cactus_load((const unsigned char *) map, (unsigned long long) st.st_size) < 0) {
        const char * err = cactus_last_error();
        munmap(map, (size_t) st.st_size);
        return error_string(env, err ? err : "Cactus could not load the model");
    }
    g_map = map; g_map_size = (size_t) st.st_size;
    return nullptr;
#else
    return error_string(env, "Cactus runtime is not available for this CPU");
#endif
}

/** Configures the tool set and returns null on success or an error message. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_openmine_ai_NativeAi_cactusInit(JNIEnv * env, jclass, jstring system_, jstring tools_, jint threads) {
#ifdef OPENMINE_HAS_CACTUS
    std::lock_guard<std::mutex> lock(g_cactus);
    if (!g_map) return error_string(env, "Needle is not loaded");
    const char * system = env->GetStringUTFChars(system_, nullptr);
    const char * tools = env->GetStringUTFChars(tools_, nullptr);
    if (threads > 0) cactus_set_threads(threads);
    int rc = cactus_init(system, tools, nullptr);
    env->ReleaseStringUTFChars(system_, system);
    env->ReleaseStringUTFChars(tools_, tools);
    // cactus_init returns a non-negative count on success and a negative value on failure.
    if (rc < 0) { const char * err = cactus_last_error(); return error_string(env, err ? err : "Cactus init failed"); }
    return nullptr;
#else
    return error_string(env, "Cactus runtime is not available for this CPU");
#endif
}

/** Runs one stateless completion and returns Cactus's JSON result (or "ERROR: ..."). */
extern "C" JNIEXPORT jstring JNICALL
Java_com_openmine_ai_NativeAi_cactusComplete(JNIEnv * env, jclass, jstring input_, jint max_tokens) {
#ifdef OPENMINE_HAS_CACTUS
    std::lock_guard<std::mutex> lock(g_cactus);
    if (!g_map) return error_string(env, "Needle is not loaded");
    const char * input = env->GetStringUTFChars(input_, nullptr);
    std::vector<char> out(64 * 1024);
    cactus_reset();
    int rc = cactus_complete(input, nullptr, 0, max_tokens, out.data(), (int) out.size());
    env->ReleaseStringUTFChars(input_, input);
    if (rc < 0) { const char * err = cactus_last_error(); return error_string(env, err ? err : "Cactus completion failed"); }
    out.back() = 0;
    return env->NewStringUTF(out.data());
#else
    return error_string(env, "Cactus runtime is not available for this CPU");
#endif
}

/** Embeds text with Needle (for hybrid retrieval). Returns null when unavailable. */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_openmine_ai_NativeAi_cactusEmbed(JNIEnv * env, jclass, jstring input_) {
#ifdef OPENMINE_HAS_CACTUS
    std::lock_guard<std::mutex> lock(g_cactus);
    if (!g_map) return nullptr;
    const char * input = env->GetStringUTFChars(input_, nullptr);
    std::vector<float> out(4096);
    int n = cactus_embed(input, nullptr, 0, out.data(), (int) out.size());
    env->ReleaseStringUTFChars(input_, input);
    if (n <= 0 || n > (int) out.size()) return nullptr;
    jfloatArray result = env->NewFloatArray(n);
    env->SetFloatArrayRegion(result, 0, n, out.data());
    return result;
#else
    return nullptr;
#endif
}
