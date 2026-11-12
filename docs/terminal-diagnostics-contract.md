# Terminal history and diagnostics

## Goal

Distinguish terminal input-path, remote SSH exchange and local processing/draw
costs without guessing that scrollback or the network is the cause. This surface
owns the history preference and numerical diagnostics exposed on SSH connection
cards and in their details. It serves the owner investigating delay and
maintainers changing terminal instrumentation.

The UI inherits the [shared UI rules](contract.md#ui-rules): concise labels,
direct common actions, accessible controls and truthful availability/error
feedback. Diagnostics must preserve the compact [terminal panel](terminal-panel-contract.md),
[input and paste safety](terminal-text-input-contract.md), and existing
[SSH/reconnect behavior](ssh-persist-contract.md).

Each terminal owns its numeric counters and timing windows. The SSH session owns
probes tied to its exact authenticated client; the session collection owns
visibility, snapshot publication and grouping by `TunnelRef`
(profile/device/port). Card/details consumers do not own or open SSH clients.

## History setting

- Settings → Terminal → History rows offers 500, 1000, 3000 and 10000.
  Default: 1000, including older settings without this value.
- Preserve a valid stored choice. Invalid/unknown values or types fall back to
  1000 without discarding unrelated settings.
- Capture the choice when a new SSH session/terminal emulator is constructed.
  Existing terminals are not recreated, trimmed or disconnected by changing it.
  Auto-reconnect of the same session retains that terminal's original limit;
  opening a new tab or explicitly replacing a session uses the new setting.
- Pass the same configured buffer-row value to `TerminalSession` and
  `TerminalEmulator`. This is the total main-buffer limit, including visible
  rows, not a promise of that many off-screen rows. Preserve existing text;
  recheck buffer semantics when changing the pinned Termux library.
- Local history is not tmux's remote history. The alternate-screen and tmux
  scroll/copy behavior belongs to the [SSH contract](ssh-persist-contract.md).

## Compact card and detail view

The SSH connection/tunnel card exposes a short, tappable SSH response/down/up
line. Tapping opens per-tab details, including the measurement scope and limits.
Keep the line and sheet usable with large fonts, including 200%, and expose a
labelled open action to accessibility services.

These are terminal-session measurements, not all frpc, Files, MQTT or web traffic.
Count plaintext terminal-channel bytes, not SSH encryption/protocol overhead or
local terminal notes. Rates are bytes per second; timings are milliseconds.

- Do not create or authenticate a connection merely to populate a card.
  Without an observed session/measurement, show unavailable (`—`), not zero.
- Observed live idle traffic may be zero; stopped/unobserved data is not.
  Details distinguish connected/disconnected and paused observation, and show
  sample count and last-sample age. Retained historical values are not current.
- The response summary is a recent mean, not a capacity test. Details must include
  last/mean/max and failures/timeouts for a bounded recent window. Current response
  freshness expires after 45 seconds; the recent window is approximately two
  minutes. Connection-total failure counters alone do not establish compliance
  with the recent-window requirement.
- For multiple tabs sharing a `TunnelRef`, sum rates of connected tabs only when
  every connected tab has observed rates. Show the sample-weighted response mean
  only when every connected tab has fresh response samples. Do not hide an
  unavailable tab behind a partial sum or one healthy tab's response. Ended tabs
  may retain historical details but do not inflate the current summary.
- A measurement on an existing SSH connection is not proof that a new listener
  can start or a new connection will succeed. Do not hide an independent
  tunnel/listener error, including `address already in use`.

## SSH response measurement

- Probe an existing, authenticated, open SSH client through its actual frp route.
  Never use ICMP ping on localhost or a separate direct device path.
- Use a bounded read-only SSH global request/reply (`keepalive@openssh.com`), not
  terminal text or a shell command. Positive and explicit negative protocol
  replies can demonstrate a completed round trip. Timeout, transport error and
  unknown exceptions cannot count as successful responses.
- Preserve the existing SSHJ keep-alive provider, interval and failure policy.
  Do not replace reconnect policy, cancel another global request, remove a
  timed-out promise from SSHJ's reply queue, or guess reply ownership.
- Wait up to three seconds without cancelling the promise. At most one diagnostic
  request may remain outstanding per connection. A timeout prevents further
  diagnostic sends until that request completes or the connection is replaced.
  Late replies free the slot but do not become fresh samples.
- Sample no more frequently than every 20 seconds, only while the app is visible.
  Probe work/waiting never runs on the UI or terminal writer thread. Diagnostic
  failure does not terminate an otherwise usable SSH session.
- Bind observations to the exact client, connection generation and observation
  lifetime. Ignore completions from closed/replaced connections or a backgrounded
  sampler, even if observation resumes before the reply arrives.

The negative-reply distinction is pinned to SSHJ 0.41.2 behavior. A dependency
change must recheck protocol-reply classification and neighboring global-request
promise ownership; arbitrary exceptions must not be reclassified as replies.

Image insertion from the terminal records no image bytes, source URIs, remote
paths or file names in diagnostics or logs.

## Local measurements

Collect numbers only, without forcing redraws or changing stream buffering:

| Measurement | Boundary and interpretation |
| --- | --- |
| Received/sent bytes and rolling rates | Plaintext terminal-channel IO. A successful local write is not a remote-delivery acknowledgement. |
| Input to stream write | Approximate time from an owner input event to the first accepted write block, including the before-write guard. That block can be a terminal protocol reply; IME handling can shift the boundary. Modifier-only hardware keys do not start the timer. This is not network latency. |
| Before-write wait | Time in the existing terminal before-write path, including the tmux copy-mode guard; diagnostics do not remove the protection. |
| Incoming processing | Sampled received-data queue/backpressure and main-queue/emulator processing delay. At most one marker may be pending, at a bounded rate. This is not precise GPU/display latency. |
| Draw CPU | Thread CPU time around existing terminal view draw calls, not forced frame polling or the time pixels appeared on a physical display. |
| Buffer state | Configured history limit, current transcript occupancy when supported by the pinned Termux API, and main/alternate-screen state. |

During stable foreground observation, coalesce numeric snapshots at most once
per second per connection, even when another tab opens. Membership and visibility
changes refresh availability and release removed/hidden sessions from observation.
Counters must not emit `StateFlow` updates per byte/chunk/frame. Pause active
probes and extra UI markers in the background; reset rate baselines on resume so
background bytes are not presented as a current foreground rate. Byte totals may
continue counting terminal traffic while observation is paused.

Keep windows bounded. Timing statistics use one-second buckets over approximately
two minutes. Old last values retain their age, even after their recent window
empties, and must not be presented as current. Disconnect removes current rates;
a replacement connection starts new counters/windows and rejects old observations.
Removed sessions disappear from reports rather than leaving retained detail rows.

The copy-mode guard is a candidate, not a diagnosed cause of delay:
`RemoteTerminal.beforeWrite` can wait for `SshSession.leaveCopyMode`, whose command
join timeout is 10 seconds, plus SSH channel-opening time. This is not a verified
10-second end-to-end input bound. The stronger total-wait requirement in the
[SSH contract](ssh-persist-contract.md) remains unverified; diagnostics neither
relax it nor change the guard.

## Privacy and non-goals

- Do not log/store terminal text, typed keys, payloads, passwords, private keys
  or raw protocol packets. Diagnostic numbers are memory-only; the history-row
  preference is persisted separately as an app setting.
- Do not run bandwidth/speed tests, send MQTT writes, invoke controller commands
  or silently introduce a new periodic shell process.
- Do not infer pure network latency, remote program speed or a guaranteed root
  cause from one response-time sample. Explain the measurement limits.
- Physical-phone performance, live-frpc-route behavior and hardware/vendor-IME
  behavior are not established by synthetic/component tests. No production lag
  cause is established by this contract.
- Device/WB icon changes and the WB readonly/topic-card policy are separate.

## Validation

Changes to this surface must check:

- History defaults/persistence, invalid-value fallback without unrelated-setting
  loss, constructor limits, new-session capture and preservation of existing text
  and sessions across preference changes/reconnect.
- Deterministic rates, idle/unavailable/stale states, bounded timing summaries,
  multi-tab aggregation and lifecycle/generation cleanup.
- Single pending probes, timeout/late replies, replacement, cancellation,
  visibility/EOF cleanup, and explicit negative replies versus transport errors.
  Recheck pinned SSHJ behavior with a synthetic local peer where feasible.
- Numeric snapshot/marker coalescing, unchanged terminal bytes, local-note
  exclusion and disabled-observer behavior without content logging or added IO
  delay. Draw hooks must stop recording for disabled/detached owners.
- Card/details/settings accessibility and layout in portrait, landscape and
  200% font; retain truthful empty/disconnected/paused/error states.

Existing evidence seams include `TerminalMetricsTest`, `TerminalReportsTest`,
`SshResponseProbeTest`, `ProbeVisibilityTest`, `SshjResponseProbeIntegrationTest`,
`SettingsStoreTest`, `TerminalHistoryTest`, `TerminalMetricsIoTest`,
`TerminalDrawMetricsTest`, `TerminalDiagnosticsTest` and `SshSessionLifecycleTest`.
Test source is not evidence of execution. Use the repository's relevant JVM,
build, Lint and component gates for behavior changes, plus documentation and diff
checks; report physical-phone, live-tunnel and keyboard-vendor gaps separately.
