#include "anbu_engine.h"

#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <thread>
#include <vector>

#include "llama.h"

#define LOG_TAG "anbu-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// llama.cpp's C API is NOT stable across revisions. Every call site is in this
// file. If you bump ANBU_LLAMA_CPP_TAG and the build breaks, the renames to
// look for are:
//   llama_load_model_from_file      -> llama_model_load_from_file
//   llama_new_context_with_model    -> llama_init_from_model
//   llama_free_model                -> llama_model_free
//   llama_kv_cache_clear            -> llama_memory_clear(llama_get_memory(ctx))
//   llama_n_ctx / llama_n_vocab     -> llama_model_* / llama_vocab_*
//   llama_batch_get_one(t,n,pos,seq) -> llama_batch_get_one(t,n)   [2-arg only]
//   llama_sampler_init_penalties(last_n, rep, f, p)
//                                   -> llama_sampler_init_penalties(n_vocab, last_n, rep, f, p)
//   params.use_mmap / params.use_mlock
//                                   -> params.load_mode = LLAMA_LOAD_MODE_MMAP
// ---------------------------------------------------------------------------

namespace anbu {
namespace {

std::atomic<bool> g_backend_inited{false};

void ensure_backend() {
    bool expected = false;
    if (g_backend_inited.compare_exchange_strong(expected, true)) {
        llama_backend_init();
        LOGI("llama backend initialised");
    }
}

int resolve_threads(int requested) {
    if (requested > 0) return requested;
    const unsigned hw = std::thread::hardware_concurrency();
    // Cap at 6: attention on 7B-active MoE saturates big cores well before the
    // little cores pay for themselves, and more threads means more scratch RAM.
    return std::max(1, std::min(6, static_cast<int>(hw ? hw : 4)));
}

}  // namespace

Engine::~Engine() {
    unload();
    if (g_backend_inited.load()) {
        llama_backend_free();
        g_backend_inited.store(false);
    }
}

bool Engine::load(const std::string& model_path, int n_ctx, int n_threads, std::string* error) {
    std::lock_guard<std::mutex> lock(mu_);
    unload_locked();   // mu_ is not recursive — never call unload() from here

    ensure_backend();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU-only build, no GPU backend linked
    // PRD: mmap=true, mlock=false. Upstream replaced the two bools with one enum;
    // MMAP is exactly "map it and never pin it", so the kernel pages MoE expert
    // weights in and out of the UFS copy on demand.
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;

    LOGI("loading model: %s (n_ctx=%d mmap=1 mlock=0)", model_path.c_str(), n_ctx);

    llama_model* model = llama_model_load_from_file(model_path.c_str(), mp);
    if (model == nullptr) {
        const std::string msg = "llama_model_load_from_file failed: " + model_path;
        LOGE("%s", msg.c_str());
        if (error) *error = msg;
        return false;
    }

    const int threads = resolve_threads(n_threads);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(n_ctx);
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        const std::string msg = "llama_init_from_model failed (n_ctx too large for device RAM?)";
        LOGE("%s", msg.c_str());
        if (error) *error = msg;
        return false;
    }

    model_       = model;
    ctx_         = ctx;
    n_ctx_       = n_ctx;
    n_threads_   = threads;
    model_path_  = model_path;

    // The GGUF carries its own conversation format. Without it an instruct
    // model sees raw prose and answers noticeably worse.
    const char* tmpl = llama_model_chat_template(model, nullptr);
    chat_template_ = (tmpl != nullptr) ? tmpl : "";
    LOGI("model ready: threads=%d chat_template=%s", threads,
         chat_template_.empty() ? "none" : "yes");
    return true;
}

bool Engine::format_chat(const std::string& system, const std::string& user, std::string* out) {
    std::lock_guard<std::mutex> lock(mu_);
    if (out == nullptr || model_ == nullptr || chat_template_.empty()) return false;

    std::vector<llama_chat_message> msgs;
    if (!system.empty()) msgs.push_back({"system", system.c_str()});
    msgs.push_back({"user", user.c_str()});

    // Upstream recommends 2x the summed message length; grow once and retry if
    // a template adds more than that.
    std::string buf(std::max<std::size_t>(512, (system.size() + user.size()) * 2 + 64), '\0');
    int32_t n = llama_chat_apply_template(chat_template_.c_str(), msgs.data(), msgs.size(),
                                          /*add_ass=*/true, buf.data(),
                                          static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<std::size_t>(n));
        n = llama_chat_apply_template(chat_template_.c_str(), msgs.data(), msgs.size(),
                                      /*add_ass=*/true, buf.data(),
                                      static_cast<int32_t>(buf.size()));
    }
    if (n <= 0) return false;

    out->assign(buf.data(), static_cast<std::size_t>(n));
    return true;
}

void Engine::unload_locked() {
    // Context first: it holds a back-pointer to the model.
    if (ctx_) {
        llama_free(static_cast<llama_context*>(ctx_));
        ctx_ = nullptr;
    }
    if (model_) {
        llama_model_free(static_cast<llama_model*>(model_));
        model_ = nullptr;
    }
    n_ctx_ = 0;
    n_threads_ = 0;
    model_path_.clear();
    chat_template_.clear();
}

