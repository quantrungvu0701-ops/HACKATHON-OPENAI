#!/usr/bin/env python3
"""Makes two synthetic test photos for DocCheck: a sharp fake ID card and a blurred copy.

No real document or personal data is involved. Needs Pillow (pip install pillow).

  python tools/make_test_docs.py <out-dir>
  adb push <out-dir>/doc_sharp.jpg /sdcard/Pictures/
  adb push <out-dir>/doc_blurred.jpg /sdcard/Pictures/
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
out.mkdir(parents=True, exist_ok=True)


def font(size):
    for name in ("arial.ttf", "DejaVuSans.ttf", "Helvetica.ttc"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


img = Image.new("RGB", (1600, 1200), (92, 74, 58))  # table
d = ImageDraw.Draw(img)
d.rounded_rectangle((150, 180, 1450, 1020), radius=48, fill=(226, 238, 232), outline=(40, 90, 80), width=6)
d.text((420, 215), "CONG HOA XA HOI CHU NGHIA VIET NAM", font=font(40), fill=(20, 40, 40))
d.text((560, 275), "CAN CUOC CONG DAN (MAU THU)", font=font(40), fill=(170, 30, 30))
d.rectangle((210, 380, 520, 790), fill=(180, 190, 200), outline=(60, 60, 60), width=4)
d.ellipse((290, 430, 440, 600), fill=(120, 130, 140))
d.rectangle((265, 620, 465, 790), fill=(120, 130, 140))
rows = ["So / No.: 000 000 000 000", "Ho va ten: NGUYEN VAN MAU", "Ngay sinh: 01/01/1960",
        "Gioi tinh: Nam    Quoc tich: Viet Nam", "Que quan: Mau thu, khong co that",
        "Noi thuong tru: Mau thu, khong co that"]
for i, row in enumerate(rows):
    d.text((580, 390 + i * 78), row, font=font(38), fill=(15, 25, 25))
for x in range(200, 1400, 14):  # fine lines: sharpness detail
    d.line((x, 880, x + 7, 960), fill=(60, 110, 100), width=2)

img.save(out / "doc_sharp.jpg", quality=92)
img.filter(ImageFilter.GaussianBlur(radius=11)).save(out / "doc_blurred.jpg", quality=92)
print("wrote", out / "doc_sharp.jpg", "and", out / "doc_blurred.jpg")
