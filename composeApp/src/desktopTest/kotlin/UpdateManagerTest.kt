import kotlin.test.Test
import kotlin.test.assertTrue

class UpdateManagerTest {
    @Test
    fun testIsDevelopmentInTestEnvironment() {
        assertTrue(UpdateManager.isDevelopment(), "Test environment should be detected as development environment")
    }
}
