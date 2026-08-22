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

1. Select the built-in Qwen3 0.6B model, or paste a direct Hugging Face
   `https://huggingface.co/.../resolve/.../*.litertlm` URL.
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

Models live in app-private external storage under
`[ExternalFilesDir]/models/<model-id>/`.

## HTTP API

The server binds only to `127.0.0.1`, using the first available port from
8080–8082. The API token is generated once and persisted by the app.

```bash
BASE_URL=http://127.0.0.1:8080
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

```yaml
providers:
  litert-server:
    baseUrl: http://127.0.0.1:8080/v1
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
- OpenAI function tools are returned as `tool_calls`; tool results can be sent
  back for the next serialized model turn.
- Server inference is serialized to protect the native engine.
- Download and inference work run off the Android main thread.
- Android backup is disabled; the API never binds beyond localhost.
