package fit

import crc.Crc16

class FitParser(private val bytes: ByteArray) {
    var headerSize: Int = bytes[0].toInt() and 0xFF
    var protocolVersion: Int = bytes[1].toInt() and 0xFF
    var profileVersion: Int = getUShort(bytes, 2, true)
    var recordsSize: Long = getUInt(bytes, 4, true)

    init {
        if (bytes.size < 12) {
            throw IllegalArgumentException("File is too small to be a valid FIT file.")
        }
        val signature = bytes.sliceArray(8..11).decodeToString()
        if (signature != ".FIT") {
            throw IllegalArgumentException("This file is not a valid FIT file (missing \".FIT\" signature).")
        }
    }

    class FieldDefinition(val fieldNum: Int, val size: Int, val baseType: Int)
    class DevFieldDefinition(val fieldNum: Int, val size: Int, val devIndex: Int)
    class DefinitionRecord(val isBigEndian: Boolean, val globalMessageNumber: Int, val fields: List<FieldDefinition>, val developerFields: List<DevFieldDefinition>, val offset: Int, val size: Int)
    class ParsedField(val offset: Int, val size: Int, val baseType: Int, val value: Long?)
    class DataRecord(val localId: Int, val globalMessageNumber: Int, val offset: Int, val size: Int, val fields: Map<Int, ParsedField>, val def: DefinitionRecord)

    sealed class FitRecord {
        abstract val offset: Int
        abstract val size: Int
        class Definition(val localId: Int, val globalMessageNumber: Int, override val offset: Int, override val size: Int, val definition: DefinitionRecord) : FitRecord()
        class Data(val localId: Int, val globalMessageNumber: Int, override val offset: Int, override val size: Int, val data: DataRecord) : FitRecord()
    }

    val definitions = mutableMapOf<Int, DefinitionRecord>()
    val records = mutableListOf<FitRecord>()
    private var lastTimestamp: Long = 0L

