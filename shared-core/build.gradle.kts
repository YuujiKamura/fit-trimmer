plugins {
    kotlin("multiplatform")
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
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.register<Copy>("copyWasmToSrc") {
    from(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    from(layout.buildDirectory.dir("dist/wasmJs/developmentExecutable"))
    into(rootProject.layout.projectDirectory.dir("src"))
    include("*.js", "*.wasm", "*.map")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
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
