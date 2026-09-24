# anbu

A research assistant for Android that runs entirely on your phone.

Ask it something, it digs through a knowledge base stored on the device, finds
the passages that matter, and answers using a local language model. No server,
no API key, no internet permission in the manifest at all. Once the model files
are on the phone, it works in airplane mode forever.

For questions where you'd rather not send your prompt to someone else's
datacenter.

## How it works

Two halves: a one-time setup on your PC, then everything on the phone.

**On your PC** you run a Python script over a pile of text (Wikipedia dumps,
papers, whatever you feed it). It chops the text into pieces, turns each piece
into a vector, and writes two files — one holding all the text, one holding all
the vectors. Then you copy those, plus a model file, onto the phone.

```
        ── one-time setup, on your PC ──

   your documents
          │
          ▼
   chopped into ~500-token pieces
          │
          ▼
   each piece turned into a vector
          │
          ├──> kb.db        all the text, searchable by keyword
          └──> kb.usearch   all the vectors, searchable by meaning

   copy those two files + a model onto your phone
```

**On the phone**, every question goes through the same short loop:

```
        ── every question, on your phone ──

   you type a question
          │
          ├──> keyword search  ──┐   finds exact names, dates, terms
          │                      ├──> the best passages
          └──> meaning search  ──┘   finds paraphrases and ideas
                                     │
                     question + passages
                                     │
                                     ▼
                        local model writes the answer
                                     │
                                     ▼
                        ...text appears as it's written
```

Two searches, not one, because each covers what the other misses — keywords
can't find a paraphrase, and vectors can't reliably find a serial number.

That MoE choice is the interesting bit. The model is ~18GB on disk, but only a
fraction of its experts activate per token, so with `mmap` the phone's own
kernel pages weights in from storage as needed instead of holding the whole
thing in RAM. That's what makes a model this size fit in a normal phone.

Under the hood: llama.cpp for inference, USearch for vectors, SQLite for text,
all compiled for arm64 in C++ and called from Kotlin via JNI. The UI is Jetpack
Compose. There are three screens — splash, search, chat.

## What you need

- An arm64 Android phone on Android 12 or newer, or GrapheneOS. No Play Services
  involved anywhere.
- About 20GB free on the phone.
- A PC with `python3`, `curl` and `adb` to do the one-time setup.

## Quick start

Grab the APK from the [Releases page](https://github.com/KikiProjecto/anbu/releases)
into `dist/anbu.apk`, plug the phone in with USB debugging on, then:

```bash
./scripts/setup.sh
```

No release published yet? Skip that first step — if there's no APK in `dist/`,
the script builds one instead (that path needs the Android SDK + NDK).

It downloads the model (~18GB, resumable), pulls two Wikipedia shards, builds
the knowledge base, installs the APK, and pushes everything into place. Give it
a while; the rest is instant afterwards. Then open the app.

The script pins one model and one corpus, so there's nothing to choose:

| | |
| --- | --- |
| Model | `unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF`, `Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf` |
| | 18,556,686,752 bytes · sha256 `6c997b8af17debdfb01d890214400ccbab00db6acc0ba8da5de1cc906c4774d0` |
| Corpus | `wikimedia/wikipedia`, config `20231101.en`, first 100,000 articles |
| Licence | model: Apache-2.0 (Qwen3) · text: CC BY-SA 4.0 (Wikipedia) |

Both are public downloads from Hugging Face. `./scripts/setup.sh --articles N`
takes more or fewer articles; `--skip-model` rebuilds and re-pushes only the
knowledge base.

**On a Samsung, turn off Auto Blocker first** (Settings → Security and privacy).
It is on by default on newer One UI, and it blocks both `adb install` and
`adb push` — you get a popup and a dead USB debugging toggle.

## Doing it by hand

If you'd rather not use the script, the same steps, manual:

```bash
cd anbu-app
cp local.properties.example local.properties   # point sdk.dir at your SDK
./gradlew assembleDebug
```

You'll need the Android SDK with NDK `27.0.12077973`. The native deps
(llama.cpp, USearch) get cloned during the first build, so the build machine
needs internet — the phone never does.

Then build a corpus and push the three files:

```bash
python3 anbu-app/tools/build_knowledge_base.py --embedder hash corpus.jsonl --out-dir out
```

The app looks for `model.gguf`, `kb.db` and `kb.usearch` in
`/sdcard/Android/data/com.anbu.research/files/` — that location needs no
permissions. **The names matter**, so rename the script's output on the way in
(`corpus.db` → `kb.db`, `corpus.usearch` → `kb.usearch`).

Two things that will bite you. **Install the app and open it once before you
push** — that folder doesn't exist until the app asks for it, and pushing into a
folder adb created itself can leave it owned by the wrong user. And
**uninstalling deletes all of it**, the whole 18GB, so back those files up
somewhere if you'd rather not re-transfer them.

## Layout

```
anbu/
  anbu-app/         the Android app
    app/src/main/cpp/        C++ backend (llama.cpp, USearch, SQLite, JNI)
    app/src/main/java/...    Kotlin — core/ engine wrappers, ui/ Compose screens
    tools/                   Python corpus builder + a host-side C++ self-check
  scripts/setup.sh           one-shot: fetch, build, install, push
```

## Honest bits

- The "meaning search" half of the pipeline ships as a **hash embedder** — a
  deterministic stand-in that needs no model, not a trained one. Keyword search
  is doing the real work today. Swapping in a real embedder means changing
  `kEmbedderName` in `cpp/anbu_rag.cpp`, wiring an embedding call into
  `RagStore::set_embedder`, rebuilding the native side, and rebuilding the
  corpus with `--embedder all-minilm`; the app refuses a corpus built by a
  different embedder rather than silently returning noise.
- The release APK is **debug-signed**, because there is no release keystore yet.
  It installs and runs fine; it just isn't signed for the Play Store.
- The model writes what it finds in the retrieved passages. If a question isn't
  covered by the corpus you built, it says so instead of guessing — that is the
  point, but it also means a 100k-article corpus has real gaps.

## Status

First version of the UI is done, more will be updated soon.

More details, build notes, the embedder hook, deviations from the spec, all is in
[`anbu-app/README.md`](anbu-app/README.md).
