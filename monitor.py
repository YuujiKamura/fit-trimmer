# -*- coding: utf-8 -*-
import glob, json, time, os

def draw_progress():
    while True:
        os.system('cls' if os.name == 'nt' else 'clear')
        print("🚴 FIT TRIMMER - ACTIVE JOBS MONITOR")
        print("=" * 40)

        status_files = glob.glob("H:/マイドライブ/20260621/*.status.json")
        if not status_files:
            print("No active encoding jobs found.")
        else:
            for sf in status_files:
                try:
                    with open(sf, 'r') as f:
                        data = json.load(f)
                    name = os.path.basename(sf).replace(".status.json", "")
                    prog = data['progress']
                    bar = "█" * int(prog / 5) + "░" * (20 - int(prog / 5))
                    print(f"🎬 {name[:30]}...")
                    print(f"   [{bar}] {prog}% ({data['sec']}/{data['total']}s)")
                    print("-" * 40)
                except:
                    pass

        time.sleep(2)

if __name__ == "__main__":
    draw_progress()
