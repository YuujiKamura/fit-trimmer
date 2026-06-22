# KMP Migration (shared-core) Implementation Plan

> **For Gemini / Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extract and migrate the core MP4 metadata scanner and FIT file trimming/rebuilding logic from JavaScript/Python into a pure Kotlin Multiplatform (KMP) library targetable to Desktop (JVM) and Web (Wasm).

**Architecture:** A multiplatform library module (`shared-core`) using `kotlinx-io` for target-independent binary buffer operations and `kotlinx-datetime` for handling temporal conversions. Platform targets: Desktop (JVM) and Web (Wasm).

**Tech Stack:** Kotlin 2.0.0, kotlinx-io 0.3.0, kotlinx-datetime 0.6.0, Gradle Wrapper.

---

### Task 1: Setup Gradle Wrapper and Multi-Module Structure

**Files:**
- Create: `settings.gradle.kts` (Root)
- Create: `build.gradle.kts` (Root)
- Create: `gradle.properties` (Root)
- Copy: `gradlew`, `gradlew.bat`, `gradle/wrapper/` from `C:/Users/yuuji/StudioProjects/trianglelist/`

**Step 1: Copy Gradle Wrapper**
Copy the following wrapper files to enable local build without global gradle installation.
Run:
```powershell
Copy-Item -Path C:/Users/yuuji/StudioProjects/trianglelist/gradlew -Destination ./
Copy-Item -Path C:/Users/yuuji/StudioProjects/trianglelist/gradlew.bat -Destination ./
Copy-Item -Path C:/Users/yuuji/StudioProjects/trianglelist/gradle -Destination ./ -Recurse -Force
```

**Step 2: Create settings.gradle.kts**
Write target module mappings including the shared-core module.
Create: `settings.gradle.kts`
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fit-trimmer"
include(":shared-core")
```

**Step 3: Create root build.gradle.kts**
Define root plugin versions.
Create: `build.gradle.kts`
```kotlin
plugins {
    // Gradle plugins for multiplatform
    kotlin("multiplatform") version "2.0.0" apply false
}
```

**Step 4: Create gradle.properties**
Define versions and build configs.
Create: `gradle.properties`
```properties
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
```

**Step 5: Run task check**
Run: `./gradlew tasks`
Expected: SUCCESS with a list of tasks.

**Step 6: Commit**
```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/
git commit -m "infra: setup gradle wrapper and multi-module structure"
```

---

### Task 2: Implement shared-core Build Specification

**Files:**
- Create: `shared-core/build.gradle.kts`

**Step 1: Write multiplatform gradle script**
Set up targets for JVM (Desktop) and Wasm JS/Wasm Wass.
Create: `shared-core/build.gradle.kts`
```kotlin
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm("desktop")
    
    @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.3.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
```

**Step 2: Verify project compilation structure**
Run: `./gradlew :shared-core:compileKotlinDesktop`
Expected: SUCCESS (no source files yet, compiles empty target).

**Step 3: Commit**
```bash
git add shared-core/build.gradle.kts
git commit -m "feat: add shared-core gradle build configuration"
```

---

### Task 3: Migrate CRC-16 Calculation Module

**Files:**
- Create: `shared-core/src/commonMain/kotlin/crc/Crc16.kt`
- Create: `shared-core/src/commonTest/kotlin/crc/Crc16Test.kt`

**Step 1: Implement CRC-16 core helper**
Implement the lookup table and compute function matching the JS `crc16` implementation.
Create: `shared-core/src/commonMain/kotlin/crc/Crc16.kt`
```kotlin
package crc

