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
#include "chat.h"
#include "sampling.h"
#include <memory>
#include <mutex>

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

// ---- Helper slot: a small GGUF loaded directly through libllama (not a second server; the embedded
// server keeps process-wide state). Used for short background jobs such as tagging imported notes.
static std::mutex g_helper_lock;
static common_init_result_ptr g_helper;
static common_chat_templates_ptr g_helper_templates;

extern "C" JNIEXPORT jstring JNICALL
Java_com_openmine_ai_NativeAi_helperLoad(JNIEnv * env, jclass, jstring path_, jint threads, jint context) {
    std::lock_guard<std::mutex> lock(g_helper_lock);
    if (g_helper) return nullptr;
    const char * path = env->GetStringUTFChars(path_, nullptr);
    common_params params;
    params.model.path = path;
    env->ReleaseStringUTFChars(path_, path);
    params.n_ctx = context;
    params.n_batch = 512;
    params.cpuparams.n_threads = threads;
    params.cpuparams_batch.n_threads = threads;
    params.sampling.temp = 0.0f;                    // deterministic tags
    params.warmup = false;
    llama_backend_init();
    auto init = common_init_from_params(params);
    if (!init || !init->model() || !init->context()) return env->NewStringUTF("ERROR: helper model could not be loaded");
    g_helper_templates = common_chat_templates_init(init->model(), "");
    g_helper = std::move(init);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openmine_ai_NativeAi_helperUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_helper_lock);
    g_helper_templates.reset();
    g_helper.reset();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openmine_ai_NativeAi_helperLoaded(JNIEnv *, jclass) { return g_helper ? JNI_TRUE : JNI_FALSE; }

static jbyteArray bytes(JNIEnv * env, const std::string & text) {
    jbyteArray out = env->NewByteArray((jsize) text.size());
    env->SetByteArrayRegion(out, 0, (jsize) text.size(), (const jbyte *) text.data());
    return out;
}

/** One stateless chat completion with the model's own template. Returns UTF-8 bytes, or "ERROR: ..." bytes.
 * Bytes, not a jstring: NewStringUTF rejects 4-byte UTF-8 such as emoji in model output. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_openmine_ai_NativeAi_helperComplete(JNIEnv * env, jclass, jbyteArray system_, jbyteArray user_, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_helper_lock);
    if (!g_helper) return bytes(env, "ERROR: helper model is not loaded");
    llama_context * ctx = g_helper->context();
    const llama_vocab * vocab = llama_model_get_vocab(g_helper->model());
    common_chat_templates_inputs inputs;
    auto utf8 = [env](jbyteArray array) {
        std::string text((size_t) env->GetArrayLength(array), '\0');
        env->GetByteArrayRegion(array, 0, (jsize) text.size(), (jbyte *) text.data());
        return text;
    };
    common_chat_msg system; system.role = "system"; system.content = utf8(system_);
    common_chat_msg user; user.role = "user"; user.content = utf8(user_);
    inputs.messages = {system, user};
    inputs.enable_thinking = false;
    common_chat_params chat = common_chat_templates_apply(g_helper_templates.get(), inputs);
    // User text is tokenized without special-token parsing so it cannot inject template markers.
    std::vector<llama_token> tokens;
    bool first = true;
    for (const auto & part : chat.prompt.parts) {
        auto t = common_tokenize(ctx, part.text, first, !part.is_input);
        tokens.insert(tokens.end(), t.begin(), t.end());
        first = false;
    }
    const int n_ctx = (int) llama_n_ctx(ctx);
    if ((int) tokens.size() + max_tokens >= n_ctx) return bytes(env, "ERROR: input is too long for the helper model");
    llama_memory_clear(llama_get_memory(ctx), true);
    g_helper->reset_samplers();
    common_sampler * sampler = g_helper->sampler(0);
    const int n_batch = 512;
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        const int n = (int) std::min<size_t>(n_batch, tokens.size() - i);
        if (llama_decode(ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return bytes(env, "ERROR: helper decode failed");
    }
    std::string out;
    for (int i = 0; i < max_tokens; i++) {
        llama_token token = common_sampler_sample(sampler, ctx, -1);
        common_sampler_accept(sampler, token, true);
        if (llama_vocab_is_eog(vocab, token)) break;
        out += common_token_to_piece(ctx, token);
        if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) break;
    }
    return bytes(env, out);
}
