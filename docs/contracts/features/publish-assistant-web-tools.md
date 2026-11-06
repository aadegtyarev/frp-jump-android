# Publish the assistant Web-tools release

Status: metadata complete and verified, 2026-11-06; final publication acceptance pending.
Base: `7681bbdf17fcc59115268fda6c954d16219ec939` (accepted Web-tools PR183).

## Metadata verification

The parent consumed the complete mandatory scout plan before editing and accepted
the two-file metadata implementation after a fresh independent Terra review found
no issues. Matching checks recorded 1,548 JVM results without failures, errors or
skips: 1,504 app executed, 18 core and 26 adapter up-to-date. Both debug APKs,
Lint, 77-document check, whitespace and frozen-patch Gitleaks passed. Historical
60/60 synthetic API 35 UI evidence remains unchanged-source evidence, not a new
release-APK execution. All 711 other accepted source files are unchanged.

The reviewed 714-file candidate is contract-only HEAD
`7bb9263cd475566c771a95bc7012316640a45a82` plus frozen metadata, manifest SHA256
`d516a5cf11b90ab4840fdd3ec1364d05083ce5b38cd9552e3fa7e23b1a056aba`.
Archive this completed metadata contract and remove it before the one release PR.
Only lifecycle metadata may change after review. Final release acceptance still
requires the exact-tag signed workflow and independent public APK verification
specified below; neither is claimed complete here.

## Result and scope

Publish the next patch release **0.3.51 / versionCode 63** through the existing
signed GitHub Release workflow. The current public release is 0.3.50 / code62;
remote main equals the base and no v0.3.51 tag or release branch exists.

Change only `versionName`/`versionCode` in `app/build.gradle.kts` and add concise
Russian in-app release notes at `docs/releases/0.3.51.md`, following existing
release-note language. Include the accepted public Web tools, both autosave entry
paths, next-Send behavior and static/image/provider limitations. Search/fetch has
no paid service, but ordinary model token/image charges still apply. Do not claim
live availability, JavaScript rendering or successful visual understanding.
This temporary contract is the only additional lifecycle artifact in the repo.

Read [assistant behavior](../../agent-contract.md), the full affected
[application contract](../../contract.md), [release procedure](../../../README.md#release)
and [meta-contract](../meta-contract.md). Existing source behavior is accepted;
no durable behavior contract change is needed for version/notes metadata alone.

## Execution and validation

Mandatory read-only scout before parent implementation. The parent reads the
actual complete minimal plan and records disposition before edits. The parent is
the sole metadata writer; one fresh independent Terra reviewer checks the whole
release diff, full affected files and retained accepted behavior. Use one async
workflow for scout/handoff and final review, with a supervisor barrier to ensure
the parent consumes the plan and completes metadata/gates before review starts.
Native 30-minute scout/handoff and review safeguards are not time estimates.
One implementation pass and one final review; a blocking new product/CI/signing
issue requires operator disposition, not an automatic feature repair campaign.

Run affected full JVM suites, debug app/test assembly, Lint, documentation,
whitespace and candidate-range Gitleaks with the existing toolchain. Record actual
counts and cache states. Preserve the accepted PR183 60/60 synthetic API35 UI
matrix as unchanged-source evidence, not a fresh release-APK execution; no repeat
emulator run, device install, production startup, profiles/keys or live services.
Verify all non-metadata source bytes stay unchanged from the accepted base.

## Publication and acceptance

One release branch, one checked metadata PR and matched-head/equal-tree merge.
After review, complete/check/snapshot/delete this temporary contract and scan
new commits before push. Tag only the merged main commit as `v0.3.51`; push that
tag to invoke the existing Release workflow. Existing owner permissions may be
used without changing repository rules, signing, secrets, CI or dependencies.
No manual alternate signer/build or fallback publication after infrastructure
failure. Preserve exact failures; no automatic workflow rerun or duplicate release.

Require the exact-tag workflow to succeed, including tests/build/publish/key
cleanup. Download the public APK and checksum, compare with the CI artifact and
retain its matching R8 mapping. Verify SHA256, expected existing release signer,
package/version/code/min29/target35/non-debuggable/arm64 and bundled frpc. Inspect
archive credential-like entries and bounded DEX secret-marker findings without
printing private material; format-marker strings are not PEM-key bodies. No
installed-device or live-model certification follows from archive checks.

The release is complete only after public artifact verification and a concise
report with release/APK links, checks and residual limits. Do not send a Telegram
file unless separately requested. No subsequent feature, release or repair is
automatically authorized.
