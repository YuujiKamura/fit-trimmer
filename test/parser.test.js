const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { FitParser, scanMp4Atoms } = require('../src/parser');

// Absolute paths to target files for testing
const fitFilePath = "H:\\\\マイドライブ\\\\20260621\\\\Lunch_Ride.fit";
const lrvFilePath = "H:\\\\マイドライブ\\\\20260621\\\\LRV_20260621_110949_009.lrv";

test('FIT Parser Tests', async (t) => {
    await t.test('should successfully parse Lunch_Ride.fit without errors', () => {
        assert.ok(fs.existsSync(fitFilePath), `Test file missing: ${fitFilePath}`);
        
        const data = fs.readFileSync(fitFilePath);
        const parser = new FitParser(data.buffer);
        
        // This should not throw 'Data message references undefined local ID' anymore
        assert.doesNotThrow(() => {
            parser.parse();
        });
        
        assert.strictEqual(parser.records.length, 20032, 'Should parse exactly 20032 records');
    });
});

test('MP4 Parser (scanMp4Atoms) Tests', async (t) => {
    await t.test('should extract creation time and duration from trailer-located moov in LRV_20260621_110949_009.lrv', async () => {
        assert.ok(fs.existsSync(lrvFilePath), `Test file missing: ${lrvFilePath}`);
        
        const fd = fs.openSync(lrvFilePath, 'r');
        const stats = fs.statSync(lrvFilePath);
        const fileSize = stats.size;
        
        const scanChunkSize = 100 * 1024 * 1024; // 100MB
        
        let metaResult = null;
        const onMvhd = (meta) => {
            metaResult = meta;
        };
        
        // 1. Scan Front
        const frontBuf = Buffer.alloc(Math.min(scanChunkSize, fileSize));
        fs.readSync(fd, frontBuf, 0, frontBuf.length, 0);
        const frontBytes = new Uint8Array(frontBuf);
        const frontView = new DataView(frontBytes.buffer);
        
        let found = scanMp4Atoms(frontBytes, frontView, 0, frontBytes.length, onMvhd);
        assert.strictEqual(found, false, 'moov atom should not be in the front 100MB of this file');
        
        // 2. Scan End (Trailer)
        if (!found && fileSize > scanChunkSize) {
            const endStart = fileSize - scanChunkSize;
            const endBuf = Buffer.alloc(scanChunkSize);
            fs.readSync(fd, endBuf, 0, endBuf.length, endStart);
            const endBytes = new Uint8Array(endBuf);
            const endView = new DataView(endBytes.buffer);
            
            let moovIdx = -1;
            for (let j = 0; j < endBytes.length - 8; j++) {
                if (endBytes[j] === 109 && endBytes[j+1] === 111 && endBytes[j+2] === 111 && endBytes[j+3] === 118) { // 'moov'
                    moovIdx = j - 4;
                    break;
                }
            }
            
            if (moovIdx >= 0) {
                found = scanMp4Atoms(endBytes, endView, moovIdx, endBytes.length, onMvhd);
            }
        }
        
        fs.closeSync(fd);
        
        assert.strictEqual(found, true, 'Should find moov atom at the end chunk');
        assert.ok(metaResult, 'Should invoke onMvhd callback');
        assert.strictEqual(metaResult.creationTime, 3864854390, 'Creation time should match expected raw value');
        assert.strictEqual(metaResult.timescale, 30000, 'Timescale should be 30000');
        assert.strictEqual(metaResult.duration, 53973920, 'Duration should be 53973920');
        
        const durationSec = metaResult.duration / metaResult.timescale;
        assert.ok(Math.abs(durationSec - 1799.13) < 0.01, `Duration should be approx 1799.13s, got ${durationSec}s`);
    });
});
