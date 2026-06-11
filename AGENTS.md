# AGENTS.md

## Project

LiteRT Server is a native Android/Kotlin app that runs a local LiteRT-LM model and exposes an authenticated localhost HTTP API.

## Requirements

- JDK 17
- Android SDK platform 35
- Android build tools 35.0.0 or newer
- Gradle wrapper from this repository

If the SDK is not in a standard location, set:

```bash
export ANDROID_HOME=/usr/lib/android-sdk
export ANDROID_SDK_ROOT=/usr/lib/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

Adjust paths for your workstation.

## Build commands

Debug APK:

```bash
./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release-optimized APK:

```bash
./gradlew assembleRelease
```

The Gradle release build enables R8 minification and resource shrinking. It produces an unsigned aligned-by-Gradle release artifact at:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

To create a locally installable signed release APK:

```bash
keytool -genkeypair \
  -keystore app/build/outputs/apk/release/litert-server-release.jks \
  -storepass litert-server \
  -keypass litert-server \
  -alias litert-server \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=LiteRT Server,O=Local Build,C=US"

zipalign -f -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/litert-server-release-aligned.apk

apksigner sign \
  --ks app/build/outputs/apk/release/litert-server-release.jks \
  --ks-pass pass:litert-server \
  --key-pass pass:litert-server \
  --out app/build/outputs/apk/release/litert-server-release.apk \
  app/build/outputs/apk/release/litert-server-release-aligned.apk
```

Install:

```bash
adb install app/build/outputs/apk/release/litert-server-release.apk
```

If a debug build is already installed, uninstall first because the signing key differs:

```bash
adb uninstall com.litert.server
adb install app/build/outputs/apk/release/litert-server-release.apk
```

## Verification

Verify signature:

```bash
apksigner verify --verbose app/build/outputs/apk/release/litert-server-release.apk
```

Inspect permissions:

```bash
aapt dump permissions app/build/outputs/apk/release/litert-server-release.apk
```

Expected requested permissions are limited to internet, foreground service, notifications, battery optimization exemption, and AndroidX's non-exported dynamic receiver permission.

## Model artifact management

- Built-in catalog entries live in `ModelCatalog` inside `ModelDownloadManager.kt`.
- Custom Hugging Face models are persisted in the app's `model_registry` shared preferences.
- Optional Hugging Face access tokens are encrypted with Android Keystore before being stored in `model_registry`; authenticated requests must only target Hugging Face API/download URLs.
- Model files live under app-private external storage: `[ExternalFilesDir]/models/<model-id>/`.
- Downloads authenticate to Hugging Face when a token is saved, use parallel HTTP Range segments when the resolver supports ranges, write to `<filename>.part*`, resume interrupted work, and rename to the final `.litertlm` only after minimum-size validation.
- Keep arbitrary custom models constrained to Hugging Face `.litertlm` files; the app's engine path uses LiteRT-LM, not MediaPipe `.task` or generic `.tflite` loaders.

## Security and privacy notes

- The embedded Ktor server binds to `127.0.0.1` only.
- Non-health API routes require `Authorization: Bearer <token>`.
- The token is generated per service instance and shown in the Server tab curl examples.
- Android backup is disabled in the manifest.
- Camera, media-read, network-state, and wake-lock permissions are intentionally not requested.

## Maintainer guidance

- Do not remove the LiteRT, Ktor, serialization, OkHttp, or SLF4J R8 rules without a release build and runtime smoke test.
- Keep API changes reflected in the Server tab examples.
- Prefer release builds for performance comparisons; debug builds include tooling and skip R8 optimization.