    fun parse() {
        var offset = headerSize
        val endOffset = headerSize + recordsSize.toInt()
        lastTimestamp = 0L
        records.clear()
        definitions.clear()

        while (offset < endOffset && offset < bytes.size) {
            val recordStart = offset
            if (offset >= bytes.size) {
                throw IllegalArgumentException("Truncated FIT file: reached end of bytes before finishing records.")
            }
            val headerByte = bytes[offset].toInt() and 0xFF
            offset++

            val isCompressed = (headerByte and 0x80) != 0

            if (isCompressed) {
                val localId = (headerByte and 0x60) ushr 5
                val timeOffset = (headerByte and 0x1F).toLong()
                val def = definitions[localId] ?: throw IllegalStateException("Data references undefined local ID: $localId")

                if (lastTimestamp != 0L) {
                    var timestamp = (lastTimestamp and 0xFFFFFFE0L) + timeOffset
                    if (timestamp < lastTimestamp) timestamp += 0x20L
                    lastTimestamp = timestamp
                }
                
                var dataSize = 0
                for (f in def.fields) dataSize += f.size
                for (f in def.developerFields) dataSize += f.size

                val recordEnd = offset + dataSize
                if (recordEnd > bytes.size) {
                    throw IllegalArgumentException("Truncated FIT file: compressed data record ends out of bounds.")
                }
                
                var currentOffset = recordStart + 1
                val parsedFields = mutableMapOf<Int, ParsedField>()

                for (f in def.fields) {
                    val fieldStart = currentOffset
                    currentOffset += f.size
                    var valObj: Long? = null
                    if (f.fieldNum == 253) {
                        valObj = getUInt(bytes, fieldStart, !def.isBigEndian)
                        lastTimestamp = valObj
                    } else if (f.size == 4 && (f.baseType == 0x86 || f.baseType == 0x8C || f.baseType == 0x0C)) {
                        valObj = getUInt(bytes, fieldStart, !def.isBigEndian)
                    } else if (f.size == 4 && f.baseType == 0x85) {
                        valObj = getInt(bytes, fieldStart, !def.isBigEndian).toLong()
                    } else if (f.size == 2 && (f.baseType == 0x84 || f.baseType == 0x8B || f.baseType == 0x0B)) {
                        valObj = getUShort(bytes, fieldStart, !def.isBigEndian).toLong()
                    } else if (f.size == 2 && f.baseType == 0x83) {
                        valObj = getShort(bytes, fieldStart, !def.isBigEndian).toLong()
                    } else if (f.size == 1 && (f.baseType == 0x02 || f.baseType == 0x0A || f.baseType == 0x00)) {
                        valObj = bytes[fieldStart].toLong() and 0xFF
                    } else if (f.size == 1 && (f.baseType == 0x01 || f.baseType == 0x81)) {
                        valObj = bytes[fieldStart].toLong()
                    }
                    parsedFields[f.fieldNum] = ParsedField(fieldStart - recordStart, f.size, f.baseType, valObj)
                }

                if (!parsedFields.containsKey(253) && lastTimestamp != 0L) {
                    parsedFields[253] = ParsedField(0, 0, 0, lastTimestamp)
                }

                val dataRecord = DataRecord(localId, def.globalMessageNumber, recordStart, recordEnd - recordStart, parsedFields, def)
                records.add(FitRecord.Data(localId, def.globalMessageNumber, recordStart, recordEnd - recordStart, dataRecord))
                offset = recordEnd
            } else {
                val isDefinition = (headerByte and 0x40) != 0
                val hasDeveloperData = (headerByte and 0x20) != 0
                val localId = headerByte and 0x0F

                if (isDefinition) {
                    if (offset + 5 > bytes.size) {
                        throw IllegalArgumentException("Truncated FIT file: definition record header out of bounds.")
                    }
                    offset++ 
                    val architecture = bytes[offset].toInt() and 0xFF
                    offset++
                    val isBigEndian = architecture == 1
                    val globalMessageNumber = getUShort(bytes, offset, !isBigEndian)
                    offset += 2
                    val fieldCount = bytes[offset].toInt() and 0xFF
                    offset++
                    if (offset + fieldCount * 3 > bytes.size) {
                        throw IllegalArgumentException("Truncated FIT file: definition fields out of bounds.")
                    }
                    val fields = mutableListOf<FieldDefinition>()
                    for (i in 0 until fieldCount) {
                        fields.add(FieldDefinition(bytes[offset].toInt() and 0xFF, bytes[offset+1].toInt() and 0xFF, bytes[offset+2].toInt() and 0xFF))
                        offset += 3
                    }
                    val developerFields = mutableListOf<DevFieldDefinition>()
                    if (hasDeveloperData) {
                        if (offset >= bytes.size) {
                            throw IllegalArgumentException("Truncated FIT file: developer fields count out of bounds.")
                        }
                        val devFieldCount = bytes[offset].toInt() and 0xFF
                        offset++
                        if (offset + devFieldCount * 3 > bytes.size) {
                            throw IllegalArgumentException("Truncated FIT file: developer fields out of bounds.")
                        }
                        for (i in 0 until devFieldCount) {
                            developerFields.add(DevFieldDefinition(bytes[offset].toInt() and 0xFF, bytes[offset+1].toInt() and 0xFF, bytes[offset+2].toInt() and 0xFF))
                            offset += 3
                        }
                    }
                    val def = DefinitionRecord(isBigEndian, globalMessageNumber, fields, developerFields, recordStart, offset - recordStart)
                    definitions[localId] = def
                    records.add(FitRecord.Definition(localId, globalMessageNumber, recordStart, offset - recordStart, def))
                } else {
                    val def = definitions[localId] ?: throw IllegalStateException("Data references undefined local ID: $localId")
                    var dataSize = 0
                    for (f in def.fields) dataSize += f.size
                    for (f in def.developerFields) dataSize += f.size
                    val recordEnd = offset + dataSize
                    if (recordEnd > bytes.size) {
                        throw IllegalArgumentException("Truncated FIT file: data record ends out of bounds.")
                    }
                    
                    var currentOffset = recordStart + 1
                    val parsedFields = mutableMapOf<Int, ParsedField>()
                    for (f in def.fields) {
                        val fieldStart = currentOffset
                        currentOffset += f.size
                        var valObj: Long? = null
                        if (f.fieldNum == 253) {
                            valObj = getUInt(bytes, fieldStart, !def.isBigEndian)
                            lastTimestamp = valObj
                        } else if (f.size == 4 && (f.baseType == 0x86 || f.baseType == 0x8C || f.baseType == 0x0C)) {
                            valObj = getUInt(bytes, fieldStart, !def.isBigEndian)
                        } else if (f.size == 4 && f.baseType == 0x85) {
                            valObj = getInt(bytes, fieldStart, !def.isBigEndian).toLong()
                        } else if (f.size == 2 && (f.baseType == 0x84 || f.baseType == 0x8B || f.baseType == 0x0B)) {
                            valObj = getUShort(bytes, fieldStart, !def.isBigEndian).toLong()
                        } else if (f.size == 2 && f.baseType == 0x83) {
                            valObj = getShort(bytes, fieldStart, !def.isBigEndian).toLong()
                        } else if (f.size == 1 && (f.baseType == 0x02 || f.baseType == 0x0A || f.baseType == 0x00)) {
                            valObj = bytes[fieldStart].toLong() and 0xFF
                        } else if (f.size == 1 && (f.baseType == 0x01 || f.baseType == 0x81)) {
                            valObj = bytes[fieldStart].toLong()
                        }
                        parsedFields[f.fieldNum] = ParsedField(fieldStart - recordStart, f.size, f.baseType, valObj)
                    }
                    val dataRecord = DataRecord(localId, def.globalMessageNumber, recordStart, recordEnd - recordStart, parsedFields, def)
                    records.add(FitRecord.Data(localId, def.globalMessageNumber, recordStart, recordEnd - recordStart, dataRecord))
                    offset = recordEnd
                }
            }
        }
    }

