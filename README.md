# LiteRT Server

Native Android app for downloading a LiteRT-LM model and serving it through an
authenticated, OpenAI-compatible localhost API.

## Requirements

- JDK 17
- Android SDK platform 35
- Android build tools 35.0.0 or newer
- Android 8.0+ device

Build and install:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.litert.server/.MainActivity
```

## Usage

The app has one screen:

1. Select the built-in Qwen3 0.6B or Gemma 3n E4B model, or paste a direct
   Hugging Face `https://huggingface.co/.../resolve/.../*.litertlm` URL.
2. Download the model. Interrupted downloads resume from the validated partial
   file.
3. Set the native context limit and GPU preference.
4. Tap **Start**.
5. Copy the endpoint, model ID, and API token from the Connection section.

Repository pages are intentionally rejected because a repository can contain
multiple hardware-specific LiteRT artifacts. Paste the direct `.litertlm` file
URL instead.

The built-in test model is the pinned 474.61 MiB
[`litert-community/Qwen3-0.6B`](https://huggingface.co/litert-community/Qwen3-0.6B)
mixed INT4 artifact with a 2048-token native context. This artifact has
published LiteRT-LM 0.13.1 OpenCL GPU results.

The larger built-in model is the pinned 4,919,541,760-byte
[`google/gemma-3n-E4B-it-litert-lm`](https://huggingface.co/google/gemma-3n-E4B-it-litert-lm)
INT4 artifact. It advertises a 32K context and defaults to a 24,576-token native
limit on this 16 GB Snapdragon 8 Gen 3 device. A direct 20,023-token request
completed without terminating the server; a roughly 30K request with a 32,768
native allocation did terminate the process, so the default keeps headroom for
generation and OMP's large system prompt. The repository is gated: accept the
Gemma license, create a Hugging Face token with public-gated-repository read
access, and save it in the app before downloading. The app encrypts the token
with Android Keystore and only attaches it to Hugging Face requests.

Two additional built-in options use the official Apache-2.0
`litert-community` Gemma 4 LiteRT-LM artifacts:

- Gemma 4 E2B: 2,588,147,712 bytes; minimum published device memory 8 GB.
- Gemma 4 E4B: 3,659,530,240 bytes; minimum published device memory 12 GB.

Both artifacts are ungated and pin the official Gallery catalog revisions.
They advertise 32,000-token contexts, and the native context remains editable
while the server is stopped. E2B defaults to 32,000; it initialized on GPU and
completed an authenticated completion on the 16 GB Snapdragon 8 Gen 3 test
device. E4B defaults to 16,384: its 32,000-token allocation terminated the app
and its launching terminal, 24,576 also failed, and 16,384 initialized on GPU
and completed an authenticated completion.

Models live in app-private external storage under
`[ExternalFilesDir]/models/<model-id>/`.

## HTTP API

`PORT` is the persisted localhost port selected in the app while the server is
stopped. It is stored as `server_port`, defaults (and migrates) to `8080`, and
accepts only `1024` through `65535`. The server binds exactly to
`127.0.0.1:<PORT>`; it never falls back to another port. A collision is a
startup error naming `127.0.0.1:<PORT>`.

The API token persists across process death. Regenerate it only while the
server is stopped. Regeneration enters `ServerStatus.CONFIGURING` before I/O;
while `ServerStatus.CONFIGURING`, configuration controls are busy and
non-mutable. It creates 24
`SecureRandom` bytes encoded as URL-safe Base64 without padding, synchronously
commits the candidate, and then atomically updates the service field and
snapshot. If the commit fails, the old token is retained. A running server
captures an immutable token, so regeneration never rotates a live server. A
successful stopped regeneration immediately invalidates the old token for the
next server instance. The endpoint, curl examples, and token shown by the UI
recompose immediately.

```bash
PORT=8080 # replace with the port selected in the app
BASE_URL="http://127.0.0.1:${PORT}"
TOKEN="<token shown in the app>"
MODEL=qwen3-0.6b

# No authentication
curl "$BASE_URL/health"

# Bearer authentication required
curl "$BASE_URL/v1/models" \
  -H "Authorization: Bearer $TOKEN"

curl -N "$BASE_URL/v1/chat/completions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$MODEL\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}"
```

Supported routes:

- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions`

Chat completions support JSON responses and incremental server-sent events.
Requests are stateless. The app does not expose legacy chat, reset, vision, or
in-app chat routes.

## OMP model entry

Add a provider to `~/.omp/agent/models.yml`:
Replace `<PORT>` with the exact port selected in the app; it is not necessarily
the default `8080`.

```yaml
providers:
  litert-server:
    baseUrl: http://127.0.0.1:<PORT>/v1
    api: openai-completions
    apiKey: LITERT_SERVER_TOKEN
    authHeader: true
    compat:
      supportsDeveloperRole: false
      supportsReasoningEffort: false
      supportsUsageInStreaming: true
      maxTokensField: max_tokens
    models:
      - id: qwen3-0.6b
        name: LiteRT Qwen3 0.6B
        reasoning: true
        input: [text]
        contextWindow: 2048
        maxTokens: 512
        cost:
          input: 0
          output: 0
          cacheRead: 0
          cacheWrite: 0
```

Then run with the token copied from the app:

```bash
export LITERT_SERVER_TOKEN="<token>"
omp --model litert-server/qwen3-0.6b
```

## Runtime behavior

- The foreground service exclusively owns downloads, the LiteRT engine, and
  the HTTP server.
- Engine start is explicit and idempotent across Activity recreation.
- GPU initialization falls back to CPU when unavailable and exposes the native
  fallback reason in the app and `/health`.
- Prompt/tool behavior is explicit catalog metadata named `ToolPromptProfile`,
  mirroring GGUF's template-metadata authority rather than inferring from an
  architecture or model ID. The built-in Qwen profile is `TAGGED_JSON`; Gemma
  is `STRICT_JSON_RELAY`; custom models default to `TAGGED_JSON`. `.litertlm`
  does not expose Jinja or GGUF metadata here, so the server performs no such
  metadata extraction.
- `TAGGED_JSON` uses the tagged tool protocol. `STRICT_JSON_RELAY` uses bare
  JSON examples, forces tool temperature to 0, and relays the
  trailing/current tool-result text verbatim only when the request's last
  message is a `role: "tool"` result with a non-null `tool_call_id` matching
  an assistant tool call earlier in the same request. It never replays a
  matched result from an older turn after a later user message or after a
  newer unmatched tool result. If those conditions are not met, it performs
  normal generation and never labels the result completed by a known function
  name.
- OpenAI function tools are returned as `tool_calls`.
- Server inference is serialized to protect the native engine.
- Download and inference work run off the Android main thread.
- Android backup is disabled; the API never binds beyond localhost.
