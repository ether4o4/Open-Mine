"""Real shell/process/HTTP fixture tests. The tiny HTTP fixture is NOT GGUF inference."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "main/assets/sandbox/morsllm.sh"
FIXTURE = r'''
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
int main(int argc,char **argv){
  int port=8080;
  for(int i=1;i<argc;i++){ if(!strcmp(argv[i],"--version")){puts("TEST FIXTURE ONLY");return 0;} if(!strcmp(argv[i],"--port")&&i+1<argc)port=atoi(argv[++i]); }
  int fd=socket(AF_INET,SOCK_STREAM,0),yes=1;
  setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&yes,sizeof yes);
  struct sockaddr_in a={0};a.sin_family=AF_INET;a.sin_port=htons(port);a.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
  if(bind(fd,(struct sockaddr*)&a,sizeof a)||listen(fd,8))return 2;
  const char *state=getenv("FIXTURE_HEALTH");if(!state)state="ok";
  while(1){int c=accept(fd,0,0);if(c<0)continue;char buf[4096];read(c,buf,sizeof buf);char body[128];snprintf(body,sizeof body,"{\"status\":\"%s\"}",state);char response[512];snprintf(response,sizeof response,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n%s",strlen(body),body);write(c,response,strlen(response));close(c);}
}
'''

class EngineLifecycle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler_dir = tempfile.TemporaryDirectory(prefix="openmine-engine-test-compiler-")
        source = Path(cls.compiler_dir.name) / "fixture.c"
        source.write_text(FIXTURE)
        cls.binary = source.with_suffix("")
        subprocess.run(["cc", str(source), "-o", str(cls.binary)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.compiler_dir.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="openmine-engine-test-")
        self.root = Path(self.temp.name)
        for p in ("bin", "models", "run"): (self.root / p).mkdir()
        shutil.copy2(self.binary, self.root / "bin/llama-server")
        (self.root / "models/test.gguf").write_bytes(b"GGUF" + bytes(32))
        self.env = dict(os.environ, MORSLLM_ROOT=str(self.root), MORSLLM_HEALTH_TIMEOUT="2")
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            self.port = s.getsockname()[1]
        self.original_metadata = None

    def run_engine(self, *args, success=True):
        result = subprocess.run(["bash", str(SCRIPT), *args], env=self.env, capture_output=True, text=True, timeout=12)
        data = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(success, result.returncode == 0, result.stderr + result.stdout)
        return data

    def tearDown(self):
        if self.original_metadata: (self.root / "run/server.json").write_text(self.original_metadata)
        subprocess.run(["bash", str(SCRIPT), "stop"], env=self.env, capture_output=True, timeout=12)
        self.temp.cleanup()

    def test_existing_engine_fast_path_runs_without_package_install(self):
        self.assertTrue(self.run_engine("provision")["already_built"])

    def test_health_checked_server_status_and_stop(self):
        self.assertTrue(self.run_engine("serve", "test.gguf", "--port", str(self.port))["ready"])
        state = self.run_engine("status")
        self.assertTrue(state["running"])
        self.assertTrue(state["ready"])
        self.assertTrue(state["provisioned"])
        self.assertFalse(self.run_engine("stop")["running"])
        self.assertFalse(self.run_engine("status")["running"])
        self.assertTrue((self.root / "models/test.gguf").exists())

    def test_http_200_loading_is_not_readiness_and_timeout_stops_server(self):
        self.env["FIXTURE_HEALTH"] = "loading"
        result = self.run_engine("serve", "test.gguf", "--port", str(self.port), success=False)
        self.assertEqual("health_timeout", result["error"])
        self.assertIn("log_path", result)
        self.assertFalse((self.root / "run/server.pid").exists())
        self.assertFalse(self.run_engine("status")["ready"])

    def test_saved_unrelated_pid_is_never_signalled(self):
        process = subprocess.Popen(["sleep", "20"])
        try:
            (self.root / "run/server.pid").write_text(str(process.pid))
            (self.root / "run/server.json").write_text("{}")
            self.assertEqual("server_identity_unverified", self.run_engine("stop", success=False)["error"])
            self.assertIsNone(process.poll())
            self.assertFalse(self.run_engine("status")["running"])
        finally:
            process.terminate(); process.wait()

    def test_reused_pid_with_different_start_time_is_not_signalled(self):
        self.run_engine("serve", "test.gguf", "--port", str(self.port))
        path = self.root / "run/server.json"
        self.original_metadata = path.read_text()
        meta = json.loads(self.original_metadata);meta["start_ticks"] = "0";path.write_text(json.dumps(meta))
        self.assertEqual("server_identity_unverified", self.run_engine("stop", success=False)["error"])
        os.kill(meta["pid"], 0)

    def test_cancel_loading_stops_owned_model(self):
        self.env["FIXTURE_HEALTH"] = "loading"
        self.env["MORSLLM_HEALTH_TIMEOUT"] = "300"
        process = subprocess.Popen(["bash", str(SCRIPT), "serve", "test.gguf", "--port", str(self.port)], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline=time.monotonic()+5
            while not (self.root / "run/server.json").exists() and time.monotonic()<deadline: time.sleep(.05)
            time.sleep(.1)
            process.terminate();process.communicate(timeout=10)
            self.assertFalse((self.root / "run/server.pid").exists())
        finally:
            if process.poll() is None: process.kill();process.communicate()

    def test_invalid_model_and_port_do_not_launch(self):
        (self.root / "models/bad.gguf").write_bytes(b"not model data")
        self.assertEqual("invalid_or_missing_gguf", self.run_engine("serve", "bad.gguf", success=False)["error"])
        self.assertEqual("invalid_port", self.run_engine("serve", "test.gguf", "--port", "65536", success=False)["error"])
        self.assertFalse((self.root / "run/server.pid").exists())

if __name__ == "__main__": unittest.main(verbosity=2)
