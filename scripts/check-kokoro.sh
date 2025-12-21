#!/bin/bash
# Verify Kokoro TTS server connectivity and list available voices

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Read KOKORO_SERVER_URL from local.properties
LOCAL_PROPS="$PROJECT_ROOT/local.properties"

if [[ ! -f "$LOCAL_PROPS" ]]; then
    echo "Error: local.properties not found at $LOCAL_PROPS"
    exit 1
fi

KOKORO_URL=$(grep "^KOKORO_SERVER_URL=" "$LOCAL_PROPS" | cut -d'=' -f2-)

if [[ -z "$KOKORO_URL" ]]; then
    echo "Error: KOKORO_SERVER_URL not set in local.properties"
    exit 1
fi

echo "Kokoro TTS Server Check"
echo "======================="
echo "Server URL: ${KOKORO_URL%%@*}@***" # Hide credentials in output
echo ""

# Health check
echo "1. Health Check"
echo "---------------"
HEALTH_RESPONSE=$(curl -s -w "\n%{http_code}" "$KOKORO_URL/health" 2>&1) || true
HTTP_CODE=$(echo "$HEALTH_RESPONSE" | tail -n1)
BODY=$(echo "$HEALTH_RESPONSE" | sed '$d')

if [[ "$HTTP_CODE" == "200" ]]; then
    echo "Status: OK (HTTP $HTTP_CODE)"
    echo "Response: $BODY"
else
    echo "Status: FAILED (HTTP $HTTP_CODE)"
    echo "Response: $BODY"
    echo ""
    echo "Server may be down or URL may be incorrect."
    exit 1
fi

echo ""

# List voices
echo "2. Available Voices"
echo "-------------------"
VOICES_RESPONSE=$(curl -s -w "\n%{http_code}" "$KOKORO_URL/v1/audio/voices" 2>&1) || true
HTTP_CODE=$(echo "$VOICES_RESPONSE" | tail -n1)
BODY=$(echo "$VOICES_RESPONSE" | sed '$d')

if [[ "$HTTP_CODE" == "200" ]]; then
    echo "Status: OK (HTTP $HTTP_CODE)"
    echo ""
    # Pretty print JSON if jq is available
    if command -v jq &> /dev/null; then
        echo "$BODY" | jq -r '.voices[] | "  - \(.voice_id): \(.name // .voice_id)"' 2>/dev/null || echo "$BODY"
    else
        echo "$BODY"
    fi
else
    echo "Status: FAILED (HTTP $HTTP_CODE)"
    echo "Response: $BODY"
fi

echo ""

# Test TTS synthesis
echo "3. TTS Synthesis Test"
echo "---------------------"
TEST_TEXT="Hello, this is a test."
TTS_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$KOKORO_URL/dev/captioned_speech" \
    -H "Content-Type: application/json" \
    -d "{\"input\": \"$TEST_TEXT\", \"voice\": \"bf_emma\", \"stream\": false}" 2>&1) || true
HTTP_CODE=$(echo "$TTS_RESPONSE" | tail -n1)

if [[ "$HTTP_CODE" == "200" ]]; then
    echo "Status: OK (HTTP $HTTP_CODE)"
    echo "TTS synthesis working for voice 'bf_emma'"
else
    BODY=$(echo "$TTS_RESPONSE" | sed '$d')
    echo "Status: FAILED (HTTP $HTTP_CODE)"
    echo "Response: $BODY"
    echo ""
    echo "Note: The server may use different voice IDs than expected."
fi

echo ""
echo "======================="
echo "Check complete."
