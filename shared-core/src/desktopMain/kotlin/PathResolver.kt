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

    /**
     * Returns the unified temp_work directory located at the project root.
     */
    fun getTempWorkDir(): File {
        return File(getProjectRoot(), "temp_work")
    }

    /**
     * Returns the unified tmp_hud directory located at the project root.
     */
    fun getTmpHudDir(): File {
        return File(getProjectRoot(), "tmp_hud")
    }
}
