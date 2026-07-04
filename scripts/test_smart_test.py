import unittest
import os
import sys
import tempfile
import sqlite3

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smart_test import SmartTestRunner

class TestSmartTestRunner(unittest.TestCase):
    def setUp(self):
        # Create a temporary database for testing
        self.db_fd, self.db_path = tempfile.mkstemp()
        self.runner = SmartTestRunner(db_path=self.db_path)
        
    def tearDown(self):
        os.close(self.db_fd)
        os.unlink(self.db_path)

    def test_schema_initialization(self):
        # Verify the database has the correct table schema
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='test_history'")
        table = cursor.fetchone()
        self.assertIsNotNone(table, "test_history table should be created")
        conn.close()

    def test_dependency_mapping(self):
        # Test case: shared-core changes should trigger all tests
        changed_files = ["shared-core/src/commonMain/kotlin/fit/HudRenderer.kt"]
        test_classes = {
            "fit.HudRendererTest": "shared-core/src/commonTest/kotlin/fit/HudRendererTest.kt",
            "utils.PlateDetectorTest": "composeApp/src/desktopTest/kotlin/utils/PlateDetectorTest.kt"
        }
        
        # Determine which tests should run
        to_run = self.runner.filter_tests(changed_files, test_classes)
        self.assertIn("fit.HudRendererTest", to_run)
        self.assertIn("utils.PlateDetectorTest", to_run)

    def test_local_dependency_only(self):
        # Test case: composeApp utils changes should trigger PlateDetectorTest but NOT HudRendererTest
        changed_files = ["composeApp/src/desktopMain/kotlin/utils/PlateDetector.kt"]
        test_classes = {
            "fit.HudRendererTest": "shared-core/src/commonTest/kotlin/fit/HudRendererTest.kt",
            "utils.PlateDetectorTest": "composeApp/src/desktopTest/kotlin/utils/PlateDetectorTest.kt"
        }
        
        to_run = self.runner.filter_tests(changed_files, test_classes)
        self.assertNotIn("fit.HudRendererTest", to_run)
        self.assertIn("utils.PlateDetectorTest", to_run)

    def test_test_file_change_itself(self):
        # Test case: Changing a test file should trigger that test
        changed_files = ["composeApp/src/desktopTest/kotlin/utils/PlateDetectorTest.kt"]
        test_classes = {
            "fit.HudRendererTest": "shared-core/src/commonTest/kotlin/fit/HudRendererTest.kt",
            "utils.PlateDetectorTest": "composeApp/src/desktopTest/kotlin/utils/PlateDetectorTest.kt"
        }
        
        to_run = self.runner.filter_tests(changed_files, test_classes)
        self.assertNotIn("fit.HudRendererTest", to_run)
        self.assertIn("utils.PlateDetectorTest", to_run)

    def test_no_changes(self):
        # Test case: No changed files should result in no tests running
        changed_files = []
        test_classes = {
            "fit.HudRendererTest": "shared-core/src/commonTest/kotlin/fit/HudRendererTest.kt",
            "utils.PlateDetectorTest": "composeApp/src/desktopTest/kotlin/utils/PlateDetectorTest.kt"
        }
        
        to_run = self.runner.filter_tests(changed_files, test_classes)
        self.assertEqual(len(to_run), 0, "No tests should run if no files changed")

if __name__ == '__main__':
    unittest.main()
