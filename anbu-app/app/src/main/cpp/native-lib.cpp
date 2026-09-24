// JNI surface. Kotlin talks to C++ only through this file.
//
//   initEngine(modelPath, dbPath, indexPath) -> Boolean   [PRD Phase 2]
//   searchContext(query, topK)               -> String    [JSON array]
//   streamInference(prompt, callback)        -> Unit      [token-by-token]
//
// Kotlin calls streamInference from Dispatchers.IO; the callback is therefore
// fired on that same JVM thread for the whole generation. No thread is spawned
// here, so no AttachCurrentThread dance is needed — but we still guard it,
// because a future caller may hand us a detached thread.
#include <jni.h>

#include <android/log.h>

#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "anbu_engine.h"
#include "anbu_json.h"
#include "anbu_rag.h"
#include "anbu_text.h"

#define LOG_TAG "anbu-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;

anbu::Engine   g_engine;
anbu::RagStore g_rag;
std::mutex     g_init_mu;
std::string    g_last_error = "engine not initialised";

// Generation is serialised by Engine's own mutex; this flag lets the UI get a
// clean "busy" error instead of blocking a second request behind the first.
std::mutex     g_gen_mu;

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    // GetStringUTFChars gives *modified* UTF-8, which mangles astral codepoints.
    // Read real UTF-16 and decode to standard UTF-8 ourselves.
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return {};
    const jsize len = env->GetStringLength(s);
    std::string out = anbu::utf16_to_utf8(
        std::u16string_view(reinterpret_cast<const char16_t*>(chars), static_cast<std::size_t>(len)));
    env->ReleaseStringChars(s, chars);
    return out;
}

// UTF-16 in, jstring out. Never NewStringUTF: llama.cpp emits standard UTF-8 and
// JNI's modified UTF-8 cannot carry astral codepoints.
jstring to_jstring(JNIEnv* env, const std::u16string& s) {
    return env->NewString(reinterpret_cast<const jchar*>(s.data()),
                          static_cast<jsize>(s.size()));
}

// Fires the Kotlin callback on whichever thread is running the generation.
class Callback {
public:
    Callback(JNIEnv* env, jobject obj) {
        if (env == nullptr || obj == nullptr) return;
        // Local refs are thread-scoped; keep a global ref so emit() can fire
        // from a detached worker thread if a future caller uses one.
        jobject g = env->NewGlobalRef(obj);
        if (g == nullptr) return;
        obj_ = g;
        jclass cls = env->GetObjectClass(g);
        on_token_ = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        on_done_  = env->GetMethodID(cls, "onComplete", "(Ljava/lang/String;)V");
        on_error_ = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
        valid_ = on_token_ != nullptr && on_done_ != nullptr && on_error_ != nullptr;
    }

    ~Callback() {
        if (obj_ != nullptr && g_vm != nullptr) {
            void* raw = nullptr;
            if (g_vm->GetEnv(&raw, JNI_VERSION_1_6) == JNI_OK) {
                static_cast<JNIEnv*>(raw)->DeleteGlobalRef(obj_);
            }
        }
    }

    bool valid() const { return valid_; }

    void token(const std::string& utf8) {
        if (!valid_ || utf8.empty()) return;
        // A token can end mid-codepoint; hold the tail until the next token
        // completes it, otherwise the UI renders replacement garbage.
        pending_ += utf8;
        const std::size_t cut = anbu::utf8_complete_prefix(pending_);
        if (cut == 0) return;
        emit(on_token_, anbu::utf8_to_utf16(pending_.substr(0, cut)));
        pending_.erase(0, cut);
    }

    void complete(const std::string& stats) {
        if (!valid_) return;
        if (!pending_.empty()) {   // flush a dangling partial sequence
            emit(on_token_, anbu::utf8_to_utf16(pending_));
            pending_.clear();
        }
        emit(on_done_, anbu::utf8_to_utf16(stats));
    }

