"""Run the real e2e launcher with fake tools and check its child server exits."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest


class E2eCleanupTest(unittest.TestCase):
    def test_server_is_reaped_after_success_and_test_failure(self):
        source = Path(__file__).resolve().parents[1]
        for test_exit in (0, 1):
            with self.subTest(test_exit=test_exit), tempfile.TemporaryDirectory(prefix="fp-launcher-test-") as tmp:
                root = Path(tmp)
                (root / "tools").mkdir()
                for script in ("test-e2e.sh", "lib.sh"):
                    (root / "tools" / script).write_bytes((source / script).read_bytes())
                (root / "server/target").mkdir(parents=True)
                (root / "server/target/fuelprint-server-0.1.0-SNAPSHOT.jar").touch()
                (root / "apps/node_modules").mkdir(parents=True)
                (root / "apps/operations/e2e").mkdir(parents=True)
                fake_bin = root / "bin"
                fake_bin.mkdir()
                scripts = {
                    "java": '#!/bin/sh\nprintf "%s" "$$" > "$TEST_SERVER_PID_FILE"\nexec /bin/sleep 30\n',
                    "python3": '#!/bin/sh\nprintf "54321\\n"\n',
                    "curl": '#!/bin/sh\ntest -s "$TEST_SERVER_PID_FILE"\n',
                    "npm": '#!/bin/sh\nexit "$TEST_NPM_EXIT"\n',
                }
                for name, content in scripts.items():
                    executable = fake_bin / name
                    executable.write_text(content)
                    executable.chmod(0o755)
                pid_file = root / "server.pid"
                env = dict(os.environ, PATH=str(fake_bin) + os.pathsep + os.environ["PATH"],
                           TEST_SERVER_PID_FILE=str(pid_file), TEST_NPM_EXIT=str(test_exit))
                pid = None
                try:
                    result = subprocess.run(["/bin/bash", str(root / "tools/test-e2e.sh")],
                                            env=env, capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, test_exit, result.stdout + result.stderr)
                    pid = int(pid_file.read_text())
                    deadline = time.monotonic() + 2
                    while self.alive(pid) and time.monotonic() < deadline:
                        time.sleep(0.02)
                    self.assertFalse(self.alive(pid), f"e2e launcher left server {pid} alive")
                finally:
                    if pid is None and pid_file.exists():
                        pid = int(pid_file.read_text())
                    if pid is not None and self.alive(pid):
                        os.kill(pid, signal.SIGTERM)

    @staticmethod
    def alive(pid):
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False


if __name__ == "__main__":
    unittest.main()
