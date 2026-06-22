const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { FitParser, scanMp4Atoms } = require('../src/parser');

// Absolute paths to target files for testing
const fitFilePath = "H:\\\\マイドライブ\\\\20260621\\\\Lunch_Ride.fit";
const lrvFilePath = "H:\\\\マイドライブ\\\\20260621\\\\LRV_20260621_110949_009.lrv";

test('FIT Parser Tests', async (t) => {
    // 1. Existing integration test
    await t.test('should successfully parse Lunch_Ride.fit without errors', () => {
        assert.ok(fs.existsSync(fitFilePath), `Test file missing: ${fitFilePath}`);
        
        const data = fs.readFileSync(fitFilePath);
        const parser = new FitParser(data.buffer);
        
        assert.doesNotThrow(() => {
            parser.parse();
        });
        
        assert.strictEqual(parser.records.length, 20032, 'Should parse exactly 20032 records');
    });

    // 2. FitParser Edge Cases: Buffer too small
    await t.test('FitParser should throw when buffer is too small (< 12 bytes)', () => {
        const buffer = new ArrayBuffer(8);
        assert.throws(() => {
            new FitParser(buffer);
        }, /File is too small to be a valid FIT file/);
    });

    // 3. FitParser Edge Cases: Invalid signature
    await t.test('FitParser should throw when signature is invalid', () => {
        const buffer = new ArrayBuffer(14);
        const view = new DataView(buffer);
        view.setUint8(0, 14); // Header size
        view.setUint8(1, 32); // Protocol
        view.setUint16(2, 1000, true); // Profile
        view.setUint32(4, 0, true); // Data size
        // Tag ".FIT" is bytes 8-11. We set them to "BAD!"
        view.setUint8(8, 66); // 'B'
        view.setUint8(9, 65); // 'A'
        view.setUint8(10, 68); // 'D'
        view.setUint8(11, 33); // '!'

        assert.throws(() => {
            new FitParser(buffer);
        }, /not a valid FIT file/);
    });

    // 4. FitParser Edge Cases: Data message referencing undefined local ID
    await t.test('FitParser should throw when data message references undefined local ID', () => {
        // Construct a minimum valid FIT header
        const buffer = new ArrayBuffer(15);
        const view = new DataView(buffer);
        view.setUint8(0, 14); // Header size
        view.setUint8(1, 32);
        view.setUint16(2, 2000, true);
        view.setUint32(4, 1, true); // 1 byte data
        // ".FIT"
        view.setUint8(8, 46); // '.'
        view.setUint8(9, 70); // 'F'
        view.setUint8(10, 73); // 'I'
        view.setUint8(11, 84); // 'T'

        // Record start at offset 14.
        // Set normal data header with local ID 1, but no definition exists.
        view.setUint8(14, 0x01); 

        const parser = new FitParser(buffer);
        assert.throws(() => {
            parser.parse();
        }, /Data message references undefined local ID: 1/);
    });

    // 5. FitParser Edge Cases: Compressed timestamp message referencing undefined local ID
    await t.test('FitParser should throw when compressed timestamp references undefined local ID', () => {
        const buffer = new ArrayBuffer(15);
        const view = new DataView(buffer);
        view.setUint8(0, 14); // Header size
        view.setUint8(1, 32);
        view.setUint16(2, 2000, true);
        view.setUint32(4, 1, true); // 1 byte data
        // ".FIT"
        view.setUint8(8, 46);
        view.setUint8(9, 70);
        view.setUint8(10, 73);
        view.setUint8(11, 84);

        // Record start at offset 14.
        // Set compressed timestamp header with local ID 2 (0x80 | (local_id << 5) | time_offset)
        // 0x80 | (2 << 5) | 10 => 0x80 | 0x40 | 10 = 0xC0 | 10 = 0xCA
        view.setUint8(14, 0xCA); 

        const parser = new FitParser(buffer);
        assert.throws(() => {
            parser.parse();
        }, /Data message references undefined local ID \(compressed\): 2/);
    });

    // 6. FitParser Unit Test: Full mock parsing (Little Endian, Big Endian, Developer Fields, Compressed Timestamps)
    await t.test('FitParser should parse mock records successfully (normal, big-endian, developer fields)', () => {
        // Construct mock FIT data
        const recordsSize = 100;
        const totalSize = 14 + recordsSize;
        const buffer = new ArrayBuffer(totalSize);
        const view = new DataView(buffer);
        const bytes = new Uint8Array(buffer);

        // FIT Header
        view.setUint8(0, 14); // Header size
        view.setUint8(1, 32);
        view.setUint16(2, 2000, true);
        view.setUint32(4, recordsSize, true);
        // ".FIT"
        bytes.set([46, 70, 73, 84], 8);

        // Offset 14: Definition message (local ID 0, global msg 20, 2 fields, Little Endian)
        let off = 14;
        bytes[off++] = 0x40; // normal header, definition, local ID 0
        bytes[off++] = 0; // reserved
        bytes[off++] = 0; // architecture: 0 = Little Endian
        view.setUint16(off, 20, true); off += 2; // global message number 20 (record)
        bytes[off++] = 2; // field count: 2
        
        // Field 1: fieldNum 253 (timestamp), size 4, baseType 0x86 (uint32)
        bytes[off++] = 253;
        bytes[off++] = 4;
        bytes[off++] = 0x86;
        
        // Field 2: fieldNum 7 (power), size 2, baseType 0x84 (uint16)
        bytes[off++] = 7;
        bytes[off++] = 2;
        bytes[off++] = 0x84;

        // Offset 27: Data message (local ID 0)
        bytes[off++] = 0x00; // normal header, data, local ID 0
        view.setUint32(off, 1000000, true); off += 4; // timestamp value 1000000
        view.setUint16(off, 250, true); off += 2; // power value 250

        // Offset 34: Compressed timestamp data message
        // Local ID 0, time offset 15. Header: 0x80 | (0 << 5) | 15 => 0x8F
        bytes[off++] = 0x8F;
        view.setUint32(off, 1000015, true); off += 4; // timestamp (updated)
        view.setUint16(off, 260, true); off += 2; // power value 260

        // Offset 41: Big Endian Definition message (local ID 1, global msg 20, 1 field, Big Endian)
        bytes[off++] = 0x41; // normal header, definition, local ID 1
        bytes[off++] = 0; // reserved
        bytes[off++] = 1; // architecture: 1 = Big Endian
        view.setUint16(off, 20, false); off += 2; // global message number 20 (Big Endian)
        bytes[off++] = 1; // field count: 1
        
        // Field 1: fieldNum 3 (heart rate), size 1, baseType 0x02 (uint8)
        bytes[off++] = 3;
        bytes[off++] = 1;
        bytes[off++] = 0x02;

        // Offset 50: Big Endian Data message
        bytes[off++] = 0x01; // normal header, data, local ID 1
        bytes[off++] = 145; // heart rate value 145

        // Offset 52: Definition message with Developer Data (local ID 2, global msg 20, 1 normal field, 1 developer field)
        bytes[off++] = 0x40 | 0x20 | 0x02; // normal header, definition, has developer data, local ID 2
        bytes[off++] = 0; // reserved
        bytes[off++] = 0; // architecture: 0 = Little Endian
        view.setUint16(off, 20, true); off += 2;
        bytes[off++] = 1; // field count: 1
        // Normal field: fieldNum 1, size 4, baseType 0x85 (int32)
        bytes[off++] = 1;
        bytes[off++] = 4;
        bytes[off++] = 0x85;
        
        // Developer fields count
        bytes[off++] = 1; // 1 developer field
        // Developer field: fieldNum 0, size 1, devIndex 0
        bytes[off++] = 0;
        bytes[off++] = 1;
        bytes[off++] = 0;

        // Offset 67: Developer Data message (local ID 2)
        bytes[off++] = 0x02; // normal header, data, local ID 2
        view.setInt32(off, -999, true); off += 4; // normal field value
        bytes[off++] = 42; // developer field value (size 1)

        // Set the recordsSize accurately up to off
        view.setUint32(4, off - 14, true);

        const parser = new FitParser(buffer);
        assert.doesNotThrow(() => {
            parser.parse();
        });

        // Verify parsing results
        assert.ok(parser.records.length > 0);
        
        // Check local ID 0 data
        const r1 = parser.records.find(r => !r.isDefinition && r.localId === 0 && r.fields[253]?.value === 1000000);
        assert.ok(r1, 'Should parse first data record');
        assert.strictEqual(r1.fields[7]?.value, 250, 'Power should be 250');

        // Check compressed timestamp data record
        const r2 = parser.records.find(r => !r.isDefinition && r.localId === 0 && r.fields[253]?.value === 1000015);
        assert.ok(r2, 'Should parse compressed timestamp record');
        assert.strictEqual(r2.fields[7]?.value, 260, 'Power should be 260');

        // Check Big Endian data record
        const r3 = parser.records.find(r => !r.isDefinition && r.localId === 1);
        assert.ok(r3, 'Should parse big endian record');
        assert.strictEqual(r3.fields[3]?.value, 145, 'Heart rate should be 145');

        // Check Developer Data record
        const r4 = parser.records.find(r => !r.isDefinition && r.localId === 2);
        assert.ok(r4, 'Should parse developer data record');
        assert.strictEqual(r4.fields[1]?.value, -999, 'Int32 value should be -999');
    });
});

