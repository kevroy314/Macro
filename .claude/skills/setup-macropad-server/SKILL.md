---
name: setup-macropad-server
description: Set up the self-hosted MacroPad AI daemon on this machine — container runtime, LAN TLS, mDNS, Claude sign-in, and pairing the phone. Use when someone asks to install, set up, or host the MacroPad server, or says "set this up for me" in this repo.
---

# Setting up the MacroPad AI daemon

You are installing a small server on someone's own computer. It runs Claude Code sessions
against **their** Claude subscription to estimate the macros in photographs of food.

Read `docs/self-hosting.md` for what the user has already been told; this file is how you
actually do it, and where the sharp edges are.

## Hold these rules

1. **Never open a port on the router, and never suggest UPnP.** This machine holds live
   Claude credentials. The default install is reachable on the home network only.
2. **Never register a domain, configure dynamic DNS, or request a public certificate.** If
   the user wants access from outside, the answer is the optional Tailscale step below —
   nothing else.
3. **Never widen the agent's tool allowlist** in `app/config.py`. `Read`, `WebSearch` and
   `WebFetch` is the boundary that makes it safe to run a credentialed session over
   photographs of text that strangers wrote.
4. **Ask before installing system software**, once, listing everything. Then don't ask
   again mid-run.
5. **Never put the API key, or any key you generate, into a file you commit.** Everything
   secret lives under `macropad-ai/data/`, which is git-ignored.
6. If the user already has a working setup, **do not reconfigure it.** Ask what they want
   changed. An existing public hostname with a real certificate is a supported
   configuration, not a problem to fix.

## Before you start

Check what's already true rather than assuming a bare machine:

```bash
uname -s; uname -m                      # platform
docker --version 2>/dev/null            # container runtime?
ls ~/.claude/.credentials.json 2>/dev/null   # already signed in to Claude?
ls macropad-ai/data/ 2>/dev/null        # an existing install?
```

If `macropad-ai/data/macropad.db` exists, this is an existing install. Say so, and ask
before touching anything.

There is an `install.sh` that does all of this. Prefer running it and reading its output
over doing the steps by hand — then you only have to explain what happened. Do the steps
manually when it fails, or when the user wants something it doesn't offer.

## The sequence

Work in this order. Each step is verifiable — verify before moving on, and if a step
fails, stop and report rather than continuing with a broken foundation.

### 1. Container runtime

- **Linux**: `curl -fsSL https://get.docker.com | sh`, then `sudo usermod -aG docker
  "$USER"`. The group doesn't apply to the current shell — use `sg docker -c '...'` for
  the rest of the run rather than telling the user to log out.
- **macOS**: Homebrew, then `brew install colima docker docker-compose` and
  `colima start --cpu 2 --memory 4`. Prefer Colima over Docker Desktop: no GUI step, no
  licensing question.
- **Windows**: this must run inside WSL 2. Linux containers need a Linux kernel and
  Windows hasn't got one. `wsl --install -d Ubuntu` from an elevated PowerShell, which
  reboots **only if** Virtual Machine Platform wasn't already enabled. After the reboot,
  continue inside the WSL distro.

Verify: `docker run --rm hello-world`.

### 2. TLS for a machine with no public name

No authority will certify a home address, so the server makes its own certificate and
the phone is told to trust exactly that one. The daemon does this — don't hand-roll it
with `openssl`, because the pin has to be the SHA-256 of the SubjectPublicKeyInfo and
this gets that right:

```bash
docker compose run --rm macropad-ai python -m app.cli cert
```

It is idempotent and **must stay that way**: replacing an existing certificate
invalidates the pin on every phone already paired with this server.

The daemon serves HTTPS if and only if `data/tls/cert.pem` exists. That is what keeps an
existing install working — a server behind a reverse proxy that terminates TLS has no
certificate here and must keep speaking plain HTTP, or the proxy in front of it breaks.
**If you find an existing install with no `data/tls/`, ask before creating one.**

### 3. Announce it on the network

The daemon advertises nothing itself: a bridge-networked container cannot put multicast
onto the LAN. Publish from the host instead, so the app can find the server again after
DHCP moves it.

```bash
# Linux (needs avahi-utils)
avahi-publish -s MacroPad _macropad._tcp 8321 &

# macOS
dns-sd -R MacroPad _macropad._tcp local 8321 &
```

Make it survive a reboot — a systemd unit on Linux, a launchd plist on macOS.

### 4. Claude sign-in

Reuse `~/.claude` if it exists. Otherwise sign in **inside the container**, so nothing
Node-related lands on the host:

```bash
docker compose run --rm -it macropad-ai claude
```

Claude Code needs a Pro, Max, Team, Enterprise, or Console plan; the free tier does not
include it. If the account has no eligible plan, say so once and stop — don't retry a
loop that cannot succeed. `ANTHROPIC_API_KEY` in `data/env` is the pay-per-token
alternative, and it is a different billing arrangement, so present it as one.

### 5. Start it, and check it rejects as well as accepts

```bash
docker compose up -d --build
curl -sfk https://macropad.local:8321/api/v1/health                        # expect 200
curl -sko /dev/null -w '%{http_code}\n' https://macropad.local:8321/api/v1/jobs   # expect 401
```

The 401 is the important one. A server that answers unauthenticated requests is
misconfigured, and only this check catches it.

### 6. Pair the phone

```bash
docker compose exec macropad-ai python -m app.cli setup-qr    # url + key + cert pin
docker compose exec macropad-ai python -m app.cli release-qr  # APK download
```

`setup-qr` takes an optional user id; with no argument it pairs the primary user. If the
server is reachable at an address the daemon can't work out for itself, set
`MACROPAD_PUBLIC_URL` in `data/env`.

The setup code is `macropad://setup?url=…&key=…&pin=…`. The `pin` is what makes the
self-signed certificate work; omit it and the phone will refuse to connect.

### 7. Optional — access from outside the house

**Offer this as a question, defaulting to no.** It costs a free account and a second app
on every phone, and most people don't need it: the app already queues jobs photographed
away from home and sends them when the server is reachable.

If they want it:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --hostname macropad
sudo tailscale serve --bg 8321
```

That yields a real certificate on a `.ts.net` name with no open ports. The user then
installs the Tailscale app on each phone and re-scans the setup code — this time with no
pin, because the certificate is publicly trusted.

## When you're done

Tell them, briefly:
- the address, and that it works at home
- that meals photographed elsewhere send themselves later
- where the data is (`macropad-ai/data/`) and that photos are swept after 90 days
- how to invite someone else (Settings → Share & Invite)
- that their food photos go from their server to Anthropic's API, because that's what
  produces the estimate

## Troubleshooting

| Symptom | Cause |
|---|---|
| Phone says the certificate doesn't match | Re-generated the certificate without re-scanning the setup code. Print a fresh QR. |
| App can't find the server after a router restart | DHCP moved it. Settings → **Find on network**, or check mDNS is running. |
| Jobs stay "queued" at home | The phone is on a guest network, or on cellular with wifi off. |
| Estimates fail with an auth error | `docker compose exec macropad-ai claude` and sign in again. |
| Estimates fail but the daemon looks healthy | Run `python -m app.cli doctor`. A VPN on the host can drop port 53 out of Docker's bridge while leaving other traffic alone — the daemon serves fine and every estimate fails. `docker-compose.doh.yml` resolves over HTTPS instead. |
| Nothing works after a reboot on Windows | WSL doesn't start at boot on its own. This is a known rough edge — a scheduled task is the workaround. |
