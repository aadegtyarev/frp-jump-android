# frp-jump for Android — MQTT explorer contract

Status: approved by the owner 2026-10-31; revised after review the same day;
publishing reshaped to the owner's answers the same day (one Publish…,
editable topic, slices 2a/2b). The UI rules of `docs/contract.md`
apply unchanged.

A generic MQTT explorer for a broker reached through a frp-jump tunnel: the
topic tree browsed like folders, live values, publishing anywhere in the
tree, retained topics handled, branches deleted. No vendor conventions are
built in (the tree is the broker's tree as is); payload views are pluggable
so a convention can be added later without touching the explorer.

## What it is for

The owner's typical jobs on a controller (for example a Wiren Board):

1. **Look around.** Which devices and controls exist, what they hold now,
   which change and how often. → the tree with values, counts and ages.
2. **Check one thing live.** A sensor, a relay state, a rule's output:
   watch one topic or a branch change in time. → a topic's history and the
   branch feed.
3. **Act.** Switch a relay, set a value, send a command or a JSON payload
   to a topic, possibly one that does not exist yet under a known branch.
   → publish at the selected topic or under it.
4. **Clean up.** Stale retained topics of a removed device or a renamed
   rule linger forever. → delete one retained topic or a whole branch.
5. **Take it elsewhere.** A path for a script or a rule, a value for a
   bug report. → copy path, copy value, paste a path to publish to.

Anything that does not serve one of these is out.

## Words

The same thing is called the same way on every screen:

- **Topic**: a path the broker delivers messages for (`/devices/x/controls/K1`).
  A topic's **levels** are the parts between `/`; a level may be empty.
- **Topic filter**: what MQTT subscribes to, a topic that may hold the
  wildcards `+` (one level) and `#` (this level and all below).
- **Search**: a match on topic paths, never on values: a plain query is
  a case-insensitive substring (in the tree and the feed); in the tree a
  query with `* ? + #` is a **search pattern**, matched level by level
  and exactly, case included (see "The tree"). Searching never
  subscribes; a pattern's filter is pinned only on request.
- **Pin**: a topic or branch the owner keeps subscribed, with a **mask**,
  the form of its topic filter (see "Pins"). In Russian «Подписка».
- **Pattern**: a topic template with named parts used by plugin rules
  (see "Payload views (plugins)"); not a topic filter.
- **Seen topic**: a topic the session has received at least one message
  for. Every count in the explorer counts seen topics; the app cannot know
  topics the broker has not sent.
- **Session**: one explorer connected to the broker of one connection.

## Where it lives

- A fourth connection kind, **MQTT**, next to Web, SSH and TCP, with the
  preset 1883 (8883, which turns TLS on, comes with TLS in stage 5). On the wire it is a plain TCP
  port (`protocol` `tcp`), so the server sees no change. Its main action
  is **Explore** («Обзор», named for what it opens), which opens the
  explorer for that tunnel, bringing the tunnel up first, as Terminal
  does for SSH. "Copy address" is in "⋮" as
  for the other kinds. TCP keeps its main action "Copy"; the TCP preset
  1883 moves to the MQTT kind. Existing TCP connections (also on 1883)
  stay TCP; a TCP connection's "⋮" offers **Open as MQTT**, which changes
  its kind to MQTT and opens the explorer. It works with the tunnel up:
  the wire protocol stays `tcp`, so the lock on changing the kind of a
  live connection does not apply.
- The bottom bar stays four items. The "Terminals" tab becomes
  **Sessions** and lists terminal and MQTT sessions together, each with its
  connection's name and kind.
- Like the terminal, an explorer session lives in the app, not the screen:
  leaving keeps it connected (the tree keeps filling), with the notice
  "N sessions keep running: Sessions tab"; "Explore" on the same connection
  returns to its one session; sessions close only deliberately, from the
  Sessions tab (✕ on its row) or the explorer's "⋮", with Undo (UI rules):
  the session ends when Undo is gone.
- Counts of sessions include both kinds: the notification ("N sessions")
  and the "keeps running" notice.

## Connecting

- Broker: `127.0.0.1:<local port>` of the tunnel. Client id
  `frpjump-<random>`.
- **Version.** MQTT 5 first. The app falls back to 3.1.1 when the broker
  answers CONNECT with CONNACK "unsupported protocol version" or closes the
  connection before any CONNACK; the session then stays on 3.1.1 until it
  closes and shows "MQTT 3.1.1" in its details. The Wiren Board firmware on
  Debian 13 (trixie) ships Mosquitto 2.0.21, older controllers (Debian 11)
  Mosquitto 2.0.20; both speak 5.
- **Session state.** Nothing is kept on the broker: MQTT 5 with clean start
  and session expiry 0, 3.1.1 with clean session. Keep alive 30 s.
- **Login** (stage 1). Anonymous by default. When the broker refuses with
  "not authorized" or "bad user name or password", a login dialog like
  SSH's asks for user and password, with **Remember** (per connection,
  sealed like SSH passwords). A remembered login is tried first; refused,
  the dialog opens again with the user filled in and "Wrong user or
  password".
