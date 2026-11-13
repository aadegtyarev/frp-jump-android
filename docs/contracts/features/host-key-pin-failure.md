# Fix: a failed host-key save must not crash the app

Status: Approved by the operator via the peer session (Р28, 2026-11-13). Small fix, released before ops PR 4.

## Problem

When the owner trusts a host key, `HostKeyStore.pin` can fail, for example with a storage write or a Keystore error. Two places call it
inside a launched coroutine with no handler, so the exception crashes the process, and the tunnel service with it:

- Files: `ui/files/FilesSession.kt`, around lines 425–443.
- Key login: `ui/ssh/KeyLogin.kt`, around lines 85–97.

The terminal's own trust path (`ssh/SshSession.kt`, around lines 335–358) and the web certificate trust (`ui/web/WebScreen.kt`, which uses
`runCatching`) are the references for honest handling.

## Required behavior

- A failed save is caught. Cancellation is never swallowed: rethrow `CancellationException`.
- The key is not trusted: nothing is pinned, and the flow does not continue as if it were.
- The owner sees an honest error in the screen's existing style. Reuse an existing string that means "could not save" if one fits
  (check the terminal's and the web's texts); otherwise add a short RU/EN string.
- The flow returns to a state where the owner can try again or leave. It never hangs in "busy".
- Look for any other place that calls `pin` or `forget` the same way, and treat it the same. List them all.

## Acceptance criteria

1. Unit or instrumented tests per call site: a `HostKeyStore` whose `pin` throws causes no crash, nothing is trusted, the error is
   shown, and the owner can retry.
2. A mutation that removes the catch fails the tests.
3. `docs/ssh-persist-contract.md` and `docs/files-contract.md` say that a failed save shows an error and trusts nothing, if they describe
   trust. Promises must not be weakened.
4. The full gate, `git diff --check`, and the docs checker pass.
