#!/bin/bash
set -e

# The host's ~/.claude is bind-mounted at $CLAUDE_CONFIG_DIR so the container and
# the host CLI share one .credentials.json — an isolated copy would break the host
# the first time either side rotated the OAuth refresh token.
#
# ~/.claude.json is different: it is rewritten constantly (atomic rename), which a
# single-file bind mount cannot follow. We seed a container-local copy once, from a
# read-only mount, and let the container own it from then on. Nothing auth-critical
# lives in it.
if [ ! -f "$HOME/.claude.json" ] && [ -f /seed/claude.json ]; then
    cp /seed/claude.json "$HOME/.claude.json"
    chmod 600 "$HOME/.claude.json"
fi

mkdir -p "$MACROPAD_DATA_DIR/jobs"

# Serve HTTPS only when a certificate is actually present.
#
# This is what keeps an existing install working. A server behind a reverse proxy that
# terminates TLS has no certificate here and must keep speaking plain HTTP — switching
# it to HTTPS would break the proxy_pass in front of it. A fresh LAN install has a
# certificate, and gets TLS without being asked.
# Only for the server. `docker compose run ... python -m app.cli` and
# `docker compose exec ... claude` come through here too, and appending uvicorn's
# flags to those makes them read the flags as their own arguments.
if [ "${1:-}" = "uvicorn" ]; then
    CERT="$MACROPAD_DATA_DIR/tls/cert.pem"
    KEY="$MACROPAD_DATA_DIR/tls/key.pem"
    if [ -f "$CERT" ] && [ -f "$KEY" ]; then
        echo "macropad-ai: serving HTTPS with $CERT"
        set -- "$@" --ssl-certfile "$CERT" --ssl-keyfile "$KEY"
    else
        echo "macropad-ai: serving plain HTTP (no certificate in $MACROPAD_DATA_DIR/tls)"
    fi
fi

exec "$@"
