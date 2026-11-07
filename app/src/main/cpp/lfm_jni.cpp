// JNI bridge: LFM3-Omni (GGUF + mmproj) on llama.cpp / libmtmd.
// Text, image and audio in -> streamed text out. Conversation state lives here.
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"

#define TAG "lfm3omni"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct State {
    llama_model *model = nullptr;
    llama_context *lctx = nullptr;
    mtmd_context *mctx = nullptr;
    common_sampler *smpl = nullptr;
    common_chat_templates_ptr tmpls;
    std::vector<common_chat_msg> history;
    llama_pos n_past = 0;
    int n_ctx = 0;
    int n_batch = 1024;
    std::atomic<bool> stop{false};
};

State g;

void android_log_cb(ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
}

void clear_conversation() {
    g.history.clear();
    g.n_past = 0;
    if (g.lctx) llama_memory_clear(llama_get_memory(g.lctx), true);
    if (g.smpl) common_sampler_reset(g.smpl);
}

void unload_all() {
    if (g.smpl) { common_sampler_free(g.smpl); g.smpl = nullptr; }
    if (g.mctx) { mtmd_free(g.mctx); g.mctx = nullptr; }
    if (g.lctx) { llama_free(g.lctx); g.lctx = nullptr; }
    if (g.model) { llama_model_free(g.model); g.model = nullptr; }
    g.tmpls.reset();
    g.history.clear();
    g.n_past = 0;
}

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 character.
size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4 && ((unsigned char) s[i - 1] & 0xC0) == 0x80) { i--; back++; }
    if (i == 0) return n;
    unsigned char lead = (unsigned char) s[i - 1];
    size_t need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    return (back + 1 >= (int) need) ? n : i - 1;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_hobby_lfm3omni_LfmEngine_nativeInit(JNIEnv *env, jobject, jstring libDir) {
    llama_log_set(android_log_cb, nullptr);
    mtmd_helper_log_set(android_log_cb, nullptr);
    ggml_backend_load_all_from_path(jstr(env, libDir).c_str());
    llama_backend_init();
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_hobby_lfm3omni_LfmEngine_nativeLoad(JNIEnv *env, jobject, jstring jmodel, jstring jmmproj,
                                             jint nCtx, jint nThreads) {
    unload_all();
    const std::string model_path = jstr(env, jmodel), mmproj_path = jstr(env, jmmproj);

    llama_model_params mp = llama_model_default_params();
    g.model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!g.model) { LOGe("failed to load model"); return 1; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = g.n_batch;
    cp.n_ubatch = g.n_batch;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    g.lctx = llama_init_from_model(g.model, cp);
    if (!g.lctx) { LOGe("failed to create context"); return 2; }
    g.n_ctx = (int) llama_n_ctx(g.lctx);
    // Lets Stop abort a decode that is in progress (e.g. a long prompt / image batch).
    llama_set_abort_callback(g.lctx, [](void *) { return g.stop.load(); }, nullptr);

    mtmd_context_params mcp = mtmd_context_params_default();
    mcp.use_gpu = false;
    mcp.n_threads = nThreads;
    mcp.print_timings = false;
    mcp.warmup = false;
    g.mctx = mtmd_init_from_file(mmproj_path.c_str(), g.model, mcp);
    if (!g.mctx) { LOGe("failed to load mmproj"); return 3; }

    common_params_sampling sp;
    sp.temp = 0.0f;  // greedy, as recommended by the model card
    g.smpl = common_sampler_init(g.model, sp);
    g.tmpls = common_chat_templates_init(g.model, "");
    g.history.clear();
    g.n_past = 0;
    LOGi("loaded: n_ctx=%d vision=%d audio=%d", g.n_ctx, mtmd_support_vision(g.mctx), mtmd_support_audio(g.mctx));
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_hobby_lfm3omni_LfmEngine_nativeUnload(JNIEnv *, jobject) { unload_all(); }

extern "C" JNIEXPORT void JNICALL
Java_dev_hobby_lfm3omni_LfmEngine_nativeReset(JNIEnv *, jobject) { clear_conversation(); }

extern "C" JNIEXPORT void JNICALL
Java_dev_hobby_lfm3omni_LfmEngine_nativeStop(JNIEnv *, jobject) { g.stop = true; }

// returns 0 ok, >0 error codes
extern "C" JNIEXPORT jint JNICALL
Java_dev_hobby_lfm3omni_LfmEngine_nativeGenerate(JNIEnv *env, jobject, jstring jsystem, jstring jtext,
                                                 jobjectArray jmedia, jint maxTokens, jobject cb) {
    if (!g.model || !g.lctx || !g.mctx) return 10;
    g.stop = false;

    jclass cbCls = env->GetObjectClass(cb);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)V");

    const std::string system = jstr(env, jsystem);
    const std::string text = jstr(env, jtext);
    const int nMedia = jmedia ? env->GetArrayLength(jmedia) : 0;

    // Load media (image / audio files) into bitmaps.
    mtmd::bitmaps bitmaps;
    const mtmd_helper_init_opt opt = mtmd_helper_init_opt_default();
    for (int i = 0; i < nMedia; i++) {
        auto path = jstr(env, (jstring) env->GetObjectArrayElement(jmedia, i));
        auto res = mtmd_helper_bitmap_init_from_file(g.mctx, path.c_str(), false, opt);
        if (!res.bitmap) { LOGe("cannot load media %s", path.c_str()); return 11; }
        bitmaps.entries.emplace_back(res.bitmap);
        if (res.video_ctx) mtmd_helper_video_free(res.video_ctx);
    }

    // Build the user message: one marker per media item, then the text.
    std::string content;
    for (int i = 0; i < nMedia; i++) content += mtmd_default_marker();
    content += text;

    // Start over if the context is nearly full (history is simply dropped).
    if (g.n_past > g.n_ctx * 3 / 4) { LOGi("context nearly full, resetting"); clear_conversation(); }

    common_chat_msg user;
    user.role = "user";
    user.content = content;

    std::string formatted;
    if (g.history.empty() && !system.empty()) {
        common_chat_msg sys;
        sys.role = "system";
        sys.content = system;
        formatted = common_chat_format_single(g.tmpls.get(), g.history, sys, false, true);
        g.history.push_back(sys);
    }
    formatted += common_chat_format_single(g.tmpls.get(), g.history, user, true, true);
    g.history.push_back(user);

    mtmd_input_text it{formatted.c_str(), formatted.size(), /*add_special*/ g.n_past == 0, /*parse_special*/ true};
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    auto bptr = bitmaps.c_ptr();
    if (mtmd_tokenize(g.mctx, chunks.ptr.get(), &it, bptr.data(), bptr.size()) != 0) {
        LOGe("tokenize failed");
        g.history.pop_back();
        return 12;
    }

    // Evaluate chunk by chunk so Stop can interrupt a long image/audio encode.
    llama_pos new_past = g.n_past;
    const size_t nChunks = mtmd_input_chunks_size(chunks.ptr.get());
    for (size_t i = 0; i < nChunks; i++) {
        if (g.stop) { clear_conversation(); return 15; }
        const bool last = i + 1 == nChunks;
        if (mtmd_helper_eval_chunk_single(g.mctx, g.lctx, mtmd_input_chunks_get(chunks.ptr.get(), i),
                                          new_past, 0, g.n_batch, last, &new_past) != 0) {
            if (g.stop) { clear_conversation(); return 15; }
            LOGe("eval failed at chunk %zu/%zu", i, nChunks);
            clear_conversation();
            return 13;
        }
    }
    g.n_past = new_past;

    // Generation loop.
    const llama_vocab *vocab = llama_model_get_vocab(g.model);
    common_batch batch(g.lctx);
    std::string answer, pending;
    int rc = 0;
    for (int i = 0; i < maxTokens && !g.stop; i++) {
        if (g.n_past + 4 >= g.n_ctx) break;
        llama_token tok = common_sampler_sample(g.smpl, g.lctx, -1);
        common_sampler_accept(g.smpl, tok, true);
        if (llama_vocab_is_eog(vocab, tok)) break;

        pending += common_token_to_piece(g.lctx, tok);
        size_t n = utf8_complete_prefix(pending);
        if (n > 0) {
            std::string out = pending.substr(0, n);
            pending.erase(0, n);
            answer += out;
            jstring js = env->NewStringUTF(out.c_str());
            env->CallVoidMethod(cb, onToken, js);
            env->DeleteLocalRef(js);
        }

        batch.clear();
        batch.add(tok, g.n_past++, 0, true);
        if (llama_process(g.lctx, LLAMA_PROCESS_TYPE_DECODE, batch.get())) { rc = g.stop ? 0 : 14; break; }
    }

    common_chat_msg asst;
    asst.role = "assistant";
    asst.content = answer;
    g.history.push_back(asst);
    return rc;
}
