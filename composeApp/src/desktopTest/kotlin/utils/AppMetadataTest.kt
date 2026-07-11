package utils

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import com.sun.jna.Native
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

interface TestShell32 : StdCallLibrary {
    fun GetCurrentProcessExplicitAppUserModelID(ppszAppID: PointerByReference): Int

    companion object {
        val INSTANCE = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            Native.load("shell32", TestShell32::class.java) as TestShell32
        } else {
            null
        }
    }
}

class AppMetadataTest {

    @Test
    fun testIconResourceExists() {
        val resource = javaClass.classLoader.getResource("icon.png")
        assertNotNull(resource, "icon.png resource must exist in desktopMain resources")
    }

    @Test
    fun testAppUserModelIdSetting() {
        val os = System.getProperty("os.name")
        if (os.startsWith("Windows", ignoreCase = true)) {
            // Execute the metadata setter
            AppMetadata.setAppUserModelId()

            // Verify using Windows API
            val ptrRef = PointerByReference()
            val hr = TestShell32.INSTANCE!!.GetCurrentProcessExplicitAppUserModelID(ptrRef)
            assertEquals(0, hr, "GetCurrentProcessExplicitAppUserModelID must return S_OK (0)")

            val ptr = ptrRef.value
            try {
                val currentAumid = ptr.getWideString(0)
                assertEquals("YuujiKamura.FitTrimmer", currentAumid, "AUMID must be explicitly set to YuujiKamura.FitTrimmer")
            } finally {
                // Free the co-allocated memory using Ole32 if needed, but not strictly required for test exit
            }
        }
    }
}
