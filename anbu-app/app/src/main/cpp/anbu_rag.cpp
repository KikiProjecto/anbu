#include "anbu_rag.h"

#include <android/log.h>
#include <sqlite3.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <unordered_map>

#include "anbu_text.h"

#if ANBU_HAVE_USEARCH
#  if __has_include(<usearch/index_dense.hpp>)
#    include <usearch/index_dense.hpp>
#    define ANBU_USEARCH_HEADER 1
#  elif __has_include(<usearch/usearch.hpp>)
#    include <usearch/usearch.hpp>
#    define ANBU_USEARCH_HEADER 1
#  endif
#endif

#if !defined(ANBU_USEARCH_HEADER)
#  undef ANBU_HAVE_USEARCH
#  define ANBU_HAVE_USEARCH 0
#endif

#define LOG_TAG "anbu-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace anbu {
namespace {

// Reciprocal Rank Fusion constant. 60 is the value from the original RRF paper;
// it flattens the head of each list enough that neither retriever dominates.
constexpr double kRrfK = 60.0;

constexpr const char* kSchemaError =
    "knowledge base schema not found — expected tables 'chunks' and FTS5 table "
    "'chunks_fts' (rowid = chunks.id) plus 'documents'. Regenerate with the Phase-1 pipeline.";

EmbedFn    g_embed_fn   = nullptr;
void*      g_embed_user = nullptr;
int        g_embed_dims = 0;

// ponytail: placeholder embedder — deterministic hashing, zero dependencies.
// It makes the whole init/retrieve/generate path testable today, but retrieval
// quality is lexical-grade. Replace with all-MiniLM-L6-v2 INT8 via ONNX Runtime
// (or the TFLite build) by calling set_embedder() before initEngine; nothing
// else has to change.
bool hash_embed(const char* text, int dims, float* out, void* /*user*/) {
    if (text == nullptr || dims <= 0) return false;
    std::memset(out, 0, sizeof(float) * static_cast<size_t>(dims));

    std::uint64_t h = 1469598103934665603ull;
    bool any = false;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(text);
    auto emit = [&](std::uint64_t token_hash) {
        const auto bucket = static_cast<int>(token_hash % static_cast<std::uint64_t>(dims));
        const float sign = (token_hash >> 63) ? -1.0f : 1.0f;
        out[bucket] += sign;
        any = true;
    };

    for (; *p; ++p) {
        const unsigned char c = *p;
        const bool word = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') ||
                          (c >= 'A' && c <= 'Z') || c >= 0x80;
        if (word) {
            h ^= (c >= 'A' && c <= 'Z') ? (c + 32) : c;
            h *= 1099511628211ull;
        } else if (h != 1469598103934665603ull) {
            emit(h);
            h = 1469598103934665603ull;
        }
    }
    if (h != 1469598103934665603ull) emit(h);
    if (!any) return false;

    float norm = 0.0f;
    for (int i = 0; i < dims; ++i) norm += out[i] * out[i];
    norm = std::sqrt(norm);
    if (norm <= 0.0f) return false;
    for (int i = 0; i < dims; ++i) out[i] /= norm;
    return true;
}

// Must match the string build_knowledge_base.py writes into the corpus `meta`
// table. This build ships exactly one query embedder, so an index authored with
// a different one is unusable — and fails silently, because 384-d MiniLM
// vectors and 384-d hashed vectors are the same shape and same dim count.
constexpr const char* kEmbedderName = "hash";

}  // namespace

// ---------------------------------------------------------------------------

struct RagStore::Impl {
    sqlite3* db = nullptr;
    bool has_fts = false;
    bool has_docs = false;

#if ANBU_HAVE_USEARCH
    unum::usearch::index_dense_t index;
    bool index_loaded = false;
#endif
};

RagStore::RagStore() = default;
RagStore::~RagStore() { close(); }

void RagStore::set_embedder(EmbedFn fn, void* user, int dims) {
    g_embed_fn   = fn;
    g_embed_user = user;
    g_embed_dims = dims;
    if (g_embed_fn == nullptr) {
        g_embed_fn   = &hash_embed;
        g_embed_dims = dims > 0 ? dims : 384;   // all-MiniLM-L6-v2 width
    }
}

bool RagStore::has_embedder() { return g_embed_fn != nullptr; }

