# Paste long-press hint

Status: Approved by the operator on 2026-11-12 (always shown, muted when unavailable: parent decision). Temporary; deleted after verified completion.

## Goal and behavior

Paste in the keys panel has a long press (opens the photo picker). Like the pencil on the keyboard key, it gets a small corner hint: the image glyph (`ImageGlyph`) in the top-end corner of the key.

- Always shown. Drawn in the key's normal tint while the long press is on offer (tab Open, no image on its way, i.e. `canOfferImage`), in the muted tint otherwise. A hint that comes and goes would confuse; a muted one still says that a long press exists.
- Decorative: no semantics of its own, no touch target. TalkBack keeps the existing long-click action and label.
- The paste glyph stays the main, larger mark; the hint does not overlap it, also at 320 dp, in landscape and at 200% font.
- Other long-press keys: the keyboard key already has the pencil; no other key in the panel has a long press.

## Non-goals

No change to the long press, the tap, the semantics, strings or the menu; no hint on snippet chips or elsewhere.

## Affected surfaces

- [Terminal panel contract](../../terminal-panel-contract.md) (keys, decorative hints)
- [Terminal text input contract](../../terminal-text-input-contract.md) (the pencil rule, for reference)

## Acceptance and validation

- Instrumentation test `PasteLongPressHintTest`: hint node drawn; full tint when offered, muted when not; no overlap with the paste glyph; the same at 320 dp. Mutation (hint not muted when unavailable) fails it.
- Screenshots `hint-paste-<state>-<orientation>-<font>.png` in portrait 2.0, landscape 1.0 and 2.0.
- Full gates, `git diff --check`, repo-docs checker.
