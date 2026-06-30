# -*- coding: utf-8 -*-
"""
hud_encoder.py - FIT telemetry and MP4 video overlay generator.
Generates an ASS subtitle track and encodes it into the video using FFmpeg.
"""

import argparse
import datetime
import json
import os
import subprocess
import sys
from pathlib import Path
import fitparse

# Ensure UTF-8 output on Windows terminal
if sys.platform.startswith("win"):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

def find_ffmpeg():
    # 1. Check imageio-ffmpeg
    try:
        import imageio_ffmpeg
        path = imageio_ffmpeg.get_ffmpeg_exe()
        if path and os.path.exists(path):
            return path
    except ImportError:
        pass

    # 2. Check system PATH
    for cmd in ["ffmpeg.exe", "ffmpeg"]:
        try:
            subprocess.run([cmd, "-version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return cmd
        except Exception:
            pass

    # 3. Fallbacks
    fallbacks = [
        r"C:\Users\yuuji\AppData\Local\Programs\Python\Python313\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe",
        "ffmpeg"
    ]
    for p in fallbacks:
        if os.path.exists(p):
            return p
    return "ffmpeg"

def find_ffprobe():
    # Try finding ffprobe based on ffmpeg path
    ffmpeg_path = find_ffmpeg()
    if ffmpeg_path != "ffmpeg" and "ffmpeg" in ffmpeg_path.lower():
        ffprobe_path = ffmpeg_path.lower().replace("ffmpeg", "ffprobe")
        if os.path.exists(ffprobe_path):
            return ffprobe_path
    
    for cmd in ["ffprobe.exe", "ffprobe"]:
        try:
            subprocess.run([cmd, "-version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return cmd
        except Exception:
            pass
    return "ffprobe"

def get_video_info(video_path):
    ffprobe = find_ffprobe()
    cmd = [
        ffprobe, "-v", "quiet",
        "-show_entries", "format=duration,creation_time:stream=width,height,r_frame_rate",
        "-of", "json", video_path
    ]
    try:
        res = subprocess.check_output(cmd)
        data = json.loads(res.decode('utf-8'))
        
        fmt = data.get("format", {})
        duration = float(fmt.get("duration", 0.0))
        creation_time = fmt.get("tags", {}).get("creation_time")
        
        streams = data.get("streams", [{}])
        width = int(streams[0].get("width", 1920))
        height = int(streams[0].get("height", 1080))
        
        # Frame rate parsing (e.g. "30/1" or "60000/1001")
        r_frame_rate = streams[0].get("r_frame_rate", "30/1")
        if "/" in r_frame_rate:
            num, den = map(float, r_frame_rate.split("/"))
            fps = num / den if den != 0 else 30.0
        else:
            fps = float(r_frame_rate)
            
        return {
            "duration": duration,
            "width": width,
            "height": height,
            "fps": fps,
            "creation_time": creation_time
        }
    except Exception as e:
        print(f"[WARN] Failed to parse video metadata: {e}. Falling back to default values.")
        return {
            "duration": 3600.0,
            "width": 1920,
            "height": 1080,
            "fps": 30.0,
            "creation_time": None
        }

def format_ass_time(seconds):
    td = datetime.timedelta(seconds=max(0.0, seconds))
    total_seconds = int(td.total_seconds())
    hours = total_seconds // 3600
    minutes = (total_seconds % 3600) // 60
    secs = total_seconds % 60
    centiseconds = int(td.microseconds / 10000)
    return f"{hours}:{minutes:02d}:{secs:02d}.{centiseconds:02d}"


def lerp_frame_value(v0, v1, alpha: float, as_int: bool = False):
    """2点間を線形補間する。

    Args:
        v0: 開始値
        v1: 終了値
        alpha: 補間係数 [0.0, 1.0]
        as_int: True のとき結果を round() した int で返す

    Returns:
        補間後の値（float または int）
    """
    value = v0 + (v1 - v0) * alpha
    if as_int:
        return int(round(value))
    return value


def frames_between(t0: float, t1: float, fps: float):
    """t0 から t1 (exclusive) までのフレームを (frame_time, alpha) で yield する。

    Args:
        t0: セグメント開始時刻 (秒)
        t1: セグメント終了時刻 (秒)
        fps: 動画フレームレート

    Yields:
        (frame_time: float, alpha: float) のタプル
    """
    frame_dur = 1.0 / fps
    seg_dur = t1 - t0
    if seg_dur <= 0:
        return
    t = t0
    while t < t1 - frame_dur * 0.5:  # 丸め誤差を吸収するための 0.5フレーム余裕
        alpha = min((t - t0) / seg_dur, 1.0)
        yield t, alpha
        t += frame_dur

def get_color_tags(pwr, hr):
    # Dynamic styling for Power (W)
    if pwr <= 100:
        pwr_c = "&H008AE6" # Green
    elif pwr <= 200:
        pwr_c = "&H00D4FF" # Yellow
    elif pwr <= 300:
        pwr_c = "&H0080FF" # Orange
    else:
        pwr_c = "&H0000FF" # Red

    # Dynamic styling for Heart Rate (bpm)
    if hr <= 120:
        hr_c = "&HFFB6C1" # Light Blue
    elif hr <= 150:
        hr_c = "&H00E6A8" # Greenish Yellow
    elif hr <= 170:
        hr_c = "&H0080FF" # Orange
    else:
        hr_c = "&H0000FF" # Red
        
    return pwr_c, hr_c

def generate_ass(fit_path, video_info, video_start_utc, output_ass, offset_sec=0.0):
    print(f"[INFO] Parsing FIT data...")
    vid_start = datetime.datetime.fromisoformat(video_start_utc.replace('Z', '+00:00')).replace(tzinfo=None)
    vid_start += datetime.timedelta(seconds=offset_sec)
    
    vid_end = vid_start + datetime.timedelta(seconds=video_info["duration"])

    fit = fitparse.FitFile(fit_path)
    records = []
    
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
                'alt': d.get('enhanced_altitude') or d.get('altitude') or 0.0,
                'grd': d.get('grade') or 0.0,
                'dst': (d.get('distance') or 0.0) / 1000.0 # to km
            })
            
    if not records:
        raise ValueError("[ERROR] No FIT telemetry records align with the video time frame.")

    # Align starting distance
    start_dst = records[0]['dst']
    for r in records:
        r['dst'] = max(0.0, r['dst'] - start_dst)

    # Write ASS File
    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: HUD_Panel,Arial,10,&H642A2A2A,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,0,0,40,1
Style: HUD_Base,Segoe UI Semibold,24,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,1.5,1,2,0,0,50,1
Style: HUD_Label,Segoe UI,16,&H00B0B0B0,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,1.5,0,2,0,0,50,1
Style: HUD_Value,Consolas,44,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,1.5,0,2,0,0,50,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""

    fps = video_info.get("fps", 30.0)
    frame_dur = 1.0 / fps
    print(f"[INFO] Smoothing mode: per-frame interpolation @ {fps:.3f} fps (frame_dur={frame_dur*1000:.2f} ms)")

    with open(output_ass, 'w', encoding='utf-8') as f:
        f.write(header)

        # --- Panel background: 1 event per FIT segment (no per-frame overhead needed) ---
        for i in range(len(records) - 1):
            curr = records[i]
            nxt = records[i + 1]
            start_str = format_ass_time(curr['t'])
            end_str = format_ass_time(nxt['t'])
            panel_draw = "{\\p1\\pos(410,910)}m 0 0 l 1100 0 l 1100 120 l 0 120{\\p0}"
            f.write(f"Dialogue: 0,{start_str},{end_str},HUD_Panel,,0,0,0,,{panel_draw}\n")

        # --- Labels: also static per segment ---
        for i in range(len(records) - 1):
            curr = records[i]
            nxt = records[i + 1]
            start_str = format_ass_time(curr['t'])
            end_str = format_ass_time(nxt['t'])
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Label,,0,0,0,,{{\\pos(490,940)}}SPEED\n")
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Label,,0,0,0,,{{\\pos(680,940)}}POWER\n")
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Label,,0,0,0,,{{\\pos(850,940)}}CADENCE\n")
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Label,,0,0,0,,{{\\pos(1020,940)}}HEART RATE\n")
            f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Label,,0,0,0,,{{\\pos(1240,940)}}DIST / ELEV / GRD\n")

        # --- Values: per-frame linear interpolation for smooth animation ---
        for i in range(len(records) - 1):
            curr = records[i]
            nxt = records[i + 1]

            for frame_t, alpha in frames_between(curr['t'], nxt['t'], fps):
                frame_end = frame_t + frame_dur
                start_str = format_ass_time(frame_t)
                end_str   = format_ass_time(frame_end)

                spd = lerp_frame_value(curr['spd'], nxt['spd'], alpha)
                pwr = lerp_frame_value(curr['pwr'], nxt['pwr'], alpha, as_int=True)
                cad = lerp_frame_value(curr['cad'], nxt['cad'], alpha, as_int=True)
                hr  = lerp_frame_value(curr['hr'],  nxt['hr'],  alpha, as_int=True)
                alt = lerp_frame_value(curr['alt'], nxt['alt'], alpha)
                grd = lerp_frame_value(curr['grd'], nxt['grd'], alpha)
                dst = lerp_frame_value(curr['dst'], nxt['dst'], alpha)

                pwr_c, hr_c = get_color_tags(pwr, hr)

                f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Value,,0,0,0,,"
                        f"{{\\pos(490,985)}}{{\\c&H00DFFF&}}{spd:.1f}{{\\fs24}} km/h\n")
                f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Value,,0,0,0,,"
                        f"{{\\pos(680,985)}}{{\\c{pwr_c}}}{pwr}{{\\fs24}} W\n")
                f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Value,,0,0,0,,"
                        f"{{\\pos(850,985)}}{cad}{{\\fs24}} rpm\n")
                f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Value,,0,0,0,,"
                        f"{{\\pos(1020,985)}}{{\\c{hr_c}}}{hr}{{\\fs24}} bpm\n")
                f.write(f"Dialogue: 1,{start_str},{end_str},HUD_Value,,0,0,0,,"
                        f"{{\\pos(1240,985)}}{{\\fs32}}{dst:.1f}km / {alt:.0f}m / {grd:.1f}%\n")

    print(f"[SUCCESS] Generated ASS HUD Subtitles: {output_ass}")

