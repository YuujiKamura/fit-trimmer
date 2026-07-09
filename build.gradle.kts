plugins {
    // Gradle plugins for multiplatform
    kotlin("multiplatform") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = "UTF-8"
    }
}

val verifyNoStrayFileIO = tasks.register("verifyNoStrayFileIO") {
    group = "verification"
    description = "Verify that no stray direct file I/O operations are used outside the cache system."

    val sourceDirs = project.subprojects.map { sub ->
        sub.file("src")
    }.filter { it.exists() }
    
    inputs.files(sourceDirs)

    doLast {
        val allowedFiles = setOf(
            "PathResolver.kt",
            "CacheRegistry.kt",
            "CacheJobManager.kt",
            "FileSignalCache.kt",
            "HudFileNameFormatter.kt",
            "JobStateManager.kt",
            "PlateCacheManager.kt",
            "Localizer.kt",
            "Main.kt",
            "FitTrimmerMainContent.kt",
            "HudEncodePipeline.kt",
            "WindowsVideoPlayerState.kt",
            "NativeHudEncoder.kt",
            "VideoUtils.kt",
            "PlateDetectionManager.kt",
            "VideoPreviewArea.kt",
            "ControlPlane.kt",
            "DynamicHud.kt",
            "GuiCache.kt",
            "TelemetryAligner.kt",
            "AppViewModel.kt",
            "BatchFolderLoader.kt",
            "BatchQueueCache.kt",
            "CrashSimulator.kt"
        )

        val violations = mutableListOf<String>()

        sourceDirs.forEach { srcDir ->
            srcDir.walk().forEach { file ->
                if (file.extension == "kt" && !file.path.contains("Test") && !allowedFiles.contains(file.name)) {
                    val lines = file.readLines()
                    lines.forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("import") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                            return@forEachIndexed
                        }
                        if (trimmed.contains("File(") || 
                            trimmed.contains(".writeText(") || 
                            trimmed.contains(".writeBytes(") || 
                            (trimmed.contains(".delete()") && !trimmed.contains("lockFile.delete")) || 
                            trimmed.contains(".deleteRecursively()")
                        ) {
                            violations.add("${file.path}:${index + 1} -> $trimmed")
                        }
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "REJECTED: Direct file I/O detected outside approved Cache classes. " +
                "Please use fit.CacheRegistry or fit.PathResolver to manage temp files/cache!\n" +
                violations.joinToString("\n")
            )
        } else {
            println("✅ Architecture verification passed: No stray direct File I/O detected.")
        }
    }
}

subprojects {
    tasks.configureEach {
        if (name.startsWith("compileKotlin")) {
            dependsOn(verifyNoStrayFileIO)
        }
    }
}
