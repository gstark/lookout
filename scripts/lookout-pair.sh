#!/usr/bin/env bash
# Finds Lookout phones on your tailnet and pairs this machine with one.
#
#   scripts/lookout-pair.sh discover               List the Lookout phones that this machine can reach.
#   scripts/lookout-pair.sh pair <host> [name]     Ask the phone for access. Prints a code and a pair id.
#   scripts/lookout-pair.sh wait <host> <pair_id>  Wait for the person to tap Allow. Prints the token.
#
# <host> is ip[:port]. Use the IPv4 address that discover prints: a token from a tailnet pairing
# works only from the address that paired. Needs curl and jq. discover also needs the tailscale CLI.
# Set LOOKOUT_PORT when the phones do not use port 8080.
set -euo pipefail

port="${LOOKOUT_PORT:-8080}"

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

base_url() { case "$1" in *:*) echo "http://$1" ;; *) echo "http://$1:$port" ;; esac; }

# Calls the server. Prints the body. On an HTTP error it prints the message of the server and fails.
call() { # max_seconds curl-args...
  local max="$1" body code; shift
  body=$(mktemp)
  code=$(curl -sS -o "$body" -w '%{http_code}' --max-time "$max" "$@") || { rm -f "$body"; return 1; }
  if [ "${code:0:1}" != 2 ]; then
    echo "error $code: $(jq -r '.error | "\(.code): \(.message)"' "$body" 2>/dev/null || cat "$body")" >&2
    rm -f "$body"
    return 1
  fi
  cat "$body"; rm -f "$body"
}

discover() {
  local found
  found=$(tailscale status --json |
    jq -r '.Peer[]? | select(.Online) | [(.TailscaleIPs // [] | map(select(contains(":") | not)) | first), .HostName] | select(.[0]) | @tsv' |
    { while IFS=$'\t' read -r ip name; do
        ( hello=$(curl -fsS --max-time 3 "http://$ip:$port/v1/hello" 2>/dev/null) || exit 0
          [ "$(jq -r '.service? // empty' <<<"$hello" 2>/dev/null)" = lookout ] || exit 0
          printf '%s\t%s\t%s\tpairing %s\n' "$ip:$port" "$name" "$(jq -r .device <<<"$hello")" "$(jq -r .pairing <<<"$hello")" ) &
      done; wait; })
  if [ -z "$found" ]; then echo "No Lookout phone answered on port $port." >&2; exit 1; fi
  echo "$found"
}

pair() {
  local host="$1" name="${2:-$(hostname -s)}" base reply
  base=$(base_url "$host")
  reply=$(call 10 -X POST -H 'Content-Type: application/json' --data "$(jq -n --arg name "$name" '{name: $name}')" "$base/v1/pair")
  echo "code: $(jq -r .code <<<"$reply")"
  echo "pair_id: $(jq -r .pair_id <<<"$reply")"
  echo "Tell the person at the phone the code. They must tap Allow in $(( $(jq -r .expires_in_ms <<<"$reply") / 1000 )) seconds."
  echo "Then run: $0 wait $host $(jq -r .pair_id <<<"$reply")"
}

wait_for() {
  local host="$1" id="$2" base reply token device
  base=$(base_url "$host")
  reply=$(call 130 "$base/v1/pair/$id?wait_ms=120000")
  if [ "$(jq -r .state <<<"$reply")" != approved ]; then echo "The request still waits on the phone. Run the same command again." >&2; exit 3; fi
  token=$(jq -r .token <<<"$reply")
  device=$(jq -r .device <<<"$reply")
  echo "url: $base/mcp"
  echo "token: $token"
  jq -r 'if .bound_to then "bound_to: \(.bound_to)" else empty end' <<<"$reply"
  echo "claude mcp add --scope user --transport http $device $base/mcp --header \"Authorization: Bearer $token\""
}

case "${1:-}" in
  discover) [ $# -eq 1 ] || usage; discover ;;
  pair) [ $# -ge 2 ] && [ $# -le 3 ] || usage; pair "${@:2}" ;;
  wait) [ $# -eq 3 ] || usage; wait_for "$2" "$3" ;;
  *) usage ;;
esac
