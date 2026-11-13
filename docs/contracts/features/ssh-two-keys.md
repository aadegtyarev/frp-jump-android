# Feature: two SSH keys, end to end (К1)

Status: approved by the operator via the coordinator, 2026-11-13 (Р33: Д1 option A, Д2, Д3, Д7). It ships in one release with К2
("visible tab actions"), before ops PR 4. Key colours (К3) and the phone key's export and restore (К4) are separate and later;
do not build them here, but leave the sheet's per-key blocks easy to extend.

Plan and mockups: `docs/reviews/ssh-keys-longpress-plan.md` (§1.2-1.5, §2 "common core", option A's "Choice of key" and "Copy and
Share", §6) and `docs/reviews/ssh-keys-longpress-mockups.html` (A: the key choice at 100% and 200%, the "SSH keys" sheet, the
password dialog with the key's name, the "· key" card). Both are untracked; read them in the main checkout at
`/home/adegtyarev/dev/frp-jump-android/docs/reviews/`.

Affected surface contracts:
- `docs/ssh-persist-contract.md` Security (~294-311): two keys, and a new normative "Use key login" paragraph;
- `docs/contract.md` ~556: SSH keys;
- `docs/files-contract.md`: only if `ssh_key_refused` wording is quoted there.

## Required behavior

1. **Use key login installs the chosen key.**
   - `KeyInstallTarget` carries the chosen key, bound to its public line, not to the "imported" slot.
   - Before anything is written, `KeyInstaller` reads and loads the chosen key. If that fails, the result is a new
     `KeyUnreadable`: "Could not read the chosen key. Nothing was changed." / «Не удалось прочитать выбранный ключ. Ничего не
     изменено.», and the script never runs. This fixes today's defect: the key is read after the `authorized_keys` write.
   - If the key was removed or replaced in Settings between the choice and the run (the public line no longer matches), nothing
     is written. The same `KeyUnreadable`-style result applies, or its own result if clearer.
   - The script appends the chosen key's line; a duplicate is left as is, as today, ignoring the comment.
   - The test login uses ONLY the chosen key; the other one is not loaded.
   - KEY is remembered and the password forgotten only after that login succeeds, as today.
   - Cancel, Retry, host-key trust and the password dialog's user change keep the chosen key.
2. **Choice of key, only when there are two** (Д2).
   - Option A's dialog: "Use key login: wb:22" / «Вход по ключу: wb:22»; "Which key to add for user root?"; two radio rows with
     name, type and fingerprint; "I will add it to ~/.ssh/authorized_keys and check a login with it."; Cancel / Install.
   - The phone key is preselected.
   - With one key, the flow is today's, with no extra step.
   - The same dialog opens from every "Use key login" entry: the connection's ⋮ and the tab's menu.
   - The working text and the password note name the chosen key: "Installing the phone key…" / "…the imported key…".
3. **The "SSH keys" sheet** (Settings).
   - The row and the sheet are renamed "SSH keys" / «SSH-ключи». The row's summary is the fingerprint when there is one key, and
     "Phone and imported" / «Телефона и импортированный» when there are two.
   - Two like blocks: the title ("Phone key" / "Imported key"), type and fingerprint, then Copy and Share icons in the title row,
     then the key line.
   - A long line is folded to 3 lines; a tap unfolds it (TalkBack action "Show the whole key").
   - The imported key also shows "Tried first when logging in." and Replace / Remove.
   - Share's chooser title is the key's name. Copy and Share work for BOTH keys.
   - The sheet hint teaches the "Use key login" path.
4. **Wording for two keys.** Apply the plan's §1.3 table. The `key_login` label stays "Use key login…" / «Вход по ключу…» (Д7).
   The comment of the imported key's line stays "imported" (Д3).
5. **Accessibility.**
   - The dialog is a `selectableGroup` with `Role.RadioButton` rows whose text includes name, type and fingerprint.
   - The sheet's icons have labels: "Copy the phone key", "Share the imported key", and so on.

## Unchanged

- Login tries the imported key first, then the phone key (`SshKeyStore.identities`).
- Login memory stays user plus method only; nothing records which key.
- Keys are sealed as today; no private material leaves the app.
- Model-facing texts are untouched (plan §6). `ssh_command` refusals stay fixed English text.

## Acceptance criteria

1. Unit tests:
   - the chosen key's line goes to the script's stdin;
   - the test login uses only the chosen key, and the other is never loaded;
   - an unreadable key means the script did not run;
   - a key removed or replaced between choice and run means nothing is written;
   - Retry, host-key trust and `withUser` keep the key;
   - a duplicate line is untouched;
   - the password is forgotten only after success;
   - `KeyLoginFlow` with two keys shows the choice first, and with one key goes straight to installing.
   - `KeyInstallTest` (19 today) stays green, adapted where its API changes.
2. androidTest:
   - the choice dialog, its defaults and TalkBack roles;
   - the sheet with both keys, Copy and Share for each, fold and unfold;
   - the renamed row and its summary.
   - All in EN and RU, and at 200% font where layout matters.
3. Mutations killed:
   - the key read after the write;
   - verifying with both keys;
   - the imported key preselected;
   - the dialog shown with one key.
4. The contracts are updated, and the "Use key login" paragraph is written into ssh-persist Security without weakening anything
   from today's KDoc.
5. The full gate, `git diff --check`, the docs checker. Emulator: the new androidTests in portrait and landscape at 1.0 and 2.0,
   EN and RU; the font scale is restored to 1.0. Synthetic keys and an in-process or test SSH server only.