test('MP4 Parser (scanMp4Atoms) Tests', async (t) => {
    // 1. Existing integration test
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
        
        const frontBuf = Buffer.alloc(Math.min(scanChunkSize, fileSize));
        fs.readSync(fd, frontBuf, 0, frontBuf.length, 0);
        const frontBytes = new Uint8Array(frontBuf);
        const frontView = new DataView(frontBytes.buffer);
        
        let found = scanMp4Atoms(frontBytes, frontView, 0, frontBytes.length, onMvhd);
        assert.strictEqual(found, false, 'moov atom should not be in the front 100MB of this file');
        
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

    // Helper to construct an MP4 atom buffer in-memory
    function createAtom(type, payloadBytes = []) {
        const size = 8 + payloadBytes.length;
        const buf = new ArrayBuffer(size);
        const view = new DataView(buf);
        const bytes = new Uint8Array(buf);
        
        view.setUint32(0, size, false); // size (Big Endian)
        for (let i = 0; i < 4; i++) {
            bytes[4 + i] = type.charCodeAt(i);
        }
        if (payloadBytes.length > 0) {
            bytes.set(payloadBytes, 8);
        }
        return bytes;
    }

    // 2. scanMp4Atoms Edge Case: Empty buffer
    await t.test('scanMp4Atoms should return false on empty buffer', () => {
        const bytes = new Uint8Array(0);
        const view = new DataView(bytes.buffer);
        let called = false;
        const res = scanMp4Atoms(bytes, view, 0, 0, () => called = true);
        assert.strictEqual(res, false);
        assert.strictEqual(called, false);
    });

    // 3. scanMp4Atoms Edge Case: Buffer too small
    await t.test('scanMp4Atoms should return false on buffer smaller than atom header (8 bytes)', () => {
        const bytes = new Uint8Array([0, 0, 0, 4, 97, 98, 99]); // 7 bytes
        const view = new DataView(bytes.buffer);
        let called = false;
        const res = scanMp4Atoms(bytes, view, 0, 7, () => called = true);
        assert.strictEqual(res, false);
        assert.strictEqual(called, false);
    });

    // 4. scanMp4Atoms Edge Case: Size field is 0
    await t.test('scanMp4Atoms should terminate search when size is 0', () => {
        const bytes = new Uint8Array([0, 0, 0, 0, 109, 111, 111, 118, 0, 0, 0, 8, 109, 111, 111, 118]);
        const view = new DataView(bytes.buffer);
        let called = false;
        const res = scanMp4Atoms(bytes, view, 0, bytes.length, () => called = true);
        assert.strictEqual(res, false);
        assert.strictEqual(called, false);
    });

    // 5. scanMp4Atoms Edge Case: Size field is less than 8
    await t.test('scanMp4Atoms should skip and continue when size is less than 8', () => {
        // First atom: size 4 (less than 8), type 'free'
        // Second atom: size 8, type 'free'
        const bytes = new Uint8Array([0, 0, 0, 4, 102, 114, 101, 101, 0, 0, 0, 8, 102, 114, 101, 101]);
        const view = new DataView(bytes.buffer);
        const res = scanMp4Atoms(bytes, view, 0, bytes.length, null);
        assert.strictEqual(res, false, 'Should terminate gracefully without loop');
    });

    // 6. scanMp4Atoms Unit Test: Nested structure with mvhd version 0 (32-bit)
    await t.test('scanMp4Atoms should successfully parse version 0 mvhd within nested atoms', () => {
        // Construct mvhd version 0 payload (28 bytes):
        // version (1 byte) = 0
        // flags (3 bytes) = [0, 0, 0]
        // creation time (4 bytes) = 3000000000 (Big Endian)
        // modification time (4 bytes) = 3000000001 (Big Endian)
        // timescale (4 bytes) = 1000 (Big Endian)
        // duration (4 bytes) = 60000 (Big Endian)
        const mvhdPayload = [
            0, 0, 0, 0, 
            111, 198, 117, 0,  // creation time: 0x6FC87500 = 1875375360
            0, 0, 0, 0,        // modification time
            0, 0, 3, 232,      // timescale: 1000
            0, 0, 234, 96      // duration: 60000
        ];
        const mvhdAtom = createAtom('mvhd', mvhdPayload);

        // Nest it inside mdia -> trak -> moov
        const mdiaAtom = createAtom('mdia', Array.from(mvhdAtom));
        const trakAtom = createAtom('trak', Array.from(mdiaAtom));
        
        let moovCalled = false;
        const moovAtom = createAtom('moov', Array.from(trakAtom));

        const bytes = new Uint8Array(moovAtom);
        const view = new DataView(bytes.buffer);

        let metadata = null;
        const res = scanMp4Atoms(
            bytes, 
            view, 
            0, 
            bytes.length, 
            (m) => metadata = m,
            (offset, size) => moovCalled = true
        );

        assert.strictEqual(res, true, 'Should find mvhd inside moov');
        assert.strictEqual(moovCalled, true, 'onMoovFound should be triggered');
        assert.ok(metadata);
        assert.strictEqual(metadata.creationTime, 1875277056);
        assert.strictEqual(metadata.timescale, 1000);
        assert.strictEqual(metadata.duration, 60000);
    });

    // 7. scanMp4Atoms Unit Test: Nested structure with mvhd version 1 (64-bit)
    await t.test('scanMp4Atoms should successfully parse version 1 mvhd within nested atoms', () => {
        // Construct mvhd version 1 payload (40 bytes):
        // version (1 byte) = 1
        // flags (3 bytes) = [0, 0, 0]
        // creation time (8 bytes) = 0x00000000E0000000 (lower 32-bit: 3758096384)
        // modification time (8 bytes) = 0
        // timescale (4 bytes) = 90000 (0x00015F90)
        // duration (8 bytes) = 0x00000000000F4240 (lower 32-bit: 1000000)
        const mvhdPayload = [
            1, 0, 0, 0,
            0, 0, 0, 0, 224, 0, 0, 0, // 64-bit creation time (high 4 bytes 0, low 4 bytes 0xE0000000)
            0, 0, 0, 0, 0, 0, 0, 0,    // 64-bit mod time
            0, 1, 95, 144,             // 32-bit timescale: 90000
            0, 0, 0, 0, 0, 15, 66, 64  // 64-bit duration (low 4 bytes 0x000F4240 = 1000000)
        ];
        const mvhdAtom = createAtom('mvhd', mvhdPayload);

        const bytes = new Uint8Array(mvhdAtom);
        const view = new DataView(bytes.buffer);

        let metadata = null;
        const res = scanMp4Atoms(bytes, view, 0, bytes.length, (m) => metadata = m);

        assert.strictEqual(res, true);
        assert.ok(metadata);
        assert.strictEqual(metadata.creationTime, 3758096384);
        assert.strictEqual(metadata.timescale, 90000);
        assert.strictEqual(metadata.duration, 1000000);
    });
});
