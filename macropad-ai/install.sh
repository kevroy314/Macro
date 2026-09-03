#!/usr/bin/env bash
#
# Set up the MacroPad AI daemon on this machine.
#
# The default install is reachable on your home network only. It opens no port on your
# router, registers no domain, and needs no certificate authority. Reaching it from
# outside the house is offered at the end, and defaults to no.
#
# Safe to re-run: every step checks whether it is already done.

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_FILE="$HOME/.macropad-install"
COMPOSE_PROJECT="macropad-ai"
PORT=8321

# ------------------------------------------------------------------ presentation

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'; RESET=$'\033[0m'
else
    BOLD=""; DIM=""; RED=""; GREEN=""; RESET=""
fi

step()  { printf '\n%s==>%s %s%s\n' "$BOLD" "$RESET" "$1" "$RESET"; }
info()  { printf '    %s\n' "$1"; }
note()  { printf '    %s%s%s\n' "$DIM" "$1" "$RESET"; }
ok()    { printf '    %s+%s %s\n' "$GREEN" "$RESET" "$1"; }
fail()  { printf '\n%serror:%s %s\n\n' "$RED" "$RESET" "$1" >&2; exit 1; }

ask() {
    # ask "question" "y"|"n"  -> returns 0 for yes
    local prompt="$1" default="${2:-n}" reply hint="[y/N]"
    [ "$default" = "y" ] && hint="[Y/n]"
    if [ ! -t 0 ]; then
        [ "$default" = "y" ]
        return
    fi
    read -r -p "    $prompt $hint " reply || reply=""
    reply="${reply:-$default}"
    [[ "$reply" =~ ^[Yy] ]]
}

done_step()    { grep -qxF "$1" "$STATE_FILE" 2>/dev/null; }
mark_done()    { touch "$STATE_FILE"; grep -qxF "$1" "$STATE_FILE" || echo "$1" >> "$STATE_FILE"; }

# ------------------------------------------------------------------ 0: preflight

OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
    Linux|Darwin) ;;
    *) fail "This script handles Linux and macOS. On Windows, run install.ps1 first — it sets up WSL and then calls this script inside it." ;;
esac

if grep -qi microsoft /proc/version 2>/dev/null; then
    IN_WSL=1
else
    IN_WSL=0
fi

cat <<BANNER

  ${BOLD}MacroPad AI — setup${RESET}

  A small server on this machine that estimates the macros in photographs of
  food, using your own Claude subscription.

  ${DIM}platform    $OS $ARCH$([ "$IN_WSL" = 1 ] && echo " (WSL)")
  install to  $REPO_DIR
  reachable   your home network only, unless you choose otherwise at the end${RESET}

BANNER

EXISTING=0
if [ -f "$REPO_DIR/data/macropad.db" ]; then
    EXISTING=1
    info "Found an existing install — your data and settings will be left alone."
fi

MISSING=()
command -v docker >/dev/null 2>&1 || MISSING+=("Docker")
if [ "$OS" = "Darwin" ]; then
    command -v colima >/dev/null 2>&1 || MISSING+=("Colima")
fi

if [ ${#MISSING[@]} -gt 0 ]; then
    info "This will install: ${MISSING[*]}"
    info "You will be asked for your password once."
    echo
    ask "Continue?" y || { echo; info "Nothing was changed."; exit 0; }
fi

# ------------------------------------------------------- 1: container runtime

step "Container runtime"

install_docker_linux() {
    curl -fsSL https://get.docker.com | sh
    sudo usermod -aG docker "$USER" || true
}

install_docker_macos() {
    if ! command -v brew >/dev/null 2>&1; then
        info "Installing Homebrew first…"
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
        eval "$(/opt/homebrew/bin/brew shellenv 2>/dev/null || /usr/local/bin/brew shellenv)"
    fi
    # Colima rather than Docker Desktop: installs from the command line, no GUI step
    # and no licensing question.
    brew install colima docker docker-compose
}

if command -v docker >/dev/null 2>&1; then
    ok "Docker is already installed"
else
    case "$OS" in
        Linux)  install_docker_linux ;;
        Darwin) install_docker_macos ;;
    esac
    ok "Docker installed"
fi

if [ "$OS" = "Darwin" ] && ! docker info >/dev/null 2>&1; then
    info "Starting Colima…"
    colima start --cpu 2 --memory 4
fi

# The docker group does not apply to a shell that is already running. Re-exec once
# under it rather than telling someone to log out and back in.
if ! docker info >/dev/null 2>&1; then
    if [ "$OS" = "Linux" ] && [ -z "${MACROPAD_REEXEC:-}" ] && command -v sg >/dev/null 2>&1; then
        info "Picking up your new docker group membership…"
        export MACROPAD_REEXEC=1
        exec sg docker -c "MACROPAD_REEXEC=1 '$0' $*"
    fi
    fail "Docker is installed but not responding. If it was just installed, log out and back in, then re-run this script."
fi
ok "Docker is running"

# -------------------------------------------------------- 2: certificate + config

step "Certificate for this machine"

