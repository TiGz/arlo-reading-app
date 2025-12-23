#!/bin/bash
# Save current Supabase access token for adam@higherleveldev.com account
# This allows switching between accounts by copying token files

set -e

TOKEN_FILE="$HOME/.supabase/access-token"
BACKUP_FILE="$HOME/.supabase/access-token.adam@hld"

if [ ! -f "$TOKEN_FILE" ]; then
    echo "❌ No access token found at $TOKEN_FILE"
    echo "   Run 'supabase login --no-browser' first"
    exit 1
fi

cp "$TOKEN_FILE" "$BACKUP_FILE"
echo "✅ Saved token to $BACKUP_FILE"
echo ""
echo "To restore later, run:"
echo "  cp ~/.supabase/access-token.adam@hld ~/.supabase/access-token"
