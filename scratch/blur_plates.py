import os
import sys
import urllib.request
import argparse
import glob
import json
import cv2
from ultralytics import YOLO

def main():
    parser = argparse.ArgumentParser(description="YOLO License Plate Blur Tool")
    parser.add_argument("--video", type=str, default="temp_trim_test.mp4", help="Path to input video (supports wildcards)")
    parser.add_argument("--output", type=str, default="temp_trim_test_blurred.mp4", help="Path to output file")
    parser.add_argument("--start-time", type=float, default=0.0, help="Start time in seconds to seek to")
    parser.add_argument("--max-frames", type=int, default=0, help="Max frames to process (0 for all)")
    parser.add_argument("--conf", type=float, default=0.25, help="Confidence threshold for YOLO")
    parser.add_argument("--export-coords", action="store_true", help="Export coordinates to JSON file instead of writing video")
    args = parser.parse_args()

    # Resolve wildcard paths (useful for encoding bypass)
    resolved_inputs = glob.glob(args.video)
    if not resolved_inputs:
        print(f"Error: Input video '{args.video}' not found.")
        return
    input_video_path = resolved_inputs[0]
    print(f"Resolved input video path: {input_video_path}")

    # Pre-trained license plate YOLOv8 model on Hugging Face
    model_url = "https://huggingface.co/yasirfaizahmed/license-plate-object-detection/resolve/main/best.pt"
    model_path = os.path.join(os.path.dirname(__file__), "yolov8n_plate.pt")
    
    # Download model if not exists
    if not os.path.exists(model_path):
        print(f"Downloading pre-trained license plate model from {model_url}...")
        try:
            urllib.request.urlretrieve(model_url, model_path)
            print("Download complete.")
        except Exception as e:
            print(f"Error downloading model: {e}")
            return
    
    # Load model
    print("Loading YOLOv8 license plate model...")
    model = YOLO(model_path)
    
    cap = cv2.VideoCapture(input_video_path)
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    
    # Seek to start time if specified
    if args.start_time > 0.0:
        print(f"Seeking to start time: {args.start_time}s...")
        cap.set(cv2.CAP_PROP_POS_MSEC, args.start_time * 1000.0)
        start_frame_idx = int(cap.get(cv2.CAP_PROP_POS_FRAMES))
        print(f"Started at frame: {start_frame_idx}/{total_frames}")
    else:
        start_frame_idx = 0
    
    frames_to_process = total_frames - start_frame_idx
    if args.max_frames > 0:
        frames_to_process = min(frames_to_process, args.max_frames)
        
    print(f"Processing video: {width}x{height} @ {fps}fps, Frames to process: {frames_to_process}/{total_frames}")
    
    if args.export_coords:
        # Coordinate Export Mode
        records = []
        frame_idx = 0
        while cap.isOpened() and (args.max_frames == 0 or frame_idx < args.max_frames):
            ret, frame = cap.read()
            if not ret:
                break
                
            time_ms = int(cap.get(cv2.CAP_PROP_POS_MSEC))
            results = model.predict(frame, conf=args.conf, verbose=False)
            
            frame_boxes = []
            for result in results:
                boxes = result.boxes
                for box in boxes:
                    x1, y1, x2, y2 = map(int, box.xyxy[0])
                    frame_boxes.append({"x1": x1, "y1": y1, "x2": x2, "y2": y2})
                    
            if frame_boxes:
                records.append({
                    "timeMs": time_ms,
                    "boxes": frame_boxes
                })
                
            frame_idx += 1
            if frame_idx % 50 == 0 or frame_idx == frames_to_process:
                print(f"PROGRESS: {(frame_idx / frames_to_process) * 100:.1f}%")
                sys.stdout.flush()
                
        # Write JSON output
        data = {
            "videoPath": input_video_path,
            "records": records
        }
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"Done! Plate coordinates saved to {args.output}")
        sys.stdout.flush()
        
    else:
        # Video Blurring Mode
        fourcc = cv2.VideoWriter_fourcc(*'mp4v')
        out = cv2.VideoWriter(args.output, fourcc, fps, (width, height))
        
        frame_idx = 0
        while cap.isOpened() and (args.max_frames == 0 or frame_idx < args.max_frames):
            ret, frame = cap.read()
            if not ret:
                break
                
            # Run YOLO detection
            results = model.predict(frame, conf=args.conf, verbose=False)
            
            # Apply blur to each detected license plate
            for result in results:
                boxes = result.boxes
                for box in boxes:
                    x1, y1, x2, y2 = map(int, box.xyxy[0])
                    
                    x1 = max(0, x1)
                    y1 = max(0, y1)
                    x2 = min(width, x2)
                    y2 = min(height, y2)
                    
                    if x2 > x1 and y2 > y1:
                        roi = frame[y1:y2, x1:x2]
                        k_w = int((x2 - x1) * 0.5) | 1
                        k_h = int((y2 - y1) * 0.5) | 1
                        k_w = max(5, k_w)
                        k_h = max(5, k_h)
                        blurred_roi = cv2.GaussianBlur(roi, (k_w, k_h), 0)
                        frame[y1:y2, x1:x2] = blurred_roi
                        
            out.write(frame)
            frame_idx += 1
            if frame_idx % 50 == 0 or frame_idx == frames_to_process:
                print(f"PROGRESS: {(frame_idx / frames_to_process) * 100:.1f}%")
                sys.stdout.flush()
                
        out.release()
        print(f"Done! Blurred video saved to {args.output}")
        sys.stdout.flush()
        
    cap.release()

if __name__ == "__main__":
    main()
