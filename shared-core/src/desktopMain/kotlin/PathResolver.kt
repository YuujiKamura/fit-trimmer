package fit

import java.io.File

object PathResolver {
    /**
     * Resolves the unified project root directory.
     * Handles cases where the current working directory (user.dir) is either the root folder,
     * or one of the submodules (composeApp / shared-core).
     */
    fun getProjectRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        return if (userDir.name == "composeApp" || userDir.name == "shared-core") {
            userDir.parentFile
        } else {
            userDir
        }
    }

    private fun isGoogleDrivePath(path: String): Boolean {
        val normalized = path.replace("\\", "/").lowercase()
        return normalized.contains("google drive") || 
               normalized.contains("マイドライブ") || 
               normalized.contains("my drive") ||
               normalized.startsWith("g:/") || 
               normalized.startsWith("h:/")
    }

    /**
     * Returns the temp_work directory.
     * If [videoPath] is provided and it is a writable local drive (not on Google Drive),
     * returns a "temp_work" directory inside the video file's parent directory to avoid
     * writing to C: drive and to speed up file copy/moves on the same drive.
     */
    fun getTempWorkDir(videoPath: String? = null): File {
        if (!videoPath.isNullOrEmpty()) {
            try {
                if (!isGoogleDrivePath(videoPath)) {
                    val videoFile = File(videoPath)
                    val parentDir = videoFile.parentFile
                    if (parentDir != null && parentDir.exists() && parentDir.canWrite()) {
                        val localTemp = File(parentDir, "temp_work")
                        if (localTemp.exists() || localTemp.mkdirs()) {
                            return localTemp
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback to project root on any resolution error
            }
        }
        return File(getProjectRoot(), "temp_work")
    }

    /**
     * Returns the unified tmp_hud directory located at the project root.
     */
    fun getTmpHudDir(): File {
        return File(getProjectRoot(), "tmp_hud")
    }
}
