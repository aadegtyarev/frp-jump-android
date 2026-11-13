# Feature contract: Settings behind a gear, the tunnel chip as a number

Status: approved (operator decisions Р35 and Р36(a), 2026-11-13; mockups `docs/reviews/settings-gear-mockups.html`). Ships in
0.3.80, as its own PR after Р32 part A.

## Goal and user-visible behavior

Settings leaves the bottom navigation. It becomes an ordinary screen in the back stack, opened by a gear (⚙) at the right end of the
Devices, Connections and Sessions headers, with a back arrow. The bottom navigation keeps the sections the owner switches between:
Devices / Connections / Sessions, plus "Assistant" while the assistant is on. The profile switcher on Devices gets the room: the tunnel
chip shows the link glyph and the number, and the Devices header has no power button any more.

## Scenarios

1. **Devices header**, left to right: the profile switcher, the tunnel chip (only while tunnels are up, as now), the search magnifier, ⚙.
   - ⚙ is always the last item on the right.
   - The icon buttons sit next to each other, each with its own 44 dp field. The 8 dp gap stays only between the switcher and the rest.
   - There is no power button.
2. **The tunnel chip** shows the Connections section's link glyph and the number of tunnels up.
   - TalkBack reads it as now ("1 tunnel" / «1 туннель», plural forms kept), as a button that opens Connections.
   - It is the same number as before; tunnels whose disconnect waits for Undo are not counted.
   - With no tunnels there is no chip.
3. **Connections header**, left to right: the title, the power button "Disconnect all" (while any tunnel is up or being restored, as
   now), the search magnifier (from 6 connections, as now), ⚙.
   - When the title and the icons do not fit in one row (200% font in portrait at 360 dp, for example), the icons go on a row of their
     own and the title goes below them, whole: it is never cut and never broken inside a word. Otherwise everything is on one row.
