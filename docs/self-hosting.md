# Setting up the AI Estimator

MacroPad works completely on its own. Everything on this page is optional.

The AI Estimator is for the meals where you genuinely don't know the numbers — a
restaurant dish, a takeaway order, a plate someone else cooked. You photograph it, add a
sentence, and a Claude session running **on your own machine** researches it and logs the
result.

There is no MacroPad server. There is no account to create with us. If you want this
feature, you run the server, and your food photos go to your computer and nowhere else.

---

## What you need

| | |
|---|---|
| **A computer that stays on** | Any old laptop, desktop, or mini PC. Linux or macOS is smoother than Windows — see the note at the bottom. |
| **A Claude subscription** | Claude Code needs a Pro, Max, Team, Enterprise, or Console account. The free Claude tier does not include it. This is the one thing that costs money, and it's paid to Anthropic, not to us. |
| **About fifteen minutes** | Most of which is waiting for downloads. |

You do **not** need a domain name, a static IP, dynamic DNS, a certificate, or any change
to your router. If you've read guides that say otherwise, that's the "reach it from
anywhere" setup — it's [further down](#reaching-it-from-outside-the-house), and it's
optional too.

---

## The short version

```bash
git clone https://github.com/kevroy314/Macro.git
cd Macro/macropad-ai
./install.sh
```

The script tells you what it's about to do and asks once. Then it installs a container
runtime, sets the server up on your home network, signs you in to Claude, starts
everything, and finishes by printing two QR codes: one to install the app, one to point
the app at your server.

Scan them with your phone. That's the setup.

---

## Or let Claude do it

If you have [Claude Code](https://claude.com/claude-code), clone the repo, open it, and
say:

> set up the MacroPad server for me

The repo ships instructions for exactly this. Claude will check what you already have,
tell you what it wants to install, and walk through the same steps below — stopping to ask
before anything that needs your password.

---

## What it actually does

Nothing here is hidden, so if you'd rather do it by hand, this is the list.

**1 — Installs a container runtime.** Docker on Linux, Colima on macOS. It asks for your
password once, because installing system software requires it.

**2 — Puts the server on your home network.** It picks up an address on your LAN and
announces itself as `macropad.local`, so your phone can find it again if your router
hands it a different address later.

**3 — Makes a certificate.** No public authority will issue a certificate for a machine
on your home network, so the server makes its own. The setup QR code carries that
certificate's fingerprint, and your phone is then set to trust **that one certificate and
nothing else**. This is narrower than ordinary web security, not looser — your phone
trusts one specific key rather than every certificate authority in the world.

**4 — Signs you in to Claude.** A browser link appears; you click it once. The login runs
inside the container, so you never install Node or Claude Code on your computer.

**5 — Starts the server and checks it.** Including checking that it *rejects* requests
without a key, which is the test that catches a broken setup.

**6 — Prints the QR codes.** One installs the app. One configures it.

---

## Using it away from home

Your server is on your home network, so your phone can only reach it at home. That
matters, because the meals you most want estimated are the ones you're eating out.

**The app handles this.** Photograph the meal at the restaurant and submit it as normal.
The job is saved on your phone, and it's sent automatically the next time your server is
reachable — usually the moment you walk back in the door. The estimate lands that evening
rather than at the table.

If that's good enough, you're done, and you never created an account or installed a
second app.

---

## Reaching it from outside the house

If you'd rather have the answer while you're still at the table, there's an optional step.

The installer can set the server up on [Tailscale](https://tailscale.com), a free private
network. Your phone and your server join it, and they can then reach each other from
anywhere, over an encrypted connection, **without opening any port on your router** and
without your server ever being reachable from the public internet.

What it costs you:

- One free account (sign in with Google, GitHub, or Microsoft — one click)
- The Tailscale app installed on each phone that uses MacroPad
- A dependency on a third party for the connection setup

That's a real cost, which is why it isn't the default. The free Personal plan covers 6
people and unlimited devices, indefinitely.

The installer offers this as a question with "no" as the default. You can change your mind
later.

---

## Sharing with someone else

One server can serve a household. Each person gets their own key, and **nobody can see
anyone else's food** — jobs, planning conversations and backups are all private to the
person who created them.

From the app: **Settings → Share & Invite → Invite someone**. It creates their key and
shows a QR code for them to scan.

Everyone shares your Claude subscription, so everyone's estimates count against the same
usage limits.

---

## Where your data lives

Everything stays in the `macropad-ai/data` directory on your machine:

```
data/macropad.db      the estimate log
data/jobs/<id>/       the photos you sent, and the session transcript
data/backups/         a backup per person, three deep
data/users.json       who has a key
```

Photos are deleted after 90 days by default. Change `MACROPAD_IMAGE_RETENTION_DAYS` in
`data/env` if you'd rather keep them longer or not at all.

Your food photos and descriptions are sent from the server to Anthropic's API in order to
run the model — that's what produces the estimate. See the
[privacy policy](privacy-policy.md) for exactly what goes where.

---

## Already have a server setup?

If you're already running this behind your own domain name with a real certificate and a
reverse proxy, **nothing here changes that.** The LAN setup is an additional path for
people who don't want to configure DNS and certificates, not a replacement.

The app only ever re-discovers a server whose address is a `.local` name or a private IP.
A public hostname is never touched, so a brief outage can't cause your phone to repoint
itself somewhere else.

---

## A note on Windows

It works, and it's the hard way to do it.

Linux containers need a Linux kernel, and Windows doesn't have one — so every option
there (Docker Desktop, Podman, Rancher) runs one in a lightweight VM called WSL 2. The
installer sets that up, which needs one reboot the first time.

The bigger issue is that Windows doesn't keep this kind of thing running across a restart
without extra configuration, and it expects someone to be logged in. On Linux and macOS
the server just comes back on its own after a power cut.

If you have the choice, an old laptop running Linux is a much better home for this.

---

## If something goes wrong

```bash
cd macropad-ai
docker compose logs macropad-ai       # what the server is saying
docker compose ps                     # is it running?
python -m app.cli show-key            # print the API key again
python -m app.cli setup-qr            # print the setup QR again
```

The web log at `https://macropad.local:8321/` shows every estimate, the photos, the
itemised numbers, the sources it used, and the full session transcript — which is usually
enough to see what the model was thinking.

Still stuck? [Open an issue](https://github.com/kevroy314/Macro/issues).
