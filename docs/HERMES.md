# Hermes on your NAS

The phone uses Hermes's supported gateway API: `POST /v1/runs`, streaming run
events, run status, approval responses, Stop, and session history. Every prompt
uses the configured Hermes agent, tools, and model on the NAS. The fork does not
require Arch Linux on the phone.

## Connect

1. Run the Hermes gateway on your NAS with its API server enabled. Configure a
   strong `API_SERVER_KEY` and a private `API_SERVER_HOST` the phone can reach.
   The usual port is **8642**. Existing installations can check
   `/health` and authenticated `/v1/capabilities`.
2. Connect the phone to the same LAN or Tailscale network.
3. Install the fork of **Omalauncher**, open settings → **Hermes · NAS**, and enter
   `http://YOUR-TAILSCALE-IP:8642` and the gateway's `API_SERVER_KEY`. Save and
   select **test connection**. The dashboard login and dashboard port are
   separate from the gateway API key and port.
   On Android 17 or newer, allow the local network permission when prompted.
4. Select **Hermes · NAS** in the prompt bar. Enter a prompt and tap **ask**,
   or use the bar's microphone. Replies open in the native Hermes chat panel.
   Existing app search behavior still opens matching applications.

The URL can also be an HTTPS origin or a multiplexed profile such as
`https://YOUR-HOST/p/personal`. A trailing `/v1` is accepted. HTTP addresses
must be private LAN, loopback, Tailscale, or `.local` hosts. The client never
forwards its key through HTTP redirects. Android Keystore encrypts the saved
key, and the credential file is excluded from Android backups.

## Conversations and interruptions

The phone retains a Hermes session ID across app restarts and fetches the
latest 100 history entries from the NAS. **New chat** starts a new session;
older sessions stay on the NAS. Closing the panel leaves its current request
connected while Android keeps the launcher process alive.

The launcher saves the pending prompt and an idempotency key before sending
it. If acceptance is interrupted, **reconnect** retries with that same key.
Once a run ID is known, reconnect checks that run's status instead of sending
another prompt. After losing an event stream, status polling recovers final
output and permission requests. **Stop** asks Hermes to interrupt the run;
the panel waits for its final state before accepting another prompt.

Permission requests show the choices Hermes allows. Approvals are sent for
the exact request ID. Changing the NAS address requires the active request to
finish; an interrupted connection permits replacing the key for the same NAS.

If the gateway has restarted and no longer retains the run, reconnect can
report it missing. Select **return to saved conversation** to clear that local
run reference and reload the NAS history. The interrupted prompt is never
resubmitted by this recovery action.

## Build and test

The fork keeps upstream's Gradle, Kotlin, and Android SDK versions. Gradle
downloads its declared JVM toolchain when needed.

```sh
./scripts/fetch-proot.sh
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Its application ID is
`dev.omalauncher`; the main activity class remains
`dev.omalauncher.MainActivity`. It uses the original app identity. Android
requires the same signing key for an update over an existing installation.
The preview uses a development key, so installing it in place of the original
requires uninstalling the original first. Uninstalling can delete its settings
and phone Linux files; preserve them before removing the app. The first preview
(`0.4.0-hermes.1`) used `dev.omalauncher.hermes`; remove that separate preview
yourself if you no longer need it.

Transport tests cover URL validation, authentication, redirect refusal,
idempotency headers, SSE framing, and truncated streams. The optional live
NAS test requires `HERMES_TEST_URL` and `HERMES_TEST_KEY` in the test process
environment. It starts a separate test session and checks streaming,
deduplication, and context across two turns. It leaves that session in the NAS
history. Do not put credentials in the repository or an APK.
