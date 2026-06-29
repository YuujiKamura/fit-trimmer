import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UpdateManagerTest {
    @Test
    fun testIsDevelopmentInTestEnvironment() {
        assertTrue(UpdateManager.isDevelopment(), "Test environment should be detected as development environment")
    }

    @Test
    fun testIsDevelopmentPath() {
        // 開発環境のパス
        assertTrue(UpdateManager.isDevelopmentPath("C:\\Users\\yuuji\\fit-trimmer\\composeApp\\build\\libs\\composeApp-desktop.jar"), "Gradle build libs jar should be development")
        assertTrue(UpdateManager.isDevelopmentPath("C:\\Users\\yuuji\\fit-trimmer\\composeApp\\build\\classes\\kotlin\\desktop\\main"), "Classes dir should be development")
        assertTrue(UpdateManager.isDevelopmentPath("/Users/yuuji/fit-trimmer/composeApp/build/libs/composeApp-desktop.jar"), "Mac Gradle build libs jar should be development")
        
        // リリース環境（インストール済み）のパス
        assertFalse(UpdateManager.isDevelopmentPath("C:\\Program Files\\FitTrimmer\\app\\FitTrimmer.jar"), "Installed program jar should not be development")
        assertFalse(UpdateManager.isDevelopmentPath("C:\\Program Files\\FitTrimmer\\FitTrimmer.exe"), "Installed program exe should not be development")
        assertFalse(UpdateManager.isDevelopmentPath("/Applications/FitTrimmer.app/Contents/Resources/FitTrimmer.jar"), "Installed Mac app jar should not be development")
    }
}
