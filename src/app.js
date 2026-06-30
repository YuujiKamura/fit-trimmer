/**
 * FIT TRIMMER - Application Core Logic
 */

// Global State
let wasmModule = null;
let wasmInitPromise = null;
let videoFilesList = [];
let fitFileBuffer = null;
let fitFilename = "";
let fitTimestamps = [];
let fitTelemetryData = null;
let activeVideo = null;
let isWasmLoaded = false;
let debugWs = null;
let syncOffset = 0.0;

// HUD Configuration
const hudConfig = {
    speed: { visible: true, left: 10, top: 10, color: "#3b82f6" },
    power: { visible: true, left: 35, top: 10, color: "#10b981" },
    cadence: { visible: true, left: 60, top: 10, color: "#8b5cf6" },
    heartrate: { visible: true, left: 80, top: 10, color: "#ef4444" },
    elevation: { visible: true, left: 10, top: 60, color: "#f59e0b" },
    powerchart: { visible: true, left: 60, top: 60, color: "#ec4899" }
};

// UI Bindings
const videoDrop = document.getElementById('video-dropzone');
const videoInput = document.getElementById('video-input');
const videoName = document.getElementById('video-name');
const fitDrop = document.getElementById('fit-dropzone');
const fitInput = document.getElementById('fit-input');
const fitName = document.getElementById('fit-name');
const trimBtn = document.getElementById('trim-btn');
const consoleEl = document.getElementById('console');
const statusBanner = document.getElementById('status-banner');
const videoListCard = document.getElementById('video-list-card');
const videoListContainer = document.getElementById('video-list');
const hudEditorCard = document.getElementById('hud-editor-card');
const previewVideo = document.getElementById('preview-video');
const previewDummyBg = document.getElementById('preview-dummy-bg');
const hudOverlay = document.getElementById('hud-overlay');
const offsetSlider = document.getElementById('offset-slider');
const offsetValBadge = document.getElementById('offset-val-badge');
const exportHudBtn = document.getElementById('export-hud-btn');

// --- Initialization ---

async function fetchBuildInfo() {
    try {
        const response = await fetch('https://api.github.com/repos/YuujiKamura/fit-trimmer/commits/main');
        if (!response.ok) return;
        const data = await response.json();
        const sha = data.sha.substring(0, 7);
        const date = new Date(data.commit.committer.date);
        const jst = new Date(date.getTime() + (9 * 60 * 60 * 1000));
        const formatted = jst.toISOString().replace('T', ' ').substr(0, 19) + " JST";
        document.querySelector('.commit-hash').textContent = sha;
        document.querySelector('.update-time').textContent = formatted;
    } catch (e) { console.warn('Build info fetch failed', e); }
}

if (window["shared-core"]) {
    wasmInitPromise = window["shared-core"].then(module => {
        wasmModule = module;
        isWasmLoaded = true;
        log("Kotlin Wasm module loaded.", "success");
        checkSyncCapability();
        return module;
    }).catch(err => {
        log("Wasm Load Failed: " + err.message, "error");
        console.error("Wasm init error detail:", err);
        wasmInitPromise = null;
        throw err;
    });
} else {
    log("Warning: shared-core.js not detected. Wasm features unavailable.", "error");
}

// --- Logging & Errors ---

function log(message, type = 'info') {
    const time = new Date().toTimeString().split(' ')[0];
    const div = document.createElement('div');
    div.className = 'log-entry';
    let style = type === 'error' ? 'log-error' : (type === 'success' ? 'log-success' : '');
    div.innerHTML = `<span class="log-time">[${time}]</span><span class="${style}">${message}</span>`;
    if (consoleEl) { consoleEl.appendChild(div); consoleEl.scrollTop = consoleEl.scrollHeight; }
}

function showVisualError(msg) {
    const overlay = document.getElementById('debug-error-overlay');
    const list = document.getElementById('debug-error-list');
    if (overlay) overlay.style.display = 'block';
    if (list) {
        const div = document.createElement('div');
        div.style.cssText = 'margin-bottom:4px; border-bottom:1px solid rgba(255,255,255,0.2); padding-bottom:4px;';
        div.textContent = msg;
        list.appendChild(div);
    }
}

