package fit

import crc.Crc16

class FitParser(private val bytes: ByteArray) {
    var headerSize: Int = bytes[0].toInt() and 0xFF
    var protocolVersion: Int = bytes[1].toInt() and 0xFF
    var profileVersion: Int = getUShort(bytes, 2, littleEndian = true)
    var recordsSize: Long = getUInt(bytes, 4, littleEndian = true)

    init {
        if (bytes.size < 12) {
            throw IllegalArgumentException("File is too small to be a valid FIT file.")
        }
        val signature = bytes.sliceArray(8..11).decodeToString()
        if (signature != ".FIT") {
            throw IllegalArgumentException("This file is not a valid FIT file (missing \".FIT\" signature).")
        }
    }

    class FieldDefinition(
        val fieldNum: Int,
        val size: Int,
        val baseType: Int
    )

    class DevFieldDefinition(
        val fieldNum: Int,
        val size: Int,
        val devIndex: Int
    )

    class DefinitionRecord(
        val isBigEndian: Boolean,
        val globalMessageNumber: Int,
        val fields: List<FieldDefinition>,
        val developerFields: List<DevFieldDefinition>,
        val bytes: ByteArray
    )

    class ParsedField(
        val offset: Int,
        val size: Int,
        val baseType: Int,
        val value: Long?
    )

    class DataRecord(
        val localId: Int,
        val globalMessageNumber: Int,
        val bytes: ByteArray,
        val fields: Map<Int, ParsedField>,
        val def: DefinitionRecord
    )

    sealed class FitRecord {
        abstract val bytes: ByteArray
        class Definition(val localId: Int, val globalMessageNumber: Int, override val bytes: ByteArray, val definition: DefinitionRecord) : FitRecord()
        class Data(val localId: Int, val globalMessageNumber: Int, override val bytes: ByteArray, val data: DataRecord) : FitRecord()
    }

    val definitions = mutableMapOf<Int, DefinitionRecord>()
    val records = mutableListOf<FitRecord>()

    fun parse() {
        var offset = headerSize
        val endOffset = headerSize + recordsSize.toInt()

        while (offset < endOffset && offset < bytes.size) {
            val recordStart = offset
            val headerByte = bytes[offset].toInt() and 0xFF
            offset++

            val isCompressed = (headerByte and 0x80) != 0

            if (isCompressed) {
                val localId = (headerByte and 0x60) ushr 5
                // Compressed timestamp header ignores some parts, but needs definition
                val def = definitions[localId] ?: throw IllegalStateException("Data message references undefined local ID (compressed): $localId")

                var dataSize = 0
                for (f in def.fields) {
                    dataSize += f.size
                }
                for (f in def.developerFields) {
                    dataSize += f.size
                }

                val recordEnd = offset + dataSize
                if (recordEnd > bytes.size) {
                    break
                }
                val recordBytes = bytes.copyOfRange(recordStart, recordEnd)

                var currentOffset = recordStart + 1
                val parsedFields = mutableMapOf<Int, ParsedField>()

                for (f in def.fields) {
                    val fieldStart = currentOffset
                    currentOffset += f.size

                    var valObj: Long? = null
                    if (f.size == 4 && (f.baseType == 0x86 || f.baseType == 0x0C)) {
                        valObj = getUInt(bytes, fieldStart, !def.isBigEndian)
                    } else if (f.size == 4 && f.baseType == 0x85) {
                        valObj = getInt(bytes, fieldStart, !def.isBigEndian).toLong()
                    } else if (f.size == 2 && (f.baseType == 0x84 || f.baseType == 0x0B)) {
                        valObj = getUShort(bytes, fieldStart, !def.isBigEndian).toLong()
                    } else if (f.size == 1 && (f.baseType == 0x02 || f.baseType == 0x0A || f.baseType == 0x00)) {
                        valObj = bytes[fieldStart].toLong() and 0xFF
                    }

                    parsedFields[f.fieldNum] = ParsedField(
                        offset = fieldStart - recordStart,
                        size = f.size,
                        baseType = f.baseType,
                        value = valObj
                    )
                }

                offset = recordEnd
                val dataRecord = DataRecord(localId, def.globalMessageNumber, recordBytes, parsedFields, def)
                records.add(FitRecord.Data(localId, def.globalMessageNumber, recordBytes, dataRecord))
            } else {
                val isDefinition = (headerByte and 0x40) != 0
                val hasDeveloperData = (headerByte and 0x20) != 0
                val localId = headerByte and 0x0F

                if (isDefinition) {
                    offset++ // Reserved
                    val architecture = bytes[offset].toInt() and 0xFF
                    offset++
                    val isBigEndian = architecture == 1

                    val globalMessageNumber = getUShort(bytes, offset, !isBigEndian)
                    offset += 2

                    val fieldCount = bytes[offset].toInt() and 0xFF
                    offset++

                    val fields = mutableListOf<FieldDefinition>()
                    for (i in 0 until fieldCount) {
                        val fieldNum = bytes[offset].toInt() and 0xFF
                        val size = bytes[offset + 1].toInt() and 0xFF
                        val baseType = bytes[offset + 2].toInt() and 0xFF
                        offset += 3
                        fields.add(FieldDefinition(fieldNum, size, baseType))
                    }

                    val developerFields = mutableListOf<DevFieldDefinition>()
                    if (hasDeveloperData) {
                        val devFieldCount = bytes[offset].toInt() and 0xFF
                        offset++
                        for (i in 0 until devFieldCount) {
                            val fieldNum = bytes[offset].toInt() and 0xFF
                            val size = bytes[offset + 1].toInt() and 0xFF
                            val devIndex = bytes[offset + 2].toInt() and 0xFF
                            offset += 3
                            developerFields.add(DevFieldDefinition(fieldNum, size, devIndex))
                        }
                    }

                    val recordBytes = bytes.copyOfRange(recordStart, offset)
                    val def = DefinitionRecord(isBigEndian, globalMessageNumber, fields, developerFields, recordBytes)
                    definitions[localId] = def
                    records.add(FitRecord.Definition(localId, globalMessageNumber, recordBytes, def))
                } else {
                    val def = definitions[localId] ?: throw IllegalStateException("Data message references undefined local ID: $localId")

                    var dataSize = 0
                    for (f in def.fields) {
                        dataSize += f.size
                    }
                    for (f in def.developerFields) {
                        dataSize += f.size
                    }

                    val recordEnd = offset + dataSize
                    if (recordEnd > bytes.size) {
                        break
                    }
                    val recordBytes = bytes.copyOfRange(recordStart, recordEnd)

                    var currentOffset = recordStart + 1
                    val parsedFields = mutableMapOf<Int, ParsedField>()

                    for (f in def.fields) {
                        val fieldStart = currentOffset
                        currentOffset += f.size

                        var valObj: Long? = null
                        if (f.size == 4 && (f.baseType == 0x86 || f.baseType == 0x0C)) {
                            valObj = getUInt(bytes, fieldStart, !def.isBigEndian)
                        } else if (f.size == 4 && f.baseType == 0x85) {
                            valObj = getInt(bytes, fieldStart, !def.isBigEndian).toLong()
                        } else if (f.size == 2 && (f.baseType == 0x84 || f.baseType == 0x0B)) {
                            valObj = getUShort(bytes, fieldStart, !def.isBigEndian).toLong()
                        } else if (f.size == 1 && (f.baseType == 0x02 || f.baseType == 0x0A || f.baseType == 0x00)) {
                            valObj = bytes[fieldStart].toLong() and 0xFF
                        }

                        parsedFields[f.fieldNum] = ParsedField(
                            offset = fieldStart - recordStart,
                            size = f.size,
                            baseType = f.baseType,
                            value = valObj
                        )
                    }

                    offset = recordEnd
                    val dataRecord = DataRecord(localId, def.globalMessageNumber, recordBytes, parsedFields, def)
                    records.add(FitRecord.Data(localId, def.globalMessageNumber, recordBytes, dataRecord))
                }
            }
        }
    }

