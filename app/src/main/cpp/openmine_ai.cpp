// JNI bridge for Open Mine's built-in model engine: llama.cpp's server runs in-process on loopback,
// so LFM3 is reached through the same OpenAI-compatible client as every other model.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <clocale>
#include <csignal>
#include <string>
#include <vector>

#include "common.h"
#include "arg.h"
#include "llama.h"
#include "server-stream.h"

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
