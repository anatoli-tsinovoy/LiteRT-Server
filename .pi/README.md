# Pi coding-agent setup for LiteRT Server

This directory contains project-local Pi helper files for connecting `@earendil-works/pi-coding-agent` to this app's OpenAI-compatible endpoint.

## Endpoint shape

LiteRT Server exposes:

- `GET /health` without auth
- `GET /v1/models` with `Authorization: Bearer <token>`
- `POST /v1/chat/completions` with `Authorization: Bearer <token>`

The Android service binds to `127.0.0.1` on the device and tries ports `8080`, `8081`, then `8082`. The active port and bearer token are shown in the app's Server tab.

## When Pi runs on a laptop or desktop

Forward the selected Android device port to the host:

```bash
adb forward tcp:8080 tcp:8080
```

If the app shows port `8081` or `8082`, forward that same port instead.

Verify the service before starting Pi:

```bash
curl http://127.0.0.1:8080/health

curl http://127.0.0.1:8080/v1/models \
  -H "Authorization: Bearer $LITERT_SERVER_TOKEN"
```

## When Pi runs in Termux on the same phone

No `adb forward` is needed. Use the loopback URL directly:

```text
http://127.0.0.1:8080/v1
```

## Configure Pi

Pi reads custom model providers from:

```text
~/.pi/agent/models.json
```

Copy `.pi/agent/models.litert-server.example.json` into that path, or merge its `litert-server` provider into an existing `models.json`.

Set the app token before launching Pi:

```bash
export LITERT_SERVER_TOKEN='<token shown in the LiteRT Server app>'
pi
```

Then select the local provider from Pi:

```text
/model → litert-server / gemma-4-e2b-it
```

Use `gemma-4-e4b-it` instead if that is the active downloaded model.

## Compatibility notes

- The app endpoint is OpenAI Chat Completions-compatible, not the OpenAI Responses API.
- Pi should use `api: "openai-completions"`.
- `supportsDeveloperRole` should be disabled so Pi sends a standard `system` prompt.
- `supportsReasoningEffort` should be disabled because the LiteRT Server endpoint ignores reasoning parameters.
- The local `.litertlm` model may not be tool-call trained. Pi can connect to it, but coding-agent tool-use quality depends on the installed model.
## Avoid immediate auto-compaction

Pi's default compaction reserves more tokens than small local configs provide. If `contextWindow` is set to `8192`, Pi can compact immediately after a short response because its default reserve is `16384` tokens.

This project includes `.pi/settings.json` with smaller local-model compaction values:

```json
{
  "compaction": {
    "enabled": true,
    "reserveTokens": 1024,
    "keepRecentTokens": 2048
  },
  "defaultThinkingLevel": "off"
}
```

Run `/trust` in Pi, restart Pi, and make sure your `~/.pi/agent/models.json` uses the context windows and `maxTokens` values from `.pi/agent/models.litert-server.example.json`:

- `gemma-4-e2b-it`: `contextWindow: 128000`, `maxTokens: 128000`
- `gemma-4-e4b-it`: `contextWindow: 32000`, `maxTokens: 32000`

If compaction still interrupts testing, temporarily disable it in `.pi/settings.json`:

```json
{ "compaction": { "enabled": false } }
```

