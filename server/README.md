# Kokoro TTS Server

1. Install Docker
2. Run: `docker compose up -d`
3. Test: `curl http://localhost:8880/health`

Server runs on port 8880. Update `KOKORO_SERVER_URL` in `local.properties` to match your server's IP address.
