# -*- coding: utf-8 -*-
from PIL import Image, ImageDraw, ImageFont
import os

# デザイン定数 (hud_encode.py Pro Edition)
HUD_W, HUD_H = 1000, 280
M = 24
BAR_H = 12
C = {
    'bg': (10, 15, 28, 210),
    'accent': (59, 130, 246),
    'power': (16, 185, 129),
    'hr': (239, 68, 68),
    'text': (255, 255, 255),
    'muted': (148, 163, 184),
    'border': (255, 255, 255, 40)
}

def gen():
    img = Image.new('RGBA', (1920, 1080), (30, 35, 45, 255))
    draw = ImageDraw.Draw(img)

    # Position: Bottom Center
    bx, by = (1920 - HUD_W)//2, 1080 - HUD_H - 60

    # Panel
    draw.rounded_rectangle([bx, by, bx + HUD_W, by + HUD_H], radius=20, fill=C['bg'], outline=C['border'], width=1)

    # Mock Large Fonts
    def draw_metric(x, y, label, val, unit, color, bar_ratio):
        draw.text((x, y), label, fill=C['muted'])
        draw.text((x, y + 25), val, fill=color) # Large val
        draw.text((x + 130, y + 70), unit, fill=C['muted'])
        # Bar
        bw = 260
        draw.rounded_rectangle([x, y + 130, x + bw, y + 130 + BAR_H], radius=6, fill=(40, 45, 60))
        draw.rounded_rectangle([x, y + 130, x + int(bw * bar_ratio), y + 130 + BAR_H], radius=6, fill=color)

    # Metrics
    draw_metric(bx + 40, by + 40, "SPEED", "34.2", "km/h", C['text'], 0.7)
    draw_metric(bx + 360, by + 40, "POWER", "285", "W", C['power'], 0.6)
    draw_metric(bx + 680, by + 40, "HEART RATE", "162", "bpm", C['hr'], 0.8)

    # Bottom info
    draw.text((bx + 40, by + 210), "CAD: 92 rpm  |  ALT: 124m  |  GRADE: +2.4%", fill=C['text'])
    draw.text((bx + HUD_W - 140, by + 210), "0:12:45", fill=C['muted'])

    output = "new_pro_design.png"
    img.save(output)
    print(f"Professional design generated: {os.path.abspath(output)}")

if __name__ == "__main__":
    gen()
