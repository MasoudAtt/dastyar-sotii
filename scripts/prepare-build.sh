#!/usr/bin/env bash
#
# آماده‌سازی پیش از build.
#
# این فایل عمداً از خود workflow جدا شده است: گیت‌هاب اجازه نمی‌دهد یک
# workflow با توکن پیش‌فرض، فایل workflow دیگری را تغییر دهد. بنابراین هر
# منطقی که ممکن است بعداً عوض شود اینجا زندگی می‌کند تا از راه معمول
# (همان zip) به‌روز شود و دیگر نیازی به ویرایش دستی workflow نباشد.
#
# کار فعلی: دانلود مدل تشخیص گفتار فارسی و گذاشتن آن داخل assets.
# مدل (~۶۰ مگابایت) عمداً در ریپو نگه داشته نمی‌شود.

set -euo pipefail

ASSETS_DIR="app/src/main/assets"
MODEL_DIR="${ASSETS_DIR}/model-fa"

if [ -d "$MODEL_DIR" ] && [ -n "$(ls -A "$MODEL_DIR" 2>/dev/null)" ]; then
  echo "model already present, skipping download"
  du -sh "$MODEL_DIR"
  exit 0
fi

CANDIDATES="vosk-model-small-fa-0.5 vosk-model-small-fa-0.42 vosk-model-small-fa-0.4"

downloaded=""
for name in $CANDIDATES; do
  url="https://alphacephei.com/vosk/models/${name}.zip"
  echo "trying ${url}"
  if curl -fL --retry 2 --max-time 900 -o model.zip "$url"; then
    downloaded="$name"
    break
  fi
done

if [ -z "$downloaded" ]; then
  echo "ERROR: could not download any Persian Vosk model."
  echo "Checked: $CANDIDATES"
  exit 1
fi
echo "downloaded: ${downloaded}"

rm -rf /tmp/vosk-model "$MODEL_DIR"
mkdir -p /tmp/vosk-model "$ASSETS_DIR"
unzip -q model.zip -d /tmp/vosk-model

# نام پوشه داخل آرشیو را حدس نمی‌زنیم؛ همان تک‌پوشه موجود را برمی‌داریم.
inner="$(find /tmp/vosk-model -mindepth 1 -maxdepth 1 -type d | head -1)"
if [ -z "$inner" ]; then
  echo "ERROR: unexpected archive layout"
  ls -la /tmp/vosk-model
  exit 1
fi

mv "$inner" "$MODEL_DIR"
rm -f model.zip

echo "--- model installed ---"
ls -la "$MODEL_DIR"
du -sh "$MODEL_DIR"

# اگر ساختار مدل درست نباشد، بهتر است همین‌جا شکست بخوریم تا اینکه اپ
# روی گوشی بی‌صدا خراب شود.
if [ ! -d "${MODEL_DIR}/am" ]; then
  echo "ERROR: model looks incomplete (no 'am' directory)"
  exit 1
fi
echo "model OK"
