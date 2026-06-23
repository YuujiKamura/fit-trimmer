# -*- coding: utf-8 -*-
from PIL import Image, ImageDraw, ImageFont
import numpy as np
import os

HUD_W, HUD_H = 800, 460
C = {
    'bg': (6, 10, 22, 200),
    'stroke': (59, 130, 246, 120),
    'header': (59, 130, 246),
    'text': (235, 240, 250),
    'muted': (90, 100, 120),
    'hr': (239, 68, 68),
}

def gen():
    # 1920x1080 canvas
    img = Image.new('RGBA', (1920, 1080), (20, 20, 25, 255))
    draw = ImageDraw.Draw(img)

    # Left Panel
    lx, ly = 50, 50
    draw.rounded_rectangle([lx, ly, lx + 440, ly + HUD_H], radius=15, fill=C['bg'], outline=C['stroke'], width=2)
    draw.text((lx+20, ly+20), "FIT RIDER - ACTIVE METRICS", fill=C['header'])
    draw.text((lx+20, ly+70), "SPEED: 34.2 km/h", fill=C['text'])
    draw.text((lx+20, ly+180), "POWER: 285 W", fill=(234, 179, 8)) # Zone 4
    draw.text((lx+20, ly+290), "HEART RATE: 162 bpm", fill=C['hr'])

    # Right Panel
    rx, ry = 510, 50
    draw.rounded_rectangle([rx, ry, rx + 340, ry + HUD_H], radius=15, fill=C['bg'], outline=C['stroke'], width=2)
    draw.text((rx+20, ry+20), "RIDE ANALYTICS", fill=C['header'])

    # Dummy Elevation Graph
    gx, gy, gw, gh = rx+20, ry+240, 300, 80
    draw.rectangle([gx, gy, gx+gw, gy+gh], outline=C['muted'])
    points = []
    for i in range(gw):
        points.append((gx + i, gy + gh - 10 - int(np.sin(i/50)*20 + 20)))
    draw.line(points, fill=(150, 160, 180), width=2)
    draw.ellipse([gx+150-4, points[150][1]-4, gx+150+4, points[150][1]+4], fill=C['hr'])
    draw.text((gx, gy-20), "ELEVATION PROFILE", fill=C['muted'])

    output = "final_hud_design.png"
    img.save(output)
    print(f"Generated: {os.path.abspath(output)}")

if __name__ == "__main__":
    gen()