- **TLS** (stage 5). On for 8883, or by a switch on the connection; the
  broker's certificate is pinned on first use, exactly as for web pages
  (the same "Trust this certificate?" and "The certificate has changed"
  dialogs with both SHA-256 fingerprints).

## Subscriptions

All the session's subscriptions, in MQTT 5, use Retain As Published (a
live message keeps the retain flag it was published with) and Retain
Handling 0 (retained values are sent on every subscribe). 3.1.1 has
neither option; see "Retained topics" for what that changes.

- **Tree snapshot.** On connect the app subscribes to `#`, records what
  arrives and unsubscribes. On a Wiren Board the retained topics are
  nearly the whole tree, so the snapshot is too. The snapshot ends at the
  first of:
  - 1 s without a new topic (counted from the broker's SUBACK); new
    values of topics already in the tree do not count, since a live
    controller sends them all the time, while a known retained value
    re-sent unchanged (the re-delivery after a reconnect or on a
    branch re-read) does;
  - 10 s after the SUBACK;
  - the session's topic limit.

  Ended on 10 s or on the limit, the tree says so ("The tree was read for
  10 s, new topics kept coming: some may be missing" / "Topic
  limit reached: 100 000"). A topic that is not retained appears only when
  a message arrives for it while a subscription covers it. "Refresh tree"
  (in "⋮") clears the tree and takes a new snapshot.
- **The open branch.** Live values come for the branch being viewed: at
  path `<p>` the app is subscribed to `<p>/#`, which also matches `<p>`
  itself, moving the subscription as the owner navigates. The root `/`
  (the whole tree) is not subscribed live, to spare traffic and the phone:
  its rows show what the session has seen, that is the snapshot plus what
  the open branches and the pins brought (so their counts and ages move
  while a branch is open), and "Refresh tree" re-takes the snapshot. Live
  values start at the first level; the empty first level is the branch
  `/#`. A large branch fills up to the session's topic limit and then says
  so as above.
- **`$` topics.** By MQTT rules `#` and any filter starting with `+` never
  match topics starting with `$`, so `$SYS` is never in the snapshot or
  the root. The root always shows a `$SYS` row; opening it subscribes
  `$SYS/#` like any branch, and a pin may name it (`$SYS/#`). A broker
  without `$SYS` leaves the branch empty ("No messages").
- **Pins.** Kept subscribed for the whole session (see "Pins").
- **Refused.** A broker that refuses `#` (SUBACK failure, usually an ACL)
  gets an inline banner on the tree: "The broker does not allow reading the
  whole tree" with **Pin a branch…**, which asks for a path and pins it
  with `#`. The tree is then built from the pins and the open branch; a
  refused branch says so on its level.
- **One message, one entry.** Messages are keyed by topic. One publish can
  arrive more than once when several of the session's subscriptions match
  it (the open branch and a pin). Under MQTT 5 the app matches the topic
  against its own filters and takes a message only through the first
  matching subscription in the order open branch, then pins in their
  order; copies through the others are dropped. (The client library does
  not let the app set subscription identifiers; the result is the same.) Under 3.1.1 a copy is a
  message identical to the one just received for that topic (payload,
  retain, QoS), arriving before any other message for that topic, at most
  one fewer than the number of matching subscriptions. A copy is never
  shown, counted or kept in history.
- **Limit.** One limit for the session: 100 000 topics, 20 values of
  history each. At the limit new topics stop being added while the known
  ones keep updating, and the tree says so ("Topic limit reached: 100 000;
  new topics are not shown"); "Refresh tree" starts over.

## Pins

- A pin is a subscription the owner keeps. Its mask, for a topic `<t>`:
  - **Topic only** (default): `<t>`;
  - **One level below**: `<t>/+`, the direct children, not `<t>` itself;
  - **Whole branch**: `<t>/#`, `<t>` and everything below it.
- Pinned from a topic's or a branch's card ("Pin…", choose the mask);
  "Unpin" on the same card. The Pin sheet says in one line what a pin
  gives (the value keeps updating while other branches are browsed; the
  pin is kept for the connection and subscribed again on every connect)
  and each mask says what it covers (topic only; `+` the direct children;
  `#` everything inside, the topic too). A search pattern with only `+`
  and `#` pins its filter ("Subscribe to this filter", see "The tree").
  From stage 5 also typed as a topic filter on the Subscriptions tab.
- A typed topic filter is checked before it is saved: not empty; `#` only
  as a whole level and only the last one; `+` only as a whole level. The
  field says which rule failed. The owner is told that a filter whose
  first level is a wildcard (`+/…`, `#`) never matches `$` topics.
- Pins are kept per connection on the phone, also after the session and
  the tunnel are closed, and are subscribed whenever a session of that
  connection opens. Pinned topics carry a pin mark in the tree and are
  listed on top of the feed.

## The tree

- A path bar on top as in Files, but its root is a house icon, read out
  as "Root" (the whole tree; a `/` would read as a real topic prefix
  here), then one part per level. The empty first level, under which every
  topic starting with `/` lives, is the part `/` (read out as "leading
  slash") with no separator after it, so `/devices/wb-mr6c_1` reads
  `⌂ /devices / wb-mr6c_1`, each of `⌂`, `/`, `devices` and `wb-mr6c_1` a
  part of its own; an empty level further down is `(empty)`. The card of
  the empty first level shows and copies its path as `/`, publishes from
  `/`, and pins only as `/+` or `/#` (its own text is empty, no topic). Tap a part to go there; tap the path to type
  or paste a topic, taken as is (`/devices/x` and `devices/x` are different
  places); the dialog opens with the current path selected whole, so a
  paste replaces it, and the keyboard's action key is Go. A typed path to a topic without children opens its parent level
  and the topic's card; a path nothing has been seen under opens that
  level, empty, saying so. Back goes up a level, then leaves.
- Rows are the children of the current level:
  - a branch: name, number of seen topics under it, the newest change's age;
  - a topic with a value: name, the value shortened to one line, its age,
    a mark when retained, a pin mark when pinned;
  - a node can be both (a value and children): shown as a branch with its
    value line; a tap opens its card (the value is what is usually
    wanted), whose **Open branch (N)** opens its level. A branch without
    a value of its own opens its level on a tap. Every branch row has
    "⋮" for its card;
  - the empty first level is named `/ (leading slash)`; an empty level
    further down `(empty)`; an empty value is shown as `(empty value)`.
- Ages: a value delivered as retained when a subscription starts (the
  snapshot, a branch or pin just subscribed: a topic's first value with
  the retain flag, until 1 s after the SUBACK or after the last such value)
  was set before the session saw it, and when is not known. It reads
  "before connecting" instead of an age or a time, in the row, the card
  and its history, and is no change of its branches, until a new value
  comes. A branch whose topics are all such reads "before connecting" too.
  A SUBACK that never comes keeps this window open 10 s after the
  subscribe at most. "Refresh tree" forgets every value, so the values it
  re-reads read "before connecting" again (strictly, before the refresh),
  including ones seen changing earlier in the session; a reconnect keeps
  the tree, so an equal value re-sent keeps its label and one that changed
  while away is a change seen at its arrival.
- Order: `(empty)` first, `$SYS` last, the rest by name in natural order:
  runs of digits compare by number (`K2` before `K10`), other text
  case-insensitively, ties by exact text.
- A value that just changed: its age label turns the accent colour for a
  moment and fades back; with system animations off it switches back
  without a fade. Nothing else moves.
- Search in the top bar filters the current level and below by path
  substring (`K1`, `meta/error`) or by a search pattern (below) and shows the matching topics as a flat
  list, paths relative to the current level as their real text (at the
  root `/devices/x/controls/K1`); tap opens the topic. The field's hint
  says where it looks ("Search in /devices/x", at the root "Search the
  whole tree"); an empty result in a branch offers **Search the whole
  tree**, which widens it until the search is closed. A long path in the
  hint is cut in the middle. Going elsewhere from a hit (its card's Open
  branch) or by a typed path closes the search.
- **Search patterns.** A query holding any of `* ? + #` is a pattern,
  matched level by level, exactly (case counts, as in MQTT):
  - `*` is any run of characters within one level (none too), `?` exactly
    one character; neither crosses `/`. Every other character is literal;
    there is no escaping (a topic holding `*` is found with `?` there);
  - `+` is exactly one level (an empty one too), `#` this level and all
    below and, as in MQTT, the level above it; each takes a whole level,
    `#` only the last. Mixing is allowed: `/devices/+/controls/K*`;
  - without a leading `/` the pattern starts at the open level (or the
    root once widened to the whole tree); with one it starts at the root,
    and the leading `/` is the empty first level, so `/devices/+` finds the
    topics starting with `/devices/`. A pattern whose first level at the
    root starts with a wildcard skips `$` topics, as `+` and `#` do. The
    level the search starts at is never a hit of its own;
  - hits are listed as for a substring, relative to where the pattern
    starts (at the root, full paths); `meta` and the like only when the
    pattern asks for them;
  - while the query is a pattern, one muted line on top of the hits says
    the syntax (`* ? within a level · + one level · # all below · / first:
    from the root`). `+` or `#` with other characters in a level, or `#`
    before the last level, is not searched: that line says why instead
    ("+ and # take a whole level; within a level use * and ?", "# can only
    be the last level");
  - under the hits of a pattern with only whole-level `+` and `#`, its
    absolute filter (a relative pattern put under the level it started
    at) and **Subscribe to this filter**, which pins it ("Pinned: <filter>"
    notice); already pinned (that filter, or a pin delivering all it does,
    such as `/devices/#` for `/devices/+/controls/#`), the button is off
    and says so; in a closed session it is off with "Session closed". A
    pattern with `*` or `?` shows the button off with "The broker does not
    understand * and ?: only + and # can be subscribed". The reason is read
    out with the button. A relative `#` pinned in branch `X` (`X/#`) also
    delivers `X` itself, as MQTT does, although the search did not list it;
  - patterns are exact, as the broker is; when one finds nothing but
    would ignoring case, the empty result says so ("Case matters: N found
    ignoring case"), one extra run only then;
  - the field's hint shows only while the field is empty, so it never
    speaks of a pattern; where a pattern starts is in its syntax line
    (`/ first: from the root`) and in the hits' paths.
  Patterns are matched by walking the tree level by level, looking an
  exact level up rather than scanning it, with no regular expressions: a
  glob level costs at most pattern × level characters.
- The explorer's "⋮" and an empty level ("No messages") offer **Publish
  here…**: the publish sheet with the topic `<level>/` (cursor at its end,
  so the new topic's name is typed straight after it), a way to create a
  topic nothing has been seen at; at the root the topic starts empty.

## A topic

Tapping a topic (or "⋮" on a branch) opens its card, a bottom sheet:

- the name, then right under it the value in full, large when it is a
  short line, with a copy button, then size, retained, QoS and time;
- the view under it, small: **Text**, **JSON** (pretty-printed, foldable)
  or **Hex**, detected (not UTF-8 → Hex; UTF-8 that parses as a JSON
  object or array → JSON; else Text) and switchable by hand for this card;
- the full path with a copy button; a branch's last change, only when it
  is not the value's own time;
- the last values with times (up to 20), newest first;
- actions, by stage:
  - stage 1: copy path and copy value (the buttons next to them, labelled
    for screen readers), **Open branch (N)** (a node with children),
    **Pin…** / **Unpin**;
  - stage 2: **Publish…**, the card's main (filled) button;
  - stage 4: **Delete** (a retained topic) and **Delete branch** (a
    branch), both in the card's "⋮".

## Publishing

One sheet, opened by **Publish…** in a topic's or a branch's card. There is
no separate "publish under": the topic field is the way to any other topic.

- **Topic:** prefilled with the card's path (or `<level>/` from "Publish
  here…"), cursor at the end, so a subtopic is a `/name` away; any path
  can be typed over it or pasted.
  The prefilled path itself can be sent as is, a branch's too (it is also
  a topic of that name). Checked before Send: not empty, no `+` or `#`, no
  NUL character, at most 65 535 bytes in UTF-8. A topic starting with `$`
  gets a warning (brokers reserve these) but can be sent.
- **Payload:** a text field. From a topic with a value it starts with
  that value, selected whole (typing replaces it), when the value is
  UTF-8 and at most 2 KB, else empty; the focus is then on it. From a
  branch without a value, or "Publish here…", the focus is on the topic. 2b adds a format switch Text / JSON (JSON is
  validated and can be formatted) and the last payloads sent to this
  topic, one tap away.
- **Retain** and **QoS** (0/1/2); with Retain on and an empty value the
  sheet warns in one line that it deletes the topic's retained value; 2a starts each sheet at retain off,
  QoS 1; 2b remembers them per topic.
- From stage 6 only: the status/command switch (see the plugin below).
  Before it the value goes to the topic in the field, nothing else.
- Send answers on the button itself (its label becomes the answer,
  wrapping onto a second line rather than cut, in the error colours for a
  refusal or failure, read out by screen readers, until anything is
  changed), so nothing below it moves:
  - MQTT 5 with QoS 1 or 2: "Accepted by the broker", or "Refused: <the
    broker's reason>" (for example "not authorized");
  - QoS 0, or 3.1.1 (whose brokers drop a refused publish silently):
    "Sent, not confirmed".

  If the topic is covered by a subscription its new value shows in the
  tree.

## Feed

The branch's live changes as a list, newest on top: time, path relative to
the branch, value. Pause (the list stops, counting what arrives), filter by
search, tap an entry to open its topic. Pinned topics are listed on top
with their latest values. A retained value re-sent by the broker on a new
subscribe, equal to the value already known, is not a change: not in the
feed, not in history, no highlight. This is the "what is going on" view;
the tree is the "what is there" view.

## Retained topics and deleting

- **The mark.** Under MQTT 5 a message's retain flag is the publisher's
  (Retain As Published): retain 1 sets the mark; an empty retained
  message means the retained value was cleared, so the topic shows
  "(cleared)" without the mark; an empty message that is not retained is
  an ordinary value, `(empty value)`. Under 3.1.1 brokers deliver live
  messages without the flag, so the mark is sticky: set by any message
  delivered with retain 1 (on subscribe), cleared only by an empty message
  or by Delete.
- Moving the branch subscription re-delivers the branch's retained
  values; this refreshes marks and is not shown as change (see "Feed").
- **Delete** (a retained topic, card "⋮"): the topic goes from the tree at
  once with **Undo** (UI rules); when Undo is gone, publishes an
  empty retained message, QoS 1, to it (the MQTT way to remove it); the
  answer as for Send.
- **Delete branch** (a branch, card "⋮"):
  1. The app re-reads the branch (subscribes `<p>/#` afresh, the snapshot's
     stopping rule) and takes the topics delivered as retained.
  2. No confirm: the app unsubscribes the branch and shows, for a few
     seconds, how many it will delete with **Undo** ("Deleting 37
     retained topics under /devices/wb-old · 4 not retained, untouched ·
     Undo"). Undo puts the branch back; nothing was sent.
  3. When Undo is gone it publishes an empty retained message, QoS 1, to
     each, with progress ("12 of 37 deleted") and **Stop**, which stops
     before the next one.
  4. After the last one it re-reads the branch again, then sums up: "N
     deleted, M refused, K came back". Refused: an MQTT 5 reason code on
     the PUBACK. Came back: deleted topics delivered as retained again by
     the re-read (under 3.1.1 a refused delete shows up here, as 3.1.1
     reports no refusal).

  Only the topics the fresh re-read delivers as retained are published to,
  not every seen topic: an empty message to a command topic such as
  `.../on` could act on the device.

## A session's life

- **Tunnel drop.** The explorer shows a "Reconnecting…" banner, keeps the
  tree and disables Send. The tunnel comes back on the same local port
  (as all tunnels do); the client reconnects after 1, 2 and 5 s, then every
  10 s, subscribes the open branch and the pins again and re-takes the
  snapshot, which refreshes values and retained marks. Topics are not
  removed by a reconnect; "Refresh tree" does that.
- **Disconnecting the connection** closes its session when the
  disconnect's Undo is gone (UI rules).
- **Process killed or phone rebooted.** The tunnels come back (contract
  scenario 8); the explorer session does not: the tree lives only in
  memory. "Explore" opens a new session; its pins are subscribed again.

## Feedback (the UI rules applied)

- Progress where tapped: the "Explore" button shows a spinner until the
  broker answers; the snapshot and "Refresh tree" show a bar under the
  path bar with the count ("Reading the tree… 1 234 topics"); Delete
  branch shows its progress in its dialog.
- Rare actions live in "⋮": Delete, Delete branch, Refresh tree, Close
  session.
- Failures, in place of the tree, each with **Retry**:
  - connect refused: "No broker answers on port 1883 of <device>";
  - login refused: the login dialog, as above;
  - TLS: the certificate dialogs of web pages; not trusted, "Not
    connected: the certificate was not trusted";
  - no common version: "The broker refused MQTT 5 and 3.1.1".

## Payload views (plugins)

- A payload view turns a topic's raw bytes into what the card and the
  tree row show. Built in: Text, JSON, Hex.
- Views are chosen by topic filter (first match wins), so a later view for
  a convention (Wiren Board `/devices/+/controls/+`, Zigbee2MQTT, Home
  Assistant discovery) plugs in by declaring its filters and how it shows
  a value and its metadata. The explorer never depends on a view.
- Plugin form: declarative (a JSON description of filters and fields
  shipped with the app or imported), not downloaded code: Android forbids
  loading foreign code into an app of this kind safely. Plugins are enabled
  per connection.
- **First plugin: "Status and command topics".** A list of rules, each a
  status pattern, a command pattern and optional exclusions.
  - Pattern syntax: levels split by `/` as in topics; a level is literal
    text, `{name}` (exactly one level), or `{name...}` (one or more levels;
    at most one per pattern, at any position). A pattern holds at least one
    literal level and no MQTT wildcards. A pattern starting with `/` has an
    empty first level and matches only topics that have one. The command
    pattern uses only names of the status pattern.
  - Matching: without `{name...}` the topic has as many levels as the
    pattern and each level matches in turn (literal: equal text, case
    sensitive; `{name}`: any one level, which may be empty). With
    `{name...}` the levels before it match the topic's first levels one by
    one, the levels after it match the topic's last levels one by one, and
    `{name...}` takes what is left in between, which must be at least one
    level. So `{x...}/state` matches `home/light/state` with `x` =
    `home/light`, and `{x...}/set` then gives `home/light/set`.
  - Order matters: rules are tried top to bottom, the first match wins;
    the owner's own rules come first, then the enabled presets in their
    shipped order, most specific first:
    1. Wiren Board: `/devices/{d}/controls/{c}` →
       `/devices/{d}/controls/{c}/on`;
    2. Zigbee2MQTT: `zigbee2mqtt/{d}` → `zigbee2mqtt/{d}/set`, except
       `zigbee2mqtt/bridge` and `zigbee2mqtt/bridge/{rest...}`;
    3. Tasmota: `stat/{d}/{p}` → `cmnd/{d}/{p}`, except `stat/{d}/LWT`;
    4. a generic "state/set" pair: `{x...}/state` → `{x...}/set`, except
       `zigbee2mqtt/bridge/state`.
  - An exclusion is a pattern; a topic matching it is not matched by the
    rule, on either side.
  - For a topic matching a status pattern, the command topic comes from
    the other pattern. A topic matching a command pattern is shown as the
    command of its status topic, under it, when that status topic is in
    the tree; with no status topic it stays an ordinary topic.
  - The publish sheet gets a switch "Status topic / Command topic". Its
    default: the command topic when a status rule matches the selected
    topic, else the topic itself. The rule that matched is shown; the
    switch can be changed for this send.

  The owner turns presets on per connection and adds own rules in the app
  (two patterns, no code), so a new device kind needs a rule, not a plugin.
  It is simple on purpose: it proves the plugin plumbing (description
  format, matching, enable per connection, override in the publish sheet)
  before any richer view.

## Later: charts

Not part of the delivery stages 1–6 or of the acceptance; the data model
keeps room for it: numeric values of chosen topics are collected while the
session runs and drawn as charts over time, several on one chart with their
own scales, to see how parameters depend on each other (a room's
temperature against a valve's position, a current against a relay). The
topic history then needs a longer, numeric-only buffer per charted topic;
nothing else in the explorer changes for it.

## Stored on the phone

| What | Where |
| --- | --- |
| Pins with their masks, enabled plugins and own rules, retain/QoS per topic and last payloads sent (2b) (per connection) | app-private storage |
| Broker logins (only with Remember), pinned broker certificates | sealed / app-private, as for SSH and web |
| The tree and history | memory only, for the session |

## Delivery

1. The MQTT connection kind (preset 1883), "Open as MQTT" for TCP
   connections, and the Sessions tab; connect
   (MQTT 5 with the 3.1.1 fallback, anonymous, then login), tree snapshot,
   root and `$SYS`, the open branch live, path bar, search, topic card with
   Text/JSON/Hex, Copy path, Copy value, Pin with masks, reconnect.
2. Publish, in two slices:
   - 2a: the sheet (topic, payload as text, retain, QoS), Send and the
     broker's answer;
   - 2b: Text/JSON with validation and formatting, recent payloads, retain
     and QoS remembered per topic.
3. Feed with pause, filter and pinned topics.
4. Retained delete for a topic and a branch.
5. TLS (preset 8883, pinned certificate); the Subscriptions tab: pins
   listed, masks changed, typed topic filters.
6. Plugin infrastructure (declarative descriptions, matching, enable per
   connection) with the first plugin, "Status and command topics", and the
   publish sheet's switch; Text/JSON/Hex views move onto the same plumbing.
7. Later: charts of chosen numeric topics, overlaid.

Each stage is delivered in small PRs, each reviewed; a stage's first
build for the owner's phone goes out once something in it is visible, then
after each visible PR. Checked on the emulator against a test broker.
There is one explorer session per connection.

## Acceptance

On a Wiren Board on the tunnel unless said otherwise.

1. Connect, tree, card, pins, login:
   - A new MQTT connection on 1883 has the main action "Explore"; the
     explorer opens with the tree; the root holds `/ (leading slash)` and `$SYS`,
     is not subscribed live and shows what the session has seen;
     an old TCP connection on 1883 becomes MQTT by "Open as MQTT";
     `/devices/*/controls/*` and `/devices/*/meta` show live values;
     `wb-mr6c_1/controls/K1` changes as the relay switches.
   - `$SYS` opens and shows the broker's topics.
   - On the Wiren Board, whose values change all the time, the snapshot
     ends about 1 s after the last new topic and shows no banner. A test
     broker that keeps publishing new topics: the snapshot stops at 10 s
     and says so. The topic limit, lowered in a test build: "Topic
     limit reached".
   - `/devices/wb-mr6c_1/meta` shows as JSON, a binary payload as Hex; the
     switch changes the view.
   - Pin `K1` (topic only) and `controls` with "One level below": `K1` and
     the controls update while another branch is open; close the tunnel
     and open "Explore" again: both are subscribed again.
   - A broker with a password: the login dialog; a wrong password says
     "Wrong user or password"; with Remember the next session connects
     without asking.
   - A broker speaking only 3.1.1 (Mosquitto 1.5 or older; MQTT 5 came in
     1.6), and a test server that closes the connection on an MQTT 5
     CONNECT: both sessions connect on 3.1.1 and say so.
   - Cut the tunnel for 20 s (airplane mode): "Reconnecting…", then the
     same local port, the tree kept, pins and branch live again.
   - Leaving and returning keeps the session; the tree is not rebuilt; the
     Sessions tab lists it with the terminals; Disconnect warns "N sessions
     will close" counting both.
2. Publish:
   - 2a: from the card of `/devices/wb-mr6c_1/controls/K1`, Publish…,
     append `/on` to the prefilled path, send `1`: the relay switches and
     the tree shows the new state. From a branch card, a new subtopic
     reached by editing the path is created.
   - 2b: invalid JSON is flagged before Send and Format pretty-prints
     valid JSON; a payload sent before is one tap away on reopening the
     sheet for that topic; retain and QoS come back as last set for it.
   - Answers (2a): a test broker with an ACL denying a topic: QoS 1 under
     MQTT 5 says "Refused: not authorized"; QoS 0 says "Sent, not
     confirmed".
3. The feed of `/devices/wb-msw-v3_21` shows temperature updates; pause
   stops it and counts; pinned topics are on top; navigating away and
   back adds no repeated entries.
4. Retained:
   - Publish retained `x` to a test topic: marked retained under MQTT 5
     and 3.1.1; publish empty retained: "(cleared)"; an empty non-retained
     message shows `(empty value)`.
   - A retained branch of a removed device is deleted with one tap, the
     notice counting the retained topics with Undo; the summary says "N deleted, 0
     refused, 0 came back"; the branch is gone after a reconnect. A device
     that republishes during the delete shows in "came back".
5. TLS: a broker on 8883 asks to trust its certificate; after the
   certificate is replaced, "The certificate has changed" with both
   fingerprints; not trusted, "Not connected" with Retry. The Subscriptions
   tab takes `$SYS/#` and refuses `a/#/b`.
6. With the Wiren Board preset on, the publish sheet for `K1` defaults to
   "Command topic", shows the rule, and `1` goes to `K1/on` and switches
   the relay; switched to "Status topic", the value goes to `K1` itself.
   With the generic preset on, `home/light/state` offers
   `home/light/set`.

## Decided

- MQTT 5 with a 3.1.1 fallback, after the brokers on the owner's
  controllers (Mosquitto 2.0.20 and 2.0.21).
- A fourth connection kind, MQTT, with the main action "Explore"
  (first "MQTT"; renamed after the UX walkthrough to name what it opens,
  as "Terminal" does for SSH).
- The explorer stays generic: no vendor conventions (Wiren Board meta,
  titles, units, `/on`, read-only controls) in the explorer itself; they
  come as payload views (plugins).
- The path bar's root is a house icon ("Root"); the empty first level is
  `/` ("leading slash").
- The bottom bar stays four items; "Terminals" becomes "Sessions" for
  terminal and MQTT sessions.
- One concept, the pin (a subscription with a mask); no separate "Watch".
- Login in stage 1, TLS in stage 5.
- The change highlight is the age label in the accent colour, nothing more.
- The root is not subscribed live: it shows what the session has seen
  (the snapshot plus open branches and pins), "Refresh tree" re-takes the
  snapshot; live values start at the first level (economy of traffic and
  phone load).
- The snapshot's quiet second counts what fills the tree (new topics,
  retained values re-sent unchanged, as on a reconnect or a branch
  re-read), not new values of known topics, so a live controller does not
  run it into the 10 s cap.
- At the topic limit new topics stop being added, known ones keep
  updating, and the screen says so.
- Delete branch publishes only to topics a fresh re-read of the branch
  delivers as retained, so no command topic gets an empty message.
- Existing TCP connections on 1883 stay TCP; "Open as MQTT" in their "⋮"
  converts them.
- Plugin patterns allow one `{name...}` at any position.

## Open risks

- Memory: 100 000 topics with 20 values each is up to 2 million values; a
  few large payloads per topic could exhaust a phone's memory before the
  limit is reached.
- A busy broker over mobile data: the snapshot and large branches pull
  every message the broker sends, costing data and battery.
- The tunnel is a TCP port on 127.0.0.1 open to every app on the phone
  while it is up (`docs/contract.md`); a broker without a password is then
  open to them too.
