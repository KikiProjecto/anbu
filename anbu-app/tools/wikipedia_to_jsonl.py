#!/usr/bin/env python3
"""Convert wikimedia/wikipedia parquet shards into the JSONL the KB builder eats.

  python3 wikipedia_to_jsonl.py --limit 100000 -o corpus.jsonl train-00000-of-00041.parquet

Shards come from https://huggingface.co/datasets/wikimedia/wikipedia (config
20231101.en, 41 shards, ~400 MB each). Columns are id/url/title/text, which map
straight onto the builder's {"title","source","url","text"}.

Requirements: pip install pyarrow
"""

from __future__ import annotations

import argparse
import json
import sys


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("shards", nargs="+", help="parquet files, in order")
    ap.add_argument("--limit", type=int, default=0, help="stop after N articles (0 = all)")
    ap.add_argument("--min-chars", type=int, default=200,
                    help="skip stubs shorter than this (default 200)")
    ap.add_argument("-o", "--out", required=True, help="JSONL to write")
    args = ap.parse_args()

    try:
        import pyarrow.parquet as pq
    except ImportError:
        print("pip install pyarrow", file=sys.stderr)
        return 1

    written = 0
    with open(args.out, "w", encoding="utf-8") as out:
        for path in args.shards:
            pf = pq.ParquetFile(path)
            for batch in pf.iter_batches(columns=["title", "text", "url"]):
                col = batch.to_pydict()
                for i in range(batch.num_rows):
                    text = (col["text"][i] or "").strip()
                    if len(text) < args.min_chars:
                        continue
                    out.write(json.dumps({
                        "title": (col["title"][i] or "").strip(),
                        "source": "wikipedia",
                        "url": col["url"][i] or "",
                        "text": text,
                    }, ensure_ascii=False) + "\n")
                    written += 1
                    if args.limit and written >= args.limit:
                        print(f"{written} articles -> {args.out}")
                        return 0

    print(f"{written} articles -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
