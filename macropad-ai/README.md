# macropad-ai

The macro-estimation daemon behind MacroPad's AI entry.

You photograph a meal — or a nutrition label, a menu, a DoorDash order summary, a
paper receipt — add a sentence of context, and this runs a Claude Code session that
researches it and returns protein/carbs/fat. If a missing detail would move the
calorie estimate by more than a threshold you set, it asks one or two short questions
that you answer from a phone notification.

Everything runs on your machine, on your own Claude credentials.

## Quick start

```bash
cd macropad-ai
./install.sh
```

Installs a container runtime if needed, makes a certificate, signs you in to Claude,
starts the server, and prints QR codes to pair your phone. Safe to re-run. On Windows,
run `install.ps1` first — it sets up WSL and then calls this.

Or by hand:

```bash
cp data/env.example data/env      # optional; sane defaults either way
docker compose up -d --build
docker compose logs macropad-ai | grep 'API key'
```

The API key is generated on first boot and stored in `data/apikey`. Paste it, and the
server address, into MacroPad → Settings → AI Estimator.

```bash
docker compose exec macropad-ai python -m app.cli show-key     # print it again
docker compose exec macropad-ai python -m app.cli rotate-key   # invalidate and reissue
docker compose exec macropad-ai python -m app.cli stats        # jobs by status
docker compose exec macropad-ai python -m app.cli setup-qr     # pair a phone
docker compose exec macropad-ai python -m app.cli release-qr   # install the app
```

## Reaching it without a domain name

A server on a home network has no name a public authority will certify, so it makes its
own certificate and the phone is told to trust exactly that one — narrower than
public-CA trust, not weaker.

```bash
docker compose exec macropad-ai python -m app.cli cert
```

**The daemon serves HTTPS if and only if `data/tls/cert.pem` exists.** That is
deliberate: an existing install behind a reverse proxy that terminates TLS has no
certificate here and keeps being served plain HTTP, so the proxy in front of it goes on
working. Creating a certificate is opt-in, and it is idempotent — replacing one would
invalidate the pin on every phone already paired.

The pin is the base64 SHA-256 of the SubjectPublicKeyInfo, the same value produced by:

```bash
openssl x509 -in data/tls/cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
```

`setup-qr` puts it in the `macropad://setup` link as `pin`, so pairing a phone
establishes trust by scanning rather than by clicking through a warning.

The app finds the server again after DHCP moves it by looking for `_macropad._tcp` on
the local network. That advertisement is published by the host — `avahi-publish` on
Linux, `dns-sd` on macOS — because a bridge-networked container cannot put multicast
onto the LAN. `install.sh` sets it up.

Set `MACROPAD_PUBLIC_URL` in `data/env` if the address the daemon works out for itself
isn't the one phones should use.

## Sharing the server

Each person gets their own API key, and every job and planning thread is owned by
whoever created it. Two phones pointed at this daemon cannot see each other's photos,
estimates, or conversations — cross-user reads and writes return 404, and the web log
shows only the jobs belonging to the Google address you signed in with.

From the app: **Settings → Share & Invite → Invite someone**. It creates their key and
shows a QR code they scan from *their* Settings → Scan a setup code. Inviting the same
email again re-shares that person's existing code rather than making a second account. "Share the app
itself" shows a QR of the APK download link for installing in the first place.

From the shell:

```bash
docker compose exec macropad-ai python -m app.cli add-user nipuni "Nipuni" nipuni@example.com
docker compose exec macropad-ai python -m app.cli list-users
docker compose exec macropad-ai python -m app.cli rotate-user-key nipuni
```

Only the **first** user in `data/users.json` can mint keys — otherwise anyone who ever
held a key could add themselves back. Everyone shares the same Claude subscription, so
their jobs count against the same rate limits; `python -m app.cli stats` breaks usage
down per person, and the web log shows the same split.

Jobs created before multi-user existed are adopted by the first user on startup.

