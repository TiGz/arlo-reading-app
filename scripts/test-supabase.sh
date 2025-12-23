#!/bin/bash
# Test Supabase Edge Functions for Arlo Reading App

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Load secrets
if [ -f "$PROJECT_DIR/secrets.properties" ]; then
    source <(grep = "$PROJECT_DIR/secrets.properties" | sed 's/ *= */=/g')
else
    echo "❌ secrets.properties not found"
    exit 1
fi

BASE_URL="$SUPABASE_URL/functions/v1"

echo "🧪 Testing Supabase Edge Functions"
echo "=================================="
echo ""

# Test sync-stats
echo "📤 Testing sync-stats..."
SYNC_RESULT=$(/usr/bin/curl -s "$BASE_URL/sync-stats" \
  -H "Authorization: Bearer $SUPABASE_ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "device_id": "test-device-cli",
    "device_name": "CLI Test Device",
    "app_version": "1.0.0-test"
  }')

if echo "$SYNC_RESULT" | grep -q '"success":true'; then
    echo "✅ sync-stats: OK"
else
    echo "❌ sync-stats: FAILED"
    echo "$SYNC_RESULT"
fi
echo ""

# Test query-stats (today)
echo "📊 Testing query-stats (today)..."
QUERY_RESULT=$(/usr/bin/curl -s "$BASE_URL/query-stats?q=today&device=CLI%20Test%20Device" \
  -H "Authorization: Bearer $SUPABASE_ANON_KEY")

if [ "$QUERY_RESULT" != "" ]; then
    echo "✅ query-stats (today): OK"
    echo "   Response: $QUERY_RESULT"
else
    echo "❌ query-stats: FAILED"
fi
echo ""

# Test query-stats (week)
echo "📅 Testing query-stats (week)..."
WEEK_RESULT=$(/usr/bin/curl -s "$BASE_URL/query-stats?q=week&device=CLI%20Test%20Device" \
  -H "Authorization: Bearer $SUPABASE_ANON_KEY")

if [ "$WEEK_RESULT" != "" ]; then
    echo "✅ query-stats (week): OK"
else
    echo "❌ query-stats (week): FAILED"
fi
echo ""

# Test query-stats (lifetime)
echo "🏆 Testing query-stats (lifetime)..."
LIFETIME_RESULT=$(/usr/bin/curl -s "$BASE_URL/query-stats?q=lifetime&device=CLI%20Test%20Device" \
  -H "Authorization: Bearer $SUPABASE_ANON_KEY")

if [ "$LIFETIME_RESULT" != "" ]; then
    echo "✅ query-stats (lifetime): OK"
else
    echo "❌ query-stats (lifetime): FAILED"
fi
echo ""

echo "=================================="
echo "🎉 All tests completed!"