    data class TelemetryPoint(
        val timestamp: Double,
        val speed: Double,
        val power: Double,
        val cadence: Double,
        val heartRate: Double,
        val elevation: Double,
        var grade: Double,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val distance: Double = 0.0
    )

    fun getTelemetry(): List<TelemetryPoint> {
        val list = mutableListOf<TelemetryPoint>()
        for (r in records) {
            if (r is FitRecord.Data && r.globalMessageNumber == 20) {
                val fields = r.data.fields
                val ts = fields[253]?.value?.toDouble() ?: continue
                
                val rawSpeed = fields[73]?.value ?: fields[6]?.value
                val speedVal = if (rawSpeed != null) (rawSpeed.toDouble() / 1000.0) * 3.6 else 0.0
                val powerVal = fields[7]?.value?.toDouble() ?: 0.0
                val cadenceVal = fields[4]?.value?.toDouble() ?: 0.0
                val hrVal = fields[3]?.value?.toDouble() ?: 0.0
                val rawElev = fields[78]?.value ?: fields[2]?.value
                val elevVal = if (rawElev != null) (rawElev.toDouble() / 5.0) - 500.0 else 0.0
                val gradeVal = 0.0 // Field 9 is temperature, not grade. We will calculate grade dynamically below.
                
                val rawLat = fields[0]?.value
                val rawLon = fields[1]?.value
                val latVal = if (rawLat != null) rawLat.toDouble() * (180.0 / 2147483648.0) else 0.0
                val lonVal = if (rawLon != null) rawLon.toDouble() * (180.0 / 2147483648.0) else 0.0
                val rawDistance = fields[5]?.value
                val distanceVal = if (rawDistance != null) rawDistance.toDouble() / 100.0 else 0.0
                
                list.add(TelemetryPoint(ts, speedVal, powerVal, cadenceVal, hrVal, elevVal, gradeVal, latVal, lonVal, distanceVal))
            }
        }
        
        // Calculate grade if it's missing
        val hasGrade = list.any { kotlin.math.abs(it.grade) > 0.01 }
        if (!hasGrade && list.isNotEmpty()) {
            val window = 5 // 5 seconds half-window
            for (i in list.indices) {
                val startIdx = maxOf(0, i - window)
                val endIdx = minOf(list.size - 1, i + window)
                val startPt = list[startIdx]
                val endPt = list[endIdx]
                
                val dt = endPt.timestamp - startPt.timestamp
                if (dt > 0) {
                    val dElev = endPt.elevation - startPt.elevation
                    var sumSpeed = 0.0
                    for (j in startIdx..endIdx) {
                        sumSpeed += list[j].speed / 3.6 // m/s
                    }
                    val avgSpeedMs = sumSpeed / (endIdx - startIdx + 1)
                    val dist = avgSpeedMs * dt
                    
                    if (dist > 5.0) {
                        list[i].grade = (dElev / dist) * 100.0
                    }
                }
            }
        }
        
        return list
    }

