#!/bin/bash
# Ensure we're logged into Supabase CLI with the Arlo account
# Email: adam@higherleveldev.com

set -e

EXPECTED_EMAIL="adam@higherleveldev.com"

echo "🔍 Checking Supabase CLI login status..."

# Check if logged in and get current account
CURRENT_ORGS=$(supabase orgs list 2>&1) || {
    echo "❌ Not logged in to Supabase CLI"
    echo ""
    echo "🔑 Logging in (no browser auto-open)..."
    supabase login --no-browser
    exit 0
}

# Try to identify the account by checking orgs
# If we see the expected org pattern, we're on the right account
if echo "$CURRENT_ORGS" | grep -qi "Adam Personal"; then
    echo "✅ Already logged in as expected account"
    echo ""
    echo "Current organizations:"
    echo "$CURRENT_ORGS" | grep -v "A new version"
else
    echo "⚠️  Currently logged in to a different Supabase account"
    echo ""
    echo "Current organizations:"
    echo "$CURRENT_ORGS" | grep -v "A new version"
    echo ""
    echo "Expected account: $EXPECTED_EMAIL"
    echo ""
    read -p "Switch to the Arlo account? (y/n) " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "🔄 Logging out..."
        # Clear the existing token
        rm -f ~/.supabase/access-token 2>/dev/null || true
        echo "🔑 Logging in as $EXPECTED_EMAIL..."
        echo "📋 Copy the URL below and open in an incognito browser:"
        echo ""
        supabase login --no-browser
    else
        echo "Keeping current account."
    fi
fi
