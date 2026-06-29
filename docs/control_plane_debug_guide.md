# Control Plane Debugging & Verification Guide

This document records the protocol specifications, command structures, and troubleshooting steps for the HUD Encoder / Fit-Trimmer **Control Plane (CP)**. Use this as a reference to verify, debug, or automate HUD preview playback and seeking.

---

## 1. Overview
The Control Plane is a built-in TCP server inside the Compose Desktop App. It allows external test tools, scripts, or agents to remotely query state, seek playback, or trigger encoding without depending on fragile GUI automation (such as coordinates, mouse clicks, or UI focus).

---

## 2. Technical Specifications
* **Default Port**: `48099` (TCP)
* **Session Metadata File**: On startup, the app writes details to `~/.fittrimmer_cp.json` containing the bound port and process ID (PID):
  ```json
  {"port": 48099, "pid": 12345}
  ```
* **Protocol Frame**: Single-line JSON commands ended with a newline character `\n`.
* **Serialization Model**: Uses `kotlinx.serialization` with polymorphic type discrimination. Every command must include a `"type"` property containing the serialized command name.

---

## 3. Command Payload Reference
Commands defined in `shared-core/src/commonMain/kotlin/fit/CpProtocol.kt` are serialized into the following formats:

### Play
Starts or resumes video playback.
* **Payload**:
  ```json
  {"type": "play"}
  ```

### Pause
Pauses video playback.
* **Payload**:
  ```json
  {"type": "pause"}
  ```

### GetState
Queries current app status, active file paths, encoding progress, and telemetry info.
* **Payload**:
  ```json
  {"type": "get_state"}
  ```
* **Success Response Schema**:
  ```json
  {
    "settings": { ... },
    "fitPath": "F:\\Insta360\\Evening_Ride.fit",
    "videoPath": "F:\\Insta360\\VID_20260628_194327_001.mp4",
    "isEncoding": false,
    "progress": 0.0,
    "videoStartUtc": "2026-06-28T10:43:27Z",
    "isAligningTelemetry": false
  }
  ```

### Seek
Seeks the video player to a target playhead position.
* **Payload**:
  ```json
  {"type": "seek", "timeMs": 15000}
  ```

### Reload HUD
Triggers a live reload of the HUD canvas style settings.
* **Payload**:
  ```json
  {"type": "reload_hud"}
  ```

---

## 4. Verification Scripts

You can use the following helper Python script located at `scratch/send_cp_command.py` to send raw command packets:

```python
import socket
import json
import sys

def send_command(cmd_dict, port=48099):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect(('127.0.0.1', port))
        
        # Send serialized command ended with a newline
        cmd_str = json.dumps(cmd_dict) + "\n"
        s.sendall(cmd_str.encode('utf-8'))
        
        # Read response
        response = s.recv(4096).decode('utf-8')
        s.close()
        print(f"Sent: {cmd_str.strip()}")
        print(f"Response: {response.strip()}")
        return response
    except Exception as e:
        print(f"Error: {e}")
        return None

if __name__ == '__main__':
    # Default type is "play"
    cmd_type = sys.argv[1] if len(sys.argv) > 1 else "play"
    cmd = {"type": cmd_type}
    send_command(cmd)
```

**Execution example**:
```bash
# Trigger Play
python scratch/send_cp_command.py play

# Trigger Pause
python scratch/send_cp_command.py pause
```

---

## 5. Troubleshooting & Critical Pitfalls

### 1. `Address already in use: bind` Error
* **Symptom**: The Gradle run log outputs `Control Plane Error: Address already in use: bind` and commands return a socket connection failure or error responses.
* **Cause**: A previously launched Kotlin/Java app instance is still running as a background zombie process, holding port `48099` active.
* **Fix**: Force kill all running JVM processes to free the socket port:
  ```powershell
  taskkill /F /IM java.exe
  ```
  Then relaunch the application (`.\run_desktop.bat`).

### 2. HEVC/H.265 Decoding Failures on Video Preview
* **Symptom**: Playback is triggered (`isPlaying=true` logs are printed) but the UI preview stays frozen on the first frame and timeline progress remains at `00:00`.
* **Cause**: Insta360 LRV proxy files use HEVC (H.265) compression. If the host OS lacks an HEVC hardware extension, the Windows Media Foundation (WMF) backend will fail to decode frames and freeze.
* **Fix**: The proxy extraction pipeline converts the LRV file into a standardized H.264 MP4 file using the bundled FFmpeg binary's `libopenh264` encoder with a duration limit of 30 seconds (`-t 30`). This guarantees OS-native compatibility and lightweight seek times.