// --- File Drop Handling ---

function setupDropzone(zone, input, onFilesSelected, isMultiple = false) {
    input.addEventListener('click', (e) => { input.value = ""; e.stopPropagation(); });
    zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('active'); });
    zone.addEventListener('dragleave', () => zone.classList.remove('active'));
    zone.addEventListener('drop', (e) => {
        e.preventDefault();
        zone.classList.remove('active');
        if (e.dataTransfer.files.length > 0) onFilesSelected(isMultiple ? e.dataTransfer.files : e.dataTransfer.files[0]);
    });
    input.addEventListener('change', () => {
        if (input.files.length > 0) onFilesSelected(isMultiple ? input.files : input.files[0]);
    });
}

setupDropzone(videoDrop, videoInput, handleVideoFiles, true);
setupDropzone(fitDrop, fitInput, handleFitFile, false);

async function handleVideoFiles(files) {
    videoDrop.classList.add('file-selected', 'video', 'loading');
    const subText = document.getElementById('video-sub');
    try {
        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            if (videoFilesList.some(v => v.name === file.name && v.file.size === file.size)) continue;
            log(`Processing video: ${file.name}...`);
            videoName.textContent = "Processing " + file.name + "...";
            if (subText) subText.textContent = `Extracting mvhd atom metadata (${i+1}/${files.length})...`;
            await parseVideoMetadata(file);
        }
    } finally {
        videoDrop.classList.remove('loading');
        videoName.textContent = videoFilesList.length > 0 ? `${videoFilesList.length} video(s) selected` : "Select Video File(s)";
        if (subText) subText.textContent = "Drag & drop MP4 / LRV / MOV (Multiple OK)";
        renderVideoList();
        checkSyncCapability();
    }
}

async function parseVideoMetadata(file) {
    try {
        const scanSize = Math.min(file.size, 16 * 1024 * 1024); // 16MB
        let metadata = null;

        const findMvhdDirectly = (bytes) => {
            const view = new DataView(bytes.buffer);
            for (let i = 0; i < bytes.length - 32; i++) {
                if (bytes[i] === 109 && bytes[i+1] === 118 && bytes[i+2] === 104 && bytes[i+3] === 100) {
                    const version = bytes[i + 4];
                    let creationTime, timescale, duration;
                    let offset = i + 8;
                    if (version === 1) {
                        offset += 8;
                        creationTime = view.getUint32(offset, false); offset += 4;
                        offset += 8;
                        timescale = view.getUint32(offset, false); offset += 4;
                        offset += 4;
                        duration = view.getUint32(offset, false);
                    } else {
                        creationTime = view.getUint32(offset, false); offset += 4;
                        offset += 4;
                        timescale = view.getUint32(offset, false); offset += 4;
                        duration = view.getUint32(offset, false);
                    }
                    if (timescale > 0 && duration > 0 && creationTime > 1000000) {
                        return { creationTime, timescale, duration };
                    }
                }
            }
            return null;
        };

        const processBuffer = async (offset) => {
            const blob = file.slice(offset, offset + scanSize);
            const buffer = await blob.arrayBuffer();
            const bytes = new Uint8Array(buffer);
            scanMp4Atoms(bytes, new DataView(buffer), 0, bytes.length, (meta) => { metadata = meta; });
            if (metadata) return true;
            metadata = findMvhdDirectly(bytes);
            return metadata !== null;
        };

        if (!await processBuffer(0)) {
            log(`Metadata not found at front. Searching trailer for ${file.name}...`);
            await processBuffer(Math.max(0, file.size - scanSize));
        }

        if (!metadata) throw new Error("MVHD metadata not found. Try a different video file.");

        const unixTimestamp = metadata.creationTime - 2082844800;
        const durationSeconds = metadata.duration / metadata.timescale;
        videoFilesList.push({
            id: Math.random().toString(36).substr(2, 9),
            file, name: file.name, startDt: new Date(unixTimestamp * 1000),
            endDt: new Date((unixTimestamp + durationSeconds) * 1000),
            durationSeconds, unixTimestamp, isSyncable: false
        });
        log(`Loaded ${file.name}`, 'success');
    } catch (err) { log(`Failed ${file.name}: ${err.message}`, 'error'); }
}

