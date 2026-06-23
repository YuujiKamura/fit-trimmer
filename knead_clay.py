# -*- coding: utf-8 -*-
import os, json
from PIL import Image, ImageDraw, ImageFont

class FinalCell:
    """背景なし、超密着、高密度のデータユニット"""
    def __init__(self, label, value, unit, color, fonts):
        self.label, self.value, self.unit = label, str(value), unit
        self.color, self.f = color, fonts
        self.m_giant = self.f['giant'].getmetrics()
        self.m_label = self.f['label'].getmetrics()
        self.val_w = self.f['giant'].getlength(self.value)
        # 高さを計算: ラベル(A+D) + 密着(1) + 数値(A)
        self.height = (self.m_label[0] + self.m_label[1]) + 1 + self.m_giant[0]

    def draw(self, draw, x, y):
        def draw_t(pos, text, font, fill, anchor):
            # 視認性確保のための極薄アウトライン（座布団代わり）
            for dx, dy in [(-1,-1),(1,1),(0,1),(0,-1)]:
                draw.text((pos[0]+dx, pos[1]+dy), text, font=font, fill=(0,0,0,180), anchor=anchor)
            draw.text(pos, text, font=font, fill=fill, anchor=anchor)

        # 1. Label
        draw_t((x, y), self.label, self.f['label'], (160, 160, 160), "la")
        # 2. Value (1px 密着)
        val_y = y + (self.m_label[0] + self.m_label[1]) + 1
        baseline_y = val_y + self.m_giant[0]
        draw_t((x, baseline_y), self.value, self.f['giant'], (255, 255, 255), "ls")
        # 3. Unit
        draw_t((x + self.val_w + 6, baseline_y - 2), self.unit, self.f['unit'], self.color, "ls")

class FinalGraph:
    """背景なしのグラフモジュール"""
    def __init__(self, label, data, w, h, fonts, mode="bar"):
        self.label, self.data, self.w, self.h = label, data, w, h
        self.f, self.mode = fonts, mode
        self.m_label = self.f['label'].getmetrics()
        self.height = (self.m_label[0] + self.m_label[1]) + h + 5

    def draw(self, draw, x, y, current_ratio=0.0):
        draw.text((x, y), self.label, font=self.f['label'], fill=(160, 160, 160), anchor="la")
        gy = y + (self.m_label[0] + self.m_label[1]) + 4

        # グラフ本体（枠線なし、塗りつぶしのみ）
        if self.mode == "bar":
            bw = self.w / len(self.data)
            for i, v in enumerate(self.data):
                bh = (v / 400) * self.h
                # パワーゾーンカラーをシミュレート
                c = (16, 185, 129) if v < 250 else (234, 179, 8)
                draw.rectangle([x+i*bw, gy+self.h-bh, x+(i+1)*bw-1, gy+self.h], fill=c)
        else:
            pts = [(x + (i/(len(self.data)-1))*self.w, gy + self.h - (v/200)*self.h) for i, v in enumerate(self.data)]
            split = int((len(self.data)-1) * current_ratio)
            draw.line(pts[:split+1], fill=(59, 130, 246), width=2)
            draw.polygon(pts[:split+1] + [(pts[split][0], gy+self.h), (x, gy+self.h)], fill=(59, 130, 246, 60))
            draw.line(pts[split:], fill=(140, 140, 140), width=1)
            # 現在地マーカー
            cx = x + self.w * current_ratio
            draw.polygon([(cx-8, gy-8), (cx+8, gy-8), (cx, gy)], fill=(239, 68, 68))

def render_preview(output_name):
    f_path = "C:/Windows/Fonts/segoeuib.ttf"
    if not os.path.exists(f_path): f_path = "arial.ttf"
    fonts = {
        'giant': ImageFont.truetype(f_path, 40), # 絶妙なサイズ感
        'label': ImageFont.truetype(f_path, 11),
        'unit':  ImageFont.truetype(f_path, 14)
    }

    img = Image.new('RGBA', (1920, 1080), (12, 13, 15, 255))
    draw = ImageDraw.Draw(img)

    # 6数値 + 2グラフ = 全8構成要素
    cells = [
        FinalCell("SPEED", "26.2", "km/h", (59, 130, 246), fonts),
        FinalCell("CADENCE", "79", "rpm", (167, 139, 250), fonts),
        FinalCell("POWER", "175", "W", (16, 185, 129), fonts),
        FinalCell("HEART RATE", "148", "bpm", (239, 68, 68), fonts),
        FinalCell("GRADE", "4.0", "%", (251, 191, 36), fonts),
        FinalCell("W/KG", "2.1", "w/kg", (45, 212, 191), fonts)
    ]

    cx, cy = 60, 60
    for c in cells:
        c.draw(draw, cx, cy)
        cy += c.height + 20

    # パワーグラフ
    p_data = [100, 120, 200, 280, 240, 180] * 3
    p_graph = FinalGraph("POWER TREND", p_data, 300, 60, fonts, mode="bar")
    p_graph.draw(draw, cx, cy)
    cy += p_graph.height + 20

    # 高度グラフ
    a_data = [10, 20, 50, 100, 150, 140, 130, 160, 180]
    a_graph = FinalGraph("ELEVATION", a_data, 300, 60, fonts, mode="line")
    a_graph.draw(draw, cx, cy, current_ratio=0.7)

    img.save(output_name)
    print(f"TRULY FINAL HUD Rendered: {output_name}")

if __name__ == "__main__":
    render_preview("fit_trimmer_truly_final.png")