    fun trim(videoStartUtcSeconds: Long, videoEndUtcSeconds: Long): ByteArray {
        val fitEpochSec = 631065600L
        val videoStartFit = videoStartUtcSeconds - fitEpochSec
        val videoEndFit = videoEndUtcSeconds - fitEpochSec

        val filteredRecords = mutableListOf<FitRecord>()
        val recordMessagesInRange = mutableListOf<DataRecord>()

        for (r in records) {
            when (r) {
                is FitRecord.Definition -> {
                    filteredRecords.add(r)
                }
                is FitRecord.Data -> {
                    val tsField = r.data.fields[253]
                    if (tsField != null && tsField.value != null) {
                        val ts = tsField.value
                        if (ts in videoStartFit..videoEndFit) {
                            filteredRecords.add(r)
                            if (r.globalMessageNumber == 20) {
                                recordMessagesInRange.add(r.data)
                            }
                        }
                    } else {
                        // Keep non-timestamped metadata records
                        filteredRecords.add(r)
                    }
                }
            }
        }

        if (recordMessagesInRange.isEmpty()) {
            throw IllegalArgumentException("No data points fell within the trimmed video timeline.")
        }

        val firstRecordTs = recordMessagesInRange.first().fields[253]?.value ?: 0L
        val lastRecordTs = recordMessagesInRange.last().fields[253]?.value ?: 0L

        var firstDistance = 0L
        var lastDistance = 0L

        val firstDistField = recordMessagesInRange.first().fields[5]
        if (firstDistField?.value != null) {
            firstDistance = firstDistField.value
        }
        val lastDistField = recordMessagesInRange.last().fields[5]
        if (lastDistField?.value != null) {
            lastDistance = lastDistField.value
        }

        val actualDurationSec = lastRecordTs - firstRecordTs
        val actualDistanceM = (lastDistance - firstDistance) / 100.0

        var recordBodySize = 0
        for (r in filteredRecords) {
            recordBodySize += r.bytes.size
        }

        val newFitBytes = ByteArray(headerSize + recordBodySize + 2)
        // 1. Copy Header
        bytes.copyInto(newFitBytes, 0, 0, headerSize)
        // Update header records_size
        setUInt(newFitBytes, 4, recordBodySize.toLong(), littleEndian = true)

        var currentOffset = headerSize
        for (r in filteredRecords) {
            val recBytes = r.bytes.copyOf()
            val recOffset = currentOffset
            recBytes.copyInto(newFitBytes, recOffset)
            currentOffset += recBytes.size

            if (r is FitRecord.Definition) continue

            val dataRec = (r as FitRecord.Data).data

            // Modify distance offset inside record message (message 20)
            if (dataRec.globalMessageNumber == 20) {
                val distField = dataRec.fields[5]
                if (distField?.value != null) {
                    val curDist = distField.value
                    val newDistVal = maxOf(0L, curDist - firstDistance)
                    setUInt(newFitBytes, recOffset + distField.offset, newDistVal, !dataRec.def.isBigEndian)
                }
            }

            // Modify session and lap messages
            if (dataRec.globalMessageNumber == 18 || dataRec.globalMessageNumber == 19) {
                val startTimeField = dataRec.fields[2]
                if (startTimeField != null) {
                    setUInt(newFitBytes, recOffset + startTimeField.offset, firstRecordTs, !dataRec.def.isBigEndian)
                }

                val tsField = dataRec.fields[253]
                if (tsField != null) {
                    setUInt(newFitBytes, recOffset + tsField.offset, lastRecordTs, !dataRec.def.isBigEndian)
                }

                // total_elapsed_time (fieldNum 7): uint32 (ms)
                val elapsedField = dataRec.fields[7]
                if (elapsedField != null) {
                    setUInt(newFitBytes, recOffset + elapsedField.offset, Math.round(actualDurationSec * 1000.0), !dataRec.def.isBigEndian)
                }

                // total_timer_time (fieldNum 8): uint32 (ms)
                val timerField = dataRec.fields[8]
                if (timerField != null) {
                    setUInt(newFitBytes, recOffset + timerField.offset, Math.round(actualDurationSec * 1000.0), !dataRec.def.isBigEndian)
                }

                // total_distance (fieldNum 9): uint32 (cm)
                val totalDistField = dataRec.fields[9]
                if (totalDistField != null) {
                    setUInt(newFitBytes, recOffset + totalDistField.offset, Math.round(actualDistanceM * 100.0), !dataRec.def.isBigEndian)
                }
            }
        }

        // 2. Re-compute Header CRC if present
        if (headerSize == 14) {
            val headCrc = Crc16.calculate(newFitBytes.sliceArray(0..11))
            setUShort(newFitBytes, 12, headCrc, littleEndian = true)
        }

        // 3. Re-compute entire File CRC
        val fileCrc = Crc16.calculate(newFitBytes.sliceArray(0 until (headerSize + recordBodySize)))
        setUShort(newFitBytes, headerSize + recordBodySize, fileCrc, littleEndian = true)

        return newFitBytes
    }