mkdir -p "$REPO_DIR/data"
[ -f "$REPO_DIR/data/env" ] || cp "$REPO_DIR/data/env.example" "$REPO_DIR/data/env" 2>/dev/null || touch "$REPO_DIR/data/env"

BEHIND_PROXY=0
if [ "$EXISTING" = 1 ] && [ ! -f "$REPO_DIR/data/tls/cert.pem" ]; then
    note "This install has been serving plain HTTP, which is what a reverse proxy in"
    note "front of it expects. Adding a certificate here would break that."
    if ask "Is this server behind a reverse proxy that already handles HTTPS?" y; then
        BEHIND_PROXY=1
        ok "Leaving the connection setup exactly as it is"
    fi
fi

if [ "$BEHIND_PROXY" = 0 ]; then
    docker run --rm -v "$REPO_DIR/data:/data" -e MACROPAD_DATA_DIR=/data \
        macropad-ai:latest python -m app.cli cert 2>/dev/null \
        || note "(the certificate will be made on first boot)"
fi

# ------------------------------------------------------------- 3: Claude sign-in

step "Claude sign-in"

if [ -f "$HOME/.claude/.credentials.json" ]; then
    ok "Already signed in to Claude on this machine"
else
    info "Claude Code needs a Pro, Max, Team, Enterprise, or Console plan."
    info "The free Claude tier does not include it."
    echo
    info "A sign-in link will appear. Open it, approve, and paste the code back."
    echo
    docker compose -f "$REPO_DIR/docker-compose.yml" run --rm -it macropad-ai claude \
        || fail "Sign-in did not complete. Re-run this script to try again."
fi

# ------------------------------------------------------------------- 4: start it

step "Starting the server"

cd "$REPO_DIR"
docker compose up -d --build

SCHEME="http"
[ -f "$REPO_DIR/data/tls/cert.pem" ] && SCHEME="https"

info "Waiting for it to answer…"
for _ in $(seq 1 60); do
    if curl -sfk "$SCHEME://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1; then
        ok "Server is up"
        break
    fi
    sleep 2
done

curl -sfk "$SCHEME://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1 \
    || fail "The server did not start. Check: docker compose logs macropad-ai"

# The check that catches a server which answers anyone.
CODE="$(curl -sko /dev/null -w '%{http_code}' "$SCHEME://127.0.0.1:$PORT/api/v1/jobs")"
[ "$CODE" = "401" ] || fail "The server answered an unauthenticated request with $CODE, expected 401. Do not expose this server until that is fixed."
ok "Rejects unauthenticated requests"

# ------------------------------------------------------------ 5: announce on LAN

if [ "$BEHIND_PROXY" = 0 ]; then
    step "Announcing on your network"
    # Published from the host, not the container: a bridge-networked container cannot
    # put multicast onto the LAN.
    if [ "$OS" = "Darwin" ]; then
        if ! pgrep -f "dns-sd -R MacroPad" >/dev/null 2>&1; then
            nohup dns-sd -R MacroPad _macropad._tcp local "$PORT" >/dev/null 2>&1 &
            ok "Advertising as macropad.local"
        else
            ok "Already advertising"
        fi
    elif command -v avahi-publish >/dev/null 2>&1; then
        if ! pgrep -f "avahi-publish.*MacroPad" >/dev/null 2>&1; then
            nohup avahi-publish -s MacroPad _macropad._tcp "$PORT" >/dev/null 2>&1 &
            ok "Advertising as macropad.local"
        else
            ok "Already advertising"
        fi
    else
        note "avahi-publish not found — install avahi-utils so your phone can find"
        note "this server again if your router changes its address."
    fi
fi

# -------------------------------------------------------------- 6: outside access

step "Reaching it from outside the house"

note "Not required. The app saves meals photographed away from home and sends"
note "them when it can reach this server again."
echo
note "Turning this on needs a free Tailscale account and their app on each phone."
echo

if ask "Set up access from anywhere?" n; then
    if ! command -v tailscale >/dev/null 2>&1; then
        curl -fsSL https://tailscale.com/install.sh | sh
    fi
    sudo tailscale up --hostname macropad
    sudo tailscale serve --bg "$PORT"
    ok "Reachable on your tailnet"
    note "Install the Tailscale app on each phone, then re-scan the setup code below."
fi

mark_done "install"

# ------------------------------------------------------------------ 7: hand off

step "Pair your phone"

docker compose exec -T macropad-ai python -m app.cli setup-qr || true
docker compose exec -T macropad-ai python -m app.cli release-qr 2>/dev/null || \
    note "No app build is published yet — install MacroPad from GitHub Releases."

cat <<DONE

  ${BOLD}Done.${RESET}

  ${DIM}data          $REPO_DIR/data
  logs          docker compose logs -f macropad-ai
  setup code    docker compose exec macropad-ai python -m app.cli setup-qr
  invite        Settings -> Share & Invite, in the app

  Your food photos go from this machine to Anthropic's API — that is what
  produces the estimate. Nothing is sent to the MacroPad authors.${RESET}

DONE