async function handleFitFile(file) {
    fitDrop.classList.add('file-selected', 'fit', 'loading');
    fitName.textContent = "Processing " + file.name + "...";
    const subText = document.getElementById('fit-sub');
    if (subText) subText.textContent = "Loading file buffer & calling Wasm...";
    fitFilename = file.name;
    log(`Loading FIT: ${file.name} (${file.size} bytes)...`);
    try {
        const arrayBuffer = await file.arrayBuffer();
        if (!arrayBuffer || arrayBuffer.byteLength === 0) throw new Error("File is empty or could not be read.");

        fitFileBuffer = arrayBuffer;
        fitTimestamps = [];

        // Wait for wasm to finish initializing if it hasn't yet
        if (!wasmModule && wasmInitPromise) {
            log("Waiting for Wasm module to initialize...");
            if (subText) subText.textContent = "Waiting for Wasm module to initialize...";
            try {
                await Promise.race([
                    wasmInitPromise,
                    new Promise((_, reject) => setTimeout(() => reject(new Error(
                        "Wasm initialization timed out (15s). Your browser may not support Wasm GC. Try Chrome 119+ or Firefox 120+."
                    )), 15000))
                ]);
            } catch (waitErr) {
                throw new Error("Wasm module failed to load: " + waitErr.message);
            }
        }

        if (wasmModule?.getFitTelemetry) {
            const bytes = new Int8Array(arrayBuffer);
            log(`Parsing FIT in Wasm...`);
            if (subText) subText.textContent = "Parsing record messages in WebAssembly...";
            fitTelemetryData = wasmModule.getFitTelemetry(bytes);

            if (fitTelemetryData) {
                const count = fitTelemetryData.length / 7;
                for (let k = 0; k < fitTelemetryData.length; k += 7) {
                    fitTimestamps.push(fitTelemetryData[k]);
                }
                log(`Successfully extracted ${count} track points.`, 'success');
            } else {
                throw new Error("Wasm returned null telemetry. Check console for 'FIT Error'.");
            }
        } else {
            const detail = !wasmModule ? "Module is null (init failed or not loaded)" 
                : !wasmModule.getFitTelemetry ? "Module loaded but getFitTelemetry not found (keys: " + Object.keys(wasmModule).join(", ") + ")" 
                : "Unknown";
            throw new Error("Wasm module not available. " + detail);
        }

        if (fitTimestamps.length === 0) throw new Error("No track data found in FIT file.");

        const fitEpochSec = 631065600;
        const minTs = Math.min(...fitTimestamps);
        const maxTs = Math.max(...fitTimestamps);

        document.getElementById('f-start').textContent = formatJST(new Date((minTs + fitEpochSec) * 1000));
        document.getElementById('f-end').textContent = formatJST(new Date((maxTs + fitEpochSec) * 1000));
        document.getElementById('f-count').textContent = `${fitTimestamps.length} pts`;
        checkSyncCapability();
    } catch (err) {
        log(`FIT Error: ${err.message}`, 'error');
        console.error("FIT Detail:", err);
    } finally {
        fitDrop.classList.remove('loading');
        fitName.textContent = fitFilename || "Select FIT File";
        if (subText) subText.textContent = "Drag & drop Garmin / Strava FIT";
    }
}

// --- UI Rendering ---

