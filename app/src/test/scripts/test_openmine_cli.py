"""Host-side CLI contract tests; these do not establish Android service survival."""

from contextlib import contextmanager, redirect_stderr, redirect_stdout
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[4] / "scripts" / "openmine"
LOADER = importlib.machinery.SourceFileLoader("openmine_cli", str(SCRIPT))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
cli = importlib.util.module_from_spec(SPEC)
LOADER.exec_module(cli)
TOKEN = "test-private-token-" + "a" * 48


@contextmanager
def server(response):
    """One-request loopback fixture; record actual framing, not a mock send call."""
    received = []
    failures = []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(2)

        def respond():
            try:
                connection, _ = listener.accept()
                with connection:
                    connection.settimeout(2)
                    with connection.makefile("rb") as stream:
                        received.append(stream.readline(cli.MAX_REQUEST_BYTES + 1))
                    connection.sendall(response)
            except Exception as error:
                failures.append(error)

        thread = threading.Thread(target=respond, daemon=True)
        thread.start()
        try:
            with mock.patch.object(cli, "ADDRESS", listener.getsockname()):
                yield received
        finally:
            thread.join(3)
            if thread.is_alive():
                raise AssertionError("Loopback fixture did not complete")
            if failures:
                raise failures[0]


class OpenMineCliTests(unittest.TestCase):
    def invoke(self, *argv):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = cli.main(list(argv))
        return code, stdout.getvalue(), stderr.getvalue()

    def test_configure_uses_private_file_and_hidden_prompt(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "config" / "config.json"
            with mock.patch.object(cli, "config_path", return_value=path), mock.patch.object(
                cli.getpass, "getpass", return_value=TOKEN
            ) as prompt:
                code, out, err = self.invoke("configure")
                self.assertEqual(code, 0, err)
                self.assertEqual(cli.load_token(), TOKEN)
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
                self.assertNotIn(TOKEN, out + err)
                prompt.assert_called_once()

    def test_insecure_config_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "config.json"
            path.write_text(json.dumps({"token": TOKEN}))
            path.chmod(0o644)
            with mock.patch.object(cli, "config_path", return_value=path):
                code, out, err = self.invoke("status")
            self.assertEqual(code, 1)
            self.assertIn("chmod 600", err)
            self.assertNotIn(TOKEN, out + err)

    def test_real_loopback_json_line_roundtrip(self):
        with server(b'{"ok":true,"running":true,"processes":[]}\n') as received:
            result = cli.request(TOKEN, "status")
        self.assertTrue(result["running"])
        self.assertEqual(len(received), 1)
        self.assertTrue(received[0].endswith(b"\n"))
        self.assertEqual(json.loads(received[0]), {"token": TOKEN, "action": "status"})

    def test_socket_connect_is_bounded_and_loopback_only(self):
        with mock.patch.object(cli.socket, "create_connection", side_effect=ConnectionRefusedError) as connect:
            with self.assertRaises(cli.UnreachableError):
                cli.request(TOKEN, "status")
        connect.assert_called_once_with(("127.0.0.1", 8766), timeout=2)

    def test_large_request_is_rejected_before_connect(self):
        with mock.patch.object(cli.socket, "create_connection") as connect:
            with self.assertRaisesRegex(cli.BridgeError, "16384"):
                cli.request(TOKEN, "shell", command="x" * 16384)
        connect.assert_not_called()

    def test_invalid_response_is_not_success(self):
        for response in (b'[]\n', b'{"ok":"true"}\n', b'not json\n', b'{"ok":true}'):
            with self.subTest(response=response), server(response):
                with self.assertRaises(cli.BridgeError):
                    cli.request(TOKEN, "status")

    def test_unauthorized_response_fails_and_redacts_token(self):
        response = json.dumps({"ok": False, "error": "Unauthorized " + TOKEN}).encode() + b"\n"
        with server(response), mock.patch.object(cli, "load_token", return_value=TOKEN):
            code, out, err = self.invoke("status")
        self.assertEqual(code, 1)
        self.assertIn("Unauthorized", err)
        self.assertNotIn(TOKEN, out + err)

    def test_disconnected_status_is_nonzero_and_explicit(self):
        with mock.patch.object(cli, "load_token", return_value=TOKEN), mock.patch.object(
            cli, "request", side_effect=cli.UnreachableError("Not connected")
        ):
            code, out, err = self.invoke("status")
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(out)["state"], "stopped_or_unreachable")
        self.assertFalse(json.loads(out)["running"])

    def test_start_uses_explicit_android_broadcast_and_verifies_status(self):
        complete = subprocess.CompletedProcess([], 0, stdout=TOKEN)
        with mock.patch.object(cli, "load_token", return_value=TOKEN), mock.patch.object(
            cli.subprocess, "run", return_value=complete
        ) as launch, mock.patch.object(cli, "request", return_value={"ok": True, "running": True}) as request:
            code, out, err = self.invoke("start")
        self.assertEqual(code, 0, err)
        self.assertEqual(launch.call_args.args[0], [
            "/system/bin/am", "broadcast", "--user", "0", "-n",
            "com.openmine/.sandbox.RuntimeControlReceiver", "-a", "com.openmine.runtime.START",
            "--es", "token", TOKEN,
        ])
        self.assertNotIn("shell", launch.call_args.kwargs)
        request.assert_called_once()
        self.assertEqual(request.call_args.args, (TOKEN, "status"))
        self.assertNotIn(TOKEN, out + err)

    def test_broadcast_success_does_not_hide_unauthorized_bridge(self):
        with mock.patch.object(cli, "load_token", return_value=TOKEN), mock.patch.object(
            cli.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)
        ), mock.patch.object(cli, "request", side_effect=cli.BridgeError("Unauthorized")) as request:
            code, out, err = self.invoke("start")
        self.assertEqual(code, 1)
        self.assertEqual(out, "")
        self.assertIn("Unauthorized", err)
        request.assert_called_once()

    def test_start_polling_has_a_deadline(self):
        with mock.patch.object(cli, "load_token", return_value=TOKEN), mock.patch.object(
            cli.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)
        ), mock.patch.object(cli, "request", side_effect=cli.UnreachableError("Not connected")), mock.patch.object(
            cli.time, "monotonic", side_effect=[0, 0, 0, 2]
        ), mock.patch.object(cli.time, "sleep"):
            code, out, err = self.invoke("start", "--timeout", "1")
        self.assertEqual(code, 1)
        self.assertEqual(out, "")
        self.assertIn("Android may restrict", err)

    def test_shell_preserves_command_output_and_exit_status(self):
        response = {"ok": True, "result": {"success": False, "stdout": "hello", "stderr": "failure", "exit_code": 7}}
        with mock.patch.object(cli, "load_token", return_value=TOKEN), mock.patch.object(
            cli, "request", return_value=response
        ) as request:
            code, out, err = self.invoke("shell", "--timeout", "5", "printf hello; exit 7")
        self.assertEqual((code, out, err), (7, "hello\n", "failure\n"))
        request.assert_called_once_with(TOKEN, "shell", timeout=10, command="printf hello; exit 7", timeout_seconds=5)

    def test_shell_timeout_exit_code_and_missing_result(self):
        for result, expected in (({"success": False, "exit_code": 124}, 124), (None, 1)):
            with self.subTest(result=result), mock.patch.object(cli, "load_token", return_value=TOKEN), mock.patch.object(
                cli, "request", return_value={"ok": True, "result": result}
            ):
                self.assertEqual(self.invoke("shell", "sleep 9999")[0], expected)

    def test_repl_uses_shared_shell_and_skips_empty_lines(self):
        response = {"ok": True, "result": {"success": True, "stdout": "", "stderr": "", "exit_code": 0}}
        with mock.patch.object(cli, "load_token", return_value=TOKEN), mock.patch.object(
            cli, "request", return_value=response
        ) as request, mock.patch("builtins.input", side_effect=["cd /tmp", "", "export DEMO=hello", "pwd", ":quit"]):
            code, _, err = self.invoke("shell")
        self.assertEqual(code, 0, err)
        self.assertEqual([call.kwargs["command"] for call in request.call_args_list], ["cd /tmp", "export DEMO=hello", "pwd"])
        self.assertTrue(all("session_id" not in call.kwargs for call in request.call_args_list))

    def test_process_commands_use_managed_process_protocol(self):
        for argv, action, fields in (
            (("process", "start", "exec sleep 300"), "process_start", {"command": "exec sleep 300"}),
            (("process", "stop", "managed-1"), "process_stop", {"id": "managed-1"}),
            (("process", "logs", "managed-1"), "process_logs", {"id": "managed-1"}),
            (("process", "list"), "status", {}),
        ):
            with self.subTest(argv=argv), mock.patch.object(cli, "load_token", return_value=TOKEN), mock.patch.object(
                cli, "request", return_value={"ok": True, "processes": []}
            ) as request:
                self.assertEqual(self.invoke(*argv)[0], 0)
            request.assert_called_once_with(TOKEN, action, **fields)

    def test_multiple_command_arguments_are_shell_quoted(self):
        self.assertEqual(cli.command_text(["printf", "%s", "space and $substitution"]), "printf %s 'space and $substitution'")


if __name__ == "__main__":
    unittest.main()