bool RagStore::open(const std::string& db_path, const std::string& index_path, std::string* error) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!impl_) impl_ = std::make_unique<Impl>();

    if (g_embed_fn == nullptr) set_embedder(nullptr, nullptr, 0);

    // ---- SQLite: strictly read-only, the corpus is authored on the PC -------
    if (!db_path.empty()) {
        const int flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX;
        if (sqlite3_open_v2(db_path.c_str(), &impl_->db, flags, nullptr) != SQLITE_OK) {
            const std::string msg = std::string("cannot open knowledge base: ") +
                                    (impl_->db ? sqlite3_errmsg(impl_->db) : "unknown");
            if (error) *error = msg;
            LOGE("%s", msg.c_str());
            close_locked();   // open() already holds mu_
            return false;
        }
        sqlite3_busy_timeout(impl_->db, 3000);
        sqlite3_exec(impl_->db, "PRAGMA query_only=1;", nullptr, nullptr, nullptr);

        auto table_exists = [&](const char* name) {
            sqlite3_stmt* st = nullptr;
            bool found = false;
            if (sqlite3_prepare_v2(impl_->db,
                    "SELECT 1 FROM sqlite_master WHERE name=?1 LIMIT 1;", -1, &st, nullptr) == SQLITE_OK) {
                sqlite3_bind_text(st, 1, name, -1, SQLITE_STATIC);
                found = sqlite3_step(st) == SQLITE_ROW;
            }
            sqlite3_finalize(st);
            return found;
        };
        impl_->has_fts  = table_exists("chunks_fts");
        impl_->has_docs = table_exists("documents");
        if (!impl_->has_fts) {
            if (error) *error = kSchemaError;
            LOGE("knowledge base opened but chunks_fts is missing");
            return false;   // db handle stays open; caller sees the message
        }

        // Embedder identity: a mismatched corpus is worse than a missing one,
        // because the FTS leg still returns good hits and hides the fact that
        // every vector result is noise. Older corpora predate the meta table —
        // absent means "unknown", not "wrong", so they still open.
        auto meta_value = [&](const char* key) -> std::string {
            std::string value;
            sqlite3_stmt* st = nullptr;
            if (sqlite3_prepare_v2(impl_->db, "SELECT value FROM meta WHERE key=?1 LIMIT 1;",
                                   -1, &st, nullptr) == SQLITE_OK) {
                sqlite3_bind_text(st, 1, key, -1, SQLITE_STATIC);
                if (sqlite3_step(st) == SQLITE_ROW) {
                    const unsigned char* t = sqlite3_column_text(st, 0);
                    if (t) value.assign(reinterpret_cast<const char*>(t));
                }
            }
            sqlite3_finalize(st);
            return value;
        };
        const std::string author = meta_value("embedder");
        if (!author.empty() && author != kEmbedderName) {
            const std::string msg = std::string("knowledge base was built with the '") + author +
                "' embedder but this build queries with '" + kEmbedderName +
                "' — the vector results would be noise. Rebuild with --embedder " +
                kEmbedderName + ".";
            if (error) *error = msg;
            LOGE("%s", msg.c_str());
            return false;
        }
        LOGI("knowledge base ready (documents=%d embedder=%s)", impl_->has_docs ? 1 : 0,
             author.empty() ? "unknown" : author.c_str());
    }

    // ---- USearch ------------------------------------------------------------
#if ANBU_HAVE_USEARCH
    if (!index_path.empty()) {
        auto result = impl_->index.load(index_path.c_str());
        if (!result) {
            const std::string msg = std::string("cannot load vector index: ") +
                                    result.error.what();
            if (error) *error = msg;
            LOGE("%s", msg.c_str());
            return false;
        }
        impl_->index_loaded = true;
        dims_ = static_cast<int>(impl_->index.dimensions());
        LOGI("vector index ready: %zu vectors, %d dims", impl_->index.size(), dims_);
    } else {
        dims_ = g_embed_dims;
    }
#else
    if (!index_path.empty()) {
        LOGI("usearch not linked — ignoring vector index %s", index_path.c_str());
    }
    dims_ = g_embed_dims;
#endif

    if (impl_->db == nullptr
#if ANBU_HAVE_USEARCH
        && !impl_->index_loaded
#endif
    ) {
        if (error) *error = "initEngine: neither dbPath nor indexPath yielded a usable store";
        return false;
    }
    return true;
}

void RagStore::close() {
    std::lock_guard<std::mutex> lock(mu_);
    close_locked();
}

void RagStore::close_locked() {
    if (!impl_) return;
    if (impl_->db) {
        sqlite3_close(impl_->db);
        impl_->db = nullptr;
    }
#if ANBU_HAVE_USEARCH
    impl_->index_loaded = false;
#endif
    impl_.reset();
}

bool RagStore::db_ready() const { return impl_ && impl_->db != nullptr; }

bool RagStore::index_ready() const {
#if ANBU_HAVE_USEARCH
    return impl_ && impl_->index_loaded;
#else
    return false;
#endif
}

std::size_t RagStore::index_size() const {
#if ANBU_HAVE_USEARCH
    if (impl_ && impl_->index_loaded) return impl_->index.size();
#endif
    return 0;
}