function renderVideoList() {
    videoListContainer.innerHTML = "";
    videoListCard.style.display = videoFilesList.length > 0 ? "block" : "none";
    videoFilesList.forEach(video => {
        const item = document.createElement('div');
        item.className = 'video-item';
        if (activeVideo?.id === video.id) item.style.cssText = 'border-color: var(--primary); background: rgba(59, 130, 246, 0.04);';
        item.innerHTML = `
            <div class="video-info" style="cursor:pointer">
                <div class="video-title">${video.name}</div>
                <div class="video-meta-detail">Start: ${formatJST(video.startDt)} | Dur: ${Math.floor(video.durationSeconds/60)}m ${Math.floor(video.durationSeconds%60)}s</div>
            </div>
            <div class="video-status"><span class="status-tag ${video.isSyncable?'sync':'no-sync'}">${video.isSyncable?'Syncable':'No Overlap'}</span></div>
            <div class="video-actions">
                <button class="btn-mini" ${video.isSyncable?'':'disabled'}>DL</button>
                <button class="btn-remove">✕</button>
            </div>
        `;
        item.querySelector('.video-info').onclick = () => selectActiveVideo(video);
        item.querySelector('.btn-mini').onclick = (e) => { e.stopPropagation(); trimAndDownloadSingle(video); };
        item.querySelector('.btn-remove').onclick = (e) => {
            e.stopPropagation();
            videoFilesList = videoFilesList.filter(v => v.id !== video.id);
            if (activeVideo?.id === video.id) hidePreview();
            renderVideoList();
            checkSyncCapability();
        };
        videoListContainer.appendChild(item);
    });
}

function selectActiveVideo(video) {
    activeVideo = video;
    renderVideoList();
    if (previewVideo.src) URL.revokeObjectURL(previewVideo.src);
    previewVideo.src = URL.createObjectURL(video.file);
    previewVideo.style.display = "block";
    previewDummyBg.style.display = "none";
    hudEditorCard.style.display = "block";
    previewVideo.load();
}

function hidePreview() {
    activeVideo = null;
    previewVideo.style.display = "none";
    previewDummyBg.style.display = "flex";
    hudEditorCard.style.display = "none";
    if (previewVideo.src) { URL.revokeObjectURL(previewVideo.src); previewVideo.removeAttribute('src'); }
}

function checkSyncCapability() {
    if (!wasmModule || videoFilesList.length === 0 || fitTimestamps.length === 0) {
        statusBanner.className = "status-banner status-idle";
        statusBanner.innerHTML = "<span>Waiting for files...</span>";
        trimBtn.disabled = true;
        return;
    }
    const fitEpochSec = 631065600;
    const fStart = Math.min(...fitTimestamps) + fitEpochSec;
    const fEnd = Math.max(...fitTimestamps) + fitEpochSec;
    let syncableCount = 0;
    videoFilesList.forEach(v => {
        const vStart = v.startDt.getTime() / 1000;
        const vEnd = v.endDt.getTime() / 1000;
        v.isSyncable = Math.max(vStart, fStart) < Math.min(vEnd, fEnd);
        if (v.isSyncable) syncableCount++;
    });
    renderVideoList();
    if (syncableCount > 0) {
        statusBanner.className = "status-banner status-sync";
        statusBanner.innerHTML = `<span>✓ ${syncableCount} video(s) synchronized!</span>`;
        trimBtn.disabled = false;
        trimBtn.textContent = `⚡ Download All Trimmed FITs (${syncableCount})`;
    } else {
        statusBanner.className = "status-banner status-error";
        statusBanner.innerHTML = "<span>✗ No time overlap found.</span>";
        trimBtn.disabled = true;
    }
}

// --- Trimming & Export ---

function trimAndDownloadSingle(video) {
    if (!wasmModule || !fitFileBuffer || !video.isSyncable) return;
    try {
        log(`Trimming ${video.name}...`);
        const trimmed = wasmModule.trimFit(new Int8Array(fitFileBuffer), video.startDt.getTime()/1000, video.endDt.getTime()/1000);
        const blob = new Blob([trimmed.buffer], {type: 'application/octet-stream'});
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        const outName = `${fitFilename.replace(".fit","")}_${video.name.split('.')[0]}.fit`;
        a.href = url; a.download = outName; a.click();
        URL.revokeObjectURL(url);
        log(`Success: ${outName}`, 'success');
    } catch (e) { log(`Fail: ${e.message}`, 'error'); }
}

trimBtn.onclick = async () => {
    const syncable = videoFilesList.filter(v => v.isSyncable);
    for (const v of syncable) {
        trimAndDownloadSingle(v);
        await new Promise(r => setTimeout(r, 300));
    }
};

// --- HUD Preview & Editor ---

