# -*- coding: utf-8 -*-
from PIL import Image, ImageDraw, ImageFont
import os

# デザイン定数 (hud_encode.py High-Vis)
HUD_W, HUD_H = 880, 240
C = {
    'bg': (5, 8, 15, 220),
    'accent': (59, 130, 246),
    'power': (16, 185, 129),
    'hr': (239, 68, 68),
    'text': (255, 255, 255),
    'muted': (148, 163, 184),
    'border': (255, 255, 255, 60)
}

def gen():
    # 1080p Canvas
    img = Image.new('RGBA', (1920, 1080), (25, 25, 30, 255))
    draw = ImageDraw.Draw(img)

    # Position: Bottom Center, slightly raised
    bx, by = (1920 - HUD_W)//2, 1080 - HUD_H - 40

    # Background Panel (Tight)
    draw.rounded_rectangle([bx, by, bx + HUD_W, by + HUD_H], radius=15, fill=C['bg'], outline=C['border'], width=2)

    def draw_component(x, y, label, val, unit, color, ratio):
        draw.text((x, y), label, fill=C['muted'])
        # Big Value
        draw.text((x, y + 25), val, fill=C['text'])
        # Unit near bottom of val
        draw.text((x + 130 if len(val)>3 else x + 90, y + 85), unit, fill=color)
        # Bar
        draw.rounded_rectangle([x, y + 130, x + 260, y + 130 + 12], radius=4, fill=(40, 45, 60))
        draw.rounded_rectangle([x, y + 130, x + int(260 * ratio), y + 130 + 12], radius=4, fill=color)

    # 3 Pillars
    draw_component(bx + 30,  by + 20, "SPEED", "34.2", "km/h", C['accent'], 0.7)
    draw_component(bx + 310, by + 20, "POWER", "285", "W", C['power'], 0.65)
    draw_component(bx + 590, by + 20, "HEART RATE", "162", "bpm", C['hr'], 0.8)

    # Bottom Strip (Muted but readable)
    draw.text((bx + 30, by + 200), "CAD: 92 rpm   |   ALT: 124m   |   GRADE: +2.4%", fill=C['muted'])
    draw.text((bx + HUD_W - 120, by + 200), "0:12:45", fill=C['accent'])

    output = "high_visibility_design.png"
    img.save(output)
    print(f"High-visibility design generated: {os.path.abspath(output)}")

if __name__ == "__main__":
    gen()