object Crc16 {
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    fun calculate(bytes: ByteArray, initialCrc: Int = 0): Int {
        var crc = initialCrc
        for (b in bytes) {
            val byteVal = b.toInt() and 0xFF
            // First nibble
            var temp = CRC_TABLE[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor temp xor CRC_TABLE[byteVal and 0xF]
            // Second nibble
            temp = CRC_TABLE[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor temp xor CRC_TABLE[(byteVal ushr 4) and 0xF]
        }
        return crc
    }
}
```

**Step 2: Create unit test for CRC**
Create: `shared-core/src/commonTest/kotlin/crc/Crc16Test.kt`
```kotlin
package crc

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc16Test {
    @Test
    fun testSimpleCrc() {
        val data = "123456789".encodeToByteArray()
        val result = Crc16.calculate(data)
        // Check matching signature value
        assertEquals(0x31C3, result) // replace with expected calculation
    }
}
```

**Step 3: Run unit test**
Run: `./gradlew :shared-core:desktopTest`
Expected: Test runs and passes.

**Step 4: Commit**
```bash
git add shared-core/src/commonMain/kotlin/crc/Crc16.kt shared-core/src/commonTest/kotlin/crc/Crc16Test.kt
git commit -m "feat: migrate FIT CRC-16 implementation and unit tests"
```

---

### Task 4: Migrate MP4 Metadata Parser (Atom Scanner)

**Files:**
- Create: `shared-core/src/commonMain/kotlin/mp4/Mp4Parser.kt`

**Step 1: Write Mp4Parser using kotlinx-io**
Use `Buffer` and `Source` to scan for `moov` and `mvhd` atoms, extracting creationTime and duration.
Create: `shared-core/src/commonMain/kotlin/mp4/Mp4Parser.kt`
```kotlin
package mp4

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readUInt

class Mp4Parser {
    data class Metadata(
        val creationTimeSeconds: Long,
        val timescale: Long,
        val duration: Long
    )

    fun parse(bytes: ByteArray): Metadata? {
        val buffer = Buffer().apply { write(bytes) }
        return scanAtoms(buffer, 0L, bytes.size.toLong())
    }

    private fun scanAtoms(buffer: Buffer, start: Long, end: Long): Metadata? {
        var offset = start
        while (offset < end - 8) {
            val size = buffer.readUInt().toLong()
            val type = buffer.readByteArray(4).decodeToString()
            offset += 8

            if (size <= 0) break

            if (type == "moov" || type == "trak" || type == "mdia") {
                val subEnd = offset + (size - 8)
                val meta = scanAtoms(buffer, offset, subEnd)
                if (meta != null) return meta
            } else if (type == "mvhd") {
                // Parse mvhd version and timestamps
                val version = buffer.readByte().toInt() and 0xFF
                buffer.skip(3) // Flags

                val creationTime: Long
                val timescale: Long
                val duration: Long
                if (version == 1) {
                    buffer.skip(8) // Skip creation time high/low
                    creationTime = buffer.readLong() // modification time (skip)
                    timescale = buffer.readUInt().toLong()
                    duration = buffer.readLong()
                } else {
                    creationTime = buffer.readUInt().toLong()
                    buffer.skip(4) // Skip modification time
                    timescale = buffer.readUInt().toLong()
                    duration = buffer.readUInt().toLong()
                }
                return Metadata(creationTime, timescale, duration)
            } else {
                buffer.skip(size - 8)
            }
            offset += (size - 8)
        }
        return null
    }
}
```

**Step 2: Run build validation**
Run: `./gradlew :shared-core:compileKotlinDesktop`
Expected: SUCCESS

**Step 3: Commit**
```bash
git add shared-core/src/commonMain/kotlin/mp4/Mp4Parser.kt
git commit -m "feat: implement multiplatform MP4 metadata parser"
```

---

### Task 5: Migrate FIT Track Parser and Trimming Logic

**Files:**
- Create: `shared-core/src/commonMain/kotlin/fit/FitParser.kt`

**Step 1: Write FitParser using kotlinx-io**
Port `FitParser` from `parser.js`. Parse header, definitions, data messages, and rebuild targeted slices.
Create: `shared-core/src/commonMain/kotlin/fit/FitParser.kt`
```kotlin
package fit

import kotlinx.io.Buffer

class FitParser {
    // Port binary parser structures
    // Parse records into list of DataRecord / DefRecord representation classes
    // Process timestamp ranges and recalculate distances & summaries.
    // Ensure all output ByteArray payloads contain corrected sizes and checksums.
}
```

**Step 2: Run test checking compile verification**
Run: `./gradlew :shared-core:desktopTest`
Expected: SUCCESS
