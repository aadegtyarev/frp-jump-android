# Feature contract: export and restore of the phone's SSH key (К4)

Status: approved by the operator in decision Р33 (2026-11-13, parts (б), В1 and В2; mockups
`docs/reviews/ssh-keys-color-export-mockups.html`, untracked). Recorded 2026-11-18. Temporary: deleted when the slice is verified and the
surface contracts describe the new behavior.

## Goal

The owner can take the phone's own SSH key to a new phone, and only deliberately. Export is a strict, explicit action. Restore replaces the
phone's key only after the owner confirms it twice. The phone key's private half never leaves the app by any other path.

## User-visible behavior

1. **Export** (Settings → Security → SSH keys → the phone's key → «Выгрузить ключ…»):
   - An explanation screen first: what the file is, that whoever has it and its passphrase can log in where the key is installed, and that
     the file is protected by the passphrase.
   - Identity check: the system screen lock, biometric or PIN, through BiometricPrompt with device credential allowed.
     - With no screen lock set: a warning, and export still continues after an explicit «Всё равно выгрузить».
     - Cancel or failure: nothing happens.
   - The passphrase is entered twice and must match. It must not be empty. Minimum length: 8.
   - The field shows no suggestions and allows no personalised IME learning (`imeOptions` with `IME_FLAG_NO_PERSONALIZED_LEARNING`, a
     password input type), and the window is `FLAG_SECURE` while any passphrase dialog is shown.
   - The file is OpenSSH private-key format, encrypted with that passphrase: bcrypt KDF, aes256-ctr, as `ssh-keygen -p` writes it.
   - The only way out is the system «Сохранить как» (ACTION_CREATE_DOCUMENT), with a default name such as `frp-jump-phone-key`.
     - No clipboard, no share sheet, no other app, no log line containing key material or the passphrase.
     - The passphrase and the plaintext key exist in memory only for the operation and are wiped (char/byte arrays zeroed) after it.
2. **Restore** (the same place, «Восстановить из файла…»):
   - The owner picks a file (ACTION_OPEN_DOCUMENT) and enters its passphrase, in a dialog with the same FLAG_SECURE and IME rules.
   - Only an OpenSSH ed25519 key is accepted. Other types, a wrong passphrase or a damaged file each get a short, truthful message, and
     nothing changes.
   - Confirmation shows two fingerprints, the current phone key's and the file's, with «Заменить» / «Отмена».
   - After «Заменить», a fresh BiometricPrompt runs at once. Only success replaces the key; cancel or failure changes nothing. With no
     screen lock: the same warning as for export.
   - The restored key goes into the phone key's slot, with the same at-rest protection as a generated key. The imported-key slot is not
     touched.
   - After the replace, the public key shown in Settings is the new one. The app reminds the owner that devices with the old public key
     need the new one, as the existing key-change flow does if one exists.
3. **The assistant** can neither export nor restore: there is no tool, and nothing reaches the model.
4. Nothing else changes: the key generation, the imported key, logins, and К3's key marks (the restored phone key is still "the phone key").

## Non-goals

- No export of the imported key, of settings or of frp access keys (those are bound to the phone; "Export and transfer of settings" is
  deferred).
- No cloud or backup path.

## Affected surface contracts

- `docs/contract.md` §9 Settings → Security (SSH keys, :633) and the "Stored on the phone" table (the phone key's slot, unchanged storage).
- `docs/agent-contract.md`: one line stating that key export and restore are not available to the assistant.

## Promises old → new → basis

| Old | New | Basis |
| --- | --- | --- |
| The phone key's private half never leaves the app | It leaves only via export: explicit, identity-checked, passphrase-encrypted, «Сохранить как» only | Р33 (б), В1 |
| The phone key is generated on the phone | Can also be replaced by restore, after two fingerprints and a fresh identity check | Р33 В2 |
| The assistant has no access to keys | Unchanged: no export or restore tool | retained |
| No key material in logs, clipboard or backups | Retained, also for the export file and the passphrase | retained |

## Acceptance

- Unit tests:
  - export → file → restore round-trip gives the same key;
  - a wrong passphrase, a non-ed25519 key and a damaged file are each refused and nothing changes;
  - the passphrase must match and meet the minimum length;
  - arrays are zeroed after use (testable seams).
- The identity checks: tests for both (export and restore-after-replace), covering success, cancel and failure, and the no-lock warning
  path. Mutation check: removing either identity check makes a test fail.
- FLAG_SECURE: a test that the window has FLAG_SECURE while a passphrase dialog is shown and loses it after. Mutation check as above.
- No clipboard or share intent is reachable from these screens (code review and test).
- An independent security review before merge, with the old → new table in the PR.

## Validation

- Focused unit tests and assembleDebug.
- The Settings key screens' classes on the emulator in portrait 1.0 RU and landscape 2.0 EN, with screenshots.
- The system pickers and BiometricPrompt can be checked only partly on the emulator. Say what was not checked.