    companion object {
        fun getUShort(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
            val b1 = bytes[offset].toInt() and 0xFF
            val b2 = bytes[offset + 1].toInt() and 0xFF
            return if (littleEndian) {
                (b2 shl 8) or b1
            } else {
                (b1 shl 8) or b2
            }
        }

        fun getUInt(bytes: ByteArray, offset: Int, littleEndian: Boolean): Long {
            val b1 = bytes[offset].toLong() and 0xFF
            val b2 = bytes[offset + 1].toLong() and 0xFF
            val b3 = bytes[offset + 2].toLong() and 0xFF
            val b4 = bytes[offset + 3].toLong() and 0xFF
            return if (littleEndian) {
                (b4 shl 24) or (b3 shl 16) or (b2 shl 8) or b1
            } else {
                (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
            }
        }

        fun getInt(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
            val b1 = bytes[offset].toInt() and 0xFF
            val b2 = bytes[offset + 1].toInt() and 0xFF
            val b3 = bytes[offset + 2].toInt() and 0xFF
            val b4 = bytes[offset + 3].toInt() and 0xFF
            return if (littleEndian) {
                (b4 shl 24) or (b3 shl 16) or (b2 shl 8) or b1
            } else {
                (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
            }
        }

        fun setUInt(bytes: ByteArray, offset: Int, value: Long, littleEndian: Boolean) {
            if (littleEndian) {
                bytes[offset] = (value and 0xFF).toByte()
                bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
                bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
                bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
            } else {
                bytes[offset] = ((value ushr 24) and 0xFF).toByte()
                bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
                bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
                bytes[offset + 3] = (value and 0xFF).toByte()
            }
        }

        fun setUShort(bytes: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
            if (littleEndian) {
                bytes[offset] = (value and 0xFF).toByte()
                bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            } else {
                bytes[offset] = ((value ushr 8) and 0xFF).toByte()
                bytes[offset + 1] = (value and 0xFF).toByte()
            }
        }
    }
}
