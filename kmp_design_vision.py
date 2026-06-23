# -*- coding: utf-8 -*-
from PIL import Image, ImageDraw, ImageFont
import os

def gen():
    # 1400x900 アプリウィンドウをシミュレート
    img = Image.new('RGBA', (1400, 900), (10, 10, 12, 255))
    draw = ImageDraw.Draw(img)

    # 1. Sidebar (300px)
    draw.rectangle([0, 0, 300, 900], fill=(22, 23, 29, 255))
    draw.text((20, 20), "HUD DESIGNER", fill=(59, 130, 246))
    draw.text((20, 45), "KMP Desktop Edition", fill=(100, 100, 110))

    # スライダー群
    for i in range(5):
        y_top = 100 + i*60
        draw.rectangle([20, y_top, 280, y_top + 5], fill=(40, 45, 60))
        draw.ellipse([20, y_top - 3, 35, y_top + 8], fill=(59, 130, 246))

    # FIREボタン
    draw.rounded_rectangle([20, 800, 280, 860], radius=8, fill=(225, 29, 72))
    draw.text((100, 825), "START ENCODE", fill=(255, 255, 255))

    # 2. Preview Area (Canvas 16:9)
    cx, cy = 380, 180
    draw.rectangle([cx, cy, cx+960, cy+540], fill=(0, 0, 0, 255), outline=(100, 100, 110), width=2)

    # HUDを描画 (あなたの指示：背景なし・超密着)
    hx, hy = cx + 40, cy + 40
    metrics = [("SPEED", "26.2 km/h"), ("CADENCE", "79 rpm"), ("POWER", "175 W"), ("HEART RATE", "148 bpm"), ("GRADE", "4.0 %"), ("W/KG", "2.1 w/kg")]

    for lbl, val in metrics:
        draw.text((hx, hy), lbl, fill=(148, 163, 184))
        draw.text((hx, hy + 12), val, fill=(255, 255, 255))
        hy += 55

    img.save("kmp_design_vision.png")
    print(f"Vision generated: {os.path.abspath('kmp_design_vision.png')}")

if __name__ == "__main__":
    gen()
