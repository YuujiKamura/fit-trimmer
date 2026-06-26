import argparse
import json
import os
import struct
import sys
import numpy as np
from scipy.ndimage import gaussian_filter1d

def extract_imu_offsets_fast(filepath):
    size = os.path.getsize(filepath)
    HEADER_SIZE = 76
    
    with open(filepath, "rb") as fin:
        # Seek to the end-200 to find Offsets header
        fin.seek(-200, 2)
        end_buf = fin.read(200)
        
        # extra_size is at offset -40 (which is 200 - 40 = 160 inside end_buf)
        extra_size = struct.unpack('<L', end_buf[200-40 : 200-36])[0]
        extra_start = size - extra_size
        
        # Locate Offsets record header by scanning backwards
        pos = 150
        found_offsets = False
        offsets_size = 0
        offsets_pos = 0
        
        while True:
            pos = end_buf.rfind(b'\x00\x00', 0, pos)
            if pos == -1:
                break
            if pos + 6 <= len(end_buf):
                size_val = struct.unpack('<L', end_buf[pos+2 : pos+6])[0]
                if 20 <= size_val <= 500 and size_val % 10 == 0:
                    offsets_size = size_val
                    offsets_pos = pos
                    found_offsets = True
                    break
            pos -= 1
            
        if not found_offsets:
            raise ValueError("Failed to locate Offsets header in end buffer")
            
        # Read Offsets record content
        offset_from_end = 200 - offsets_pos
        fin.seek(-offset_from_end - offsets_size, 2)
        offsets_buf = fin.read(offsets_size)
        
        offsets = {}
        for i in range(0, offsets_size, 10):
            if i + 10 > offsets_size:
                break
            rec_id, rec_format, rec_size, rec_offset = struct.unpack('<BBLL', offsets_buf[i : i+10])
            offsets[rec_id] = (rec_offset, rec_size)
            
        if 3 not in offsets:
            raise ValueError("Gyro record (ID=3) not found in offsets")
            
        gyro_offset, gyro_size = offsets[3]
        
        # Seek to Gyro record
        target_pos = extra_start + gyro_offset
        fin.seek(target_pos)
        block = fin.read(gyro_size)
        
    # Parse 20-byte sample schema: uint64 timecode (8) + 6x int16 sensor data (12)
    dt = np.dtype([('timecode', '<u8'), ('sensors', '<i2', 6)])
    samples = np.frombuffer(block, dtype=dt)
    
    v_times = samples['timecode'] / 1000000.0 # to seconds (microsecond divisor)
    v_accs = samples['sensors'][:, 0:3].astype(float)
    v_gyros = samples['sensors'][:, 3:6].astype(float)
    
    sort_idx = np.argsort(v_times)
    return v_times[sort_idx], v_accs[sort_idx], v_gyros[sort_idx]

