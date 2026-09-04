"""Small maintenance commands: `python -m app.cli <command>`."""

from __future__ import annotations

import sys

from . import backups, config, db, releases, tls, users
from .main import sweep_old_images


def _server_url() -> str:
    """The address a phone should use to reach this machine."""
    import os

    override = os.environ.get("MACROPAD_PUBLIC_URL", "").strip()
    if override:
        return override.rstrip("/")
    return tls.base_url()


def _render_qr(content: str) -> None:
    """Draw a QR code with terminal blocks.

    Half-blocks put two QR rows in one text row, which is what keeps the code small
    enough to fit in a terminal and still be scannable.
    """
    try:
        import segno
    except ImportError:
        print("(install segno to see a QR code here)", file=sys.stderr)
        return
    segno.make(content, error="m").terminal(compact=True)


def print_setup_qr(user: "users.User") -> None:
    """Everything a phone needs: where the server is, who you are, and what to trust."""
    from urllib.parse import urlencode

    params = {"url": _server_url(), "key": user.key, "name": user.name}
    pin = tls.current_pin()
    if pin:
        params["pin"] = pin

    link = "macropad://setup?" + urlencode(params)

    print(f"\nPoint {user.name}'s MacroPad at this server\n")
    _render_qr(link)
    print(f"\n  server  {params['url']}")
    if pin:
        print(f"  pinned  yes (self-signed certificate)")
    else:
        print(f"  pinned  no (using ordinary certificate validation)")
    print("\nScan it from the app: Settings -> Scan a setup code.\n")


def main(argv: list[str]) -> int:
    command = argv[1] if len(argv) > 1 else "help"

    if command == "show-key":
        users.bootstrap()
        for user in users.all_users():
            print(f"{user.id:12s} {user.name:16s} {user.key}")
    elif command == "rotate-key":
        print(config.rotate_api_key())
        print("Restart the daemon and update the key in the app.", file=sys.stderr)
    elif command == "sweep-images":
        print(f"removed {sweep_old_images()} image(s)")
    elif command == "add-user":
        if len(argv) < 3:
            print("usage: add-user <id> [display name] [email]", file=sys.stderr)
            return 1
        users.bootstrap()
        user_id = argv[2]
        name = argv[3] if len(argv) > 3 else user_id.capitalize()
        email = argv[4] if len(argv) > 4 else ""
        try:
            user = users.add_user(user_id, name, email)
        except ValueError as exc:
            print(exc, file=sys.stderr)
            return 1
        print(f"{user.name}'s API key:\n  {user.key}")
        print("\nTheir jobs and threads are theirs alone; nothing existing moves.",
              file=sys.stderr)
    elif command == "list-users":
        users.bootstrap()
        for user in users.all_users():
            print(f"{user.id:12s} {user.name:16s} {user.email or '(no email)':32s} {user.key}")
    elif command == "rotate-user-key":
        if len(argv) < 3:
            print("usage: rotate-user-key <id>", file=sys.stderr)
            return 1
        try:
            user = users.rotate_key(argv[2])
        except ValueError as exc:
            print(exc, file=sys.stderr)
            return 1
        print(f"{user.name}'s new key:\n  {user.key}")
    elif command == "remove-user":
        if len(argv) < 3:
            print("usage: remove-user <id>", file=sys.stderr)
            return 1
        try:
            users.remove_user(argv[2])
        except ValueError as exc:
            print(exc, file=sys.stderr)
            return 1
        print(f"removed {argv[2]} (their jobs and threads stay on disk, unreachable)")
    elif command == "setup-qr":
        users.bootstrap()
        user_id = argv[2] if len(argv) > 2 and not argv[2].startswith("-") else None
        user = users.by_id(user_id) if user_id else users.primary_user()
        if user is None:
            print(f"no such user: {user_id}", file=sys.stderr)
            return 1
        print_setup_qr(user)
    elif command == "release-qr":
        users.bootstrap()
        user = users.primary_user()
        latest = releases.latest_release()
        if user is None or latest is None:
            print("No release has been published yet.", file=sys.stderr)
            print("Build one with MacroPad/build_release.sh first.", file=sys.stderr)
            return 1
        url = (
            f"{_server_url()}/api/v1/release/download/{latest['file']}"
            f"?key={user.key}"
        )
        print(f"\nInstall MacroPad {latest['version_name']} "
              f"(build {latest['version_code']})\n")
        _render_qr(url)
        print(f"\n  {url}\n")
    elif command == "cert":
        pin = tls.ensure_cert()
        if not pin:
            print("Could not create a certificate.", file=sys.stderr)
            return 1
        print(f"certificate  {tls.CERT_PATH}")
        print(f"public key pin  {pin}")
        print("\nRestart the daemon to serve HTTPS with it.", file=sys.stderr)
    elif command == "backups":
        users.bootstrap()
        import datetime
        for user in users.all_users():
            series = backups.history(user.id)
            print(f"{user.id}: {len(series)} backup(s)")
            for entry in series:
                when = datetime.datetime.fromtimestamp(entry["at"] / 1000)
                print(f"  {when:%Y-%m-%d %H:%M}  {entry['days']:4d} days  "
                      f"{entry['entries']:5d} entries  {entry['size_bytes'] // 1024:4d} KB")
    elif command == "stats":
        db.connect()
        jobs = [j for u in users.all_users() for j in db.list_jobs(u.id, limit=10000)]
        counts: dict[str, int] = {}
        cost = 0.0
        for job in jobs:
            counts[job["status"]] = counts.get(job["status"], 0) + 1
            cost += job["cost_usd"] or 0.0
        for status, count in sorted(counts.items()):
            print(f"{status:12s} {count}")
        print(f"{'total':12s} {len(jobs)}  (${cost:.2f} reported)")
        print()
        for row in db.usage_by_owner():
            print(f"{row['owner'] or '(unowned)':12s} {row['jobs']:4d} jobs  ${row['cost']:.2f}")
    else:
        print(
            "usage: python -m app.cli {show-key|rotate-key|sweep-images|stats"
            "|add-user|list-users|rotate-user-key|remove-user"
            "|setup-qr|release-qr|cert|backups}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
