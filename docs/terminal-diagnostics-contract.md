# Terminal history and diagnostics

Status: implemented on the 0.3.31 feature branch. This is not yet a published
release; verification and remaining device-validation gaps are recorded below.

## Goal

Distinguish terminal input-path, remote SSH exchange and local processing/draw
costs without guessing that scrollback or the network is the cause. Preserve
the compact panel and existing SSH/reconnect behavior.

The preceding compact-panel change is released as 0.3.30. This work is a
separate change and must not be described as already available in that release.

## History setting

- Settings → Terminal → History rows offers 500, 1000, 3000 and 10000.
  Default: 1000, including older settings without this value.
- Preserve a valid stored choice. Invalid/unknown values fall back to 1000
  without discarding unrelated settings.
- Capture the choice when a new SSH session/terminal emulator is constructed.
  Existing terminals are not recreated, trimmed or disconnected by changing it.
  Auto-reconnect of the same session retains that terminal's original limit;
  opening a new tab or explicitly replacing a session uses the new setting.
- Pass the same configured buffer-row value to TerminalSession and
  TerminalEmulator. This is the total main-buffer limit, including visible
  rows, not a promise of that many off-screen rows. The real-buffer tests
  verify the pinned library's allocation and preservation of existing text.

## Compact card and detail view

- On an SSH connection/tunnel card, add a short, tappable diagnostic line:
  SSH response time and observed down/up byte rates. Tapping opens details.
- Initially these are **terminal-session measurements**, not all traffic
  through frpc, file transfers, MQTT or web views. Identify that scope in
  the details and when aggregating multiple tabs for the same TunnelRef.
- Do not create or authenticate an SSH connection merely to populate a card.
  With no observed session/measurement, show unavailable, not zero.
- Observed live idle traffic may be zero; stopped/unobserved data is not.
  Show sample count/age and disconnected/stale state in details.
- The response-time summary is a recent mean, not a network-capacity test.
  Details include last/mean/max and failures/timeouts for a bounded recent
  window. Expire stale current values (45 seconds) while labelling retained
  historical samples explicitly. Use a bounded two-minute sample window.

## SSH response measurement

- Probe an existing, authenticated, open SSH client through the actual frp
  route. Never call ICMP ping on localhost or a separate direct device path.
- Use a bounded read-only SSH global request/reply, not text injected into
  the terminal and not a shell command. Both positive and explicit negative
  protocol replies can demonstrate a completed round trip; timeout, transport
  error or unknown exception cannot be counted as a successful response.
- Keep the existing SSHJ keep-alive provider, interval and failure policy.
  Do not replace reconnect policy, cancel another global request, remove a
  timed-out promise from SSHJ's reply queue, or guess which reply belongs to it.
- A probe waits up to three seconds without cancelling its promise.
  At most one diagnostic request may remain outstanding per connection.
  A timed-out request prevents further diagnostic sends until it completes
  or that connection is replaced. Late replies do not become fresh samples.
- Sample no more frequently than every 20 seconds, only while the app is
  visible. Probe work/waiting never runs on the UI or terminal writer thread.
  A diagnostic failure does not terminate an otherwise usable SSH session.
- Bind observations to the exact client and connection generation. Ignore
  completions from a closed/replaced connection or a backgrounded sampler.

## Local measurements

Collect numbers only, without forcing redraws or changing stream buffering:

- Received/transmitted plaintext terminal-channel bytes and rolling rates;
  successful local writes are not remote-delivery acknowledgements.
- Time held in the terminal input path before stream transmission, including
  the tmux copy-mode guard. Label the measured boundary rather than calling
  it network latency. The input-event-to-next-write measurement is approximate:
  it ends at the first accepted block, which can include a terminal protocol
  reply, and IME handling can shift its boundary. Modifier-only hardware key
  events do not start its timer; the existing copy-mode guard is unchanged.
- Sampled received-data/main-queue processing delay, with at most one pending
  marker and a bounded marker rate. It is not a precise GPU/display latency.
- CPU time around existing terminal view draw calls, not forced frame polling
  or a claim about when pixels appeared on the physical screen.
- Configured history limit, current transcript occupancy when supported by
  the pinned Termux API, and main/alternate-screen state.

During stable foreground observation, coalesce numeric snapshots at most once
per second per connection, even when another tab is opened. Membership and
visibility changes refresh availability and release removed/hidden sessions.
Counters must not emit StateFlow updates per byte/chunk/frame. Pause active
probes and extra UI markers in the background; refresh rate baselines when
observations resume. Keep all windows bounded. Timing statistics use one-second
buckets over approximately two minutes; old last values retain their age and
are not presented as current measurements.

The current copy-mode guard is a known candidate, not a diagnosed cause:
`RemoteTerminal.beforeWrite` can wait for `SshSession.leaveCopyMode`, whose
command join timeout is 10 seconds, plus SSH channel-opening time. Measure
this path; do not remove that protection as part of diagnostics.

## Privacy and non-goals

- Do not log/store terminal text, typed keys, payloads, passwords, private keys
  or raw protocol packets. Diagnostic numbers are memory-only.
- Do not run bandwidth/speed tests, send MQTT writes, invoke controller commands
  or silently introduce a new periodic shell process.
- Do not infer pure network latency, remote program speed or a guaranteed root
  cause from one response-time sample. Explain the measurement limits.
- Device/WB icon changes and the WB readonly/topic-card policy are separate.

## Validation

- Test defaults, persistence, invalid-value fallback and new-session capture of
  the history setting without resetting existing sessions.
- Deterministic clock tests cover byte rates, idle/unavailable/stale values,
  bounded means/maxima, multiple-session aggregation and lifecycle cleanup.
- Test a single pending probe, timeout followed by a late reply, reconnect,
  cancellation/backgrounding, and negative protocol reply versus transport error.
  Verify the actual pinned SSHJ behavior with a local test peer where feasible.
- Test counter/marker coalescing and observer callbacks without logging content
  or delaying terminal IO when observation is disabled.
- Test card/details/settings accessibility and layout, including 200% font.
- Run JVM tests, debug/AndroidTest builds, Lint, documentation and diff checks.
  Report physical-phone, live-tunnel and keyboard-vendor gaps explicitly.

## Verification record

- SettingsStore and deterministic-clock tests cover history normalization,
  rates, windows, stale/unavailable values, aggregation and generation guards.
- Probe tests cover single-flight timeout/late replies, visibility/EOF cleanup
  and request failures. A loopback-only Apache MINA test peer verifies real
  SSHJ 0.41.2 negative replies and completion of neighboring global promises.
  The peer is a JVM test dependency only, with an ephemeral in-memory host key
  and no shell factory or production credentials.
- API 35 real-buffer/queue tests verify both Termux constructor limits, existing
  text preservation, unchanged transmitted bytes, writer-wait measurement,
  local-note exclusion and paused markers. A view test checks draw CPU hooks
  stop recording for a disabled or detached owner without sending bytes.
- Component tests cover card/details availability, session close/removal,
  settings choices through the real ViewModel, and the existing terminal panel.
  Run in portrait at 100% and 200% font, and actual landscape at 100%.
- Physical-phone performance, a live frpc route and hardware/IME vendor behavior
  have not been validated. No production lag cause has been established.
- A separately reported listener `address already in use` error is not fixed
  or hidden by this change. An existing SSH connection is not proof that a new
  listener can start or that a new connection will succeed.

Environment: Pi.
Model: openai/gpt-6.1-sol.
