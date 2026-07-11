package utils

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary

interface Shell32 : StdCallLibrary {
    fun SetCurrentProcessExplicitAppUserModelID(appID: com.sun.jna.WString): Int

    companion object {
        val INSTANCE = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            Native.load("shell32", Shell32::class.java) as Shell32
        } else {
            null
        }
    }
}

object AppMetadata {
    fun setAppUserModelId() {
        val os = System.getProperty("os.name")
        if (os.startsWith("Windows", ignoreCase = true)) {
            try {
                val appID = com.sun.jna.WString("YuujiKamura.FitTrimmer")
                val hr = Shell32.INSTANCE?.SetCurrentProcessExplicitAppUserModelID(appID) ?: -1
                if (hr != 0) {
                    System.err.println("SetCurrentProcessExplicitAppUserModelID failed with hr=$hr")
                }
            } catch (e: Throwable) {
                System.err.println("Failed to set AppUserModelID: ${e.message}")
            }
        }
    }
}
