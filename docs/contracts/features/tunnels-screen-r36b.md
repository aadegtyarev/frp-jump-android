# Feature contract: Tunnels screen redesign (R36b)

Status: approved by the operator 2026-11-18. Temporary: deleted after the last slice is verified and the surface contracts describe the new
behavior. Mockups: the claude.ai artifact «Варианты экрана туннелей» (final version). Recon: `docs/reviews/conn-recon/2026-11-18/recon.md`
(untracked).

## Goal

The operator's scenario on the Tunnels tab: see what is on, turn one or all off, find a tunnel fast. Today the list re-sorts on every
visit (on tunnels jump to the top), the header's Stop all is an unlabelled icon, cards are tall, and text is cut at 200 % font. Make the
tab compact, stable and readable at 200 %.

## User-visible behavior

1. **Stable order.** Favourites first (group «★ Избранные» / "★ Favourites"), then devices by name, then profile order. Within a device
   group, the stored order. Neither a tunnel's state nor a device's online state changes the order. Nothing re-sorts on a visit, a pull
   or a state change. A new connection appears in its place by these rules.
2. **Header, one row at 100 % font, two rows at 200 %.** No title: the bottom bar names the tab, and TalkBack still gets a heading.
   - **Search field.** Always present, at any number of connections. A tap expands it to the whole header with ← (close) and ✕ (clear).
     The placeholder names the scope: «Поиск среди включённых» / «Поиск по всем туннелям». The search matches what it matches today
     (device name or label, connection name), within the shown scope. When it finds nothing there but finds tunnels in all, a line
     «Во всех: N · Показать» switches the filter off.
   - **Filter «только включённые»** ("only on"). A toggle with the link glyph and the count of on tunnels: up, connecting or being
     restored, across all profiles. It is remembered, and on by default. At 0 it is disabled and the list shows all tunnels.
     TalkBack: «Только включённые, N».
   - **⏻ Stop all.** Always in its place, disabled when nothing is on, so the header does not move. Same action and Undo as today.
     TalkBack: «Выключить все туннели, N».
   - **Gear.** As today.
3. **Compact card**, the same component on the Tunnels tab and the device card:
   - Text on the left:
     - the name, with «· device» where the view has no device header;
     - the kind as text («SSH · порт 22 · root · ключ»; the kind tile goes);
     - state and local address on one line.
   - Up to three round icon buttons on the right, vertically centred: the main action, ⏻, and ⋮ last.
     - The visible circle is 32 dp and grows with the font. The touch target is ≥ 44 dp.
     - Main-action glyph by kind: SSH terminal (with a count for 2+ sessions), WEB globe, MQTT arcs, TCP copy.
     - The main action is a tonal circle. ⏻ "turn off" is a red outline; ⏻ "turn on" is a green outline. They differ in form, not only
       in colour.
     - A down card shows only ⏻ "turn on" and ⋮.
   - Tap on the card does the main action; long press opens the menu. The offline dimming rules are unchanged.
   - TalkBack names each button with the connection name.
4. **Menu ⋮.** The same items as today, plus «В избранное» / «Убрать из избранного» and ✦ «Спросить помощника» (when the assistant is on).
5. **Swipes**, like the Devices row (`SwipeReveal`): a swipe reveals a button and the action runs on its tap.
   - Left reveals ✦ «Спросить помощника». It opens the assistant chat with the draft «Про туннель <name> (<device>): ». Nothing is sent.
     The swipe is absent when the assistant is off.
   - Right reveals ★ «В избранное» / «Убрать из избранного».
   - Both actions are also TalkBack custom actions.
6. **Assistant mark** replaces the card's assistant line.
   - Where a grant exists, a chip on its own last text line: «✦ Для сессии» (only a session grant) or «✦ Для подключения» (a remembered
     permission, with or without a session grant).
   - A tap opens a sheet:
     - title «Помощник в <name>»;
     - one line per lifetime that exists: «Вы выбрали «Для сессии». Команды помощника здесь выполняются сразу, пока туннель включён.» and
       «Вы выбрали «Для подключения» <date>. Команды помощника здесь выполняются сразу, пока подключение не изменят.»;
       «Команды и ввод во вкладки» when the grant covers both tools;
     - «Пароли помощник не вводит никогда.»;
     - buttons «Снова спрашивать» and «Закрыть».
   - «Снова спрашивать» does what Forget does today. After it, a snackbar «Помощник снова спрашивает в <name>».
   - TalkBack on the chip: «Помощник: <chip text>. Подробнее».
