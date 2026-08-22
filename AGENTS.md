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

## GPU backend

- LiteRT-LM does not implicitly fall back from `Backend.GPU()` to CPU. The app
  deliberately retries `Backend.CPU()` and exposes the original failure as
  `backend_error` in the UI and `/health`.
- Apps targeting API 31 or newer must declare these vendor libraries inside
  the manifest's `<application>` element:

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false" />
<uses-native-library android:name="libOpenCL.so" android:required="false" />
<uses-native-library android:name="libcdsprpc.so" android:required="false" />
```

- On Qualcomm SM8650/Adreno 750, successful logs include `Loaded OpenCL library
  with dlopen`, creation of an OpenCL device, and `LITERT_CL` delegation. If
  those appear, `gpu:false` is not an OpenCL-discovery problem.
- GPU engine creation requires a fully delegated graph. The former
  `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` artifact delegated only 34 of 994
  operations on SM8650; LiteRT rejected the remaining 960 CPU operations
  because its full-delegation hint was set.
- The built-in model is therefore the pinned 497,664,000-byte
  `qwen3_0_6b_mixed_int4.litertlm` artifact with a 2048-token context. It has
  published LiteRT-LM 0.13.1 OpenCL results and has been device-verified here.
  The catalog retains the former internal filename so an explicit download
  replaces the old artifact instead of orphaning it.
- The larger built-in model is the pinned 4,919,541,760-byte
  `gemma-3n-E4B-it-int4.litertlm` artifact. It advertises a 32K context and
  defaults to 4096 native tokens on the 16 GB SM8650 test device. Its Hugging
  Face repository is gated and requires accepted Gemma terms plus a token with
  public-gated-repository read access. GPU health and an authenticated
  `GEMMA_OK` completion have been device-verified; the first cold start
  terminated once before the cached retry succeeded.
- Keep CPU and GPU cache directories separate. LiteRT caches GPU programs and
  weights, and its GPU environment or failure is process-static; restart the
  app process after changing manifest libraries, models, or GPU cache state.
- Set native logging to `VERBOSE` before GPU initialization when diagnosing.
  Preserve the first native delegate error before CPU fallback. In particular,
  distinguish OpenCL loading failures from `graph is not fully delegated`.
- A GPU smoke test is complete only when `/health` reports `"gpu":true` and an
  authenticated completion succeeds. A successful engine initialization alone
  is insufficient.

## OpenAI tool calls

- LiteRT-LM generates text; it does not directly implement OpenAI function
  calling for this server. `HttpApiServer` compacts request tool schemas into
  model-specific prompts and asks for one call per turn. Qwen uses:

```text
<tool_call>{"name":"tool_name","arguments":{...}}</tool_call>
```

  Gemma uses a bare JSON object, explicit `read` and `bash` examples, exact
  argument copying, and omission of unrequested optional arguments.

- Tool parsing accepts the tagged form, a missing closing tag, or a bare JSON
  object. Extract JSON by balancing braces while respecting quoted strings and
  escapes; a regex ending at the first `}` breaks nested argument objects.
- Validate every generated tool name against the request's function tools
  before returning it. Return assistant calls through `message.tool_calls` with
  `finish_reason: "tool_calls"`; the next `role: "tool"` message must retain the
  matching `tool_call_id`.
- Prompt history labels completed tool results with their function name. This
  matters for the 0.6B model: without an explicit completed-call marker it can
  repeat the first function instead of proceeding to the next requested tool.
- Tool-enabled requests default to temperature 0. Gemma tool requests force
  temperature 0 even when the client supplies another value; Qwen retains the
  client override.
- Gemma post-tool turns return the latest completed tool-result text verbatim
  without another inference pass. This prevents repeated calls and corruption
  of exact file or command output; never parse that relayed result as a new
  model-generated tool call.
- Streaming completions are buffered before emitting content or a tool call.
  A tool call is emitted as one OpenAI-compatible SSE delta followed by
  `finish_reason: "tool_calls"`, usage, and `[DONE]`.
- Qwen has a 2048-token context. Large tool schemas plus a large file result can
  exhaust the next turn and produce an empty completion. Use a small file for
  smoke tests and keep injected schemas and tool results compact.
- Verify the local model through `omp -p` without `--no-tools`. Inspect the JSON
  event stream for successful `tool_execution_end` events; the tiny model's
  final prose can summarize tool output imperfectly even when execution was
  correct. Example:

```bash
LITERT_SERVER_TOKEN="<token>" omp -p \
  --mode json \
  --model litert-server/qwen3-0.6b \
  --tools=read,bash \
  --auto-approve \
  --no-extensions --no-skills --no-rules --no-session \
  --system-prompt \
  'Read local.properties, then call bash with command ls. One tool per turn.' \
  'Execute both tool calls.'
```

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
