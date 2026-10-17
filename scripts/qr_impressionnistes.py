#!/usr/bin/env python3
"""QR code de la page d'inscription des testeurs (impressionnistes/qr.svg).

Usage : qr_impressionnistes.py [URL]
L'adresse par défaut est celle du site Firebase Hosting « seniorvisio-impressionnistes ».
"""
import sys

import qrcode
import qrcode.image.svg

url = sys.argv[1] if len(sys.argv) > 1 else "https://seniorvisio-impressionnistes.web.app/"
img = qrcode.make(url, image_factory=qrcode.image.svg.SvgPathImage, box_size=20, border=4,
                  error_correction=qrcode.constants.ERROR_CORRECT_Q)
img.save("impressionnistes/qr.svg")
print("QR généré pour", url)
