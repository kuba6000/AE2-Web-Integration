"""Exercise the credential-free Gradle preview through its process/HTTP boundaries."""

import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import urlopen, Request


class ReleasePreviewTest(unittest.TestCase):
    def test_preview_supplies_local_metadata_but_refuses_upload_requests(self):
        script = Path(__file__).resolve().parents[1] / "release_preview.py"
        spec = importlib.util.spec_from_file_location("release_preview", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        observed = {}

        def gradle_process(command, **kwargs):
            endpoint = next(argument.split("=", 1)[1] for argument in command
                            if argument.startswith("-PreleaseCurseforgeApiEndpoint="))
            with urlopen(endpoint + "/api/game/versions", timeout=5) as response:
                versions = json.load(response)
            observed["names"] = {entry["name"] for entry in versions}
            observed["dry_run"] = "-PreleaseDryRun=true" in command
            with urlopen(endpoint + "/api/game/version-types", timeout=5) as response:
                observed["types"] = {entry["slug"] for entry in json.load(response)}
            with self.assertRaises(HTTPError) as failure:
                urlopen(Request(endpoint + "/api/projects/1122761/upload-file", data=b"no upload"), timeout=5)
            observed["upload_status"] = failure.exception.code
            return subprocess.CompletedProcess(command, 0)

        with patch("subprocess.run", side_effect=gradle_process):
            module.main(["--minecraft", "1.21.1", "--release-type", "release", "--notes", "build/release/notes.md"])
        self.assertEqual(observed["names"], {"1.21.1", "NeoForge", "Java 21", "Server"})
        self.assertEqual(observed["types"], {"minecraft", "java", "modloader", "environment"})
        self.assertTrue(observed["dry_run"])
        self.assertEqual(observed["upload_status"], 405)


if __name__ == "__main__":
    unittest.main()
