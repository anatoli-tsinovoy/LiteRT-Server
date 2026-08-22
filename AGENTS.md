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
  defaults to 24,576 native tokens on the 16 GB SM8650 test device so OMP's
  large system prompt fits with generation headroom. A direct 20,023-token
  request completed without terminating the server; a roughly 30K request with
  a 32,768 native allocation terminated the process. Its Hugging Face
  repository is gated and requires accepted Gemma terms plus a token with
  public-gated-repository read access. GPU health, an authenticated `GEMMA_OK`
  completion, and the 20K prompt have been device-verified; the first cold
  start terminated once before the cached retry succeeded.
- The built-in Gemma 4 E2B and E4B options pin the official Gallery catalog
  revisions of the `litert-community` Apache-2.0 artifacts. The generic E2B
  file is 2,588,147,712 bytes with an 8 GB published minimum; the generic E4B
  file is 3,659,530,240 bytes with a 12 GB published minimum. Both advertise
  32,000-token contexts and are ungated. E2B uses the Gallery default of 32,000
  native tokens; GPU health and an authenticated `E2B_OK` completion have been
  device-verified with that allocation. E4B defaults to 16,384 on the 16 GB
  SM8650 test device: a 32,000-token allocation terminated the app and its
  launching terminal, 24,576 also failed, and GPU health plus an authenticated
  `E4B_OK` completion succeeded with 16,384.
  The app explicitly selects its host-side Gemma `STRICT_JSON_RELAY` profile;
  it does not extract the artifacts' native tagged tool templates. The
  Gallery's optional MTP update revisions are not used because this app does
  not implement its MTP update toggle.
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
  model prompts, and the catalog's exact `ToolPromptProfile` selects the
  prompt/tool behavior. This explicit metadata is authoritative, mirroring
  GGUF template metadata; behavior is not inferred from an architecture or
  model ID. The built-in profiles are Qwen `TAGGED_JSON` and Gemma
  `STRICT_JSON_RELAY`. Custom models default to `TAGGED_JSON`. `.litertlm`
  files do not expose Jinja or GGUF metadata here, so the server does not
  extract either to select a profile. Qwen uses:

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
  `finish_reason: "tool_calls"`; the next `role: "tool"` message must retain
  the matching `tool_call_id`.
- Prompt history labels completed tool results with their function name. This
  matters for the 0.6B model: without an explicit completed-call marker it can
  repeat the first function instead of proceeding to the next requested tool.
- Tool-enabled requests default to temperature 0. `STRICT_JSON_RELAY` requests
  force temperature 0 even when the client supplies another value;
  `TAGGED_JSON` retains the client override.
- `STRICT_JSON_RELAY` relays the trailing/current tool-result text verbatim only when the request's last message is a `role: "tool"` result with a non-null `tool_call_id` (`toolCallId` internally) matching an assistant tool call earlier in the same request. A matched result from an older turn is never replayed after a later user message or after a newer unmatched tool result. Otherwise it performs normal generation and never labels the result completed by a known function name. This prevents repeated calls and corruption of exact file or command output; never parse that relayed result as a new model-generated tool call.
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

- The embedded Ktor server binds exactly to `127.0.0.1:<PORT>` only. The
  persisted `server_port` setting defaults (and migrates) to `8080`, accepts
  only `1024` through `65535`, and is editable only while `STOPPED`.
  `ServerStatus.CONFIGURING` is busy and non-mutable. There is no fallback port: a
  collision fails startup with an error naming `127.0.0.1:<PORT>`.
- `GET /health` is unauthenticated. Every `/v1/*` route requires
  `Authorization: Bearer <token>`.
- The API token persists across process death. Regeneration is allowed only
  while `STOPPED`; it enters `ServerStatus.CONFIGURING` before I/O, generates 24
  `SecureRandom` bytes encoded as URL-safe Base64 without padding, synchronously
  commits the candidate, and then atomically updates the service field and
  snapshot. If the commit fails, the old token remains. A running server
  captures an immutable token, so regeneration never rotates a live server.
  After a successful stopped regeneration, the old token is immediately
  invalid for the next server instance, and the UI recomposes its endpoint,
  curl examples, and token immediately.
- Android backup is disabled in the manifest.
- Camera, media-read, network-state, and wake-lock permissions are intentionally not requested.

## Maintainer guidance

- Do not remove the LiteRT, Ktor, serialization, OkHttp, or SLF4J R8 rules without a release build and runtime smoke test.
- Keep API changes reflected in the Server tab examples.
- Prefer release builds for performance comparisons; debug builds include tooling and skip R8 optimization.
