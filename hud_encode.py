# -*- coding: utf-8 -*-
"""
hud_encode.py  -  FIT + MP4 → グラフィカル HUD オーバーレイ動画 CLI

依存: pip install fitparse Pillow imageio-ffmpeg
"""

import argparse, datetime, json, os, subprocess, sys
from pathlib import Path

# ── 依存チェック ───────────────────────────────────────────────
try:
    import fitparse
except ImportError:
    print("pip install fitparse"); sys.exit(1)

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("pip install Pillow"); sys.exit(1)

try:
    import imageio_ffmpeg
    FFMPEG_BIN = imageio_ffmpeg.get_ffmpeg_exe()
except ImportError:
    FFMPEG_BIN = 'ffmpeg'

# ── HUD レイアウト定数 ─────────────────────────────────────────
HUD_W, HUD_H = 800, 460
M   = 18        # outer margin
GM  = 10        # gap between metric rows
BAR = 9         # bar height
BR  = 4         # bar border-radius

# ── カラーパレット ─────────────────────────────────────────────
C = {
    'bg':       (6,  10,  22, 215),
    'stroke':   (59, 130, 246,  60),
    'header':   (59, 130, 246),
    'text':     (235, 240, 250),
    'muted':    ( 90, 100, 120),
    'div':      ( 22,  28,  45),
    'track':    ( 22,  28,  45),
    # Power / HR zone colors
    'z1': (80,  80,  80),
    'z2': (59,  130, 246),
    'z3': (16,  185, 129),
    'z4': (234, 179,   8),
    'z5': (249, 115,  22),
    'z6': (239,  68,  68),
    'z7': (168,  85, 247),
}


def _load_fonts():
    """システムフォントを読み込む。フォールバックあり。"""
    candidates_mono = [
        'C:/Windows/Fonts/consolab.ttf',
        'C:/Windows/Fonts/consola.ttf',
        'C:/Windows/Fonts/arialbd.ttf',
        'C:/Windows/Fonts/arial.ttf',
    ]
    candidates_sans = [
        'C:/Windows/Fonts/arialbd.ttf',
        'C:/Windows/Fonts/arial.ttf',
        'C:/Windows/Fonts/consolab.ttf',
    ]

    def load(paths, size):
        for p in paths:
            if os.path.exists(p):
                try:
                    return ImageFont.truetype(p, size)
                except Exception:
                    pass
        return ImageFont.load_default()

    return {
        'val_lg':  load(candidates_mono, 44),   # 大きい数値 (SPD/PWR/HR)
        'val_sm':  load(candidates_mono, 26),   # 小さい数値 (CAD/ALT/GRD)
        'label':   load(candidates_sans,  15),
        'unit':    load(candidates_sans,  13),
        'header':  load(candidates_sans,  16),
        'zone':    load(candidates_sans,  13),
    }


FONTS = _load_fonts()


# ── ゾーン判定 ─────────────────────────────────────────────────
def power_color(w: int):
    """パワー値（絶対値）に応じた色を返す"""
    if   w < 150: return C['z1']  # 灰色
    elif w < 200: return C['z2']  # 青
    elif w < 250: return C['z3']  # 緑
    elif w < 300: return C['z4']  # 黄
    elif w < 400: return C['z5']  # オレンジ
    else:         return C['z6']  # 赤


def hr_color(bpm: int):
    """心拍数（絶対値）に応じた色を返す"""
    if   bpm < 100: return C['z1']
    elif bpm < 120: return C['z2']
    elif bpm < 140: return C['z3']
    elif bpm < 160: return C['z4']
    elif bpm < 175: return C['z5']
    else:           return C['z6']


# ── HUD フレーム描画 ───────────────────────────────────────────
def _rounded_rect(draw: ImageDraw.Draw, xy, radius, fill=None, outline=None, width=1):
    """PIL rounded_rectangle ラッパー (古い Pillow でも動く)"""
    try:
        draw.rounded_rectangle(xy, radius=radius, fill=fill, outline=outline, width=width)
    except AttributeError:
        draw.rectangle(xy, fill=fill, outline=outline, width=width)


