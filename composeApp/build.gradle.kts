import java.io.ByteArrayOutputStream
import java.io.IOException

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

val generateVersionKt = tasks.register("generateVersionKt") {
    val outputDir = layout.buildDirectory.dir("generated/version/fit")
    outputs.dir(outputDir)
    
    doLast {
        val versionFile = outputDir.get().file("Version.kt").asFile
        versionFile.parentFile.mkdirs()
        versionFile.writeText("""
            package fit
            const val APP_VERSION = "v$gitVersion"
        """.trimIndent())
    }
}

kotlin {
    jvm("desktop")
    
    sourceSets {
        val desktopMain by getting {
            kotlin.srcDir(generateVersionKt)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":shared-core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
                implementation("io.github.kdroidfilter:composemediaplayer:0.6.4")
                implementation("net.java.dev.jna:jna:5.14.0")
                implementation("com.microsoft.onnxruntime:onnxruntime:1.18.0")
            }
        }
        val desktopTest by getting {
            resources.srcDirs("src/desktopTest/resources", "src/desktopMain/resources")
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
    }
}

val gitVersion: String by lazy {
    val envVersion = System.getenv("APP_RELEASE_VERSION") ?: System.getProperty("APP_RELEASE_VERSION")
    if (!envVersion.isNullOrBlank()) {
        envVersion.removePrefix("v").trim()
    } else {
        try {
            project.providers.exec {
                workingDir = project.rootDir
                commandLine("git", "describe", "--tags")
            }.standardOutput.asText.get().trim().removePrefix("v")
        } catch (e: Exception) {
            "${project.version}-dev"
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "FitTrimmer"
            packageVersion = gitVersion.split("-")[0]

            windows {
                menu = true
                shortcut = true
                upgradeUuid = "682f6e9f-7fd9-4be6-bb16-3e3da5cf21ab"
                menuGroup = "FitTrimmer"
            }

            // Include network and cryptography modules explicitly to prevent SSL handshake crashes in packaged runtime
            modules("java.net.http", "jdk.crypto.ec")
        }
    }
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
    }
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val verifyNoFileLock = tasks.register("verifyNoFileLock") {
    doFirst {
        val lockFile = File(project.rootDir, "temp_work/encoding.lock")
        var isEncodingActive = false
        if (lockFile.exists()) {
            try {
                lockFile.outputStream().close()
            } catch (e: IOException) {
                isEncodingActive = true
            }
        }

        if (isEncodingActive) {
            throw GradleException(
                "REJECTED: FitTrimmer is currently executing an encoding job (temp_work/encoding.lock is locked). " +
                "Please wait for encoding to finish or cancel it in the app before cleaning/building!"
            )
        }

        val libsDir = layout.buildDirectory.dir("libs").get().asFile
        if (libsDir.exists()) {
            libsDir.listFiles()?.forEach { file ->
                if (file.extension == "jar") {
                    try {
                        file.outputStream().close()
                    } catch (e: IOException) {
                        println("WARNING: File '${file.name}' is locked by an active process, but no active encoding job was detected. Proceeding...")
                    }
                }
            }
        }
    }
}

tasks.named("clean") {
    dependsOn(verifyNoFileLock)
}

val desktopPlaybackSmoke = tasks.register<Exec>("desktopPlaybackSmoke") {
    group = "verification"
    description = "Runs the playback GUI smoke test using PowerShell."
    workingDir = project.rootDir
    commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/desktop-playback-smoke.ps1")
}

