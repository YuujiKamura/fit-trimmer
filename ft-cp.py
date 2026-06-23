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
        print("Usage: python ft-cp.py <get_state|fire|set_files|set_layout>")
        sys.exit(1)

    action = sys.argv[1]
    if action == "get_state":
        print(json.dumps(send_cmd({"type": "get_state"}), indent=2))
    elif action == "fire":
        print(send_cmd({"type": "fire"}))
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
