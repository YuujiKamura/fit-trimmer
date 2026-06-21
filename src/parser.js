/**
 * FITTER×FITTER - Parser Core Modules
 * Supports both Browser (Window context) and Node.js environments.
 */

// --- MP4 Atom Scanner ---
function scanMp4Atoms(bytes, view, start, end, onMvhdFound, onMoovFound) {
    let i = start;
    while (i < end - 8) {
        const size = view.getUint32(i, false);
        const type = String.fromCharCode(bytes[i+4], bytes[i+5], bytes[i+6], bytes[i+7]);
        
        if (size === 0) break;
        if (size < 8) {
            i += 8;
            continue;
        }
        
        if (type === 'moov' || type === 'trak' || type === 'mdia') {
            if (onMoovFound && type === 'moov') {
                onMoovFound(i, size);
            }
            if (scanMp4Atoms(bytes, view, i + 8, Math.min(i + size, end), onMvhdFound, onMoovFound)) {
                return true;
            }
        } else if (type === 'mvhd') {
            const mvhdStart = i + 8;
            const version = bytes[mvhdStart];
            
            let timeOffset = mvhdStart + 4;
            let creationTime, timescale, duration;
            if (version === 1) {
                // 64-bit creation time (lower 32-bit is sufficient for standard Unix epoch mapping)
                const high = view.getUint32(timeOffset, false);
                const low = view.getUint32(timeOffset + 4, false);
                creationTime = low;
                
                timeOffset += 16; // Skip modification time
                timescale = view.getUint32(timeOffset, false);
                const durHigh = view.getUint32(timeOffset + 4, false);
                const durLow = view.getUint32(timeOffset + 8, false);
                duration = durLow;
            } else {
                // 32-bit
                creationTime = view.getUint32(timeOffset, false);
                timeOffset += 8;
                timescale = view.getUint32(timeOffset, false);
                duration = view.getUint32(timeOffset + 4, false);
            }
            if (onMvhdFound) {
                onMvhdFound({ creationTime, timescale, duration });
            }
            return true;
        }
        i += size;
    }
    return false;
}

// --- FIT Binary Parser ---
class FitParser {
    constructor(arrayBuffer) {
        this.bytes = new Uint8Array(arrayBuffer);
        this.view = new DataView(arrayBuffer);
        this.offset = 0;
        
        this.headerSize = this.bytes[0];
        this.protocolVersion = this.bytes[1];
        this.profileVersion = this.view.getUint16(2, true);
        this.recordsSize = this.view.getUint32(4, true);
        this.offset = this.headerSize;
        
        // Validate FIT signature
        if (this.bytes.length < 12) {
            throw new Error("File is too small to be a valid FIT file.");
        }
        const tagValue = String.fromCharCode(this.bytes[8], this.bytes[9], this.bytes[10], this.bytes[11]);
        if (tagValue !== '.FIT') {
            throw new Error('This file is not a valid FIT file (missing ".FIT" signature).');
        }
        
        this.definitions = {};
        this.records = [];
    }
    
