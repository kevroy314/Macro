"""A certificate for a machine that has no public name.

No authority will certify `192.168.1.50` or `macropad.local`, so a server on a home
network makes its own and the phone is told to trust exactly that one. The app compares
the SHA-256 of the SubjectPublicKeyInfo — the same value `openssl pkey -pubin -outform
der | openssl dgst -sha256` produces, and the same one OkHttp's `CertificatePinner`
computes — so the pin printed here is the one the app checks.

This is narrower than public-CA trust rather than weaker: one key is trusted instead of
every authority in the world.
"""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import ipaddress
import socket
from pathlib import Path

from . import config

TLS_DIR = config.DATA_DIR / "tls"
CERT_PATH = TLS_DIR / "cert.pem"
KEY_PATH = TLS_DIR / "key.pem"

#: The mDNS name the installer publishes for this machine.
LOCAL_NAME = "macropad.local"

#: Ten years. Rotating it means re-pairing every phone, which is worse than a long life
#: for a key that never leaves the house.
VALID_DAYS = 3650


def is_enabled() -> bool:
    """Whether to serve HTTPS.

    Deliberately keyed on the certificate existing rather than on a flag. An existing
    install sitting behind a reverse proxy that terminates TLS has no certificate here
    and must keep being served plain HTTP, or the proxy in front of it breaks.
    """
    return CERT_PATH.is_file() and KEY_PATH.is_file()


def local_addresses() -> list[str]:
    """This machine's LAN addresses, for the certificate's SAN list."""
    found: list[str] = []
    try:
        # Connecting a UDP socket picks a route without sending anything, which is the
        # portable way to learn which interface faces the network.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("10.255.255.255", 1))
            found.append(probe.getsockname()[0])
    except OSError:
        pass

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if address not in found and not address.startswith("127."):
                found.append(address)
    except OSError:
        pass

    return found


def public_key_pin(cert_pem: bytes) -> str:
    """Base64 SHA-256 of the certificate's SubjectPublicKeyInfo."""
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    certificate = x509.load_pem_x509_certificate(cert_pem)
    spki = certificate.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(hashlib.sha256(spki).digest()).decode()


def current_pin() -> str:
    """The pin for the certificate on disk, or an empty string if there isn't one."""
    if not CERT_PATH.is_file():
        return ""
    try:
        return public_key_pin(CERT_PATH.read_bytes())
    except Exception:
        return ""


def ensure_cert(extra_names: list[str] | None = None) -> str:
    """Create the certificate if it is missing. Returns its pin.

    Idempotent: an existing certificate is left alone, because replacing it would
    invalidate the pin on every phone already paired with this server.
    """
    if is_enabled():
        return current_pin()

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    TLS_DIR.mkdir(parents=True, exist_ok=True)

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, LOCAL_NAME)])

    alt_names: list[x509.GeneralName] = [x509.DNSName(LOCAL_NAME)]
    for name in extra_names or []:
        alt_names.append(x509.DNSName(name))
    for address in local_addresses():
        try:
            alt_names.append(x509.IPAddress(ipaddress.ip_address(address)))
        except ValueError:
            continue

    now = dt.datetime.now(dt.timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=VALID_DAYS))
        .add_extension(x509.SubjectAlternativeName(alt_names), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    KEY_PATH.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    KEY_PATH.chmod(0o600)
    CERT_PATH.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))

    return current_pin()


def base_url(host: str | None = None, port: int = 8321) -> str:
    """The address to hand a phone."""
    scheme = "https" if is_enabled() else "http"
    if host is None:
        host = LOCAL_NAME if is_enabled() else (local_addresses() or ["localhost"])[0]
    return f"{scheme}://{host}:{port}"
