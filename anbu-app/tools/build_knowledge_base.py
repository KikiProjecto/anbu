#!/usr/bin/env python3
"""Phase-1 knowledge base builder. Run on a PC, copy the artifacts to the phone.

Produces, next to each input dump:
  <base>.db      SQLite: documents + chunks + chunks_fts (FTS5, rowid == chunks.id)
  <base>.usearch USearch HNSW dense index over chunk embeddings (keys == chunks.id)

Schema is fixed and is the contract with app/src/main/cpp/anbu_rag.cpp:
  CREATE TABLE documents(id INTEGER PRIMARY KEY, source TEXT, title TEXT, url TEXT);
  CREATE TABLE chunks(id INTEGER PRIMARY KEY, doc_id INTEGER, ord INTEGER, text TEXT);
  CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='chunks', content_rowid='id');

Input is a JSONL with one JSON object per line, each {"title": str, "source": str,
"url": str, "text": str}. For Wikipedia/arXiv dumps, pre-convert to that shape
first (see docs in README).

Embeddings: --embedder hash is a cheap deterministic hashing fallback so the
pipeline runs end-to-end with no model, and it is the only one this app build can
query — anbu_rag.cpp compares the corpus `meta.embedder` against its compiled-in
kEmbedderName and refuses to open a corpus authored by anything else, because a
mismatched 384-d vector corpus returns silent noise. --embedder all-minilm
(sentence-transformers, L2-normalised, 384 dims) therefore needs the native side
rebuilt to match. The HNSW index is metric=cos (normalised => cosine == dot).

Requirements: pip install numpy usearch
For --embedder all-minilm: pip install sentence-transformers
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sqlite3
import sys

import numpy as np
from pathlib import Path

CHUNK_TOKENS = 500
OVERLAP_TOKENS = 50


def chunk_text(text: str, target: int = CHUNK_TOKENS, overlap: int = OVERLAP_TOKENS) -> list[str]:
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return []
    target_chars = target * 4
    overlap_chars = overlap * 4
    if overlap_chars >= target_chars:
        # Otherwise `start` below never advances and this loops forever.
        raise ValueError("overlap must be smaller than target")
    chunks: list[str] = []
    start = 0
    n = len(text)
    while start < n:
        end = min(start + target_chars, n)
        if end < n:
            # back off to a sentence boundary for clean cuts
            for sep in (". ", "! ", "? ", "\n"):
                pos = text.rfind(sep, start, end)
                if pos > start + target_chars // 2:
                    end = pos + 1
                    break
        chunks.append(text[start:end].strip())
        if end >= n:
            break
        start = max(start, end - overlap_chars)
    return [c for c in chunks if c]


def build_hash_embedder(dims: int):
    # Matches app/src/main/cpp/anbu_rag.cpp hash_embed() byte-for-byte: the index
    # is authored here and queried there, so divergence turns the dense leg into
    # noise. Python's hash() is SipHash, salted per-process — unusable here.
    fnv_offset = 1469598103934665603
    fnv_prime = 1099511628211

    def embed(text: str) -> list[float] | None:
        vec = [0.0] * dims
        any_token = False
        h = fnv_offset
        for b in text.encode("utf-8"):
            if 0x30 <= b <= 0x39 or 0x61 <= b <= 0x7A or 0x41 <= b <= 0x5A or b >= 0x80:
                if 0x41 <= b <= 0x5A:
                    b += 0x20
                h ^= b
                h = (h * fnv_prime) & 0xFFFFFFFFFFFFFFFF
            elif h != fnv_offset:
                vec[h % dims] += -1.0 if (h >> 63) & 1 else 1.0
                any_token = True
                h = fnv_offset
        if h != fnv_offset:
            vec[h % dims] += -1.0 if (h >> 63) & 1 else 1.0
            any_token = True
        if not any_token:
            return None
        norm = math.sqrt(sum(v * v for v in vec))
        return [v / norm for v in vec] if norm > 0 else None
    return embed


def build_minilm_embedder():
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer("all-MiniLM-L6-v2")

    def embed(text: str) -> list[float]:
        return model.encode([text], normalize_embeddings=True)[0].tolist()
    return embed


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", help="JSONL files, one JSON object per line")
    ap.add_argument("--embedder", choices=["hash", "all-minilm"], default="hash")
    ap.add_argument("--dims", type=int, default=384,
                    help="embedding dims (all-minilm = 384)")
    ap.add_argument("--hnsw-m", type=int, default=16, help="usearch HNSW connectivity M")
    ap.add_argument("--hnsw-ef", type=int, default=200, help="usearch HNSW ef_construction")
    ap.add_argument("--quantization", choices=["f32", "f16", "i8"], default="f32")
    ap.add_argument("--out-dir", default=".", help="where .db/.usearch are written")
    args = ap.parse_args()

    try:
        # Not `import usearch` + `usearch.Index`: the 2.26 wheel stopped
        # re-exporting the compiled names at top level, so that raises
        # AttributeError. The submodule path works on every 2.x release.
        from usearch.index import Index, ScalarKind
    except ImportError:
        print("pip install usearch", file=sys.stderr)
        return 1

    embed = build_hash_embedder(args.dims) if args.embedder == "hash" else build_minilm_embedder()
    quant = {"f32": ScalarKind.F32, "f16": ScalarKind.F16,
             "i8": ScalarKind.I8}[args.quantization]

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    for input_path in args.inputs:
        src = Path(input_path)
        base = out_dir / src.stem
        db_path = base.with_suffix(".db")
        idx_path = base.with_suffix(".usearch")

        conn = sqlite3.connect(db_path)
        cur = conn.cursor()
        cur.execute("PRAGMA journal_mode=OFF")
        cur.execute("PRAGMA synchronous=OFF")
        # Re-runs rebuild from scratch: drop so a stale .db doesn't abort CREATE.
        cur.execute("DROP TABLE IF EXISTS documents")
        cur.execute("DROP TABLE IF EXISTS chunks")
        cur.execute("DROP TABLE IF EXISTS chunks_fts")
        cur.execute("DROP TABLE IF EXISTS meta")
        # Records which embedder authored the vectors. anbu_rag.cpp refuses to
        # open a corpus whose embedder it cannot reproduce: hashed and MiniLM
        # vectors are both 384-d, so a mismatch loads clean and retrieves noise.
        cur.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        cur.execute("INSERT INTO meta(key, value) VALUES('embedder',?),('dims',?),('quantization',?)",
                    (args.embedder, str(args.dims), args.quantization))
        cur.execute("CREATE TABLE documents(id INTEGER PRIMARY KEY, source TEXT, title TEXT, url TEXT)")
        cur.execute("CREATE TABLE chunks(id INTEGER PRIMARY KEY, doc_id INTEGER, ord INTEGER, text TEXT)")
        # Default unicode61 tokenizer, no stemming: anbu_rag.cpp queries with a
        # plain OR-of-quoted-terms expression, so a porter index would never match.
        cur.execute("CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='chunks', content_rowid='id')")

        index = Index(ndim=args.dims, metric="cos", dtype=quant,
                              connectivity=args.hnsw_m, expansion_add=args.hnsw_ef)

        chunk_id = 0
        doc_id = 0
        keys: list[int] = []
        vecs: list[list[float]] = []

        with open(src, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                obj = json.loads(line)
                title = str(obj.get("title", "")).strip()
                text = str(obj.get("text", "")).strip()
                if not text:
                    continue

                doc_id += 1
                cur.execute("INSERT INTO documents(id, source, title, url) VALUES(?,?,?,?)",
                            (doc_id, str(obj.get("source", "")), title, str(obj.get("url", ""))))

                for ord_i, chunk in enumerate(chunk_text(text)):
                    chunk_id += 1
                    cur.execute("INSERT INTO chunks(id, doc_id, ord, text) VALUES(?,?,?,?)",
                                (chunk_id, doc_id, ord_i, chunk))
                    v = embed(chunk)
                    if v is not None:
                        keys.append(chunk_id)
                        vecs.append(v)

        # FTS5 external-content table: feed it the same text.
        cur.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')")

        if keys:
            # usearch 2.26 asserts on a non-ndarray; a list of lists used to work.
            # Keys stay a plain list — add() converts those itself.
            index.add(keys, np.array(vecs, dtype=np.float32))
        index.save(str(idx_path))
        conn.commit()

        print(f"{src.name}: {doc_id} docs, {chunk_id} chunks, "
              f"{len(keys)} vectors -> {db_path.name} + {idx_path.name}")
        conn.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
