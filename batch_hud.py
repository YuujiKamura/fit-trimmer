import json
import subprocess
import os
import sys
import shutil
import time

def main():
    manifest_path = "H:/マイドライブ/20260621/sync_manifest.json"
    if not os.path.exists(manifest_path):
        print(f"Manifest not found: {manifest_path}")
        return

    with open(manifest_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    fit_file = data['fitFile']
    videos = data['videos']

    # Local temporary directory for MAX speed (IO & Processing)
    work_dir = os.path.join(os.getcwd(), "temp_work")
    os.makedirs(work_dir, exist_ok=True)

    processed_stems = set()
    to_process = []

    for v in sorted(videos, key=lambda x: x['name'].lower().endswith('.mp4'), reverse=True):
        stem = v['name'].split('.')[0].replace('LRV_', 'VID_')
        if stem not in processed_stems:
            to_process.append(v)
            processed_stems.add(stem)

    print(f"🚀 High-Speed Batch Start: {len(to_process)} videos.")

    for v in to_process:
        output_name = f"hud_{v['name'].replace('.lrv', '.mp4')}"
        final_dest = os.path.join(os.path.dirname(v['path']), output_name)

        if os.path.exists(final_dest):
            print(f"⏭️  Skipping {v['name']} (Already in Google Drive)")
            continue

        # Paths
        local_input = os.path.join(work_dir, v['name'])
        local_output = os.path.join(work_dir, output_name)

        try:
            # 1. Copy Input to Local SSD (Critical for overcoming 1.5x limit)
            print(f"📥 Copying to local SSD: {v['name']}...")
            shutil.copy2(v['path'], local_input)

            # 2. Run Kotlin CLI Encoder
            print(f"🎬 Processing (GPU Accelerated via Kotlin CLI): {v['name']}")
            start_t = time.time()
            gradle_cmd = "gradlew.bat" if os.name == "nt" else "./gradlew"
            args_str = f'--fit "{fit_file}" --video "{local_input}" --output "{local_output}" --video-start-utc "{v["startUtc"]}"'
            cmd = [
                gradle_cmd,
                ":composeApp:run",
                "--quiet",
                f"--args={args_str}"
            ]
            subprocess.run(cmd, check=True)
            elapsed = time.time() - start_t

            # 3. Move Result back to Google Drive
            print(f"📦 Moving back to Google Drive ({elapsed:.1f}s)...")
            shutil.move(local_output, final_dest)
            print(f"✅ Success: {output_name}")

        except Exception as e:
            print(f"❌ Failed {v['name']}: {e}")
        finally:
            # Cleanup local input to save space
            if os.path.exists(local_input):
                os.remove(local_input)

    # Cleanup
    if os.path.exists(work_dir) and not os.listdir(work_dir):
        os.rmdir(work_dir)

if __name__ == "__main__":
    main()
