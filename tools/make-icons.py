#!/usr/bin/env python3
"""Makes the program's icons from the user's two drawings (Pillow).

Sources (white on black): desktop/src/main/icons/source/backup-logo.jpg (cloud, shield, arrows and the word BACKUP)
and desktop/src/main/icons/source/cloud.jpg (the white cloud).

Outputs, committed so the build needs no Python:
  desktop/src/main/icons/TGBackup.ico          16,24,32,48,64,128,256; the drawing only below 64 px (the word cannot
                                               be read there), drawing + BACKUP from 64 px
  desktop/src/main/icons/TGBackup-window.ico   the cloud, 16,20,24,32,40,48,64,256: the window's own icons (taskbar
                                               button, also pinned; title bar; Alt+Tab), as the tray's cloud
  desktop/src/main/resources/tray-16.png       the cloud, for the tray
  desktop/src/main/resources/tray-32.png
  desktop/src/main/resources/web/favicon.png   64 px, as the .ico's 64 frame
  desktop/src/main/resources/web/icon-192.png  the phone's install icons (manifest.webmanifest): drawing + BACKUP
  desktop/src/main/resources/web/icon-512.png
  desktop/src/main/resources/web/apple-touch-icon.png   180 px, for the iPhone's home screen
Every icon is white inside a black circle with transparent corners.

Usage: python tools/make-icons.py [--preview out.png]
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parent.parent
ICONS = REPO / 'desktop/src/main/icons'
RES = REPO / 'desktop/src/main/resources'
LOGO = ICONS / 'source/backup-logo.jpg'
CLOUD = ICONS / 'source/cloud.jpg'
SIZES = [16, 24, 32, 48, 64, 128, 256]
WINDOW_SIZES = [16, 20, 24, 32, 40, 48, 64, 256]
INSTALL = {'icon-192.png': 192, 'icon-512.png': 512, 'apple-touch-icon.png': 180}
PAD = 8


def white_mask(path, box):
    """The white of the drawing as an alpha mask (dark JPEG noise dropped, soft edges kept)."""
    im = Image.open(path).convert('L').crop(box)
    return im.point(lambda v: 0 if v < 60 else (255 if v > 190 else int((v - 60) * 255 / 130)))


def round_icon(mask, D=1024, fill=0.80):
    """[mask] in white, scaled so its diagonal is fill*D, centred in a black circle of D px."""
    w, h = mask.size
    diag = (w * w + h * h) ** 0.5
    s = fill * D / diag
    m = mask.resize((round(w * s), round(h * s)), Image.LANCZOS)
    img = Image.new('RGBA', (D, D), (0, 0, 0, 0))
    circ = Image.new('L', (D, D), 0)
    ImageDraw.Draw(circ).ellipse((0, 0, D - 1, D - 1), fill=255)
    img.paste((0, 0, 0, 255), (0, 0), circ)
    white = Image.new('RGBA', m.size, (255, 255, 255, 255))
    img.paste(white, ((D - m.width) // 2, (D - m.height) // 2), m)
    return img


def bbox_of(path, rows=None):
    """Bounding box of the white in [path]; with [rows] (top, bottom) only that band of rows."""
    im = Image.open(path).convert('L')
    m = im.point(lambda v: 255 if v > 128 else 0)
    if rows:
        m = m.crop((0, rows[0], im.width, rows[1]))
        b = m.getbbox()
        return (b[0], b[1] + rows[0], b[2], b[3] + rows[0])
    return m.getbbox()


def grow(b):
    return (b[0] - PAD, b[1] - PAD, b[2] + PAD, b[3] + PAD)


def make():
    big = round_icon(white_mask(LOGO, grow(bbox_of(LOGO))), fill=0.86)
    # Rows 202..628 of the logo: the drawing without the word BACKUP.
    small = round_icon(white_mask(LOGO, grow(bbox_of(LOGO, (202, 628)))), fill=0.84)
    tray = round_icon(white_mask(CLOUD, grow(bbox_of(CLOUD))), fill=0.80)
    frames = [(small if s < 64 else big).resize((s, s), Image.LANCZOS) for s in SIZES]
    trays = {s: tray.resize((s, s), Image.LANCZOS) for s in (16, 32)}
    window = [tray.resize((s, s), Image.LANCZOS) for s in WINDOW_SIZES]
    install = {name: big.resize((s, s), Image.LANCZOS) for name, s in INSTALL.items()}
    return big, frames, trays, window, install


def write(frames, trays, window, install):
    frames[-1].save(ICONS / 'TGBackup.ico', sizes=[(s, s) for s in SIZES], append_images=frames[:-1])
    window[-1].save(ICONS / 'TGBackup-window.ico', sizes=[(s, s) for s in WINDOW_SIZES], append_images=window[:-1])
    for s, t in trays.items():
        t.save(RES / f'tray-{s}.png')
    frames[SIZES.index(64)].save(RES / 'web/favicon.png')
    for name, im in install.items():
        im.save(RES / 'web' / name)


def preview(big, frames, trays, out):
    """All sizes on a light and a dark taskbar-like background."""
    sheet = Image.new('RGBA', (900, 420), (240, 240, 240, 255))
    ImageDraw.Draw(sheet).rectangle((0, 210, 900, 420), fill=(32, 32, 32, 255))
    for bgy in (0, 210):
        x = 20
        for s in [16, 24, 32, 48, 64, 128]:
            sheet.alpha_composite(frames[SIZES.index(s)], (x, bgy + 20 + (128 - s) // 2))
            x += s + 30
        for s, t in trays.items():
            sheet.alpha_composite(t, (x + 20, bgy + 20 + (128 - s) // 2))
            x += s + 40
        sheet.alpha_composite(big.resize((160, 160), Image.LANCZOS), (720, bgy + 25))
    sheet.save(out)


if __name__ == '__main__':
    big, frames, trays, window, install = make()
    write(frames, trays, window, install)
    if len(sys.argv) == 3 and sys.argv[1] == '--preview':
        preview(big, frames, trays, sys.argv[2])
    print('ok')