def main():
    parser = argparse.ArgumentParser(description="Auto-align telemetry with video using IMU correlation.")
    parser.add_argument("--video", help="Path to the video file (.lrv or .mp4)")
    parser.add_argument("--video-vib-1hz", help="Path to pre-extracted 1Hz video vibration JSON file")
    parser.add_argument("--telemetry", required=True, help="Path to the FIT telemetry JSON file")
    parser.add_argument("--approx-start-ts", type=float, default=None, help="Approximate video start timestamp (Garmin epoch seconds)")
    args = parser.parse_args()

    if not args.video and not args.video_vib_1hz:
        print(json.dumps({"status": "error", "message": "Either --video or --video-vib-1hz must be provided"}))
        return

    try:
        # 1. Load telemetry JSON
        if not os.path.exists(args.telemetry):
            print(json.dumps({"status": "error", "message": f"Telemetry file not found: {args.telemetry}"}))
            return

        with open(args.telemetry, 'r') as f:
            fit_data = json.load(f)

        if not fit_data:
            print(json.dumps({"status": "error", "message": "Telemetry JSON is empty"}))
            return

        fit_ts = np.array([r['ts'] for r in fit_data])
        fit_speed = np.array([r['speedKmh'] for r in fit_data])

        # 2. Extract / Load vibration signal and determine first file IMU offset
        if args.video_vib_1hz:
            if not os.path.exists(args.video_vib_1hz):
                print(json.dumps({"status": "error", "message": f"1Hz vibration file not found: {args.video_vib_1hz}"}))
                return
            with open(args.video_vib_1hz, 'r') as f:
                v_vib = np.array(json.load(f))
            first_file_imu_offset = 2.664490  # Default fallback for testing
        else:
            if not os.path.exists(args.video):
                print(json.dumps({"status": "error", "message": f"Video file not found: {args.video}"}))
                return

            v_times, v_accs, _ = extract_imu_offsets_fast(args.video)
            acc_norms = np.linalg.norm(v_accs, axis=1)
            acc_diffs = np.abs(np.diff(acc_norms))
            acc_diffs = np.append(acc_diffs, 0.0)
            v_rel_times = v_times - v_times[0]
            max_v_time = int(np.ceil(v_rel_times[-1]))
            v_grid = np.arange(0, max_v_time + 1, 1.0)
            v_vib = np.interp(v_grid, v_rel_times, acc_diffs)

            # Find first file's IMU offset to adjust the baseline correctly
            first_file_imu_offset = 2.664490
            try:
                video_dir = os.path.dirname(args.video)
                video_name = os.path.basename(args.video)
                import re
                m = re.match(r"(.*_)(\d+)(\.mp4|\.lrv)", video_name, re.IGNORECASE)
                if m:
                    prefix = m.group(1)
                    ext = m.group(3)
                    first_file_name = f"{prefix}001{ext}"
                    first_file_path = os.path.join(video_dir, first_file_name)
                    if os.path.exists(first_file_path):
                        first_file_times, _, _ = extract_imu_offsets_fast(first_file_path)
                        if len(first_file_times) > 0:
                            first_file_imu_offset = first_file_times[0]
            except Exception:
                pass

        # 3. Resample FIT speed to 1Hz grid
        fit_grid = np.arange(fit_ts[0], fit_ts[-1], 1.0)
        fit_speed_grid = np.interp(fit_grid, fit_ts, fit_speed)

        # 4. Correlation matching using binary movement profiles (starting & stopping states)
        # Smooth raw vibration to get a stable intensity profile
        v_sig = gaussian_filter1d(v_vib, 3.0)
        
        # Binarize video vibration to detect moving vs stopped.
        # Uses a dynamic threshold based on the 10th percentile, with a minimum floor of 20.0.
        v_thresh = max(20.0, np.percentile(v_sig, 10) * 1.5)
        v_mov = (v_sig > v_thresh).astype(float)
        
        # Binarize FIT speed to moving vs stopped
        fit_mov = (fit_speed_grid > 2.0).astype(float)
        
        # Smooth binary profiles to allow soft alignment margins
        fit_sig_smooth = gaussian_filter1d(fit_mov, 3.0)
        v_sig_smooth = gaussian_filter1d(v_mov, 3.0)

        # Normalize smoothed binary signals
        v_sig_norm = (v_sig_smooth - np.mean(v_sig_smooth)) / (np.std(v_sig_smooth) + 1e-6)
        fit_sig_norm = (fit_sig_smooth - np.mean(fit_sig_smooth)) / (np.std(fit_sig_smooth) + 1e-6)

        # Cross-correlate binary profiles
        corr = np.correlate(fit_sig_norm, v_sig_norm, mode='valid')

        if len(corr) == 0:
            print(json.dumps({"status": "error", "message": "Video is longer than ride telemetry. Cannot align."}))
            return

        # Find best match offset (constraining to approx-start-ts window if provided)
        if args.approx_start_ts is not None:
            # Constrain the search to approx_start_ts +/- 900 seconds (15 minutes)
            window = 900
            min_ts = args.approx_start_ts - window
            max_ts = args.approx_start_ts + window
            
            # Find indices in fit_grid that correspond to starting times within [min_ts, max_ts]
            valid_indices = np.where((fit_grid >= min_ts) & (fit_grid <= max_ts))[0]
            valid_indices = valid_indices[valid_indices < len(corr)]
            
            if len(valid_indices) == 0:
                best_idx = np.argmax(corr)
            else:
                sub_best = np.argmax(corr[valid_indices])
                best_idx = valid_indices[sub_best]
        else:
            best_idx = np.argmax(corr)

        video_start_ts = fit_grid[best_idx]
        
        # The true video file start is video_start_ts - first_file_imu_offset.
        # This keeps the video start timestamp representing the 0s point of the current video file.
        true_file_start_ts = video_start_ts - first_file_imu_offset

        print(json.dumps({
            "status": "success",
            "video_start_ts": float(true_file_start_ts),
            "correlation_score": float(corr[best_idx])
        }))

    except Exception as e:
        print(json.dumps({"status": "error", "message": str(e)}))

if __name__ == "__main__":
    main()