    parse() {
        const endOffset = this.headerSize + this.recordsSize;
        while (this.offset < endOffset) {
            const recordStart = this.offset;
            const headerByte = this.bytes[this.offset];
            this.offset++;
            
            const isCompressed = (headerByte & 0x80) !== 0;
            
            if (isCompressed) {
                // Compressed Timestamp Header
                const localId = (headerByte & 0x60) >> 5;
                const timeOffset = headerByte & 0x1F;
                
                const def = this.definitions[localId];
                if (!def) {
                    throw new Error(`Data message references undefined local ID (compressed): ${localId}`);
                }
                
                let dataSize = 0;
                for (const f of def.fields) {
                    dataSize += f.size;
                }
                for (const f of def.developerFields) {
                    dataSize += f.size;
                }
                
                const recordEnd = this.offset + dataSize;
                const recordBytes = this.bytes.slice(recordStart, recordEnd);
                
                let currentOffset = recordStart + 1;
                const parsedFields = {};
                
                for (const f of def.fields) {
                    const fieldStart = currentOffset;
                    currentOffset += f.size;
                    
                    let val = null;
                    if (f.size === 4 && (f.baseType === 0x86 || f.baseType === 0x0C)) {
                        val = this.view.getUint32(fieldStart, !def.isBigEndian);
                    } else if (f.size === 4 && f.baseType === 0x85) {
                        val = this.view.getInt32(fieldStart, !def.isBigEndian);
                    } else if (f.size === 2 && (f.baseType === 0x84 || f.baseType === 0x0B)) {
                        val = this.view.getUint16(fieldStart, !def.isBigEndian);
                    } else if (f.size === 1 && (f.baseType === 0x02 || f.baseType === 0x0A || f.baseType === 0x00)) {
                        val = this.bytes[fieldStart];
                    }
                    
                    parsedFields[f.fieldNum] = {
                        offset: fieldStart - recordStart,
                        size: f.size,
                        baseType: f.baseType,
                        value: val
                    };
                }
                
                this.offset = recordEnd;
                this.records.push({
                    isDefinition: false,
                    localId,
                    globalMessageNumber: def.globalMessageNumber,
                    bytes: recordBytes,
                    fields: parsedFields,
                    def: def
                });
            } else {
                // Normal Header
                const isDefinition = (headerByte & 0x40) !== 0;
                const hasDeveloperData = (headerByte & 0x20) !== 0;
                const localId = headerByte & 0x0F;
                
                if (isDefinition) {
                    this.offset++; // Reserved
                    const architecture = this.bytes[this.offset];
                    this.offset++;
                    const isBigEndian = architecture === 1;
                    
                    const globalMessageNumber = this.view.getUint16(this.offset, !isBigEndian);
                    this.offset += 2;
                    
                    const fieldCount = this.bytes[this.offset];
                    this.offset++;
                    
                    const fields = [];
                    for (let i = 0; i < fieldCount; i++) {
                        const fieldNum = this.bytes[this.offset];
                        const size = this.bytes[this.offset + 1];
                        const baseType = this.bytes[this.offset + 2];
                        this.offset += 3;
                        fields.push({ fieldNum, size, baseType });
                    }
                    
                    let developerFields = [];
                    if (hasDeveloperData) {
                        const devFieldCount = this.bytes[this.offset];
                        this.offset++;
                        for (let i = 0; i < devFieldCount; i++) {
                            const fieldNum = this.bytes[this.offset];
                            const size = this.bytes[this.offset + 1];
                            const devIndex = this.bytes[this.offset + 2];
                            this.offset += 3;
                            developerFields.push({ fieldNum, size, devIndex });
                        }
                    }
                    
                    const recordBytes = this.bytes.slice(recordStart, this.offset);
                    
                    const def = {
                        isBigEndian,
                        globalMessageNumber,
                        fields,
                        developerFields,
                        recordBytes
                    };
                    
                    this.definitions[localId] = def;
                    this.records.push({
                        isDefinition: true,
                        localId,
                        globalMessageNumber,
                        bytes: recordBytes,
                        definition: def
                    });
                } else {
                    const def = this.definitions[localId];
                    if (!def) {
                        throw new Error(`Data message references undefined local ID: ${localId}`);
                    }
                    
                    let dataSize = 0;
                    for (const f of def.fields) {
                        dataSize += f.size;
                    }
                    for (const f of def.developerFields) {
                        dataSize += f.size;
                    }
                    
                    const recordEnd = this.offset + dataSize;
                    const recordBytes = this.bytes.slice(recordStart, recordEnd);
                    
                    let currentOffset = recordStart + 1;
                    const parsedFields = {};
                    
                    for (const f of def.fields) {
                        const fieldStart = currentOffset;
                        currentOffset += f.size;
                        
                        let val = null;
                        if (f.size === 4 && (f.baseType === 0x86 || f.baseType === 0x0C)) {
                            val = this.view.getUint32(fieldStart, !def.isBigEndian);
                        } else if (f.size === 4 && f.baseType === 0x85) {
                            val = this.view.getInt32(fieldStart, !def.isBigEndian);
                        } else if (f.size === 2 && (f.baseType === 0x84 || f.baseType === 0x0B)) {
                            val = this.view.getUint16(fieldStart, !def.isBigEndian);
                        } else if (f.size === 1 && (f.baseType === 0x02 || f.baseType === 0x0A || f.baseType === 0x00)) {
                            val = this.bytes[fieldStart];
                        }
                        
                        parsedFields[f.fieldNum] = {
                            offset: fieldStart - recordStart,
                            size: f.size,
                            baseType: f.baseType,
                            value: val
                        };
                    }
                    
                    this.offset = recordEnd;
                    this.records.push({
                        isDefinition: false,
                        localId,
                        globalMessageNumber: def.globalMessageNumber,
                        bytes: recordBytes,
                        fields: parsedFields,
                        def: def
                    });
                }
            }
        }
    }
}

// Global Export mapping
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { FitParser, scanMp4Atoms };
} else {
    window.FitParser = FitParser;
    window.scanMp4Atoms = scanMp4Atoms;
}
