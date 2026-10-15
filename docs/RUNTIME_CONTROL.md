# Android runtime and Termux control

Open Mine's runtime service owns the Linux shell and explicitly managed processes.
The service can remain active after the app's screen closes. Termux controls it
through an authenticated TCP bridge bound to `127.0.0.1:8766` on the same Android
device.

## Source version

This change is based on the README-linked 0.4.1 source bundle at
`21a33b8451241494d7b6c4f6a1d2296315f2bd8d` (version code 11,
application ID `com.openmine`). It also includes PR #3’s architecture validation,
engine-health/import fixes and 6% text increase. The integrated build is
`0.4.2-dev`, version code 12. That bundle is 18 commits ahead of the published
`codex/operational-integration` branch. Its restored blue orbital HUD, map,
12-entry navigation and bottom dock are preserved, as are existing storage paths.
The runtime fixes were selectively integrated without replacing the newer model,
installation, backup, or project implementations.

The linked release APK's identity was checked against its published SHA-256.
The installed phone APK has not been extracted and compared. A locally built
debug APK uses a different signing certificate: it is not an in-place update for
the published release. Retain the original signing identity for delivery; do not
uninstall the existing app to work around a signature mismatch.

## Browser and shell height

The Browser and Terminal panels retain their original default dimensions. Drag
the cyan handle above either panel up to expand it or down to shorten it. Width
stays fixed. Tap the handle for a height slider and **Reset height**. Each panel
remembers its own height across navigation and app restarts. The handle also
exposes increase, decrease and reset accessibility actions. Landscape and
accessibility layouts remain bounded by the available screen space.

## Shell input and model import

Return/Send and **Run command** submit typed shell commands directly. Accepted
commands clear the input immediately. Shift+Enter on a hardware keyboard inserts
a newline. The ↑/↓ controls above the input (and hardware arrow keys) recall the
last 100 submitted commands, stored locally across restarts. Returning past the
newest command restores the unfinished draft. Blank, invalid, or busy submissions
do not clear the input or enter history. Imported tool procedures retain their
separate exact-command review flow.

**Import from Hugging Face** opens the full model catalog in the device browser.
Choose and download a model's GGUF file, return to Open Mine, and tap **Choose
downloaded file**. This reuses the existing validated file importer; arbitrary
model formats are browsable but are not automatically compatible with the local
GGUF engine. The existing **Import GGUF weights** file picker is also retained.

## Set up once

1. Install a build containing these runtime controls. Open Open Mine and finish
   Linux shell setup so that the shell is healthy.
2. In Settings → runtime controls, enable the Termux bridge and copy its token. This
   grants command execution inside Open Mine's Linux environment. Keep the token
   private. Android notification permission, when requested, allows the ongoing
   runtime notification to be shown normally.
3. In Termux, install Python and the client from this source checkout:

   ```sh
   pkg install python
   install -m 755 scripts/openmine "$PREFIX/bin/openmine"
   openmine configure
   ```

   Paste the copied token at the hidden prompt. The token is stored in
   `~/.config/openmine/config.json` with mode `600`, inside a directory with mode
   `700`. `openmine configure TOKEN` is also supported, but the hidden prompt
   keeps the token out of shell history. The client does not print the token.

The bridge is disabled until explicitly enabled in the app. The Android receiver
and every bridge request authenticate the token. Other apps can reach loopback
on Android, so loopback binding alone does not authorize access. Disabling bridge
access in the app rejects further control requests until it is enabled again.
The saved token remains the same across disable/enable. There is no remote host
option.

## Start, shell, status, stop

```sh
openmine start
openmine status
openmine shell 'uname -a'
openmine shell
openmine stop
```

`start` sends an explicit Android broadcast to
`com.openmine/.sandbox.RuntimeControlReceiver`, then polls the authenticated bridge
for `running: true`. Broadcast acceptance alone is not reported as success.
`--timeout 20` sets the polling limit in seconds; the Android broadcast itself has
an additional limit of up to 10 seconds. A running service is distinct from a
healthy Linux installation: inspect `installed`, `shell_healthy`, and `status`
in the JSON response if commands fail.

`status` returns JSON containing runtime state and the managed process list. An
unreachable bridge reports `stopped_or_unreachable` and exits nonzero. That state
can also mean disabled bridge access, a failed service start, or a local port
conflict; it is not proof that every Linux process has exited. Authentication and
protocol errors also exit nonzero.