/**
 * 前後2点を線形補間してテレメトリ値を返す（滑らかプレビュー用）
 * @param {number} target - FITエポック秒
 * @returns {{speed, power, cadence, heartRate, elevation, grade}|null}
 */
function findTelemetryLerp(target) {
    if (!fitTelemetryData) return null;
    const size = 7;
    const count = fitTelemetryData.length / size;
    if (count === 0) return null;

    // バイナリサーチで target の直前インデックスを探す
    let low = 0, high = count - 1;
    while (low < high) {
        const mid = (low + high + 1) >> 1;
        if (fitTelemetryData[mid * size] <= target) low = mid;
        else high = mid - 1;
    }

    const i0 = low;
    const t0 = fitTelemetryData[i0 * size];

    // target が範囲外（先頭より前 or 末尾より後）
    if (target <= t0 || i0 >= count - 1) return getTelObj(i0);

    const i1 = i0 + 1;
    const t1 = fitTelemetryData[i1 * size];
    const alpha = (t1 - t0) > 0 ? (target - t0) / (t1 - t0) : 0;

    // 各フィールドを lerp
    const lerp = (a, b) => a + (b - a) * alpha;
    const o0 = i0 * size, o1 = i1 * size;
    return {
        speed:     lerp(fitTelemetryData[o0+1], fitTelemetryData[o1+1]),
        power:     lerp(fitTelemetryData[o0+2], fitTelemetryData[o1+2]),
        cadence:   lerp(fitTelemetryData[o0+3], fitTelemetryData[o1+3]),
        heartRate: lerp(fitTelemetryData[o0+4], fitTelemetryData[o1+4]),
        elevation: lerp(fitTelemetryData[o0+5], fitTelemetryData[o1+5]),
        grade:     lerp(fitTelemetryData[o0+6], fitTelemetryData[o1+6]),
    };
}

function getTelObj(idx) {
    const o = idx * 7;
    return {
        speed: fitTelemetryData[o+1], power: fitTelemetryData[o+2],
        cadence: fitTelemetryData[o+3], heartRate: fitTelemetryData[o+4],
        elevation: fitTelemetryData[o+5], grade: fitTelemetryData[o+6]
    };
}

function updateHudPreview() {
    if (!activeVideo || !fitTelemetryData) return;
    const target = (activeVideo.unixTimestamp - 631065600) + previewVideo.currentTime + syncOffset;
    const tel = findTelemetryLerp(target);  // lerp版に変更
    if (!tel) return;
    document.getElementById('hud-val-speed').textContent     = tel.speed.toFixed(1) + " km/h";
    document.getElementById('hud-val-power').textContent     = Math.round(tel.power) + " W";
    document.getElementById('hud-val-cadence').textContent   = Math.round(tel.cadence) + " rpm";
    document.getElementById('hud-val-heartrate').textContent = Math.round(tel.heartRate) + " bpm";
    document.getElementById('hud-val-elevation').textContent = tel.elevation.toFixed(1) + "m (" + (tel.grade>=0?"+":"") + tel.grade.toFixed(1) + "%)";
}

// rAFループ: ontimeupdateより高精度（~16ms更新）
let _hudRafId = null;
function _hudRafLoop() {
    if (!previewVideo.paused && !previewVideo.ended) {
        updateHudPreview();
    }
    _hudRafId = requestAnimationFrame(_hudRafLoop);
}

previewVideo.onplay  = () => { if (!_hudRafId) _hudRafId = requestAnimationFrame(_hudRafLoop); };
previewVideo.onpause = () => { cancelAnimationFrame(_hudRafId); _hudRafId = null; updateHudPreview(); };
previewVideo.onended = () => { cancelAnimationFrame(_hudRafId); _hudRafId = null; };
previewVideo.onseeked = () => updateHudPreview(); // シーク後は即更新

// offset スライダー変更時も即反映
offsetSlider.oninput = () => {
    syncOffset = parseFloat(offsetSlider.value);
    offsetValBadge.textContent = (syncOffset>=0?"+":"")+syncOffset.toFixed(1)+"s";
    updateHudPreview();
};

