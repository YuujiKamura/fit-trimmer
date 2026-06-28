import java.io.ByteArrayOutputStream

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
            }
        }
        val desktopTest by getting {
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
                commandLine("git", "describe", "--tags", "--abbrev=0")
            }.standardOutput.asText.get().trim().removePrefix("v")
        } catch (e: Exception) {
            "1.7.1"
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "FitTrimmer"
            packageVersion = gitVersion

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