`shell` with a command prints its stdout and stderr, then returns its exit code.
Quote a command containing shell operators so Termux does not interpret them:

```sh
openmine shell 'cd /tmp; export DEMO=hello; printf "%s\n" "$DEMO"'
openmine shell --timeout 60 'apk update'
```

`shell` without a command is a **line-oriented REPL**, with `:quit` or Ctrl+D to
leave. It uses the same persistent Bash session as the app terminal. Working
directory and exported variables persist between requests, including separate CLI
invocations. Requests are serialized; a busy runtime rejects overlapping commands.
Other clients and the app can change this shared shell's state. This is not a PTY:
full-screen terminal programs, password prompts, and commands requiring interactive
stdin are unsupported. Output is returned after command completion and bounded by
the runtime's output limits.

Each shell command has a timeout between 1 and 1800 seconds (default 1800). A
timeout or cancellation resets the terminal session and terminates its ordinary
background jobs. The last completed command’s exported variables and working
directory are restored; unexported shell state is not restored. Closing or interrupting the CLI does not
promise remote cancellation: a submitted command may continue until its timeout.
Use the app's Cancel control for a running terminal command.

`stop` requests service shutdown and stops its shell and managed processes. It
does not remove the installed Linux filesystem or bridge configuration. Reopen
Termux in the foreground before a later `start` if Android denies a background
start.

## Long-running services

Start long-running services explicitly through the process manager:

```sh
openmine process start 'exec sleep 300'
openmine process list
openmine process logs PROCESS_ID
openmine process stop PROCESS_ID
```

The start response includes a process ID. Use that ID for `process stop` and
`process logs`. The process list contains status and exit information; `logs`
returns the process's bounded recent stdout and stderr as JSON. Process creation
does not establish that a server is healthy or listening: inspect its status and
perform the server's own health check.

The managed command must stay in the **foreground**. Use a server's foreground
option and, where useful, `exec`; do not add `&`, `nohup`, or daemonize it. The
runtime owns the PRoot process for the command and tracks its lifecycle separately
from the terminal. Managed processes survive ordinary terminal commands and
terminal cancellation while the Android service remains alive. They do not
inherit the terminal's current directory or exported variables; put required
`cd` and `export` commands in the managed command itself. Their Linux filesystem
is shared with the terminal.

## Android lifecycle limits

The service uses Android foreground service APIs and an ongoing notification.
Closing the app screen or dismissing its recent-task card is not a request to stop
the runtime. Android still controls process lifetime and foreground service
startup eligibility. In particular:

- A headless start from Termux can be denied by Android background-start rules.
  Keeping Termux visible can help, but is not a bypass or a guarantee; opening
  Open Mine and starting its runtime is the fallback.
- Force-stop, device reboot, Android's active-app Stop action, memory pressure,
  and manufacturer battery controls can terminate the app and its Linux processes.
  This implementation does not restore jobs after process death or boot.
- The foreground service does not promise uninterrupted CPU execution while the
  device sleeps. Test the intended workload on the target Android/OEM version.
- A foreground service declaration is not proof of app-store policy eligibility.

## Protocol and verification

The bridge accepts one UTF-8 JSON line per connection and returns one JSON line.
The client uses a two-second connection timeout and a fixed loopback address.
Requests are limited to 16384 bytes and carry `token` and `action`. Actions are
`status`, `shell` (with `command` and `timeout_seconds`), `process_start` (with
`command`), `process_stop` and `process_logs` (with `id`), and `stop`. Successful responses contain
`"ok": true`; errors contain `"ok": false` and `error`. Shell results are nested
under `result` and include `success`, `stdout`, `stderr`, and `exit_code`.

Run the host-side CLI contract tests with:

```sh
python3 -m unittest discover -s app/src/test/scripts -p test_openmine_cli.py -v
```

These tests exercise actual loopback JSON framing and mocked Android broadcasts,
private configuration, authentication failures, exit status, and bounded startup.
They do not validate Android service survival. On a device, start a managed
foreground command, run several shell commands, close the Open Mine screen, then
check the same managed process from Termux. Verify that terminal cancellation
leaves it running, that `process stop` terminates it, and that `openmine stop`
closes the bridge. Also verify disabled/wrong-token rejection and the actual
background-start behavior on that device.
