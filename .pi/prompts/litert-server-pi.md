# Connect Pi to LiteRT Server

Use this checklist when connecting `pi-coding-agent` to the Android LiteRT Server app.

1. Start LiteRT Server on the Android device and load a downloaded `.litertlm` model.
2. Start the HTTP server from the app's Server tab.
3. Copy the shown bearer token.
4. If Pi runs off-device, forward the active port:

   ```bash
   adb forward tcp:8080 tcp:8080
   ```

   Replace `8080` with `8081` or `8082` if the app selected that port.

5. Export the token in the shell that starts Pi:

   ```bash
   export LITERT_SERVER_TOKEN='<token shown in app>'
   ```

6. Merge `.pi/agent/models.litert-server.example.json` into `~/.pi/agent/models.json`.
7. Launch Pi and select `/model → litert-server → gemma-4-e2b-it` or `gemma-4-e4b-it`.

Smoke-test commands:

```bash
curl http://127.0.0.1:8080/health

curl http://127.0.0.1:8080/v1/models \
  -H "Authorization: Bearer $LITERT_SERVER_TOKEN"

curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer $LITERT_SERVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-e2b-it",
    "messages": [
      {"role": "user", "content": "Say hello from LiteRT."}
    ],
    "stream": false
  }'
```

Expected constraints:

- `/health` is unauthenticated.
- `/v1/models` and `/v1/chat/completions` require `Authorization: Bearer <token>`.
- The endpoint uses OpenAI Chat Completions format.
- The installed `.litertlm` model may not reliably emit tool calls, so Pi coding-agent quality depends on the selected model.
