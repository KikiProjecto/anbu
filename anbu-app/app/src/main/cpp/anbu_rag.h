// Hybrid retrieval: SQLite FTS5 (lexical/BM25) fused with USearch (dense HNSW).
//
// The .db and .usearch files are produced offline by the Phase-1 Python pipeline
// and pushed to the device. This class only ever READS them.
//
// Expected SQLite schema (see tools/build_knowledge_base.py):
//   CREATE TABLE documents(id INTEGER PRIMARY KEY, source TEXT, title TEXT, url TEXT);
//   CREATE TABLE chunks(id INTEGER PRIMARY KEY, doc_id INTEGER, ord INTEGER, text TEXT);
//   CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='chunks', content_rowid='id');
//   -- chunks_fts rowid == chunks.id
// USearch index keys are the same chunk ids.
#pragma once

#include <cstddef>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace anbu {

struct Hit {
    long long   chunk_id = 0;
    std::string doc_id;
    std::string title;
    std::string source;
    std::string snippet;
    double      score = 0.0;   // fused RRF score, higher is better
};

// text -> dims floats, L2-normalised. Return false to skip vector search.
using EmbedFn = bool (*)(const char* text, int dims, float* out, void* user);

class RagStore {
public:
    RagStore();
    ~RagStore();
    RagStore(const RagStore&) = delete;
    RagStore& operator=(const RagStore&) = delete;

    // db_path or index_path may be empty to run in lexical-only / vector-only mode.
    bool open(const std::string& db_path, const std::string& index_path, std::string* error);
    void close();
    bool db_ready() const;
    bool index_ready() const;

    std::vector<Hit> search(const std::string& query, int top_k, std::string* error);

    // Number of chunks in the USearch index (0 when absent).
    std::size_t index_size() const;
    int dims() const { return dims_; }

    // Installs the query embedder. Without one, retrieval falls back to FTS5 only.
    static void set_embedder(EmbedFn fn, void* user, int dims);
    static bool has_embedder();

    // Formats hits into the context block handed to the LLM. Truncates whole
    // entries (never mid-entry) until max_chars is respected.
    static std::string build_context(const std::vector<Hit>& hits, std::size_t max_chars);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    mutable std::mutex mu_;
    int dims_ = 0;
    void close_locked();   // teardown without acquiring mu_
};

}  // namespace anbu