void Engine::unload() {
    std::lock_guard<std::mutex> lock(mu_);
    unload_locked();
}

bool Engine::generate(const std::string& prompt,
                      const GenParams& params,
                      const TokenSink& on_token,
                      std::string* error) {
    std::lock_guard<std::mutex> lock(mu_);
    if (ctx_ == nullptr || model_ == nullptr) {
        if (error) *error = "engine not loaded";
        return false;
    }

    stop_.store(false, std::memory_order_relaxed);

    auto* ctx   = static_cast<llama_context*>(ctx_);
    auto* model = static_cast<llama_model*>(model_);
    const llama_vocab* vocab = llama_model_get_vocab(model);

    // ---- tokenise ----------------------------------------------------------
    const int32_t text_len = static_cast<int32_t>(prompt.size());
    int32_t n_prompt = -llama_tokenize(vocab, prompt.data(), text_len, nullptr, 0, true, true);
    if (n_prompt <= 0) {
        if (error) *error = "tokenisation produced no tokens";
        return false;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(vocab, prompt.data(), text_len, tokens.data(), n_prompt, true, true) < 0) {
        if (error) *error = "llama_tokenize failed";
        return false;
    }
    if (n_prompt >= n_ctx_ - 8) {
        if (error) {
            *error = "prompt uses " + std::to_string(n_prompt) + " of " +
                     std::to_string(n_ctx_) + " context tokens; truncate the RAG context";
        }
        return false;
    }

    // ---- fresh KV for this turn -------------------------------------------
    // Positions are tracked by llama_decode itself: llama_batch_get_one() leaves
    // batch.pos == nullptr, and llama_decode() then continues from the memory's
    // per-sequence max position. Clearing the memory resets that to 0, so no
    // manual n_past bookkeeping is needed anywhere below.
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem == nullptr) {
        if (error) *error = "context exposes no KV memory module";
        return false;
    }
    llama_memory_clear(mem, true);

    // ---- prompt eval, chunked to n_batch ----------------------------------
    // n_batch cannot exceed the n_batch the context was created with.
    const int32_t n_batch = std::min<int32_t>(512, params.n_batch > 0 ? params.n_batch : 512);
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        const int32_t n = std::min<int32_t>(n_batch, n_prompt - i);
        if (llama_decode(ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            if (error) *error = "llama_decode failed during prompt eval";
            return false;
        }
        // Stop during prompt eval is the common case on an mmap'd 25GB MoE —
        // prompt eval is the long phase. Break, don't fail: the decode loop
        // below sees the same flag and exits with ok=true, so the user gets a
        // clean stop instead of "generation failed".
        if (stopping()) break;
    }

    // ---- sampler chain -----------------------------------------------------
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler* smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        llama_vocab_n_tokens(vocab), params.repeat_last_n,
        params.repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(params.top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(params.top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(params.min_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(params.temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ---- decode loop -------------------------------------------------------
    bool ok = true;
    int produced = 0;
    int n_used = n_prompt;   // prompt already occupies positions [0, n_prompt)
    for (; produced < params.max_tokens; ++produced) {
        if (stopping()) break;

        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        llama_sampler_accept(smpl, id);

        char stack_buf[256];
        int32_t n = llama_token_to_piece(vocab, id, stack_buf,
                                         static_cast<int32_t>(sizeof(stack_buf)), 0, false);
        std::string piece;
        if (n >= 0) {
            piece.assign(stack_buf, static_cast<size_t>(n));
        } else {
            // Negative return is the required buffer size (multi-byte CJK pieces).
            piece.resize(static_cast<size_t>(-n));
            n = llama_token_to_piece(vocab, id, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
            if (n < 0) { ok = false; if (error) *error = "llama_token_to_piece failed"; break; }
            piece.resize(static_cast<size_t>(n));
        }

        if (!on_token(piece)) break;   // consumer aborted

        if (llama_decode(ctx, llama_batch_get_one(&id, 1)) != 0) {
            ok = false;
            if (error) *error = "llama_decode failed during generation";
            break;
        }
        ++n_used;

        if (n_used >= n_ctx_ - 1) {
            LOGI("context window exhausted at %d tokens", n_used);
            break;
        }
    }

    llama_sampler_free(smpl);
    LOGI("generation finished: produced=%d ctx_pos=%d", produced, n_used);
    return ok;
}

std::string Engine::describe() const {
    if (model_ == nullptr) return "engine=unloaded";
    auto* model = static_cast<llama_model*>(model_);
    char desc[256] = {0};
    llama_model_desc(model, desc, sizeof(desc));
    char buf[512];
    std::snprintf(buf, sizeof(buf),
                  "model=%s n_params=%llu size_mb=%llu n_ctx=%d threads=%d mmap=1 mlock=0",
                  desc,
                  static_cast<unsigned long long>(llama_model_n_params(model)),
                  static_cast<unsigned long long>(llama_model_size(model) / (1024ull * 1024ull)),
                  n_ctx_,
                  n_threads_);
    return std::string(buf);
}

}  // namespace anbu
