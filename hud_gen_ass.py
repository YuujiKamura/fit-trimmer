# -*- coding: utf-8 -*-
"""
hud_gen_ass.py - FITデータから爆速でHUD字幕(.ass)を生成する
"""

import argparse, datetime, json, os, sys
from pathlib import Path
import fitparse

def format_ass_time(seconds):
    """ASS形式の時間フォーマット (H:MM:SS.cs) に変換"""
    td = datetime.timedelta(seconds=max(0, seconds))
    total_seconds = int(td.total_seconds())
    hours = total_seconds // 3600
    minutes = (total_seconds % 3600) // 60
    secs = total_seconds % 60
    centiseconds = int(td.microseconds / 10000)
    return f"{hours}:{minutes:02d}:{secs:02d}.{centiseconds:02d}"

def generate_ass(fit_path, video_path, video_start_utc, output_ass_path, offset_sec=0.0):
    print(f"⌛ Generating ASS HUD: {Path(output_ass_path).name}...")

    vid_start = datetime.datetime.fromisoformat(video_start_utc.replace('Z', '+00:00')).replace(tzinfo=None)
    vid_start += datetime.timedelta(seconds=offset_sec)

    # ffprobeで動画の長さを取得
    cmd = ['ffprobe', '-v', 'quiet', '-show_entries', 'format=duration', '-of', 'default=noprint_wrappers=1:nokey=1', video_path]
    try:
        duration_sec = float(subprocess.check_output(cmd).decode().strip())
    except:
        duration_sec = 3600.0 # fallback

    fit = fitparse.FitFile(fit_path)
    records = []
    vid_end = vid_start + datetime.timedelta(seconds=duration_sec)

    for rec in fit.get_messages('record'):
        d = {m.name: m.value for m in rec if m.value is not None}
        ts = d.get('timestamp')
        if ts and vid_start <= ts <= vid_end:
            elapsed = (ts - vid_start).total_seconds()
            records.append({
                't': elapsed,
                'spd': (d.get('speed') or 0.0) * 3.6,
                'pwr': d.get('power') or 0,
                'cad': d.get('cadence') or 0,
                'hr': d.get('heart_rate') or 0,
                'alt': d.get('enhanced_altitude') or d.get('altitude') or 0,
                'grd': d.get('grade') or 0.0
            })

    if not records:
        print("❌ No data points in range.")
        return

    # ASS Header
    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: HUD_Base,Arial,32,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,-1,0,0,0,100,100,0,0,1,2,0,7,30,30,30,1
Style: HUD_Val,Consolas,60,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,1,0,7,30,30,30,1
Style: HUD_Label,Arial,20,&H00AAAAAA,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,7,30,30,30,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""

    with open(output_ass_path, 'w', encoding='utf-8') as f:
        f.write(header)

        # 1秒ごとにHUDの状態を出力
        for i in range(len(records) - 1):
            curr = records[i]
            nxt = records[i+1]
            start_str = format_ass_time(curr['t'])
            end_str = format_ass_time(nxt['t'])

            # 背景パネル（描画命令 \p1 = 矩形）
            # \pos(50,50) に配置
            bg_box = "{\\p1\\pos(50,50)\\c&H160A06&\\alpha&H64&}m 0 0 l 400 0 l 400 450 l 0 450{\\p0}"
            f.write(f"Dialogue: 0,{start_str},{end_str},HUD_Base,,0,0,0,,{bg_box}\n")

            # 各メトリクス
            y_off = 70
            # Speed
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Label,,0,0,0,,{{\\pos(70,{y_off})}}SPEED\n")
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Val,,0,0,0,,{{\\pos(70,{y_off+25})}}{curr['spd']:.1f} {{\\fs30}}km/h\n")

            # Power
            y_off += 120
            pwr_color = "00FF00" if curr['pwr'] > 200 else "FFFFFF"
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Label,,0,0,0,,{{\\pos(70,{y_off})}}POWER\n")
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Val,,0,0,0,,{{\\c&H{pwr_color}&\\pos(70,{y_off+25})}}{curr['pwr']} {{\\fs30}}W\n")

            # Heart Rate
            y_off += 120
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Label,,0,0,0,,{{\\pos(70,{y_off})}}HEART RATE\n")
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Val,,0,0,0,,{{\\c&H4444EF&\\pos(70,{y_off+25})}}{curr['hr']} {{\\fs30}}bpm\n")

    print(f"✅ Created: {output_ass_path}")

if __name__ == "__main__":
    import subprocess
    parser = argparse.ArgumentParser()
    parser.add_argument('--fit', required=True)
    parser.add_argument('--video', required=True)
    parser.add_argument('--video-start-utc', required=True)
    parser.add_argument('--offset', type=float, default=0.0)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    generate_ass(args.fit, args.video, args.video_start_utc, args.output, args.offset)
