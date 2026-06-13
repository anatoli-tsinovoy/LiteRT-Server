# LiteRT Server — Android Studio Project

A complete native Android application in Kotlin that runs compatible `.litertlm` models
locally on-device via Google's LiteRT-LM SDK.

## Requirements

- Android Studio Ladybug (2024.2.1) or newer
- Android SDK 35 (Android 15)
- JDK 17
- Target device: Android 8.0+ (API 26+) — tested on OnePlus 6 / Snapdragon 845 / Adreno 630

## Project Structure

```
app/src/main/java/com/litert/server/
├── MainActivity.kt              — Entry point, navigation, state management
├── data/AppState.kt             — All data classes and app state enum
├── download/ModelDownloadManager.kt  — HuggingFace model download with resume + progress
├── engine/LiteRTEngine.kt       — LiteRT-LM SDK wrapper (GPU/CPU backend)
├── service/
│   ├── LLMForegroundService.kt  — Android foreground service (START_STICKY)
│   └── HttpApiServer.kt         — Ktor CIO embedded HTTP server on port 8080
└── ui/
    ├── ChatScreen.kt            — Text chat with streaming tokens
    ├── VisionScreen.kt          — Image + text analysis
    ├── ServerScreen.kt          — Server control panel + request log + curl examples
    ├── DownloadScreen.kt        — Model download UI with progress
    └── SettingsScreen.kt        — GPU toggle, temperature, max tokens, model management
```

## Setup

1. Clone / open this folder in Android Studio
2. Let Gradle sync (it will download ~200MB of dependencies)
3. Build and install on your device: `./gradlew installDebug`
4. On first launch, choose a built-in LiteRT-LM model or add a compatible Hugging Face `.litertlm` URL, then download or import the model file.

## HTTP API (Ktor on localhost:8080)

Once the model is loaded and the server is running, copy the per-session token from the Server tab:

```bash
TOKEN="<token shown in the app>"

# Health check does not require auth
curl http://localhost:8080/health

# OpenAI-compatible chat completions
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"model":"local-litertlm","stream":true,"reasoning_effort":"low","messages":[{"role":"user","content":"Hello!"}],"max_tokens":128}'

# Legacy chat
curl -X POST http://localhost:8080/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello!"}'

# Vision (image analysis)
curl -X POST http://localhost:8080/vision \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"imagePath":"/sdcard/DCIM/photo.jpg","prompt":"Describe this image"}'

# Reset conversation history
curl -X POST http://localhost:8080/reset \
  -H "Authorization: Bearer $TOKEN"
```

## GPU Acceleration

The app uses the Adreno 630's OpenCL 2.0 support via LiteRT-LM's GPU backend.
`libOpenCL.so` and `libvndksupport.so` are declared in the manifest.
If GPU init fails, the engine automatically falls back to CPU.

## Models

- **Built-ins**:
  - Gemma 4 E2B: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm`
  - Gemma 4 E4B: `https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm`
- **Custom models**: paste a compatible Hugging Face repo URL or direct `.litertlm` URL in the Download screen.
- **Hugging Face auth**: Download and Settings screens accept an `hf_...` access token. The app stores it encrypted with Android Keystore and sends it only to Hugging Face API/download requests.
- **Storage**: models are stored in app-private external storage under `[ExternalFilesDir]/models/<model-id>/`.
- **Safety/performance**: downloads authenticate to Hugging Face when a token is saved, use parallel HTTP Range segments when the resolver supports ranges, write partial data as `*.part*`, resume interrupted work, and atomically rename only after minimum-size validation.
- **Management**: Settings lists installed models with size/path, lets you switch models, delete one model, or delete all models/cache.

## Android 15 Notes

- Foreground service type: `specialUse|dataSync` (required by API 35)
- `POST_NOTIFICATIONS` requested at runtime
- Battery optimization exemption requested on first launch
- `ServiceCompat.startForeground()` used with correct type flags

## APK Link

https://drive.google.com/file/d/147EVwUyKYFmUYRys2-xXf1qiRUDXqL50/view?usp=sharing
