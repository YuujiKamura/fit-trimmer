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

    # Filter for MP4s (higher quality) and skip LRVs if MP4 exists
    mp4_videos = [v for v in videos if v['name'].lower().endswith('.mp4')]

    print(f"🚀 Batch Processing Start: {len(mp4_videos)} videos found.")

    for v in mp4_videos:
        output_name = f"hud_{v['name']}"
        output_path = os.path.join(os.path.dirname(v['path']), output_name)

        if os.path.exists(output_path):
            print(f"⏭️  Skipping {v['name']} (Output already exists)")
            continue

        print(f"🎬 Processing: {v['name']}")
        cmd = [
            sys.executable, "hud_encode.py",
            "--fit", fit_file,
            "--video", v['path'],
            "--output", output_path,
            "--video-start-utc", v['startUtc'],
            "--quality", "23"
        ]

        try:
            subprocess.run(cmd, check=True)
            print(f"✅ Finished: {output_name}")
        except subprocess.CalledProcessError as e:
            print(f"❌ Failed: {v['name']} with error {e}")

if __name__ == "__main__":
    main()
