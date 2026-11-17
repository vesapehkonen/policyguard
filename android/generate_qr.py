#!/usr/bin/env python3
"""Generate a Device Owner provisioning QR code for one exact APK artifact."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
from urllib.parse import urlparse


COMPONENT = "org.example.devicepolicyguard/.GuardAdminReceiver"


def provisioning_payload(apk_path: Path, download_url: str) -> dict[str, str | bool]:
    if not apk_path.is_file():
        raise ValueError(f"APK does not exist: {apk_path}")
    parsed = urlparse(download_url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError("Download URL must be an HTTP or HTTPS URL with a host and no credentials")

    with apk_path.open("rb") as apk_file:
        digest = hashlib.file_digest(apk_file, "sha256").digest()
    checksum = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
    return {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": download_url,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": checksum,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Signed APK to hash")
    parser.add_argument("url", help="HTTP or HTTPS URL serving those exact APK bytes")
    parser.add_argument("--png", type=Path, default=Path("provisioning-qr.png"), help="PNG output path")
    parser.add_argument("--json", type=Path, default=Path("provisioning.json"), help="JSON output path")
    args = parser.parse_args()
    try:
        payload = provisioning_payload(args.apk, args.url)
    except ValueError as exc:
        parser.error(str(exc))

    try:
        import qrcode
    except ImportError as exc:
        parser.error("Install QR dependency: python3 -m pip install 'qrcode[pil]'")
        raise AssertionError from exc

    encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=True)
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4)
    qr.add_data(encoded)
    qr.make(fit=True)
    args.png.parent.mkdir(parents=True, exist_ok=True)
    args.json.parent.mkdir(parents=True, exist_ok=True)
    qr.make_image(fill_color="black", back_color="white").save(args.png)
    args.json.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"SHA-256 (hex): {hashlib.sha256(args.apk.read_bytes()).hexdigest()}")
    print(f"Provisioning checksum: {payload['android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM']}")
    print(f"Wrote {args.json} and {args.png}")


if __name__ == "__main__":
    main()