def detect_encoder(ffmpeg_path):
    def check(encoder, hwaccel=None):
        cmd = [ffmpeg_path, "-y"]
        if hwaccel:
            cmd.extend(["-hwaccel", hwaccel])
        cmd.extend(["-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.1", "-c:v", encoder, "-f", "null", "-"])
        try:
            res = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return res.returncode == 0
        except Exception:
            return False

    if check("h264_nvenc", "auto"):
        return "h264_nvenc", "auto"
    if check("h264_qsv", "auto"):
        return "h264_qsv", "auto"
    if check("h264_amf", "auto"):
        return "h264_amf", "auto"
    return "libx264", None

def main():
    parser = argparse.ArgumentParser(description="Cycling HUD Overlay Video Encoder")
    parser.add_argument("--fit", required=True, help="Path to FIT telemetry file")
    parser.add_argument("--video", required=True, help="Path to input MP4 video file")
    parser.add_argument("--output", required=True, help="Path to output MP4 video file")
    parser.add_argument("--video-start-utc", help="Video recording start time (UTC, ISO-8601). If omitted, attempts to auto-read.")
    parser.add_argument("--offset", type=float, default=0.0, help="Start synchronization offset in seconds (default: 0)")
    parser.add_argument("--test", action="store_true", help="Render only the first 10 seconds for quick verification")
    args = parser.parse_args()

    ffmpeg = find_ffmpeg()
    
    # 1. Fetch Video Info
    video_info = get_video_info(args.video)
    
    # 2. Determine Video Start UTC
    start_utc = args.video_start_utc
    if not start_utc:
        if video_info.get("creation_time"):
            start_utc = video_info["creation_time"]
            print(f"[INFO] Automatically detected video creation time: {start_utc}")
        else:
            # Fallback to current time minus duration, or default
            start_utc = "2026-06-21T02:09:49Z"
            print(f"[WARN] Could not auto-detect video creation time. Using fallback: {start_utc}")

    # 3. Create temp ASS file path
    temp_ass = Path(args.output).with_suffix(".ass").absolute()
    
    try:
        # 4. Generate Subtitles
        generate_ass(args.fit, video_info, start_utc, str(temp_ass), args.offset)
        
        # 5. Determine optimal encoder
        encoder, hwaccel = detect_encoder(ffmpeg)
        print(f"[INFO] Selected Encoder: {encoder} (Hwaccel: {hwaccel})")
        
        # 6. Build FFmpeg command
        # Note: ASS subtitle path needs proper escaping for FFmpeg filter on Windows
        escaped_ass = str(temp_ass).replace("\\", "/").replace(":", "\\:")
        
        cmd = [ffmpeg, "-y"]
        if hwaccel:
            cmd.extend(["-hwaccel", hwaccel])
            
        cmd.extend(["-i", args.video])
        
        # Subtitle overlay filter
        cmd.extend(["-vf", f"ass='{escaped_ass}'"])
        
        # Encoder configuration
        cmd.extend(["-c:v", encoder])
        if encoder in ["h264_qsv", "h264_nvenc"]:
            cmd.extend(["-qp", "18"])
        else:
            cmd.extend(["-crf", "18"])
            
        cmd.extend(["-pix_fmt", "yuv420p", "-c:a", "copy"])
        
        if args.test:
            cmd.extend(["-t", "10"])
            
        cmd.append(args.output)
        
        # 7. Execute Encode
        print(f"[INFO] Encoding video...")
        subprocess.run(cmd, check=True)
        print(f"[SUCCESS] HUD Video successfully written to: {args.output}")
        
    finally:
        # Cleanup
        if temp_ass.exists():
            try:
                os.remove(temp_ass)
            except Exception:
                pass

if __name__ == "__main__":
    main()