## How authentication works

`~/.claude` is bind-mounted into the container, so the agent runs on your Claude
subscription rather than per-token API billing. Host and container share one
`.credentials.json` — an isolated copy would break the host CLI the first time either
side rotated the OAuth refresh token. `~/.claude.json` is different: it is rewritten
constantly with atomic renames, which a single-file bind mount cannot follow, so it is
seeded once into a container-local copy (nothing auth-critical lives in it).

To use a pay-per-token API key instead, put `ANTHROPIC_API_KEY=` in `data/env`.

### The sandbox is the tool allowlist

Because the container holds live credentials, and because the input includes
photographs of text that anyone could have written, the agent is confined to three
tools: `Read`, `WebSearch`, `WebFetch`. No shell, no writes, no subagents.
`Read` is restricted to the job's own directory and `WebFetch` to public hosts —
both enforced by a `can_use_tool` callback in `runner.py`, and again by the CLI's own
tool set. A prompt injected via a receipt cannot reach a shell or the credential file.

## API

All `/api/v1/*` routes need `Authorization: Bearer <key>` (or `X-MacroPad-Key`),
except `/api/v1/health`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/jobs` | multipart: `text`, `images`, `threshold_mode`, `threshold_value`, `client_job_id` |
| `GET` | `/api/v1/jobs?updated_since=&limit=` | incremental poll |
| `GET` | `/api/v1/jobs/{id}` | full detail, events, answers, steps |
| `POST` | `/api/v1/jobs/{id}/answers` | `{"answers":[{"question_id","answer"}]}` — resumes the same session |
| `POST` | `/api/v1/jobs/{id}/correct` | `{"text"}` — tell it what it got wrong; resumes and revises in place |
| `POST` | `/api/v1/jobs/{id}/cancel` | interrupt and tear down |
| `POST` | `/api/v1/jobs/{id}/retry` | re-run with corrected inputs as a linked new job |
| `DELETE` | `/api/v1/jobs/{id}` | forget it entirely |
| `POST` | `/api/v1/threads` | multipart: `text`, `images`, `context`, `client_thread_id` — start a planning thread |
| `GET` | `/api/v1/threads?updated_since=&limit=` | incremental poll |
| `GET` | `/api/v1/threads/{id}` | full detail, messages, per-reply steps |
| `POST` | `/api/v1/threads/{id}/messages` | multipart: another turn on the same thread |
| `POST` | `/api/v1/threads/{id}/cancel` | interrupt the turn in flight |
| `DELETE` | `/api/v1/threads/{id}` | forget the thread |
| `GET` | `/api/v1/backup/meta` | when the newest backup was taken, and how many are kept |
| `GET` | `/api/v1/backup/history` | every restorable backup, newest first |
| `GET` | `/api/v1/backup?at=` | the newest backup, or the one taken at that timestamp |
| `PUT` | `/api/v1/backup` | `{"backup": {...}}` — store one |
| `GET` | `/api/v1/release/latest` | the newest published build, with its notes |
| `GET` | `/api/v1/release/download/{file}` | the APK; accepts `?key=` as well as the header |
| `GET` | `/api/v1/users` | who has a key |
| `POST` | `/api/v1/users` | invite someone (primary user only); re-inviting an email re-shares their code |
| `GET` | `/api/v1/whoami` | which user this key belongs to |
| `POST` | `/api/v1/preset-tags` | background search-tagging for presets |
| `GET` | `/api/v1/health` | unauthenticated liveness |
| `GET` | `/` | web job log (see below) |

Jobs and threads both carry `progress`, the step in flight, and `steps`, everything
they have done. A planning thread's steps move onto the reply that produced them once
the turn finishes, so a conversation shows the work per answer.

`client_job_id` makes submission idempotent: a retried upload over a flaky mobile
connection returns the existing job rather than logging the meal twice.

## App updates

`MacroPad/build_release.sh` copies each signed APK into `data/releases/` and records
it in `data/releases.json` — version, checksum, and whatever you put in
`RELEASE_NOTES`. The app checks `/api/v1/release/latest` when you open Settings,
downloads over the same authenticated connection, verifies the SHA-256, and hands the
file to the system installer. The last five builds stay on disk.

```bash
RELEASE_NOTES="fixed the widget refresh" ./build_release.sh
```

The web log also shows a plain download link, which works in a browser because the
download endpoint accepts `?key=<apikey>` as well as the header. Any user's key is
accepted, not only the primary one — everyone sharing the server needs to be able to
update the app.

The updater only appears when an AI server is configured. That is deliberate: Google
Play forbids an app distributed there from updating itself by any other route, and a
Play-installed copy has no server configured, so it never sees the update UI.

## Backups

Each user's backups live in `data/backups/<user>/<timestamp>.json`, and **nothing is
ever overwritten**. Restoring is "pick a time", so the failure that actually happens —
noticing on Thursday that something was deleted on Monday — is recoverable. Any scheme
that overwrites the current copy loses that race the moment backups become frequent.

Backups run on their own once a server is connected: a few minutes after you log
something, and once a day regardless. The toggle is in **Settings → Server Backup**, and
**Restore** lists every stored backup with its date and size so you can pick one. That
list is in the app on purpose — the person who needs an old copy has a phone, not a
shell on this machine.

Old backups are thinned by age: everything from the last 48 hours, then one a day for a
month, then one a week for a year. The newest is never removed. A year of hourly
backups prunes to about 125 files, a few megabytes at this payload size.

```bash
docker compose exec macropad-ai python -m app.cli backups   # list them
```

Backups from before this layout are moved into the series on first access, keeping
their modification time as their position.

## Web log

`http://localhost:8321/` lists every job with its estimate, and each job's page shows
the photos, the itemised numbers, the sources it used, and the session transcript.

