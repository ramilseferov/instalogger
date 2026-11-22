#!/usr/bin/env bash
set -e

BASE_DIR="/storage/emulated/0/instalogger"
cd "$BASE_DIR"

echo "============================================================"
echo "  Starting InstaLogger End-to-End Integration Test          "
echo "============================================================"

# Ensure clean state before test
bash ./instalogger stop 2>/dev/null || true

# 1. Run build script
bash scripts/build.sh

# 2. Start background daemon
bash ./instalogger start
sleep 3

# 3. Verify status
STATUS_OUT=$(bash ./instalogger status)
echo "$STATUS_OUT" | grep -q "Running"
echo "[✓] Daemon is running."

# 4. Test API Health
HEALTH_RES=$(curl -s http://127.0.0.1:3000/api/health)
echo "$HEALTH_RES" | grep -q '"status":"ok"'
echo "[✓] API health endpoint verified: $HEALTH_RES"

# 5. Test Static SPA Serving
INDEX_HTML=$(curl -s http://127.0.0.1:3000/)
echo "$INDEX_HTML" | grep -q "InstaLogger — Instagram Monitor & Archiver"
echo "[✓] Static HTML serving verified."

# 6. Test Registering Target
ADD_RES=$(curl -s -X POST http://127.0.0.1:3000/api/targets \
  -H "Content-Type: application/json" \
  -d '{"username": "e2e_sample_user", "turbo": true}')
echo "$ADD_RES" | grep -q '"status":"ok"'
TARGET_ID=$(echo "$ADD_RES" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "[✓] Target successfully registered with ID: $TARGET_ID"

# 7. Test Fetching Targets List
LIST_RES=$(curl -s http://127.0.0.1:3000/api/targets)
echo "$LIST_RES" | grep -q "e2e_sample_user"
echo "[✓] Targets list endpoint returned created profile."

# 8. Test Toggling Turbo Mode
TURBO_RES=$(curl -s -X POST "http://127.0.0.1:3000/api/targets/$TARGET_ID/turbo" \
  -H "Content-Type: application/json" \
  -d '{"turbo": false}')
echo "$TURBO_RES" | grep -q '"turbo":false'
echo "[✓] Turbo mode toggle endpoint verified."

# 9. Test Logs Endpoint
LOGS_RES=$(curl -s "http://127.0.0.1:3000/api/targets/$TARGET_ID/logs")
echo "$LOGS_RES" | grep -q '"status":"ok"'
echo "[✓] Logs endpoint verified."

# 10. Stop daemon gracefully
bash ./instalogger stop
sleep 2

# 11. Verify stopped state
STOP_OUT=$(bash ./instalogger status || true)
echo "$STOP_OUT" | grep -q "Stopped"
echo "[✓] Daemon stopped cleanly."

echo "============================================================"
echo "  [✓] All End-to-End Integration Tests Passed!              "
echo "============================================================"
