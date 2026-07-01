# -*- coding: utf-8 -*-
import socket, json, sys, os

def send_cmd(cmd):
    # セッションファイルからポートを取得
    session_path = os.path.expanduser("~/.fittrimmer_cp.json")
    if not os.path.exists(session_path):
        print("❌ Session not found. App must be running.")
        return

    with open(session_path, 'r') as f:
        port = json.load(f)['port']

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect(('127.0.0.1', port))
        s.sendall((json.dumps(cmd) + "\n").encode('utf-8'))
        response = s.recv(4096).decode('utf-8')
        return json.loads(response)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python ft-cp.py <get_state|fire|run_plate_detection|stop_plate_detection|reset_plate_detection|seek_plate_detection|set_plate_cache_enabled|restore_plate_cache|discard_plate_cache|set_files|set_layout>")
        sys.exit(1)

    action = sys.argv[1]
    if action == "get_state":
        print(json.dumps(send_cmd({"type": "get_state"}), indent=2))
    elif action == "reset_plate_detection":
        print(send_cmd({"type": "reset_plate_detection"}))
    elif action == "run_plate_detection":
        cmd = {"type": "run_plate_detection"}
        if len(sys.argv) >= 3:
            cmd["maxRecords"] = int(sys.argv[2])
        if len(sys.argv) >= 4:
            cmd["maxSpeedKmh"] = float(sys.argv[3])
        if len(sys.argv) >= 5:
            cmd["detectionFps"] = float(sys.argv[4])
        if len(sys.argv) >= 6:
            cmd["paddingSeconds"] = float(sys.argv[5])
        if len(sys.argv) >= 7:
            cmd["mergeGapSeconds"] = float(sys.argv[6])
        print(send_cmd(cmd))
    elif action == "stop_plate_detection":
        print(send_cmd({"type": "stop_plate_detection"}))
    elif action == "set_plate_cache_enabled":
        if len(sys.argv) < 3:
            print("Usage: python ft-cp.py set_plate_cache_enabled <true|false>")
            sys.exit(1)
        enabled = sys.argv[2].lower() in ("1", "true", "yes", "on")
        print(send_cmd({"type": "set_plate_cache_enabled", "enabled": enabled}))
    elif action == "restore_plate_cache":
        print(send_cmd({"type": "restore_plate_cache"}))
    elif action == "discard_plate_cache":
        print(send_cmd({"type": "discard_plate_cache"}))
    elif action == "seek_plate_detection":
        index = int(sys.argv[2]) if len(sys.argv) >= 3 else 0
        print(send_cmd({"type": "seek_plate_detection", "index": index}))
    elif action == "fire":
        print(send_cmd({"type": "fire"}))
    elif action == "set_files":
        if len(sys.argv) < 4:
            print("Usage: python ft-cp.py set_files <fit_path> <video_path> [startUtc]")
            sys.exit(1)
        cmd = {"type": "set_files", "fit": sys.argv[2], "video": sys.argv[3]}
        if len(sys.argv) >= 5:
            cmd["startUtc"] = sys.argv[4]
        print(send_cmd(cmd))
    elif action == "set_layout":
        if len(sys.argv) < 9:
            print("Usage: python ft-cp.py set_layout <valSize> <tightness> <spacing> <xOffset> <yOffset> <graphH> <graphW>")
            sys.exit(1)
        settings = {
            "valSize": float(sys.argv[2]),
            "tightness": float(sys.argv[3]),
            "spacing": float(sys.argv[4]),
            "xOffset": float(sys.argv[5]),
            "yOffset": float(sys.argv[6]),
            "graphH": float(sys.argv[7]),
            "graphW": float(sys.argv[8])
        }
        print(send_cmd({"type": "set_layout", "settings": settings}))