7. **Assistant glyph.** The sparkles ✦ replace the chat bubble for the assistant in the bottom bar and in the Settings «Чат» row.
8. **Chat question card, tidy:**
   - title «Выполнить в <name>?», then the code plate;
   - the row «Разрешить» / «Отклонить»;
   - the label «Разрешать без вопросов:» with text buttons «Для сессии» / «Для подключения» / «Всегда» («Всегда» still asks a second
     time);
   - one hint: «Для сессии — пока туннель включён. Для подключения — пока его не изменят, и даже для команд из прочитанного текста.»
   - Receipts: «· для сессии» / «· для подключения» / «· подтверждения выключены».
   - Names never break inside a word.
9. **Devices tunnels chip** counts the on tunnels of all profiles, not only the current one.
10. **Buttons** across these surfaces are 32 dp tall (visible); touch targets stay ≥ 44 dp.

## Non-goals

- No change to tunnel logic, Undo timing, notifications or the operations surface.
- No change to what the assistant may do: the grant scope, lifetimes, the lock rules and the grantable tools stay.
- No change to model-facing texts or tools. The assistant does not see favourites.

## Affected surface contracts

- `docs/contract.md`:
  - §4 Device card and named connections: the card, and the assistant line at :283-294;
  - §5 Tunnels tab: order, header, search, Stop all;
  - Devices: the tunnels chip;
  - §9 Settings: the assistant row glyph;
  - "Stored on the phone": favourites and the filter.
- `docs/agent-contract.md` "Confirmation of changing calls": the card hints (:856-866), Forget (:921), and "no sheet" (:949), with the
  receipts.

## Promises old → new → basis

| Old | New | Basis |
| --- | --- | --- |
| Order: active first, then online, then the rest; pinned per visit | Favourites, then devices by name; never by state | operator R36b |
| Stop all shows only while something is up | Always shown, disabled at 0 | header must not move |
| Card line «Помощник: …» with Forget is the ONE place, with no sheet | The ONE place is the chip and its sheet; no ⋮ item and no list in Tools | operator chose variant Б, 2026-11-18 |
| Forget: one tap, both lifetimes at once, no confirmation, stops use at once, failure retried | Same, via «Снова спрашивать» in the sheet: two taps from the list | retained, one more tap (operator's choice) |
| Line shown on the device card and the Tunnels tab, also while down and while the global switch is off; year shown when not current; gone when the grant ends | Same for the chip | retained |
| Stored data: profiles, connections, settings in app-private storage, excluded from backup and transfer | Adds a favourite flag per saved connection and the remembered filter, in the same storage, with the same exclusions | retained |

## Slices (one PR each, released separately)

1. Stable order.
2. ✦ assistant glyph.
3. Compact card and assistant chip with its sheet. Independent review.
4. Chat question card and receipts.
5. Header: search, filter, Stop all.
6. Favourites, swipes and the Devices chip for all profiles. Stored data, so independent review.

## Acceptance

- The order does not change on a visit, a pull, a connect, a disconnect or a device going online or offline. Unit test on the ordering
  function, plus an instrumentation test across a tab round trip.
- At 360 dp, 200 % font, portrait and landscape:
  - no text is cut or broken inside a word;
  - card buttons are reachable;
  - the header takes at most two rows.
  Screenshots are looked at.
- The chip and sheet appear exactly when the grant or record exists, and «Снова спрашивать» ends both lifetimes at once. The existing
  grant tests are kept or ported.
- A favourite survives a restart, is excluded from backup and transfer, and is gone with its connection.
- The filter survives a restart; at 0 it is disabled.
- Swipe actions run only on the revealed button's tap. TalkBack exposes them as actions.

## Validation

- Per slice:
  - focused unit tests and assembleDebug;
  - emulator: the changed screens' classes in portrait 1.0 RU and landscape 2.0 EN.
- Before each release: the full gate and lint on main.
