# -*- coding: utf-8 -*-
from PIL import Image, ImageDraw, ImageFont
import os

# デザイン定数 (実際のエンコード設定と完全に一致させる)
HUD_W, HUD_H = 920, 260
C = {
    'bg': (5, 8, 15, 230),
    'accent': (59, 130, 246),
    'power': (16, 185, 129),
    'hr': (239, 68, 68),
    'text': (255, 255, 255),
    'muted': (148, 163, 184),
    'border': (255, 255, 255, 100)
}

def gen():
    # 1080p 背景を模したキャンバス
    img = Image.new('RGBA', (1920, 1080), (30, 30, 35, 255))
    draw = ImageDraw.Draw(img)

    # フォント読み込み (140px の爆大サイズ)
    font_path = "C:/Windows/Fonts/ariblk.ttf"
    try:
        f_giant = ImageFont.truetype(font_path, 140)
        f_label = ImageFont.truetype(font_path, 28)
        f_unit  = ImageFont.truetype(font_path, 32)
        f_small = ImageFont.truetype(font_path, 24)
    except:
        print("Font load failed, using default (this should not happen on Windows)")
        f_giant = f_label = f_unit = f_small = ImageFont.load_default()

    # Position: Bottom Center
    bx, by = (1920 - HUD_W)//2, 1080 - HUD_H - 40

    # 背景パネル (タイトに)
    draw.rounded_rectangle([bx, by, bx + HUD_W, by + HUD_H], radius=15, fill=C['bg'], outline=C['border'], width=4)

    def draw_comp(x, label, val, unit, color, ratio):
        # Label
        draw.text((bx + x, by + 20), label, font=f_label, fill=C['muted'])
        # Value
        draw.text((bx + x, by + 45), val, font=f_giant, fill=C['text'])
        # Unit
        bbox = f_giant.getbbox(val)
        vw = bbox[2] - bbox[0]
        draw.text((bx + x + vw + 10, by + 130), unit, font=f_unit, fill=color)
        # Bar
        draw.rounded_rectangle([bx + x, by + 195, bx + x + 260, by + 195 + 16], radius=6, fill=(40, 45, 60))
        draw.rounded_rectangle([bx + x, by + 195, bx + x + int(260 * ratio), by + 195 + 16], radius=6, fill=color)

    # 3大要素
    draw_comp(30,  "SPD", "34.2", "km/h", C['accent'], 0.7)
    draw_comp(320, "PWR", "285", "W", C['power'], 0.65)
    draw_comp(620, " HR", "162", "bpm", C['hr'], 0.8)

    # Bottom info
    draw.text((bx + 30, by + 225), "CAD: 92 | ALT: 124m | GRD: +2.4%", font=f_small, fill=C['muted'])
    draw.text((bx + HUD_W - 130, by + 225), "0:12:45", font=f_small, fill=C['accent'])

    output = "true_high_vis_preview.png"
    img.save(output)
    print(f"PREVIEW READY: {os.path.abspath(output)}")

if __name__ == "__main__":
    gen()
