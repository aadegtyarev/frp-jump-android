# Fix: an honest result when saving a trust decision fails

Status: approved by the operator via the coordinator (Р31, 2026-11-13). A small fix, released before ops PR 2.

## Problem

1. Web certificate trust (`ui/web/WebScreen.kt` near line 487) saves with
   `runCatching { withContext(IO) { certs?.pin(...) } }.isSuccess`. This has two faults:
   - `runCatching` also catches `CancellationException`.
   - When the save fails, the owner sees `cert_refused` ("Not opened: the certificate was not trusted" / «Не открыто: вы не
     доверились сертификату»), although they pressed Trust.
   The page correctly stays closed.
2. The host-key save failure text from 0.3.78 (`hostKeyWriteDetail`, in `ssh/HostKeyStore.kt`) puts the exception message in
   parentheses. An IOException message can carry an app-private file path.

## Required behavior

- **Web certificate.** When saving the trust fails:
  - The page still does not open (fail closed). The error handler is cancelled, as today.
  - The owner sees an honest text: "Could not save trust in the certificate (<kind>). Not opened." /
    «Не удалось сохранить доверие к сертификату (<вид>). Не открыто.»
  - Retry stays possible through the existing "Try again".
  - A `CancellationException` is rethrown, never swallowed.
- **One wording for failed trust writes.** A single function maps the failure to a short kind of error, used by the certificate and by
  all five host-key sites from 0.3.78:
  - no space left → "no space left" / «нет места»;
  - access denied → "no access" / «нет доступа»;
  - anything else → "write error" / «ошибка записи».
  - The text in parentheses never contains a path, an exception message or a stack trace.
- Model-facing texts do not change. None of these texts reaches the model; check this again.

## Acceptance criteria

1. A test pins that a failing certificate save leaves the page unopened and the handler cancelled.
2. A separate test pins that cancellation during the save propagates.
3. Unit tests for the kind mapping: ENOSPC / no space, EACCES / permission, and other. A path in the exception message never appears in
   the text.
4. The host-key tests from 0.3.78 still pass, with their expectations updated to the kind wording.
5. RU and EN strings. Contracts are updated where they quote the wording (`docs/contract.md` web section, `ssh-persist`, `files`).
6. The full gate, `git diff --check` and the docs checker pass.
