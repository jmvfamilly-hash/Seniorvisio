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
        "android.app.extra.PROVISIONING_LOCALE": "fr_FR",
    }

    # Le SSID n'est connu qu'une fois sur place (lieu de déploiement pas
    # toujours fixé à l'avance) : plutôt que de bloquer la génération du QR
    # en attendant de le connaître, on l'omet du payload quand il n'est pas
    # fourni. Sans ces extras et sans connexion réseau détectée, le
    # provisioning Android affiche alors son propre écran de sélection Wi-Fi
    # (avant de télécharger l'APK) — permet de saisir le réseau et son mot de
    # passe directement sur l'écran de la tablette, sans repasser par ce
    # script. Si le SSID est connu à l'avance, on peut toujours le fournir
    # pour sauter cet écran.
    wifi_ssid = os.environ.get("WIFI_SSID", "").strip()
    if wifi_ssid:
        payload["android.app.extra.PROVISIONING_WIFI_SSID"] = wifi_ssid
        payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = os.environ.get("WIFI_PASSWORD", "")
        payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = "WPA"

    img = qrcode.make(json.dumps(payload))
    img.save("provisioning-qr.png")


if __name__ == "__main__":
    main()