def _bar(draw: ImageDraw.Draw, x, y, w, ratio: float, color, track=None):
    """プログレスバーを描画する (ratio: 0.0〜1.0)"""
    track_c = track or C['track']
    ratio = max(0.0, min(1.0, ratio))
    _rounded_rect(draw, [x, y, x + w, y + BAR], BR, fill=track_c)
    fill_w = max(BR * 2, int(w * ratio))
    _rounded_rect(draw, [x, y, x + fill_w, y + BAR], BR, fill=color)


def _text_right(draw, x_right, y, text, font, fill):
    """右端揃えでテキストを描画する"""
    try:
        bbox = font.getbbox(text)
        tw = bbox[2] - bbox[0]
    except AttributeError:
        tw = len(text) * 8
    draw.text((x_right - tw, y), text, font=font, fill=fill)


def draw_hud_frame(row: dict, elapsed_sec: float, ftp: int, max_hr: int, all_rows: list = None, current_idx: int = 0) -> bytes:
    """1フレーム分の RGBA bytes を生成する"""
    img  = Image.new('RGBA', (HUD_W, HUD_H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if all_rows is None:
        all_rows = [row]
        current_idx = 0

    # ── 背景パネル ──────────────────────────────────────────
    _rounded_rect(draw, [0, 0, HUD_W, HUD_H], 14, fill=C['bg'])
    _rounded_rect(draw, [0, 0, HUD_W, HUD_H], 14, outline=C['stroke'], width=1)

    # ── ヘッダー ────────────────────────────────────────────
    h_y  = M
    draw.rectangle([M, h_y + 4, M + 4, h_y + 18], fill=C['header'])   # アクセントバー
    elapsed_str = f"{int(elapsed_sec)//3600:01d}:{int(elapsed_sec)%3600//60:02d}:{int(elapsed_sec)%60:02d}"
    draw.text((M + 12, h_y + 2), 'FIT RIDER', font=FONTS['header'], fill=C['text'])
    _text_right(draw, 440 - M, h_y + 2, elapsed_str, FONTS['header'], C['muted'])

    cur_y = h_y + 30

    # ── 区切り線 ────────────────────────────────────────────
    def divider():
        nonlocal cur_y
        cur_y += 6
        draw.rectangle([M, cur_y, 440 - M, cur_y + 1], fill=C['div'])
        cur_y += 8

    # ── メトリクスブロック描画ヘルパー ──────────────────────
    def metric_block(label, value_str, unit_str, ratio, bar_color,
                     zone_n=0, zone_color=None, big=True):
        nonlocal cur_y
        # ラベル行
        draw.text((M, cur_y), label, font=FONTS['label'], fill=C['muted'])
        if zone_n > 0:
            zt = f"Z{zone_n}"
            _text_right(draw, 440 - M, cur_y, zt, FONTS['zone'],
                        zone_color or C['muted'])
        cur_y += 18

        # 値 + 単位
        vfont = FONTS['val_lg'] if big else FONTS['val_sm']
        draw.text((M, cur_y), value_str, font=vfont, fill=C['text'])
        try:
            bbox = vfont.getbbox(value_str)
            vw = bbox[2] - bbox[0]
        except AttributeError:
            vw = len(value_str) * (22 if big else 14)
        draw.text((M + vw + 6, cur_y + (14 if big else 8)),
                  unit_str, font=FONTS['unit'], fill=C['muted'])
        cur_y += (48 if big else 32)

        # バー
        _bar(draw, M, cur_y, 440 - M * 2, ratio, bar_color)
        cur_y += BAR + GM

    # ── Speed ───────────────────────────────────────────────
    divider()
    spd = row['speed']
    metric_block('SPEED', f'{spd:.1f}', 'km/h',
                 ratio=min(spd / 60.0, 1.0),
                 bar_color=C['header'], big=True)

    # ── Power ───────────────────────────────────────────────
    divider()
    pwr = row['power']
    zc_p = power_color(pwr)
    metric_block('POWER', str(pwr), 'W',
                 ratio=min(pwr / 450.0, 1.0),
                 bar_color=zc_p, zone_n=0, zone_color=None, big=True)

    # ── HR ──────────────────────────────────────────────────
    divider()
    hr = row['hr']
    zc_h = hr_color(hr)
    hr_ratio = max(0.0, min(1.0, (hr - 60) / 130.0)) if hr > 0 else 0.0
    metric_block('HEART RATE', str(hr), 'bpm',
                 ratio=hr_ratio,
                 bar_color=zc_h, zone_n=0, zone_color=None, big=True)

    # ── 下段 2列 (CAD / ALT / GRD) ──────────────────────────
    divider()
    half = (440 - M * 2 - 10) // 2

    def mini(label, val_str, x):
        draw.text((x, cur_y),     label,   font=FONTS['label'], fill=C['muted'])
        draw.text((x, cur_y + 16), val_str, font=FONTS['val_sm'], fill=C['text'])

    mini('CADENCE',  f"{row['cadence']} rpm",  M)
    mini('ALTITUDE', f"{row['alt']:.0f} m",    M + half + 10)

    gr_y = cur_y + 52
    draw.text((M, gr_y), 'GRADE', font=FONTS['label'], fill=C['muted'])
    grade_color = C['z5'] if abs(row['grade']) > 8 else C['z4'] if abs(row['grade']) > 4 else C['text']
    draw.text((M, gr_y + 16), f"{row['grade']:+.1f}%", font=FONTS['val_sm'], fill=grade_color)

    # ── 右側セクション ─────────────────────────────────────
    # 左右境界線
    draw.line([440, M, 440, HUD_H - M], fill=C['div'], width=1)

    # セクションヘッダー
    draw.rectangle([440 + M, h_y + 4, 440 + M + 4, h_y + 18], fill=C['header'])
    draw.text((440 + M + 12, h_y + 2), 'RIDE ANALYTICS & PROFILE', font=FONTS['header'], fill=C['text'])

    # 1. POWER / HR リアルタイム履歴 (過去90秒)
    gx1, gy1 = 440 + 20, 70
    gx2, gy2 = HUD_W - 20, 200
    gw, gh = gx2 - gx1, gy2 - gy1
    
    draw.text((gx1, gy1 - 18), 'POWER & HEART RATE (LAST 90S)', font=FONTS['label'], fill=C['muted'])
    draw.rectangle([gx1, gy1, gx2, gy2], fill=(12, 16, 30, 255), outline=(59, 130, 246, 40))
    for i in range(1, 4):
        y = gy1 + gh * i // 4
        draw.line([gx1, y, gx2, y], fill=(59, 130, 246, 20))

    history_len = 90
    start_i = max(0, current_idx - history_len + 1)
    sub_rows = all_rows[start_i : current_idx + 1]

    max_p = max([r['power'] for r in sub_rows] + [400])
    max_h = 200
    min_h = 60

    # パワー面グラフ
    p_points = []
    for idx, r in enumerate(sub_rows):
        t_idx = history_len - len(sub_rows) + idx
        x = gx1 + (t_idx / (history_len - 1)) * gw if history_len > 1 else gx1
        p_val = r['power']
        y = gy2 - (p_val / max_p) * gh
        p_points.append((x, y))

    if len(p_points) >= 2:
        poly = [(p_points[0][0], gy2)] + p_points + [(p_points[-1][0], gy2)]
        draw.polygon(poly, fill=(59, 130, 246, 50))
        draw.line(p_points, fill=(59, 130, 246, 200), width=2)

    # 心拍折れ線
    h_points = []
    for idx, r in enumerate(sub_rows):
        t_idx = history_len - len(sub_rows) + idx
        x = gx1 + (t_idx / (history_len - 1)) * gw if history_len > 1 else gx1
        hr_val = r['hr']
        ratio = (hr_val - min_h) / (max_h - min_h) if max_h > min_h else 0.0
        ratio = max(0.0, min(1.0, ratio))
        y = gy2 - ratio * gh
        h_points.append((x, y))

    if len(h_points) >= 2:
        draw.line(h_points, fill=(239, 68, 68, 220), width=2)

    # 2. 全体高度プロファイル (ライド全体)
    egx1, egy1 = 440 + 20, 240
    egx2, egy2 = HUD_W - 20, 310
    egw, egh = egx2 - egx1, egy2 - egy1

    draw.text((egx1, egy1 - 18), 'ELEVATION PROFILE & POSITION', font=FONTS['label'], fill=C['muted'])
    draw.rectangle([egx1, egy1, egx2, egy2], fill=(12, 16, 30, 255), outline=(59, 130, 246, 40))

    altitudes = [r['alt'] for r in all_rows]
    min_alt = min(altitudes) if altitudes else 0.0
    max_alt = max(altitudes) if altitudes else 100.0
    alt_diff = max_alt - min_alt
    if alt_diff < 10.0:
        min_alt -= 5.0
        max_alt += 5.0
        alt_diff = max_alt - min_alt

    alt_points = []
    for idx, r in enumerate(all_rows):
        x = egx1 + (idx / (len(all_rows) - 1)) * egw if len(all_rows) > 1 else egx1
        y = egy2 - ((r['alt'] - min_alt) / alt_diff) * egh
        alt_points.append((x, y))

    if len(alt_points) >= 2:
        poly = [(alt_points[0][0], egy2)] + alt_points + [(alt_points[-1][0], egy2)]
        draw.polygon(poly, fill=(100, 110, 130, 40))
        draw.line(alt_points, fill=(150, 160, 180, 120), width=1)

    # 現在地プロット
    if 0 <= current_idx < len(all_rows):
        curr_x = egx1 + (current_idx / (len(all_rows) - 1)) * egw if len(all_rows) > 1 else egx1
        curr_y = egy2 - ((all_rows[current_idx]['alt'] - min_alt) / alt_diff) * egh
        draw.ellipse([curr_x - 4, curr_y - 4, curr_x + 4, curr_y + 4], fill=(239, 68, 68), outline=(255, 255, 255), width=1)
        draw.ellipse([curr_x - 7, curr_y - 7, curr_x + 7, curr_y + 7], outline=(239, 68, 68, 120), width=1)

    # 3. 勾配（斜度）履歴 (過去60秒)
    ggx1, ggy1 = 440 + 20, 350
    ggx2, ggy2 = HUD_W - 20, 420
    ggw, ggh = ggx2 - ggx1, ggy2 - ggy1
    g_mid = ggy1 + ggh // 2

    # 過去60秒の勾配
    g_history_len = 60
    g_start_i = max(0, current_idx - g_history_len + 1)
    g_sub_rows = all_rows[g_start_i : current_idx + 1]
    
    max_g = max([abs(r['grade']) for r in g_sub_rows] + [10.0])

    draw.text((ggx1, ggy1 - 18), f'GRADE HISTORY (max {max_g:.1f}%)', font=FONTS['label'], fill=C['muted'])
    draw.rectangle([ggx1, ggy1, ggx2, ggy2], fill=(12, 16, 30, 255), outline=(59, 130, 246, 40))
    draw.line([ggx1, g_mid, ggx2, g_mid], fill=(100, 110, 130, 80)) # 0% 基準線

    for idx, r in enumerate(g_sub_rows):
        t_idx = g_history_len - len(g_sub_rows) + idx
        bx = ggx1 + (t_idx / (g_history_len - 1)) * ggw if g_history_len > 1 else ggx1
        grade = r['grade']
        val_h = (grade / max_g) * (ggh / 2)
        by = g_mid - val_h
        
        bar_color = (239, 68, 68) if grade > 0 else (59, 130, 246)
        draw.line([bx, g_mid, bx, by], fill=bar_color, width=2)

    return img.tobytes()


# ── FIT パース ─────────────────────────────────────────────────
def parse_fit_telemetry(fit_path: str,
                         vid_start_utc: datetime.datetime,
                         duration_sec: float) -> list:
    vid_end = vid_start_utc + datetime.timedelta(seconds=duration_sec)
    rows = []
    for rec in fitparse.FitFile(fit_path).get_messages('record'):
        data = {d.name: d.value for d in rec if d.value is not None}
        ts = data.get('timestamp')
        if ts is None or not (vid_start_utc <= ts <= vid_end):
            continue
        elapsed = (ts - vid_start_utc).total_seconds()
        spd = data.get('speed')
        rows.append({
            'elapsed':  round(elapsed, 1),
            'speed':    round(spd * 3.6, 1) if spd else 0.0,
            'power':    int(data.get('power', 0) or 0),
            'cadence':  int(data.get('cadence', 0) or 0),
            'hr':       int(data.get('heart_rate', 0) or 0),
            'alt':      float(data.get('enhanced_altitude') or data.get('altitude') or 0),
            'grade':    float(data.get('grade') or 0),
        })
    return rows


def get_video_info(path: str):
    # ffprobe は PATH から探す (imageio_ffmpeg はffmpegのみ同梱)
    for probe in ['ffprobe', 'ffprobe.exe']:
        try:
            r = subprocess.run(
                [probe, '-v', 'quiet',
                 '-print_format', 'json', '-show_streams', '-show_format', path],
                capture_output=True, text=True, encoding='utf-8'
            )
            if r.returncode == 0:
                break
        except FileNotFoundError:
            continue
    else:
        raise RuntimeError('ffprobe が見つかりません。ffmpeg と同じ場所にあるか確認してください。')
    d = json.loads(r.stdout)
    dur = float(d['format']['duration'])
    vs = next(s for s in d['streams'] if s['codec_type'] == 'video')
    return dur, int(vs['width']), int(vs['height'])


def filename_to_utc(path: str):
    import re
    m = re.search(r'(\d{8})_(\d{6})', Path(path).stem)
    if not m:
        return None
    d, t = m.group(1), m.group(2)
    jst = datetime.datetime(int(d[:4]),int(d[4:6]),int(d[6:]),
                            int(t[:2]),int(t[2:4]),int(t[4:]),
                            tzinfo=datetime.timezone(datetime.timedelta(hours=9)))
    return jst.astimezone(datetime.timezone.utc).replace(tzinfo=None)


# ── メインエンコード ───────────────────────────────────────────
def encode(fit_path, video_path, output_path,
           offset_sec=0.0, duration_sec=None,
           quality=23, ftp=280, max_hr=185):

    print('[1/4] 動画情報を取得...')
    vid_dur, vid_w, vid_h = get_video_info(video_path)
    print(f'  {vid_w}x{vid_h}, {vid_dur:.1f}s')

    if duration_sec is None:
        duration_sec = vid_dur

    print('[2/4] 動画開始時刻を推定...')
    vid_start = filename_to_utc(video_path)
    if vid_start is None:
        raise RuntimeError('ファイル名から時刻取得不可。--video-start-utc で指定してください。')
    vid_start += datetime.timedelta(seconds=offset_sec)
    print(f'  開始 UTC: {vid_start.isoformat()} (offset={offset_sec:+.1f}s)')

    print('[3/4] FIT テレメトリーを抽出...')
    rows = parse_fit_telemetry(fit_path, vid_start, duration_sec)
    if not rows:
        raise RuntimeError('FIT データが動画時刻窓にありません。--offset で調整してください。')
    print(f'  {len(rows)} records, {rows[0]["elapsed"]:.0f}s ~ {rows[-1]["elapsed"]:.0f}s')

    print(f'[4/4] エンコード中... (CRF={quality}, FTP={ftp}W, MaxHR={max_hr})')
    print(f'  HUD: {HUD_W}x{HUD_H} px  出力: {output_path}')

    # ffmpeg フィルター: ソース動画に HUD (pipe 入力) をオーバーレイ
    # 入力 0 = HUD rawvideo (1fps, RGBA)
    # 入力 1 = ソース動画
    n_frames = int(duration_sec) + 2
    fps_src   = '29.97'

    filter_complex = (
        f'[0:v]setpts=PTS-STARTPTS[hud];'
        f'[1:v][hud]overlay=20:20:shortest=1:format=auto'
    )

    cmd = [
        FFMPEG_BIN, '-y',
        # HUD ストリーム (stdin パイプ)
        '-f', 'rawvideo',
        '-pixel_format', 'rgba',
        '-video_size', f'{HUD_W}x{HUD_H}',
        '-framerate', '1',
        '-i', 'pipe:0',
        # ソース動画
        '-i', video_path,
        '-filter_complex', filter_complex,
        '-c:v', 'libx264',
        '-pix_fmt', 'yuv420p',   # 標準 4:2:0 — 全プレーヤー互換
        '-crf', str(quality),
        '-preset', 'fast',
        '-c:a', 'copy',
    ]
    if duration_sec < vid_dur - 0.5:
        cmd += ['-t', str(int(duration_sec))]
    cmd.append(output_path)

    project_dir = os.path.dirname(os.path.abspath(__file__))
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, cwd=project_dir)

    # フレームを生成してパイプ
    last_row = rows[0]
    row_idx  = 0
    for i in range(n_frames):
        # elapsed=i に最も近いレコードを検索
        while row_idx + 1 < len(rows) and rows[row_idx + 1]['elapsed'] <= i:
            row_idx += 1
        last_row = rows[row_idx]
        frame_bytes = draw_hud_frame(last_row, float(i), ftp=ftp, max_hr=max_hr, all_rows=rows, current_idx=row_idx)
        try:
            proc.stdin.write(frame_bytes)
        except BrokenPipeError:
            break

    proc.stdin.close()
    ret = proc.wait()

    if ret != 0:
        sys.exit(ret)

    size_mb = os.path.getsize(output_path) / 1024 / 1024
    print(f'\n[DONE] {output_path} ({size_mb:.1f} MB)')
    print(f'       FTP={ftp}W  MaxHR={max_hr}bpm')


def main():
    parser = argparse.ArgumentParser(description='FIT + MP4 -> グラフィカル HUD 動画')
    parser.add_argument('--fit',      required=True)
    parser.add_argument('--video',    required=True)
    parser.add_argument('--output',   default='out_hud.mp4')
    parser.add_argument('--offset',   type=float, default=0.0,
                        help='FIT/動画 時刻ズレ補正 (秒)')
    parser.add_argument('--duration', type=float, default=None,
                        help='出力秒数 (省略=全体)')
    parser.add_argument('--quality',  type=int,   default=23)
    parser.add_argument('--ftp',      type=int,   default=280,
                        help='FTP (W) パワーゾーン計算用 (default=280)')
    parser.add_argument('--max-hr',   type=int,   default=185,
                        help='最大心拍数 HR ゾーン計算用 (default=185)')
    args = parser.parse_args()

    encode(
        fit_path=args.fit,
        video_path=args.video,
        output_path=args.output,
        offset_sec=args.offset,
        duration_sec=args.duration,
        quality=args.quality,
        ftp=args.ftp,
        max_hr=args.max_hr,
    )


if __name__ == '__main__':
    main()