    fun trim(videoStartUtcSeconds: Long, videoEndUtcSeconds: Long): ByteArray {
        val fitEpochSec = 631065600L
        val videoStartFit = videoStartUtcSeconds - fitEpochSec
        val videoEndFit = videoEndUtcSeconds - fitEpochSec
        val filteredRecords = mutableListOf<FitRecord>()
        val recordMessagesInRange = mutableListOf<DataRecord>()

        for (r in records) {
            when (r) {
                is FitRecord.Definition -> filteredRecords.add(r)
                is FitRecord.Data -> {
                    val ts = r.data.fields[253]?.value
                    if (ts != null) {
                        if (ts in videoStartFit..videoEndFit) {
                            filteredRecords.add(r)
                            if (r.globalMessageNumber == 20) recordMessagesInRange.add(r.data)
                        }
                    } else filteredRecords.add(r)
                }
            }
        }

        if (recordMessagesInRange.isEmpty()) throw IllegalArgumentException("No data points fell within the trimmed video timeline.")

        val firstRecordTs = recordMessagesInRange.first().fields[253]?.value ?: 0L
        val lastRecordTs = recordMessagesInRange.last().fields[253]?.value ?: 0L
        val firstDistance = recordMessagesInRange.first().fields[5]?.value ?: 0L
        val lastDistance = recordMessagesInRange.last().fields[5]?.value ?: 0L
        val actualDurationSec = lastRecordTs - firstRecordTs
        val actualDistanceM = (lastDistance - firstDistance) / 100.0

        var bodySize = 0
        for (r in filteredRecords) bodySize += r.size
        val newFitBytes = ByteArray(headerSize + bodySize + 2)
        bytes.copyInto(newFitBytes, 0, 0, headerSize)
        setUInt(newFitBytes, 4, bodySize.toLong(), true)

        var cur = headerSize
        for (r in filteredRecords) {
            val recOffset = cur
            bytes.copyInto(newFitBytes, recOffset, r.offset, r.offset + r.size)
            cur += r.size
            if (r is FitRecord.Data) {
                val dataRec = r.data
                if (dataRec.globalMessageNumber == 20) {
                    val df = dataRec.fields[5]
                    if (df?.value != null) setUInt(newFitBytes, recOffset + df.offset, maxOf(0L, df.value - firstDistance), !dataRec.def.isBigEndian)
                }
                if (dataRec.globalMessageNumber == 18 || dataRec.globalMessageNumber == 19) {
                    dataRec.fields[2]?.let { setUInt(newFitBytes, recOffset + it.offset, firstRecordTs, !dataRec.def.isBigEndian) }
                    dataRec.fields[253]?.let { setUInt(newFitBytes, recOffset + it.offset, lastRecordTs, !dataRec.def.isBigEndian) }
                    dataRec.fields[7]?.let { setUInt(newFitBytes, recOffset + it.offset, actualDurationSec * 1000, !dataRec.def.isBigEndian) }
                    dataRec.fields[8]?.let { setUInt(newFitBytes, recOffset + it.offset, actualDurationSec * 1000, !dataRec.def.isBigEndian) }
                    dataRec.fields[9]?.let { setUInt(newFitBytes, recOffset + it.offset, (actualDistanceM * 100).toLong(), !dataRec.def.isBigEndian) }
                }
            }
        }
        if (headerSize == 14) setUShort(newFitBytes, 12, Crc16.calculate(newFitBytes, offset = 0, length = 12), true)
        setUShort(newFitBytes, headerSize + bodySize, Crc16.calculate(newFitBytes, offset = 0, length = headerSize + bodySize), true)
        return newFitBytes
    }

    companion object {
        fun getShort(b: ByteArray, o: Int, littleEndian: Boolean) = getUShort(b, o, littleEndian).toShort()
        fun getUShort(b: ByteArray, o: Int, littleEndian: Boolean) = if (littleEndian) (b[o+1].toInt() and 0xFF shl 8) or (b[o].toInt() and 0xFF) else (b[o].toInt() and 0xFF shl 8) or (b[o+1].toInt() and 0xFF)
        fun getUInt(b: ByteArray, o: Int, littleEndian: Boolean) = if (littleEndian) (b[o+3].toLong() and 0xFF shl 24) or (b[o+2].toLong() and 0xFF shl 16) or (b[o+1].toLong() and 0xFF shl 8) or (b[o].toLong() and 0xFF) else (b[o].toLong() and 0xFF shl 24) or (b[o+1].toLong() and 0xFF shl 16) or (b[o+2].toLong() and 0xFF shl 8) or (b[o+3].toLong() and 0xFF)
        fun getInt(b: ByteArray, o: Int, littleEndian: Boolean) = if (littleEndian) (b[o+3].toInt() and 0xFF shl 24) or (b[o+2].toInt() and 0xFF shl 16) or (b[o+1].toInt() and 0xFF shl 8) or (b[o].toInt() and 0xFF) else (b[o].toInt() and 0xFF shl 24) or (b[o+1].toInt() and 0xFF shl 16) or (b[o+2].toInt() and 0xFF shl 8) or (b[o+3].toInt() and 0xFF)
        fun setUInt(b: ByteArray, o: Int, v: Long, le: Boolean) { if (le) { b[o]=(v and 0xFF).toByte(); b[o+1]=(v ushr 8 and 0xFF).toByte(); b[o+2]=(v ushr 16 and 0xFF).toByte(); b[o+3]=(v ushr 24 and 0xFF).toByte() } else { b[o]=(v ushr 24 and 0xFF).toByte(); b[o+1]=(v ushr 16 and 0xFF).toByte(); b[o+2]=(v ushr 8 and 0xFF).toByte(); b[o+3]=(v and 0xFF).toByte() } }
        fun setUShort(b: ByteArray, o: Int, v: Int, le: Boolean) { if (le) { b[o]=(v and 0xFF).toByte(); b[o+1]=(v ushr 8 and 0xFF).toByte() } else { b[o]=(v ushr 8 and 0xFF).toByte(); b[o+1]=(v and 0xFF).toByte() } }
    }
}