The web routes have no authentication of their own — they are meant to sit behind the
reverse proxy's OAuth. Don't expose port 8321 directly.

## Behind the home proxy

A reverse proxy in front of this needs a `macropad.example.com` block that
splits the two audiences:

- `/api/` bypasses oauth2-proxy and is authenticated by the API key — a phone cannot
  complete a Google OAuth redirect.
- `/` goes through oauth2-proxy as usual, so the web log is behind your Google login.

`client_max_body_size` is raised to 60 MB on `/api/` for camera photos.

## Configuration (`data/env`)

| Variable | Default | Meaning |
|---|---|---|
| `ANTHROPIC_API_KEY` | unset | Use an API key instead of the mounted subscription |
| `MACROPAD_MODEL` | `claude-opus-5` | Model for estimation |
| `MACROPAD_MAX_CONCURRENT` | `2` | Concurrent Claude sessions |
| `MACROPAD_MAX_TURNS` | `40` | Hard ceiling per session |
| `MACROPAD_IMAGE_RETENTION_DAYS` | `90` | Days to keep uploaded photos (`0` disables the sweep) |
| `MACROPAD_MAX_IMAGE_EDGE` | `1600` | Photos are downscaled to this long edge |
| `MACROPAD_MAX_IMAGES` | `6` | Images per job |
| `MACROPAD_PUBLIC_URL` | unset | Override the address put into setup QR codes |

## Data

Everything lives in `./data`, which is git-ignored:

```
data/macropad.db          jobs, answers, events
data/apikey               the API key
data/env                  your configuration
data/jobs/<id>/           the uploaded photos and the session transcript
data/tls/                 the self-signed certificate, if there is one
data/backups/<user>/      one file per backup, named by timestamp
data/users.json           who holds a key
```

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) at the repo root. The one rule worth
repeating here: the agent's three-tool allowlist is the security boundary that makes it
safe to run a credentialed Claude session over photographs of text strangers wrote.
Widening it is a security change, not a feature.
