# -*- coding: utf-8 -*-
import sqlite3, json, os
from PIL import Image, ImageDraw, ImageFont

DB_PATH = "rails-hub/db/development.sqlite3"

def get_layout(layout_id):
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute("SELECT settings FROM hud_layouts WHERE id = ?", (layout_id,))
    row = cur.fetchone()
    conn.close()
    return json.loads(row[0]) if row else None

def render_preview(layout_id, output_name):
    s = get_layout(layout_id)
    if not s: return

    # 1080p Canvas
    img = Image.new('RGBA', (1920, 1080), (20, 20, 25, 255))
    draw = ImageDraw.Draw(img)

    # Fonts
    font_path = "C:/Windows/Fonts/ariblk.ttf"
    def f(size):
        try: return ImageFont.truetype(font_path, size)
        except: return ImageFont.load_default()

    f_giant = f(s['font_giant'])
    f_label = f(s['font_label'])
    f_unit  = f(s.get('font_unit', 32))
    f_small = f(s.get('font_small', 24))

    # Position
    bx, by = (1920 - s['width']) // 2, 1080 - s['height'] - 60

    # Background
    alpha = s.get('bg_alpha', 230)
    draw.rounded_rectangle([bx, by, bx + s['width'], by + s['height']], radius=15, fill=(5, 8, 15, alpha), outline=(255,255,255,80), width=3)

    # Metrics (Simplified spacing)
    metrics = [
        ("SPD", "34.2", "km/h", (59, 130, 246)),
        ("PWR", "285", "W", (16, 185, 129)),
        ("HR", "162", "bpm", (239, 68, 68))
    ]

    x_off = 40
    for label, val, unit, color in metrics:
        draw.text((bx + x_off, by + 20), label, font=f_label, fill=(148, 163, 184))
        draw.text((bx + x_off, by + 45), val, font=f_giant, fill=(255,255,255))
        # Calc width for unit pos
        vw = f_giant.getbbox(val)[2] - f_giant.getbbox(val)[0]
        draw.text((bx + x_off + vw + 8, by + 45 + s['font_giant'] - 40), unit, font=f_unit, fill=color)

        # Bar
        draw.rounded_rectangle([bx + x_off, by + s['height'] - 65, bx + x_off + 250, by + s['height'] - 50], radius=5, fill=(40, 45, 60))
        draw.rounded_rectangle([bx + x_off, by + s['height'] - 65, bx + x_off + 180, by + s['height'] - 50], radius=5, fill=color)
        x_off += (s['width'] - 80) // 3 + 20

    # Footer
    draw.text((bx + 40, by + s['height'] - 35), "CAD: 92 | ALT: 124m | GRD: +2.4%", font=f_small, fill=(148, 163, 184))
    draw.text((bx + s['width'] - 140, by + s['height'] - 35), "0:12:45", font=f_small, fill=(59, 130, 246))

    img.save(output_name)
    print(f"Rendered: {output_name}")

if __name__ == "__main__":
    import sys
    layout_id = sys.argv[1] if len(sys.argv) > 1 else 1
    render_preview(int(layout_id), f"knead_layout_{layout_id}.png")
