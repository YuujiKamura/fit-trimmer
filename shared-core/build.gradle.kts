import java.io.IOException

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm("desktop")
    
    @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            binaries.executable()
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val desktopMain by getting {
            dependencies {
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
            }
        }
    }
}

tasks.register<JavaExec>("runFitCLI") {
    group = "application"
    mainClass.set("MainKt")
    val compilation = kotlin.jvm("desktop").compilations.getByName("main")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles
}

tasks.register<Copy>("copyWasmToSrc") {
    from(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    from(layout.buildDirectory.dir("dist/wasmJs/developmentExecutable"))
    into(rootProject.layout.projectDirectory)
    include("*.js", "*.wasm", "*.map")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    doNotTrackState("Copying to root directory which contains untrackable .gradle files")
}

tasks.named("wasmJsBrowserDistribution") {
    finalizedBy("copyWasmToSrc")
}
tasks.named("wasmJsBrowserProductionWebpack") {
    finalizedBy("copyWasmToSrc")
}
tasks.named("wasmJsBrowserDevelopmentWebpack") {
    finalizedBy("copyWasmToSrc")
}
tasks.named("wasmJsBrowserDevelopmentExecutableDistribution") {
    finalizedBy("copyWasmToSrc")
}

val verifyNoFileLock = tasks.register("verifyNoFileLock") {
    val rootDir = project.rootDir
    val buildDirProvider = layout.buildDirectory.asFile
    doFirst {
        val lockFile = File(rootDir, "temp_work/encoding.lock")
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

        val libsDir = File(buildDirProvider.get(), "libs")
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
