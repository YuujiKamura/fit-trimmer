import json
import subprocess
import os
import sys

def main():
    manifest_path = "H:/マイドライブ/20260621/sync_manifest.json"
    if not os.path.exists(manifest_path):
        print(f"Manifest not found: {manifest_path}")
        return

    with open(manifest_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    fit_file = data['fitFile']
    videos = data['videos']

    print(f"🚀 Subtitle HUD Batch Start: {len(videos)} videos found.")

    for v in videos:
        # 字幕ファイル名は動画ファイル名.assにする
        output_name = f"{os.path.splitext(v['name'])[0]}.ass"
        output_path = os.path.join(os.path.dirname(v['path']), output_name)

        print(f"🎬 Processing: {v['name']} -> {output_name}")
        cmd = [
            sys.executable, "hud_gen_ass.py",
            "--fit", fit_file,
            "--video", v['path'],
            "--video-start-utc", v['startUtc'],
            "--output", output_path
        ]

        try:
            subprocess.run(cmd, check=True)
        except subprocess.CalledProcessError as e:
            print(f"❌ Failed: {v['name']}")

    print("\n✨ All HUD subtitles generated! Just open the video in VLC or PotPlayer.")

if __name__ == "__main__":
    main()
