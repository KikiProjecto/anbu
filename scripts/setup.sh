#!/usr/bin/env bash
#
# anbu — one-time setup for a connected phone.
#
# Downloads the model and a slice of Wikipedia, builds the knowledge base,
# installs the app, then pushes the three artifacts into the app's external
# files directory. The phone itself never needs a network connection.
#
#   ./scripts/setup.sh                     # everything, sane defaults
#   ./scripts/setup.sh --apk dist/anbu.apk # use an APK you already downloaded
#   ./scripts/setup.sh --skip-corpus       # model + install only (re-push a KB)
#
# Everything it downloads lands in .anbu-assets/ (gitignored). Re-running skips
# whatever is already there and verifies checksums before it is used.

set -euo pipefail

# --- pinned assets -----------------------------------------------------------
# Both verified against the Hugging Face API, not from memory: the byte sizes
# and sha256 below are what the repos actually serve.
MODEL_REPO="unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF"
MODEL_FILE="Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf"
MODEL_SHA256="6c997b8af17debdfb01d890214400ccbab00db6acc0ba8da5de1cc906c4774d0"
MODEL_BYTES=18556686752

WIKI_BASE="https://huggingface.co/datasets/wikimedia/wikipedia/resolve/main/20231101.en"
WIKI_SHARD_NAME="train-%05d-of-00041.parquet"

# --- defaults ----------------------------------------------------------------
APK=""
SHARDS=2
ARTICLES=100000
SKIP_MODEL=0
SKIP_CORPUS=0
ASSETS=".anbu-assets"
PKG="com.anbu.research"
PHONE_DIR="/sdcard/Android/data/$PKG/files"

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "$1 not found in PATH"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apk)         APK="${2:?}"; shift 2 ;;
    --shards)      SHARDS="${2:?}"; shift 2 ;;
    --articles)    ARTICLES="${2:?}"; shift 2 ;;
    --skip-model)  SKIP_MODEL=1; shift ;;
    --skip-corpus) SKIP_CORPUS=1; shift ;;
    -h|--help)     sed -n '2,12p' "$0"; exit 0 ;;
    *)             die "unknown flag: $1 (try --help)" ;;
  esac
done

cd "$(dirname "$0")/.."
ROOT="$PWD"
mkdir -p "$ASSETS"

# --- 1. tools ----------------------------------------------------------------
say "Checking tools"
need python3
need curl
if [ "$SKIP_MODEL" = 0 ] || [ "$SKIP_CORPUS" = 0 ]; then
  VENV="$ROOT/.venv"
  [ -d "$VENV" ] || python3 -m venv "$VENV"
  # shellcheck disable=SC1091
  . "$VENV/bin/activate"
  python3 -m pip install --quiet --upgrade pip
  [ "$SKIP_CORPUS" = 1 ] || python3 -m pip install --quiet numpy usearch pyarrow
  echo "python venv: $VENV"
fi
need adb

# --- 2. model ----------------------------------------------------------------
MODEL="$ASSETS/$MODEL_FILE"
if [ "$SKIP_MODEL" = 1 ]; then
  echo "model: skipped"
elif [ -f "$MODEL" ] && [ "$(stat -c %s "$MODEL")" = "$MODEL_BYTES" ]; then
  echo "model: already downloaded"
else
  say "Downloading model (~17 GiB, resumable)"
  curl -L --fail --retry 5 --retry-delay 5 -C - \
    -o "$MODEL" "https://huggingface.co/$MODEL_REPO/resolve/main/$MODEL_FILE"
fi
if [ "$SKIP_MODEL" = 0 ]; then
  say "Verifying model checksum"
  echo "$MODEL_SHA256  $MODEL" | sha256sum -c - \
    || die "model checksum mismatch — delete $MODEL and re-run"
fi

# --- 3. corpus ---------------------------------------------------------------
KB_DB="$ASSETS/kb.db"
KB_IDX="$ASSETS/kb.usearch"
if [ "$SKIP_CORPUS" = 0 ]; then
  say "Fetching $SHARDS Wikipedia shard(s)"
  for i in $(seq 0 $((SHARDS - 1))); do
    f=$(printf "$WIKI_SHARD_NAME" "$i")
    [ -f "$ASSETS/$f" ] || curl -L --fail --retry 5 -C - -o "$ASSETS/$f" "$WIKI_BASE/$f"
  done

  say "Converting to JSONL (first $ARTICLES articles)"
  python3 anbu-app/tools/wikipedia_to_jsonl.py --limit "$ARTICLES" \
    -o "$ASSETS/corpus.jsonl" "$ASSETS"/train-*-of-00041.parquet

  say "Building knowledge base"
  # --embedder hash is the one this app build can query: anbu_rag.cpp refuses a
  # corpus whose meta.embedder differs from its compiled-in kEmbedderName.
  python3 anbu-app/tools/build_knowledge_base.py --embedder hash \
    --out-dir "$ASSETS" "$ASSETS/corpus.jsonl"
  [ -f "$ASSETS/corpus.db" ] || die "builder produced no corpus.db"
  mv -f "$ASSETS/corpus.db" "$KB_DB"
  mv -f "$ASSETS/corpus.usearch" "$KB_IDX"
fi
[ -f "$KB_DB" ] || die "no $KB_DB — run without --skip-corpus first"

# --- 4. APK ------------------------------------------------------------------
if [ "$APK" = "" ]; then
  if [ -f "$ROOT/dist/anbu.apk" ]; then
    APK="$ROOT/dist/anbu.apk"
  elif [ -x "$ROOT/anbu-app/gradlew" ]; then
    say "No dist/anbu.apk — building it (needs Android SDK + NDK 27.0.12077973)"
    ( cd anbu-app && ./gradlew --quiet assembleDebug )
    APK="$ROOT/anbu-app/app/build/outputs/apk/debug/app-debug.apk"
  else
    die "no APK. Download one from https://github.com/KikiProjecto/anbu/releases into dist/anbu.apk, or pass --apk PATH"
  fi
fi
[ -f "$APK" ] || die "APK not found: $APK"

# --- 5. device ---------------------------------------------------------------
say "Waiting for a device"
adb wait-for-device

say "Installing $(basename "$APK")"
adb install -r -d "$APK"

# The app's external files dir is created by the first getExternalFilesDir()
# call, not at install time. Pushing into a directory adb created itself can
# leave it owned by the shell rather than the app, so launch once and wait.
say "Launching once so the app creates its files directory"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 5

say "Pushing model + knowledge base (this takes a while)"
adb shell mkdir -p "$PHONE_DIR"
adb push "$MODEL"  "$PHONE_DIR/model.gguf"
adb push "$KB_DB"  "$PHONE_DIR/kb.db"
adb push "$KB_IDX" "$PHONE_DIR/kb.usearch"

say "Done"
adb shell ls -l "$PHONE_DIR"
cat <<EOF

Open the app. It should go straight to the search screen — no setup needed.

  Re-pushing a corpus later:  ./scripts/setup.sh --skip-model
  Uninstalling the app deletes $PHONE_DIR (that is the 17 GiB model too).
  Back the three files up first if you would rather not re-download them.
EOF
