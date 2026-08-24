"""
Génère le QR code de provisioning "Device Owner" Android pour Senior Visio
(voir README > Kiosque et déploiement). Appelé depuis
.github/workflows/generate-provisioning-qr.yml, jamais localement avec le
mot de passe Wi-Fi en clair — celui-ci vient d'un secret GitHub.
"""

import base64
import binascii
import json
import os
import re
import subprocess

import qrcode


def signature_checksum(apk_path: str) -> str:
    """
    Empreinte de la clé de signature de l'APK (PROVISIONING_DEVICE_ADMIN_
    SIGNATURE_CHECKSUM), pas du fichier lui-même (PACKAGE_CHECKSUM) — ce
    dernier est peu fiable pour le provisioning Device Owner par QR code à
    partir d'Android 14, Google recommande désormais explicitement la
    variante par empreinte de certificat.

    keytool -printcert ne comprend que l'ancien format de signature JAR
    (META-INF/*.RSA) : nos APK, signés uniquement au format moderne v2/v3
    (comportement par défaut d'AGP pour minSdk >= 24), le font échouer avec
    "Not a signed jar file". apksigner, lui, comprend tous les formats.
    """
    output = subprocess.run(
        [os.environ["APKSIGNER"], "verify", "--print-certs", apk_path],
        check=True, capture_output=True, text=True,
    ).stdout
    match = re.search(r"SHA-256 digest:\s*([0-9A-Fa-f:]+)", output)
    if not match:
        raise RuntimeError(f"Empreinte SHA-256 introuvable dans la sortie d'apksigner:\n{output}")
    hex_digest = match.group(1).replace(":", "")
    digest_bytes = binascii.unhexlify(hex_digest)
    return base64.urlsafe_b64encode(digest_bytes).decode().rstrip("=")


def main() -> None:
    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
            os.environ.get("ADMIN_COMPONENT", "com.seniorvisio/.admin.SeniorVisioDeviceAdminReceiver"),
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
            os.environ["APK_URL"],
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
            signature_checksum("target.apk"),
        "android.app.extra.PROVISIONING_WIFI_SSID": os.environ["WIFI_SSID"],
        "android.app.extra.PROVISIONING_WIFI_PASSWORD": os.environ["WIFI_PASSWORD"],
        "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
        "android.app.extra.PROVISIONING_LOCALE": "fr_FR",
    }

    img = qrcode.make(json.dumps(payload))
    img.save("provisioning-qr.png")


if __name__ == "__main__":
    main()
