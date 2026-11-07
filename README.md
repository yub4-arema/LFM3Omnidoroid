# LFM3 Omnidroid

Android app that runs [LiquidAI/LFM3-Omni-2B](https://huggingface.co/LiquidAI/LFM3-Omni-2B) fully on-device
with llama.cpp (GGUF) + libmtmd.

- Input: text, images, audio (mic recording or audio file)
- Output: streamed text (speech output is not implemented yet)
- Model: `LFM3-Omni-2B-Q4_K_M.gguf` + `mmproj-LFM3-Omni-2B-Q8_0.gguf` (~1.4 GB), downloaded on first launch

## Build

```
git clone --recurse-submodules <this repo>
# needs: JDK 17-21 (not 25), Android SDK 36, NDK 29.0.13113456, CMake 3.31.6
./gradlew :app:assembleDebug
```

The APK is arm64-v8a only. llama.cpp lives in `app/src/main/cpp/llama.cpp` (git submodule, needs a build with LFM3-Omni support).
