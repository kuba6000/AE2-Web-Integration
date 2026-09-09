import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "update_core_pin.py"
BOT_NAME = "KLANKER | kuba6000"
BOT_EMAIL = "agent@kuba6000.pl"


class UpdateCorePinTest(unittest.TestCase):

    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary_directory.name) / "repository"
        self.repository.mkdir()

        self.git("init")
        self.git("config", "user.name", "Test Setup")
        self.git("config", "user.email", "setup@example.invalid")
        self.git("config", "commit.gpgsign", "false")

        (self.repository / "README.md").write_text("adapter\n", encoding="utf-8")
        self.git("add", "README.md")
        self.git("commit", "-m", "Create adapter branch")
        self.old_core_sha = self.git("rev-parse", "HEAD").stdout.strip()

        (self.repository / "core-change.txt").write_text("new core\n", encoding="utf-8")
        self.git("add", "core-change.txt")
        self.git("commit", "-m", "Change core")
        self.new_core_sha = self.git("rev-parse", "HEAD").stdout.strip()

    def tearDown(self):
        self.temporary_directory.cleanup()

    def git(self, *arguments):
        return subprocess.run(
            ["git", "-C", str(self.repository), *arguments],
            check=True,
            capture_output=True,
            text=True,
        )

    def pin_core(self, core_sha):
        self.git("update-index", "--add", "--cacheinfo", f"160000,{core_sha},core")
        self.git("commit", "-m", "Pin core")
        (self.repository / "core").mkdir()
        return self.git("rev-parse", "HEAD").stdout.strip()

    def run_updater(self, core_sha):
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--repository",
                str(self.repository),
                "--core-sha",
                core_sha,
                "--author-name",
                BOT_NAME,
                "--author-email",
                BOT_EMAIL,
            ],
            capture_output=True,
            text=True,
        )

    def test_updates_only_core_gitlink_with_requested_author(self):
        previous_head = self.pin_core(self.old_core_sha)

        result = self.run_updater(self.new_core_sha)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotEqual(previous_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(self.new_core_sha, self.git("rev-parse", "HEAD:core").stdout.strip())
        self.assertEqual("core", self.git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD").stdout.strip())
        self.assertEqual(
            [BOT_NAME, BOT_EMAIL, BOT_NAME, BOT_EMAIL],
            self.git("log", "-1", "--format=%an%x00%ae%x00%cn%x00%ce").stdout.strip().split("\0"),
        )
        self.assertEqual("", self.git("status", "--porcelain").stdout)

    def test_current_pin_does_not_create_a_commit(self):
        previous_head = self.pin_core(self.new_core_sha)

        result = self.run_updater(self.new_core_sha)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(previous_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual("", self.git("status", "--porcelain").stdout)


if __name__ == "__main__":
    unittest.main()
