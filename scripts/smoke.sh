#!/usr/bin/env bash
# Smoke test: calls every Lookout endpoint and saves the images to ./smoke-out/.
# Usage: scripts/smoke.sh <ip[:port]> <token>
# Exits non-zero on the first failure.
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <ip[:port]> <token>" >&2
  exit 2
fi
host="$1"
case "$host" in *:*) ;; *) host="$host:8080" ;; esac
base="http://$host"
token="$2"
out="./smoke-out"
mkdir -p "$out"
auth=(-H "Authorization: Bearer $token")
fail=0

check() { # name expected_status curl-args...
  local name="$1" want="$2"; shift 2
  local code
  code=$(curl -sS -o "$out/$name" -w '%{http_code}' --max-time 60 "${auth[@]}" "$@") || code="curl-error"
  if [ "$code" = "$want" ]; then
    printf 'ok    %-28s %s\n' "$name" "$code"
  else
    printf 'FAIL  %-28s got %s, want %s\n' "$name" "$code" "$want"
    head -c 300 "$out/$name" 2>/dev/null; echo
    fail=1
  fi
}

check describe.json      200 "$base/v1/describe"
check status.json        200 "$base/v1/status"
check frame.jpg          200 "$base/v1/frame?fresh=true&width=1024"
check frame-grid.jpg     200 "$base/v1/frame?grid=8x6&stamp=true"
check frame.json         200 "$base/v1/frame?format=json&width=256"
check capture.jpg        200 -X POST -H "Content-Type: application/json" -d '{"width":1568}' "$base/v1/capture"
check roi-set.json       200 -X PUT -H "Content-Type: application/json" -d '{"from":"C2","to":"F5"}' "$base/v1/rois/smoke"
check rois.json          200 "$base/v1/rois"
check frame-roi.jpg      200 "$base/v1/frame?roi=smoke"
check baseline.json      200 -X POST "$base/v1/baselines/smoke"
check baselines.json     200 "$base/v1/baselines"
check diff.json          200 -X POST -H "Content-Type: application/json" -d '{"a":"smoke"}' "$base/v1/diff"
check wait.json          200 -X POST -H "Content-Type: application/json" -d '{"condition":"change","timeout_ms":2000}' "$base/v1/wait"
check history.jpg        200 "$base/v1/history?since=-5m&tiles=6"
check camera.json        200 -X PUT -H "Content-Type: application/json" -d '{}' "$base/v1/camera"
check say.json           200 -X POST -H "Content-Type: application/json" -d '{"text":"Lookout smoke test"}' "$base/v1/say"
check events.json        200 "$base/v1/events?since_id=0"
check openapi.json       200 "$base/openapi.json"
check index.html         200 "$base/?token=$token"
check mcp-init.json      200 -X POST -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' "$base/mcp"
check mcp-tools.json     200 -X POST -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' "$base/mcp"
check roi-delete.json    200 -X DELETE "$base/v1/rois/smoke"
check baseline-del.json  200 -X DELETE "$base/v1/baselines/smoke"

# The stream never ends, so take its first second only.
if curl -sS --max-time 2 "${auth[@]}" "$base/v1/stream.mjpeg?fps=2" -o "$out/stream.mjpeg" 2>/dev/null || [ -s "$out/stream.mjpeg" ]; then
  printf 'ok    %-28s %s bytes\n' "stream.mjpeg" "$(wc -c < "$out/stream.mjpeg" | tr -d ' ')"
else
  printf 'FAIL  %-28s empty\n' "stream.mjpeg"; fail=1
fi

# A wrong token must be refused.
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer wrong" "$base/v1/status")
if [ "$code" = 401 ]; then printf 'ok    %-28s %s\n' "wrong-token" "$code"; else printf 'FAIL  %-28s got %s\n' "wrong-token" "$code"; fail=1; fi

exit $fail