namespace {

// FTS5 BM25 leg. Returns chunk ids in rank order plus FTS's own snippet.
struct LexicalHit {
    long long   id;
    std::string snippet;
};

std::vector<LexicalHit> lexical_search(sqlite3* db, const std::string& match_expr, int limit) {
    std::vector<LexicalHit> out;
    if (match_expr.empty()) return out;

    static const char* kSql =
        "SELECT c.id, snippet(chunks_fts, 0, '', '', ' … ', 24) "
        "FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid "
        "WHERE chunks_fts MATCH ?1 ORDER BY bm25(chunks_fts) LIMIT ?2;";

    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db, kSql, -1, &st, nullptr) != SQLITE_OK) {
        LOGE("fts prepare failed: %s", sqlite3_errmsg(db));
        return out;
    }
    sqlite3_bind_text(st, 1, match_expr.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(st, 2, limit);
    while (sqlite3_step(st) == SQLITE_ROW) {
        LexicalHit h;
        h.id = sqlite3_column_int64(st, 0);
        if (const auto* t = sqlite3_column_text(st, 1)) {
            h.snippet.assign(reinterpret_cast<const char*>(t),
                             static_cast<size_t>(sqlite3_column_bytes(st, 1)));
        }
        out.push_back(std::move(h));
    }
    sqlite3_finalize(st);
    return out;
}

}  // namespace

std::vector<Hit> RagStore::search(const std::string& query, int top_k, std::string* error) {
    std::lock_guard<std::mutex> lock(mu_);
    std::vector<Hit> hits;
    if (!impl_) { if (error) *error = "store not open"; return hits; }
    if (top_k <= 0) top_k = 6;

    std::unordered_map<long long, double> fused;
    std::unordered_map<long long, std::string> snippets;

    // Candidate pool is wider than top_k so fusion has room to reorder.
    const int pool = std::max(top_k * 4, 16);

    // ---- leg 1: lexical ----------------------------------------------------
    if (impl_->db) {
        const auto expr = fts_match_expression(query);
        auto lex = lexical_search(impl_->db, expr, pool);
        for (size_t rank = 0; rank < lex.size(); ++rank) {
            fused[lex[rank].id] += 1.0 / (kRrfK + static_cast<double>(rank + 1));
            snippets[lex[rank].id] = std::move(lex[rank].snippet);
        }
    }

    // ---- leg 2: dense ------------------------------------------------------
#if ANBU_HAVE_USEARCH
    if (impl_->index_loaded && g_embed_fn != nullptr && dims_ > 0) {
        std::vector<float> q(static_cast<size_t>(dims_));
        if (g_embed_fn(query.c_str(), dims_, q.data(), g_embed_user)) {
            auto results = impl_->index.search(q.data(), static_cast<std::size_t>(pool));
            const std::size_t n = results.size();
            for (std::size_t rank = 0; rank < n; ++rank) {
                // `member` is a member_cref_gt proxy, not an integer — the key is
                // its `.key` field (misaligned_ref_gt), which converts implicitly.
                const auto key = static_cast<long long>(results[rank].member.key);
                fused[key] += 1.0 / (kRrfK + static_cast<double>(rank + 1));
            }
        }
    }
#endif

    if (fused.empty()) return hits;

    // ---- materialise, rank, trim -------------------------------------------
    std::vector<std::pair<long long, double>> ranked(fused.begin(), fused.end());
    std::sort(ranked.begin(), ranked.end(), [](const auto& a, const auto& b) {
        return a.second > b.second;
    });
    if (static_cast<int>(ranked.size()) > top_k) ranked.resize(static_cast<size_t>(top_k));

    if (impl_->db) {
        static const char* kSql =
            "SELECT c.id, c.doc_id, d.title, d.source, "
            "       substr(replace(c.text, char(10), ' '), 1, 400) "
            "FROM chunks c LEFT JOIN documents d ON d.id = c.doc_id WHERE c.id = ?1;";
        sqlite3_stmt* st = nullptr;
        if (sqlite3_prepare_v2(impl_->db, kSql, -1, &st, nullptr) == SQLITE_OK) {
            for (const auto& [id, score] : ranked) {
                sqlite3_reset(st);
                sqlite3_bind_int64(st, 1, id);
                if (sqlite3_step(st) != SQLITE_ROW) continue;
                Hit h;
                h.chunk_id = id;
                h.score    = score;
                auto text_at = [&](int col) {
                    const auto* t = sqlite3_column_text(st, col);
                    return t ? std::string(reinterpret_cast<const char*>(t),
                                           static_cast<size_t>(sqlite3_column_bytes(st, col)))
                             : std::string();
                };
                h.doc_id  = text_at(1);
                h.title   = text_at(2);
                h.source  = text_at(3);
                const auto it = snippets.find(id);
                h.snippet = (it != snippets.end() && !it->second.empty()) ? it->second : text_at(4);
                hits.push_back(std::move(h));
            }
        } else {
            LOGE("chunk fetch prepare failed: %s", sqlite3_errmsg(impl_->db));
        }
        sqlite3_finalize(st);
    }
    return hits;
}

std::string RagStore::build_context(const std::vector<Hit>& hits, std::size_t max_chars) {
    std::string out;
    int n = 0;
    for (const auto& h : hits) {
        std::string entry = "[" + std::to_string(++n) + "] ";
        if (!h.title.empty())  entry += h.title;
        if (!h.source.empty()) entry += " (" + h.source + ")";
        entry += "\n";
        entry += h.snippet;
        entry += "\n\n";

        // Whole entries only: a half-cut passage is worse than one fewer source.
        if (out.size() + entry.size() > max_chars) break;
        out += entry;
    }
    return out;
}

}  // namespace anbu
