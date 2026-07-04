import os
import sys
import sqlite3
import subprocess
from datetime import datetime

class SmartTestRunner:
    def __init__(self, db_path=None):
        if db_path is None:
            # Default path inside project structure
            scratch_dir = os.path.join("composeApp", "scratch")
            if not os.path.exists(scratch_dir):
                os.makedirs(scratch_dir)
            self.db_path = os.path.join(scratch_dir, "test_history.db")
        else:
            self.db_path = db_path
            
        self._init_db()

    def _init_db(self):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS test_history (
                test_class TEXT PRIMARY KEY,
                last_success_commit TEXT NOT NULL,
                last_run_timestamp TEXT NOT NULL
            )
        """)
        conn.commit()
        conn.close()

    def get_last_success_commit(self, test_class):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT last_success_commit FROM test_history WHERE test_class = ?", (test_class,))
        row = cursor.fetchone()
        conn.close()
        return row[0] if row else None

    def update_success_commit(self, test_class, commit_hash):
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        now_str = datetime.now().isoformat()
        cursor.execute("""
            INSERT INTO test_history (test_class, last_success_commit, last_run_timestamp)
            VALUES (?, ?, ?)
            ON CONFLICT(test_class) DO UPDATE SET
                last_success_commit = excluded.last_success_commit,
                last_run_timestamp = excluded.last_run_timestamp
        """, (test_class, commit_hash, now_str))
        conn.commit()
        conn.close()

    def filter_tests(self, changed_files, test_classes):
        """
        Filters the test_classes to only return those that are affected by changed_files.
        """
        if not changed_files:
            return []

        # If any build configuration file changes, run all tests
        global_triggers = [
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "local.properties",
            "gradlew",
            "gradlew.bat"
        ]
        if any(f in changed_files for f in global_triggers):
            return list(test_classes.keys())

        # If shared-core main sources change, run all tests (since composeApp depends on shared-core)
        if any(f.startswith("shared-core/src/commonMain/") or f.startswith("shared-core/src/desktopMain/") for f in changed_files):
            return list(test_classes.keys())

        to_run = []
        for test_class, test_file in test_classes.items():
            # 1. If the test file itself is changed, run it
            if test_file in changed_files:
                to_run.append(test_class)
                continue

            # 2. Check component level dependencies
            is_affected = False
            
            # For shared-core tests
            if test_file.startswith("shared-core/"):
                # We already handled shared-core main changes above.
                # If there are changes in test resources or other test code, run it.
                if any(f.startswith("shared-core/src/commonTest/") or f.startswith("shared-core/src/desktopTest/") for f in changed_files):
                    is_affected = True

            # For composeApp tests
            elif test_file.startswith("composeApp/"):
                # Determine sub-module/package from test file path
                # e.g., composeApp/src/desktopTest/kotlin/utils/PlateDetectorTest.kt -> utils
                parts = test_file.split("/")
                if "desktopTest" in parts:
                    idx = parts.index("desktopTest")
                    # Usually: ["composeApp", "src", "desktopTest", "kotlin", "utils", "PlateDetectorTest.kt"]
                    if len(parts) > idx + 2:
                        package_dir = parts[idx + 2] # e.g. "utils", "viewmodel", "components"
                        
                        # Check if main source code in the same package is changed
                        # e.g. composeApp/src/desktopMain/kotlin/utils/
                        main_package_prefix = f"composeApp/src/desktopMain/kotlin/{package_dir}/"
                        test_package_prefix = f"composeApp/src/desktopTest/kotlin/{package_dir}/"
                        
                        if any(f.startswith(main_package_prefix) or f.startswith(test_package_prefix) for f in changed_files):
                            is_affected = True
                            
                        # Special case: VideoPreviewAreaTest also depends on main preview components
                        if package_dir == "components":
                            # If FitTrimmerMainContent.kt changes, rerun UI preview components test
                            if "composeApp/src/desktopMain/kotlin/FitTrimmerMainContent.kt" in changed_files:
                                is_affected = True
                                
                        # Special case: Main AppViewModel might be affected by main layout changes
                        if package_dir == "viewmodel":
                            if "composeApp/src/desktopMain/kotlin/FitTrimmerMainContent.kt" in changed_files:
                                is_affected = True

            # Fallback safety: If we can't classify the change, run it to be safe
            # except if it is entirely unrelated (like markdown files or temp files)
            if not is_affected:
                unclassified_changes = False
                for f in changed_files:
                    # Ignore documentation, Python files, scripts, temp files, and markdown
                    if f.endswith(".md") or f.endswith(".py") or f.endswith(".bat") or f.endswith(".png") or f.endswith(".jpg") or f.startswith(".agents/") or f.startswith("scratch/") or f.startswith("temp_work/"):
                        continue
                    # If some Kotlin/Java/resource file in the module changed and wasn't caught, trigger
                    if f.startswith("shared-core/") and test_file.startswith("shared-core/"):
                        unclassified_changes = True
                    elif f.startswith("composeApp/") and test_file.startswith("composeApp/"):
                        unclassified_changes = True
                
                if unclassified_changes:
                    is_affected = True

            if is_affected:
                to_run.append(test_class)

        return to_run

    def discover_tests(self):
        """
        Discovers all test files (*Test.kt) in the project and returns a dict mapping test class names to file paths.
        """
        test_classes = {}
        # Search in shared-core and composeApp
        for module in ["shared-core", "composeApp"]:
            src_dir = os.path.join(module, "src")
            if not os.path.exists(src_dir):
                continue
            for root, _, files in os.walk(src_dir):
                for f in files:
                    if f.endswith("Test.kt"):
                        full_path = os.path.join(root, f)
                        rel_path = os.path.relpath(full_path, ".").replace("\\", "/")
                        
                        # Determine class name
                        # We can parse the package name from the file content or infer from path
                        # Infer package name from path after "kotlin/"
                        parts = rel_path.split("/")
                        if "kotlin" in parts:
                            idx = parts.index("kotlin")
                            package_parts = parts[idx + 1:-1]
                            class_name = f.replace(".kt", "")
                            if package_parts:
                                full_class = ".".join(package_parts) + "." + class_name
                            else:
                                full_class = class_name
                            test_classes[full_class] = rel_path
        return test_classes

    def get_git_changes(self, since_commit=None):
        """
        Returns a list of changed files compared to since_commit, plus any uncommitted changes.
        """
        changed_files = set()
        
        # 1. Uncommitted changes (staged and unstaged)
        try:
            unstaged = subprocess.check_output(["git", "diff", "--name-only"], text=True)
            staged = subprocess.check_output(["git", "diff", "--cached", "--name-only"], text=True)
            untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], text=True)
            
            for line in (unstaged + staged + untracked).splitlines():
                if line.strip():
                    changed_files.add(line.strip().replace("\\", "/"))
        except Exception as e:
            print(f"WARN: Failed to read uncommitted changes: {e}")

        # 2. Changes since last successful commit
        if since_commit:
            try:
                # Verify commit exists
                subprocess.check_call(["git", "cat-file", "-e", since_commit], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                diff_output = subprocess.check_output(["git", "diff", "--name-only", since_commit], text=True)
                for line in diff_output.splitlines():
                    if line.strip():
                        changed_files.add(line.strip().replace("\\", "/"))
            except subprocess.CalledProcessError:
                # If commit doesn't exist, we must assume all files changed
                print(f"WARN: Reference commit {since_commit} not found in history.")
                return None
            except Exception as e:
                print(f"WARN: Failed to read git diff for commit {since_commit}: {e}")
                
        return list(changed_files)

    def run(self, force=False):
        """
        Executes the smart testing flow.
        """
        # Get current git commit hash
        try:
            current_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        except Exception as e:
            print(f"ERROR: Failed to get current git commit: {e}")
            return False

        test_classes = self.discover_tests()
        print(f"[INFO] Discovered {len(test_classes)} test classes:")
        for tc, path in test_classes.items():
            print(f"  - {tc} ({path})")

        if force:
            print("[INFO] Force run requested. Running all tests.")
            tests_to_run = list(test_classes.keys())
        else:
            tests_to_run = set()
            
            # For each test class, compute diff since its last success
            for tc, test_file in test_classes.items():
                last_commit = self.get_last_success_commit(tc)
                if not last_commit:
                    print(f"[NEW] No run history for {tc}. Must run.")
                    tests_to_run.add(tc)
                    continue
                    
                changed_files = self.get_git_changes(last_commit)
                if changed_files is None:
                    # History commit invalid, must rerun
                    tests_to_run.add(tc)
                    continue
                    
                affected = self.filter_tests(changed_files, {tc: test_file})
                if affected:
                    print(f"[WARN] {tc} is affected by changes since success ({last_commit[:8]}):")
                    # Print first few changed files for context
                    for f in list(changed_files)[:5]:
                        print(f"    - {f}")
                    if len(changed_files) > 5:
                        print(f"    - ... and {len(changed_files)-5} more files")
                    tests_to_run.add(tc)
                else:
                    print(f"[OK] {tc} is UP-TO-DATE (last success: {last_commit[:8]}, no relevant changes).")

            tests_to_run = list(tests_to_run)

        if not tests_to_run:
            print("\n[OK] All tests are UP-TO-DATE. Nothing to run!")
            return True

        print(f"\n[RUN] Executing {len(tests_to_run)} affected test classes:")
        for tc in tests_to_run:
            print(f"  - {tc}")

        # Construct Gradle test commands
        # Separate into shared-core and composeApp targets to run correct Gradle tasks
        shared_core_tests = [tc for tc in tests_to_run if test_classes[tc].startswith("shared-core/")]
        compose_app_tests = [tc for tc in tests_to_run if test_classes[tc].startswith("composeApp/")]

        gradle_args = ["gradlew.bat" if os.name == "nt" else "./gradlew"]
        
        # Clean build cache rules to comply with "Test Verification before Claiming Completion"
        # but only for tasks we actually execute
        gradle_args.append("--no-build-cache")
        gradle_args.append("--rerun-tasks")

        if shared_core_tests:
            gradle_args.append(":shared-core:desktopTest")
            for tc in shared_core_tests:
                gradle_args.append(f"--tests")
                gradle_args.append(tc)
        
        if compose_app_tests:
            gradle_args.append(":composeApp:desktopTest")
            for tc in compose_app_tests:
                gradle_args.append(f"--tests")
                gradle_args.append(tc)

        print(f"\n[CMD] Command: {' '.join(gradle_args)}")
        try:
            # Run test task
            res = subprocess.run(gradle_args)
            if res.returncode == 0:
                print("\n[SUCCESS] Tests passed successfully!")
                # Update DB with current commit hash for all successfully run tests
                # Note: If untracked/uncommitted changes exist, we don't save history,
                # as the commit hash doesn't fully represent the workspace state.
                has_uncommitted = False
                try:
                    status = subprocess.check_output(["git", "status", "--porcelain"], text=True)
                    if status.strip():
                        has_uncommitted = True
                except:
                    pass
                    
                if has_uncommitted:
                    print("[WARN] Uncommitted changes exist. Test history not updated (requires clean commit to cache).")
                else:
                    for tc in tests_to_run:
                        self.update_success_commit(tc, current_commit)
                    print(f"[SAVE] Saved test success state at commit {current_commit[:8]}.")
                return True
            else:
                print("\n[ERROR] Test execution failed.")
                return False
        except Exception as e:
            print(f"\n[ERROR] Execution error: {e}")
            return False

if __name__ == "__main__":
    force_run = "--force" in sys.argv
    runner = SmartTestRunner()
    success = runner.run(force=force_run)
    sys.exit(0 if success else 1)