    void error(const std::string& message) {
        if (!valid_) return;
        emit(on_error_, anbu::utf8_to_utf16(message));
    }

private:
    void emit(jmethodID mid, const std::u16string& text) {
        if (g_vm == nullptr) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        void* raw = nullptr;
        if (g_vm->GetEnv(&raw, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            attached = true;
        } else {
            env = static_cast<JNIEnv*>(raw);
        }
        if (env == nullptr) return;
        jstring s = to_jstring(env, text);
        if (s != nullptr) {
            env->CallVoidMethod(obj_, mid, s);
            env->DeleteLocalRef(s);
        }
        if (env->ExceptionCheck()) {   // never let a Kotlin throw unwind into C++
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (attached) g_vm->DetachCurrentThread();
    }

    jobject obj_ = nullptr;
    jmethodID on_token_ = nullptr;
    jmethodID on_done_  = nullptr;
    jmethodID on_error_ = nullptr;
    bool valid_ = false;
    std::string pending_;
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_anbu_research_core_NativeLib_initEngine(
        JNIEnv* env, jobject /*thiz*/,
        jstring modelPath, jstring dbPath, jstring indexPath,
        jint nCtx, jint nThreads) {

    const std::string model = jstr(env, modelPath);
    const std::string db    = jstr(env, dbPath);
    const std::string index = jstr(env, indexPath);

    std::lock_guard<std::mutex> lock(g_init_mu);
    g_engine.unload();
    g_rag.close();

    std::string error;

    // Retrieval first: a missing corpus should fail fast, before we spend
    // 30+ seconds paging 25GB of expert weights in off UFS.
    if (!db.empty() || !index.empty()) {
        if (!g_rag.open(db, index, &error)) {
            g_last_error = "RAG: " + error;
            LOGE("initEngine failed: %s", g_last_error.c_str());
            return JNI_FALSE;
        }
    }

    if (!g_engine.load(model, nCtx, nThreads, &error)) {
        g_last_error = "LLM: " + error;
        LOGE("initEngine failed: %s", g_last_error.c_str());
        return JNI_FALSE;
    }

    g_last_error.clear();
    LOGI("initEngine ok: %s", g_engine.describe().c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anbu_research_core_NativeLib_lastError(JNIEnv* env, jobject /*thiz*/) {
    return to_jstring(env, anbu::utf8_to_utf16(g_last_error));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anbu_research_core_NativeLib_systemInfo(JNIEnv* env, jobject /*thiz*/) {
    std::string info = g_engine.describe();
    info += " | index_vectors=" + std::to_string(g_rag.index_size());
    info += " | dims=" + std::to_string(g_rag.dims());
    info += " | fts_table=" + std::string(g_rag.db_ready() ? "yes" : "no");
    info += " | embedder=" + std::string(anbu::RagStore::has_embedder() ? "installed" : "none");
    return to_jstring(env, anbu::utf8_to_utf16(info));
}

// Returns a JSON array of retrieved chunks: [{id,title,source,snippet,score}, ...]
extern "C" JNIEXPORT jstring JNICALL
Java_com_anbu_research_core_NativeLib_searchContext(
        JNIEnv* env, jobject /*thiz*/, jstring query, jint topK) {

    const std::string q = jstr(env, query);
    std::string error;
    const auto hits = g_rag.search(q, static_cast<int>(topK), &error);

    if (!error.empty()) g_last_error = error;

    std::string json = "[";
    for (std::size_t i = 0; i < hits.size(); ++i) {
        if (i) json += ",";
        json += "{\"id\":" + std::to_string(hits[i].chunk_id);
        json += ",\"title\":"  + anbu::json_string(hits[i].title);
        json += ",\"source\":" + anbu::json_string(hits[i].source);
        json += ",\"snippet\":" + anbu::json_string(hits[i].snippet);
        char score[32];
        std::snprintf(score, sizeof(score), "%.6f", hits[i].score);
        json += ",\"score\":" + std::string(score) + "}";
    }
    json += "]";
    return to_jstring(env, anbu::utf8_to_utf16(json));
}

extern "C" JNIEXPORT void JNICALL
Java_com_anbu_research_core_NativeLib_streamInference(
        JNIEnv* env, jobject /*thiz*/, jstring prompt, jobject callback) {

    Callback cb(env, callback);
    if (!cb.valid()) {
        LOGE("streamInference: callback does not implement TokenCallback");
        return;
    }
    if (env->PushLocalFrame(32) != 0) return;

    if (!g_engine.loaded()) {
        cb.error("engine not loaded — initEngine has not succeeded");
        env->PopLocalFrame(nullptr);
        return;
    }

    // Blocking, not try_to_lock: a query arriving while another is running must
    // wait its turn. Erroring out used to surface as a UI failure even though
    // nothing was actually wrong.
    std::lock_guard<std::mutex> lock(g_gen_mu);

    const std::string p = jstr(env, prompt);
    anbu::GenParams params;

    std::string error;
    const bool ok = g_engine.generate(p, params, [&cb](std::string_view piece) {
        cb.token(std::string(piece));
        return true;
    }, &error);

    if (ok) {
        cb.complete(g_engine.describe());
    } else {
        cb.error(error.empty() ? "generation failed" : error);
    }
    env->PopLocalFrame(nullptr);
}

// Wraps the system instruction + retrieved context in the model's own chat
// template. Falls back to plain concatenation when the GGUF carries none, so
// the Kotlin side always has exactly one prompt path.
extern "C" JNIEXPORT jstring JNICALL
Java_com_anbu_research_core_NativeLib_formatChat(
        JNIEnv* env, jobject /*thiz*/, jstring systemPrompt, jstring userPrompt) {

    const std::string sys  = jstr(env, systemPrompt);
    const std::string user = jstr(env, userPrompt);

    std::string out;
    if (!g_engine.format_chat(sys, user, &out)) {
        out = sys;
        if (!out.empty() && !user.empty()) out += "\n\n";
        out += user;
    }
    return to_jstring(env, anbu::utf8_to_utf16(out));
}

extern "C" JNIEXPORT void JNICALL
Java_com_anbu_research_core_NativeLib_stopInference(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_engine.request_stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_anbu_research_core_NativeLib_shutdown(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_init_mu);
    g_engine.request_stop();
    g_engine.unload();
    g_rag.close();
    g_last_error = "engine unloaded";
}
