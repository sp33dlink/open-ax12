#!/usr/bin/env bash
# Downloads the RadioMaster AX SDK (rmAxSdk1) into the paths the build expects and verifies
# every file against sdk/SDK_FETCH.sha256. Nothing of the SDK is committed to this repository.
#
#   scripts/fetch-sdk.sh           binaries, headers and licence texts (needed to build)
#   scripts/fetch-sdk.sh --docs    additionally the SDK manuals and the vendor demo sources
#                                  (developer reference only; not covered by the SDK licence,
#                                  so they are git-ignored and never redistributed)
#   scripts/fetch-sdk.sh --force   download again even if the files are present
#
# Gradle runs this automatically (task :sdk:fetchSdk) when a file is missing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Binaries + headers: flyshark2024/rmAxSdk, the commit that added licenses/LICENSE
# ("Permission is hereby granted to use, copy, distribute, and redistribute the SDK binaries
# and headers ..."). The binaries are identical to Radiomaster-RC/RadiomasterAX_SDK@7aa7fac.
SDK_REPO="flyshark2024/rmAxSdk"
SDK_COMMIT="0f2b2862e11aaac847858a4a8a78fde103f77bb7"
# Manuals and demo sources: Radiomaster-RC/RadiomasterAX_SDK.
DOCS_REPO="Radiomaster-RC/RadiomasterAX_SDK"
DOCS_COMMIT="7aa7facc0f3680a286a01cb7335f310211d7b9ef"

# local path | upstream path
SDK_FILES=(
  "sdk/src/main/jniLibs/arm64-v8a/librmAxSdk1.so|lib/arm64-v8a/librmAxSdk1.so"
  "sdk/src/main/jniLibs/arm64-v8a/libQt6Core_arm64-v8a.so|lib/arm64-v8a/libQt6Core_arm64-v8a.so"
  "sdk/src/main/jniLibs/arm64-v8a/libQt6Network_arm64-v8a.so|lib/arm64-v8a/libQt6Network_arm64-v8a.so"
  "sdk/src/main/jniLibs/arm64-v8a/libQt6SerialPort_arm64-v8a.so|lib/arm64-v8a/libQt6SerialPort_arm64-v8a.so"
  "sdk/src/main/jniLibs/arm64-v8a/libQt6Concurrent_arm64-v8a.so|lib/arm64-v8a/libQt6Concurrent_arm64-v8a.so"
  "sdk/src/main/jniLibs/arm64-v8a/libc++_shared.so|lib/arm64-v8a/libc++_shared.so"
  "sdk/src/main/cpp/include/rmAxSdkApi.h|include/rmAxSdk1/rmAxSdkApi.h"
  "sdk/src/main/cpp/include/rmAxSdkTypes.h|include/rmAxSdk1/rmAxSdkTypes.h"
  "sdk/licenses/RadioMaster-SDK-LICENSE.txt|licenses/LICENSE"
  "sdk/licenses/Qt-LICENSE.txt|licenses/Qt-LICENSE.txt"
)
DOC_FILES=(
  "docs/sdk/SDK_User_Manual.en.md|SDK_User_Manual.en.md"
  "docs/sdk/SDK_API_USAGE.en.md|SDK_API_USAGE.en.md"
  "docs/sdk/reference/tst_rmAxSdkApi.cpp|tst_rmAxSdkApi.cpp"
  "docs/sdk/reference/rmAxSdkJni.cpp|demo/rmAxSdk_Demo/app/src/main/cpp/rmAxSdkJni.cpp"
  "docs/sdk/reference/CMakeLists.txt|demo/rmAxSdk_Demo/app/src/main/cpp/CMakeLists.txt"
  "docs/sdk/reference/RmAxSdkBridge.kt|demo/rmAxSdk_Demo/app/src/main/java/com/example/ax12/sdk/RmAxSdkBridge.kt"
)

WITH_DOCS=0
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --docs) WITH_DOCS=1 ;;
    --force) FORCE=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "fetch-sdk: unknown option $arg" >&2; exit 2 ;;
  esac
done

if command -v sha256sum >/dev/null 2>&1; then sha() { sha256sum "$1" | cut -d' ' -f1; }
else sha() { shasum -a 256 "$1" | cut -d' ' -f1; }; fi

expected() { awk -v p="$1" '$2 == p { print $1 }' sdk/SDK_FETCH.sha256; }

fetch() { # repo commit local remote
  local repo="$1" commit="$2" local_path="$3" remote_path="$4"
  local want; want="$(expected "$local_path")"
  if [ -z "$want" ]; then echo "fetch-sdk: no checksum for $local_path in sdk/SDK_FETCH.sha256" >&2; exit 1; fi
  if [ "$FORCE" = 0 ] && [ -f "$local_path" ] && [ "$(sha "$local_path")" = "$want" ]; then return 0; fi
  mkdir -p "$(dirname "$local_path")"
  local tmp="$local_path.download"
  echo "fetch-sdk: $remote_path"
  curl -fsSL --retry 3 -o "$tmp" "https://raw.githubusercontent.com/$repo/$commit/$remote_path"
  local got; got="$(sha "$tmp")"
  if [ "$got" != "$want" ]; then
    rm -f "$tmp"
    echo "fetch-sdk: checksum mismatch for $local_path (expected $want, got $got)" >&2
    exit 1
  fi
  mv "$tmp" "$local_path"
}

for entry in "${SDK_FILES[@]}"; do fetch "$SDK_REPO" "$SDK_COMMIT" "${entry%%|*}" "${entry#*|}"; done
if [ "$WITH_DOCS" = 1 ]; then
  for entry in "${DOC_FILES[@]}"; do fetch "$DOCS_REPO" "$DOCS_COMMIT" "${entry%%|*}" "${entry#*|}"; done
fi
echo "fetch-sdk: SDK files present and verified"
