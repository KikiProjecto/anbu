# anbu — Offline AI Research App

100% offline research assistant. Kotlin + Jetpack Compose UI, C++/NDK backend:
llama.cpp (MoE GGUF, mmap) + USearch (HNSW) + SQLite FTS5. No Google Play
Services, no `INTERNET` permission, arm64-v8a only.

## Layout

```
anbu-app/
  app/src/main/
    cpp/                      # C++ backend (NDK)
      CMakeLists.txt          # builds llama.cpp + usearch + sqlite + anbu
      native-lib.cpp          # JNI bridge (initEngine/searchContext/streamInference)
      anbu_engine.cpp/.h      # llama.cpp wrapper, mmap=true mlock=false
      anbu_rag.cpp/.h         # FTS5 + USearch hybrid retrieval
      anbu_text.h             # UTF-8 -> UTF-16, FTS5 query sanitising (pure, host-testable)
      anbu_json.h             # minimal JSON emitter
    java/com/anbu/research/
      core/                   # NativeLib, AIEngineManager, TokenCallback, Models
      ui/                     # AIEngineViewModel + Splash/Search/Chat screens
  tools/
    build_knowledge_base.py   # Phase-1: dumps -> kb.db + kb.usearch
    test_text.cpp             # host self-check for anbu_text.h
```

## Build

```
# 1. Point at your SDK (copy and edit)
cp local.properties.example local.properties      # sdk.dir=/path/to/Android/Sdk

# 2. Build (fetches llama.cpp + usearch + sqlite at configure time)
./gradlew assembleDebug
```

Host pre-check of the pure text helpers (no NDK needed):

```
g++ -std=c++17 -I app/src/main/cpp tools/test_text.cpp -o /tmp/anbu_test_text && /tmp/anbu_test_text
```

## Model + corpus (Phase-1 pipeline)

Build the knowledge base on a PC, push the three artifacts to the phone:

```
python3 tools/build_knowledge_base.py --embedder hash dump.jsonl --out-dir out
# produces out/dump.db + out/dump.usearch

adb push out/dump.db  /sdcard/Android/data/com.anbu.research/files/kb.db
adb push out/dump.usearch /sdcard/Android/data/com.anbu.research/files/kb.usearch
adb push model.gguf  /sdcard/Android/data/com.anbu.research/files/model.gguf
```

`getExternalFilesDir()` maps to `/sdcard/Android/data/com.anbu.research/files/`.
The model the scripts pin is `unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF` →
`Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf` (18,556,686,752 bytes, sha256
`6c997b8af17debdfb01d890214400ccbab00db6acc0ba8da5de1cc906c4774d0`). The engine
takes the chat template from the GGUF itself (`llama_model_chat_template`), so
any MoE GGUF the pinned llama.cpp knows the architecture of works — Qwen3-MoE
and Qwen2-MoE included, which is why the 2507 (non-thinking) variant is
preferable to the base Qwen3 MoE, whose embedded template turns reasoning on by
default. Preferred storage is Q4_K_M or Q3_K_M; keep the file under ~25GB.

Corpus: `wikimedia/wikipedia` config `20231101.en` (41 parquet shards, columns
`id/url/title/text`), converted with `tools/wikipedia_to_jsonl.py`.

Input `dump.jsonl`: one JSON object per line `{"title","source","url","text"}`.
The DB schema (documents / chunks / chunks_fts) is the fixed contract with
`anbu_rag.cpp`; the `.usearch` index keys are `chunks.id`.

The builder records which embedder authored the vectors in a `meta` table.
`anbu_rag.cpp` refuses to open a corpus whose `meta.embedder` differs from its
compiled-in `kEmbedderName` — hashed and MiniLM vectors are both 384-d, so a
mismatch would load clean and return pure noise behind a working FTS leg.

## Deploying to a device

**Build.** `./gradlew assembleDebug` — verified to produce an installable APK
(`app/build/outputs/apk/debug/app-debug.apk`, 29.5 MB). `./gradlew assembleRelease`
also completes (6m34s, R8 + resource shrinking), but `app/build.gradle.kts` has no
`signingConfigs` block, so its APK is unsigned and `adb install` rejects unsigned
APKs. Add a signing config (and a `local.properties` hook for the keystore, which
does not exist yet either) before shipping a release build.

**First launch, then push.** `/sdcard/Android/data/<pkg>/files/` is created
lazily by the first `getExternalFilesDir()` call — not at install. Launch the app
once, then push. Pushing first can leave the directory owned by the shell rather
than the app, and on Android 16 there is an upstream `mkdirs()` bug that makes
`getExternalFilesDir()` return null on first launch; if you hit it,
`adb shell mkdir -p /storage/emulated/0/Android/data/com.anbu.research/files`
works around it.

**Pull your files back before uninstalling.** Uninstall deletes the whole
`Android/data/com.anbu.research/` tree, which is ~25GB of GGUF plus the corpus.

**Samsung One UI: turn Auto Blocker off first.** It is on by default on One UI
6.1.1+ and later, and it blocks both `adb install` and `adb push` — and it greys
out the USB debugging toggle, so ordinary ADB troubleshooting does not explain
what is happening. Settings → Security and privacy → Auto Blocker → off, PIN
required. Re-enable it afterwards. One UI 8.5 reportedly re-arms it on a timer,
so push the model early in the session.

**GrapheneOS: nothing special.** `adb install` is explicitly exempt from its
system-package restrictions, sideloading from the Files app is permitted, and
MTE is off by default for third-party apps that bundle native libraries. The
per-app Network toggle is irrelevant here — the manifest declares no permissions
at all, so there is nothing to grant or deny.

**16 KB pages (do not regress this).** NDK r27 still defaults to 4 KB ELF
alignment, which is fatal on an Android 15 16 KB device and produces a
user-visible compatibility dialog on every launch on Android 16.
`cpp/CMakeLists.txt` passes `-Wl,-z,max-page-size=16384`; verify after any NDK or
linker change:

```
llvm-readelf -lW app/build/.../libanbu.so | grep LOAD   # every Align must be 0x4000
zipalign -c -P 16 -v 4 app-release.apk
```

## Embedded model (optional, replaces the embedding stub)

The JNI layer ships a deterministic hashing embedder so the whole pipeline runs
with no model. For real semantic retrieval there are two changes, not one:
implement the ONNX Runtime / TFLite INT8 call behind `RagStore::set_embedder`
(`cpp/anbu_rag.h:54`) and change `kEmbedderName` (`cpp/anbu_rag.cpp:93`) to
match, then rebuild. `set_embedder` is C++-side only — `NativeLib` exposes no
Kotlin entry point for it — and the corpus must be rebuilt with the same
embedder, since `anbu_rag.cpp` refuses one authored by a different embedder.

## Notes / deviations from PRD

- `use_mlock=false` and `use_mmap=true` are set in `anbu_engine.cpp::load` per PRD.
- No `INTERNET` in the manifest by design (runtime is air-gapped). A one-time
  model download would need a separate flavor with that permission added.
- llama.cpp and usearch are fetched at configure time and pinned via
  `ANBU_LLAMA_CPP_TAG` / `ANBU_USEARCH_TAG` (`v0.4.1` and `v2.26.2`, sqlite
  amalgamation `3470200`) — llama.cpp's C API is not stable, so bump
  deliberately and rebuild. `third_party/` may hold vendored copies instead
  (gitignored; fetched on demand).
- That pin uses `llama_model_load_from_file`, `llama_init_from_model`,
  `llama_memory_clear` — see the rename notes in `anbu_engine.cpp` before
  bumping.