// Draggable Logic
function makeDraggable(id, key) {
    const el = document.getElementById(id);
    let dragging = false, sx, sy, oL, oT;
    el.onmousedown = (e) => {
        dragging = true; el.classList.add('dragging');
        sx = e.clientX; sy = e.clientY; oL = el.offsetLeft; oT = el.offsetTop;
        e.preventDefault();
    };
    document.onmousemove = (e) => {
        if (!dragging) return;
        const parent = el.parentElement;
        let nL = Math.max(0, Math.min(oL + e.clientX - sx, parent.clientWidth - el.clientWidth));
        let nT = Math.max(0, Math.min(oT + e.clientY - sy, parent.clientHeight - el.clientHeight));
        el.style.left = nL + 'px'; el.style.top = nT + 'px';
        hudConfig[key].left = (nL / parent.clientWidth * 100).toFixed(2);
        hudConfig[key].top = (nT / parent.clientHeight * 100).toFixed(2);
    };
    document.onmouseup = () => { dragging = false; el.classList.remove('dragging'); };
}
['speed','power','cadence','heartrate','elevation','powerchart'].forEach(k => {
    makeDraggable('hud-'+k, k);
    document.getElementById('switch-'+k).onchange = (e) => {
        hudConfig[k].visible = e.target.checked;
        document.getElementById('hud-'+k).style.display = e.target.checked ? 'flex' : 'none';
    };
});

// --- Python Export ---
function generatePythonRendererScript(projectData, fitBase64) {
    return `# -*- coding: utf-8 -*-
import os
import sys
import json
import subprocess
import numpy as np

# Embedded configurations from browser trimmer
EMBEDDED_PROJECT_JSON = """${JSON.stringify(projectData, null, 2)}"""
EMBEDDED_FIT_BASE64 = "${fitBase64}"

# Verify dependencies and install automatically
def auto_install(package_name, import_name=None):
    if import_name is None:
        import_name = package_name
    try:
        __import__(import_name)
    except ImportError:
        print(f"Installing {package_name} automatically...")
        try:
            subprocess.check_call([sys.executable, "-m", "pip", "install", package_name])
        except Exception as e:
            print(f"Failed to install {package_name} automatically: {e}")
            sys.exit(1)

auto_install("opencv-python", "cv2")
auto_install("Pillow", "PIL")
auto_install("fitparse")

import cv2
from PIL import Image, ImageDraw, ImageFont
from fitparse import FitFile

def load_font(size):
    font_paths = ["C:\\\\Windows\\\\Fonts\\\\arial.ttf", "/Library/Fonts/Arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
    for path in font_paths:
        if os.path.exists(path):
            try: return ImageFont.truetype(path, size)
            except: continue
    return ImageFont.load_default()

def parse_fit_records(fit_path):
    fit = FitFile(fit_path)
    records = []
    for record in fit.get_messages("record"):
        data = {}
        for field in record:
            if field.name == "timestamp": data["timestamp"] = field.value
            elif field.name in ["enhanced_speed", "speed"]:
                if field.value: data["speed"] = field.value * 3.6
            elif field.name == "power": data["power"] = field.value
            elif field.name == "cadence": data["cadence"] = field.value
            elif field.name == "heart_rate": data["heartRate"] = field.value
            elif field.name in ["enhanced_altitude", "altitude"]: data["elevation"] = field.value
            elif field.name == "grade": data["grade"] = field.value
        if "timestamp" in data: records.append(data)
    records.sort(key=lambda r: r["timestamp"])
    return records

def find_closest_telemetry(records, current_time_sec, fit_start_time):
    if not records: return {}
    target_time = fit_start_time + current_time_sec
    low, high, closest_idx, min_diff = 0, len(records)-1, 0, float("inf")
    while low <= high:
        mid = (low + high) // 2
        rec_time = records[mid]["timestamp"].timestamp()
        diff = abs(rec_time - target_time)
        if diff < min_diff: min_diff, closest_idx = diff, mid
        if rec_time < target_time: low = mid + 1
        elif rec_time > target_time: high = mid - 1
        else: return records[mid]
    return records[closest_idx]

def draw_hud(frame_np, tel, settings, width, height):
    img = Image.fromarray(cv2.cvtColor(frame_np, cv2.COLOR_BGR2RGB))
    font_large, font_small = load_font(32), load_font(14)
    hud_sets = settings.get("hudSettings", {})
    for key, item in hud_sets.items():
        if not item.get("visible", True): continue
        x, y = int(item["left"] * width / 100), int(item["top"] * height / 100)
        draw = ImageDraw.Draw(img)
        if key == "speed":
            draw.text((x, y), f"{tel.get('speed', 0.0):.1f} km/h", fill=(59, 130, 246), font=font_large)
        elif key == "power":
            draw.text((x, y), f"{tel.get('power', 0)} W", fill=(16, 185, 129), font=font_large)
    return cv2.cvtColor(np.array(img.convert('RGB')), cv2.COLOR_RGB2BGR)

def process_video():
    proj = json.loads(EMBEDDED_PROJECT_JSON)
    video_file, fit_file = proj["videoFileName"], proj["fitFileName"]
    if not os.path.exists(fit_file):
        import base64
        with open(fit_file, 'wb') as f: f.write(base64.b64decode(EMBEDDED_FIT_BASE64))
    records = parse_fit_records(fit_file)
    fit_start_time = records[0]["timestamp"].timestamp()
    cap = cv2.VideoCapture(video_file)
    fps, width, height = cap.get(cv2.CAP_PROP_FPS), int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    ffmpeg_cmd = ['ffmpeg', '-y', '-f', 'rawvideo', '-vcodec', 'rawvideo', '-pix_fmt', 'bgr24', '-s', f"{width}x{height}", '-r', f"{fps:.2f}", '-i', '-', '-i', video_file, '-map', '0:v', '-map', '1:a?', '-c:v', 'libx264', '-preset', 'veryfast', f"hud_{video_file}"]
    proc = subprocess.Popen(ffmpeg_cmd, stdin=subprocess.PIPE)
    frame_idx = 0
    while cap.isOpened():
        ret, frame = cap.read()
        if not ret: break
        tel = find_closest_telemetry(records, (frame_idx / fps) + proj.get("syncOffsetSeconds", 0.0), fit_start_time)
        hud_frame = draw_hud(frame, tel, proj, width, height)
        proc.stdin.write(hud_frame.tobytes())
        frame_idx += 1
    proc.stdin.close()
    proc.wait()
    cap.release()

if __name__ == '__main__':
    process_video()
`;
}