4. **Sessions header**: the title and ⚙.
5. **⚙** has the TalkBack label "Settings" / «Настройки» and the button role, and opens Settings over the section.
6. **Settings** is a stack screen: its top bar has a back arrow ("Back" / «Назад») and the title "Settings" / «Настройки». It has no
   bottom navigation, like the other screens opened over a section.
   - Back (the arrow or the system Back) returns to the section it was opened from, with that section as it was.
   - There is only ever one Settings in the back stack. Opening Settings while it is already in the stack returns to that entry and
     closes the screens above it; it never pushes a second one.
   - Screens opened from Settings (profile, SSH keys, the assistant's chat and provider, MQTT plugins, licenses, and the rest) work as
     now, and Back from them returns to Settings.
7. **Every path into Settings opens that same screen:**
   - "Show" in the update announcement;
   - the chat's "Set up" while the assistant is off;
   - any other in-app path that led to the Settings tab.

   Texts such as "Settings › SSH keys" stay true.
8. **The update announcement** (a newer release, once per version) is still shown on the section screens, and "Show" opens Settings.
   - An announcement that arrives while Settings is the shown screen is taken and not shown later: the version row on Settings
     already says it.
9. **Bottom navigation**: Devices, Connections, Sessions (+ Assistant while the assistant is on, unchanged).
   - The labels stay whole in both languages, in landscape, at 200% font and in a 320 dp window, as the existing rule requires.
   - The Assistant item's dot and its TalkBack state are unchanged.
10. **Removed with the Devices power button** (Р36(a), operator's decision): disconnecting the shown profile's tunnels with one button
    from Devices.
    - These remain: "Disconnect all" on Connections and in the "Tunnels" notification (every profile), and the power button on a
      device row (swipe) and on the device card (that device's tunnels).
    - Undo behaves as before for all of them.

## Non-goals

- The order and grouping inside Settings (Р39), and "Show" scrolling to the version: these come later, with the "Appearance" release.
- New icons (Р38).
- The Connections redesign (Р36(b)): its touch targets, sorting and layout.
- No setting is added, removed or renamed. No tunnel, Undo or notification behavior changes.
- No change to the chat, the provider screen or the assistant's entry points beyond what their texts say about the bottom bar.

## Affected surface contracts

- [docs/contract.md](../../contract.md):
  - Scope: the bottom navigation and the sections;
  - §3, the device row's power button "the bar's glyph" (reword: the power glyph in red);
  - §5, the Connections title, "the Devices bar's power button" (reword);
  - §8, "the same number the title says as 'N tunnels'" (reword: the Devices tunnel chip, read as "N tunnels");
  - §9 Settings: how it opens, the back arrow, one in the stack;
  - the update announcement, if described.
- [docs/agent-contract.md](../../agent-contract.md) entry points:
  - "the bottom navigation's fifth item" and "all five labels" become the item after the sections, and all labels;
  - the dot's list of section screens drops Settings.
- [docs/mqtt-contract.md](../../mqtt-contract.md): "the bottom bar has four items (a fifth…)" becomes three (a fourth…).
- README:
  - the bottom navigation list;
  - "the power button on the Devices bar (the profile shown)";
  - any "Settings tab" wording.

Promises accounted for:
- **Removed** (grounds Р36(a)): the Devices bar's power button for the shown profile.
- **Reworded**, not weakened: the chip's text becomes the glyph and the number, and its TalkBack text and number are unchanged.
- **Retained**: everything else in these sections.

## Interfaces and constraints

- `Tab` has no SETTINGS entry; `Routes.SETTINGS` stays as the route.
- Settings is reached by one navigation helper. It pops back to an existing Settings entry if there is one; otherwise it pushes
  Settings with `launchSingleTop`.
- Settings is not wrapped in `TabFrame`. The section headers take an `onSettings` callback.
- The profile switcher is never narrower than before Р35 in any configuration at 360 dp with one tunnel up: at least 126 dp at 100%
  and 66 dp at 200%, in portrait, in both languages. Expected are about 182 dp and 176 dp.
- Header icon buttons keep their 44 dp fields. ⚙ uses the existing Settings icon.
- Existing strings are reused where they exist ("Settings", "Back", the `tunnels` plurals). Any new string is added in EN and RU.
- Do not touch TV, icon-set or clipboard work. Do not change the assistant's code paths beyond the navigation calls that opened the
  Settings tab.

## Acceptance criteria

1. The bottom navigation has 3 items with the assistant off and 4 with it on. There is no `nav_settings`.
2. ⚙ is present, last on the right, with label and role, on Devices, Connections and Sessions. It opens Settings.
3. Settings has a back arrow and no bottom bar. Back returns to the section it was opened from.
4. Opening Settings while it is in the back stack does not create a second entry.
5. "Show" in the update announcement and the chat's "Set up" with the assistant off both open the same Settings.
6. Devices has no power button. The chip shows the glyph and the number, and TalkBack reads the plural text, as a button opening
   Connections. With 0 tunnels there is no chip.
7. On Connections at 200% portrait 360 dp, the title is whole, below the icons. At 100% it is on one row.
8. The profile switcher's width meets the minimum above.
9. The bottom nav labels are whole in both languages at 320 dp and 360 dp, at 100% and 200%, and in landscape.
10. Existing behavior is unchanged:
    - Connections' "Disconnect all";
    - the row and card power buttons;
    - Undo;
    - search thresholds;
    - every Settings sub-screen.

## Validation

- Unit:
  - a navigation helper test: one Settings entry, the pop to an existing one, Back to the section;
  - a source guard that no `TabFrame(Tab.SETTINGS` and no `goToTab(Tab.SETTINGS` remain.
- androidTest, compiled in the gate and run in the series e2e:
  - `BottomNavFitTest` (3 and 4 items);
  - `AssistantEntryNavTest`: update its Settings paths;
  - a header test for ⚙ on the three sections, the chip's TalkBack text and the absence of the Devices power button, the
    Connections title at 200%, and the switcher width;
  - every androidTest that reached Settings through `nav_settings` moves to ⚙.
- Full unit gate, assemble, lint, `git diff --check`, the docs checker and Gitleaks.
- A targeted emulator set is allowed by the process rule (drawing at 200%, layout): portrait and landscape, 1.0 and 2.0, EN and RU, for
  the header and bottom nav tests.
