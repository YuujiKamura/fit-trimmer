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
                implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.3.0")
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
    }
}

tasks.register<JavaExec>("runFitCLI") {
    group = "application"
    mainClass.set("MainKt")
    val compilation = kotlin.jvm("desktop").compilations.getByName("main")
    classpath = compilation.output.classesDirs + compilation.runtimeDependencyFiles
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
