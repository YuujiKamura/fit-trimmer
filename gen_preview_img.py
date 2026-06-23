# -*- coding: utf-8 -*-
from PIL import Image, ImageDraw, ImageFont
import os

# デザイン定数 (hud_encode.py と同じ)
HUD_W, HUD_H = 800, 460
M = 18
C = {
    'bg': (6, 10, 22, 180),
    'stroke': (59, 130, 246, 100),
    'header': (59, 130, 246),
    'text': (235, 240, 250),
    'muted': (90, 100, 120),
}

def gen():
    # 1920x1080 の画面を模したキャンバス
    img = Image.new('RGBA', (1920, 1080), (30, 30, 30, 255))
    draw = ImageDraw.Draw(img)

    # HUD本体 (左上に配置)
    x, y = 50, 50
    draw.rounded_rectangle([x, y, x + 440, y + HUD_H], radius=14, fill=C['bg'], outline=C['stroke'], width=2)

    # サンプル数値の描画 (簡易版)
    font = ImageFont.load_default()
    draw.text((x + M, y + 20), "FIT RIDER - DESIGN PREVIEW", fill=C['header'])
    draw.text((x + M, y + 70), "SPEED: 33.7 km/h", fill=C['text'])
    draw.text((x + M, y + 190), "POWER: 244 W (Zone High)", fill=(16, 185, 129))
    draw.text((x + M, y + 310), "HEART RATE: 152 bpm", fill=(239, 68, 68))

    # 高度プロファイルのダミー
    draw.rectangle([x + 460, y, x + 800, y + 150], fill=(12, 16, 30, 200), outline=C['stroke'])
    draw.text((x + 460 + 10, y + 10), "ELEVATION PROFILE", fill=C['muted'])

    output = "design_preview.png"
    img.save(output)
    print(f"Preview image generated: {os.path.abspath(output)}")

if __name__ == "__main__":
    gen()
