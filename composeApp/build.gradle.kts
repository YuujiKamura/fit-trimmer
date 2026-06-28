plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

kotlin {
    jvm("desktop")
    
    sourceSets {
        val desktopMain by getting {
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

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "FitTrimmer"
            packageVersion = "1.6.0"

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
