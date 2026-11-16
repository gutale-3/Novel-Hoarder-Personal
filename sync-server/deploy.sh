#!/usr/bin/env bash
# Publishes the sync service to Cloudflare Workers and prints its address.
# Needs a Cloudflare API token ("Edit Cloudflare Workers" template) in CLOUDFLARE_API_TOKEN.
set -euo pipefail
cd "$(dirname "$0")"
API=https://api.cloudflare.com/client/v4
AUTH="Authorization: Bearer ${CLOUDFLARE_API_TOKEN:?set CLOUDFLARE_API_TOKEN}"
KV_ID=65ac7c9b8b8e4bf48b26293a63a152bf   # the "inkvault-sync" KV namespace

ACCOUNT=$(curl -fsS "$API/accounts" -H "$AUTH" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"][0]["id"])')
curl -fsS -X PUT "$API/accounts/$ACCOUNT/workers/scripts/inkvault-sync" -H "$AUTH" \
  -F "metadata={\"main_module\":\"worker.js\",\"compatibility_date\":\"2024-11-01\",\"bindings\":[{\"type\":\"kv_namespace\",\"name\":\"SYNC\",\"namespace_id\":\"$KV_ID\"}]};type=application/json" \
  -F "worker.js=@worker.js;type=application/javascript+module" > /dev/null
curl -fsS -X POST "$API/accounts/$ACCOUNT/workers/scripts/inkvault-sync/subdomain" -H "$AUTH" \
  -H "Content-Type: application/json" -d '{"enabled":true}' > /dev/null
SUB=$(curl -fsS "$API/accounts/$ACCOUNT/workers/subdomain" -H "$AUTH" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["subdomain"])')
echo "https://inkvault-sync.$SUB.workers.dev"
