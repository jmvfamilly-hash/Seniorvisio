"""
Génère le QR code de provisioning "Device Owner" Android pour Senior Visio
(voir README > Kiosque et déploiement). Appelé depuis
.github/workflows/generate-provisioning-qr.yml, jamais localement avec le
mot de passe Wi-Fi en clair — celui-ci vient d'un secret GitHub.
"""

import hashlib
import base64
import json
import os

import qrcode


def apk_checksum(path: str) -> str:
    with open(path, "rb") as f:
        digest = hashlib.sha256(f.read()).digest()
    return base64.urlsafe_b64encode(digest).decode().rstrip("=")


def main() -> None:
    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
            "com.seniorvisio/.admin.SeniorVisioDeviceAdminReceiver",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
            os.environ["APK_URL"],
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM":
            apk_checksum("target.apk"),
        "android.app.extra.PROVISIONING_WIFI_SSID": os.environ["WIFI_SSID"],
        "android.app.extra.PROVISIONING_WIFI_PASSWORD": os.environ["WIFI_PASSWORD"],
        "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
        "android.app.extra.PROVISIONING_LOCALE": "fr_FR",
    }

    img = qrcode.make(json.dumps(payload))
    img.save("provisioning-qr.png")


if __name__ == "__main__":
    main()