exportHudBtn.onclick = () => {
    if (!activeVideo || !fitFileBuffer) return log("Load files first.", "error");
    log("Packaging HUD Project...");
    const trimmed = wasmModule.trimFit(new Int8Array(fitFileBuffer), activeVideo.startDt.getTime()/1000, activeVideo.endDt.getTime()/1000);
    const proj = {
        videoFileName: activeVideo.name,
        fitFileName: `trimmed_${activeVideo.name.split('.')[0]}.fit`,
        syncOffsetSeconds: syncOffset,
        hudSettings: hudConfig
    };

    // Binary to Base64 (using chunked method to avoid stack limits)
    const bytes = new Uint8Array(trimmed.buffer);
    let binary = "";
    for (let i = 0; i < bytes.length; i += 8192) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
    }
    const b64 = btoa(binary);

    const code = generatePythonRendererScript(proj, b64);
    const blob = new Blob([code], {type: 'text/plain'});
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `hud_merger_${activeVideo.name.split('.')[0]}.py`;
    a.click();
    log("Python App Generated!", "success");
};

// Utils
function formatJST(d) { return new Date(d.getTime()+9*3600000).toISOString().replace('T',' ').substr(0,19).replace(/-/g,'/'); }

fetchBuildInfo();
console.error = (m) => showVisualError("[Error] " + m);
window.onerror = (m, u, l) => { showVisualError(`[Ex] ${m} (${u}:${l})`); return false; };

// FAQ Toggle accordion logic (Fix for folded items)
document.querySelectorAll('.faq-item').forEach(item => {
    const question = item.querySelector('.faq-question');
    if (question) {
        question.addEventListener('click', () => {
            item.classList.toggle('active');
        });
    }
});
