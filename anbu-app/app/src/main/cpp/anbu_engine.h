// llama.cpp wrapper. Every llama_* symbol in the project lives in anbu_engine.cpp
// so an upstream API change is a one-file fix.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <string_view>

namespace anbu {

struct GenParams {
    int   n_ctx          = 4096;   // PRD RAM budget assumes 4096
    int   n_batch        = 512;
    int   n_threads      = 0;      // 0 = derive from CPU count, capped at 6
    int   max_tokens     = 1024;
    float temp           = 0.7f;
    int   top_k          = 40;
    float top_p          = 0.95f;
    float min_p          = 0.05f;
    float repeat_penalty = 1.10f;
    int   repeat_last_n  = 256;
};

// Returns false from the callback to abort mid-generation.
using TokenSink = std::function<bool(std::string_view)>;

class Engine {
public:
    Engine() = default;
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    // mmap=true / mlock=false per PRD: the kernel pages MoE expert weights in and
    // out of the GGUF on UFS instead of pinning ~25GB resident.
    bool load(const std::string& model_path,
              int n_ctx = 4096,
              int n_threads = 0,
              std::string* error = nullptr);

    void unload();
    bool loaded() const { return model_ != nullptr; }

    bool generate(const std::string& prompt,
                  const GenParams& params,
                  const TokenSink& on_token,
                  std::string* error);

    // Wraps a system/user exchange in the model's own chat template.
    // Returns false when the GGUF carries no template (or the engine is not
    // loaded), so callers can fall back to a plain prompt.
    bool format_chat(const std::string& system, const std::string& user, std::string* out);

    // Safe to call from any thread; observed by the generation loop.
    void request_stop() { stop_.store(true, std::memory_order_relaxed); }
    bool stopping() const { return stop_.load(std::memory_order_relaxed); }

    // "model=... ctx=... threads=... n_params=... size_mb=..."
    std::string describe() const;
    int n_ctx() const { return n_ctx_; }

private:
    mutable std::mutex mu_;   // serialises load/unload/generate
    void unload_locked();     // teardown for callers already holding mu_
    void* model_ = nullptr;   // llama_model*
    void* ctx_   = nullptr;   // llama_context*
    int   n_ctx_ = 0;
    int   n_threads_ = 0;
    std::atomic<bool> stop_{false};
    std::string model_path_;
    std::string chat_template_;   // from the GGUF; empty = model has none
};

}  // namespace anbu
