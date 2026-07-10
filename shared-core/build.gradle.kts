import java.io.IOException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
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

abstract class CopyWasmToSrcTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyFiles() {
        val destinationDir = outputDir.get().asFile
        sourceFiles.files.forEach { source ->
            source.copyTo(File(destinationDir, source.name), overwrite = true)
        }
    }
}

tasks.register<CopyWasmToSrcTask>("copyWasmToSrc") {
    sourceFiles.from(
        fileTree(layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) {
            include("*.js", "*.wasm", "*.map")
        },
        fileTree(layout.buildDirectory.dir("dist/wasmJs/developmentExecutable")) {
            include("*.js", "*.wasm", "*.map")
        }
    )
    outputDir.set(rootProject.layout.projectDirectory)
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
