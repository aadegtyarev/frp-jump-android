# frp-jump for Android — MQTT explorer contract

## Boundary and ownership

The MQTT explorer browses and publishes to a broker reached through a
frp-jump TCP tunnel. It serves users inspecting topics, watching values,
sending commands and copying paths or payloads. There is one app-owned
explorer session per connection; screens read that session rather than
owning the MQTT client or its cache.

The explorer owns generic topic, subscription, history and Publish behavior.
The Status and command topics plugin owns rule-based publish targets; the
Wiren Board plugin owns convention-specific devices, metadata and controls.
Neither changes the broker's tree or imposes a vendor policy on generic
Publish. Broker ACLs remain the authority for access.

The [shared UI rules](contract.md#ui-rules) apply throughout: common actions
stay direct, labels and feedback stay short, and progress, errors,
accessibility and safety warnings must not be lost for visual minimalism.
The normal path is connection → **Explore** → branch or topic card →
**Publish…**; pins keep selected topics live while browsing elsewhere.
The WB tab offers controls directly on a device's rows.

This reference describes available behavior except where explicitly marked
unavailable below. It is not a release or runtime-conformance report.

## Words

The same thing is called the same way on every screen:

- **Topic**: a path the broker delivers messages for (`/devices/x/controls/K1`).
  A topic's **levels** are the parts between `/`; a level may be empty.
- **Topic filter**: what MQTT subscribes to, a topic that may hold the
  wildcards `+` (one level) and `#` (this level and all below).
- **Search**: a match on topic paths, never on values: a plain query is
  a case-insensitive substring; a
  query with `* ? + #` is a **search pattern**, matched level by level
  and exactly, case included (see "The tree"). Searching never
  subscribes; a pattern's filter is pinned only on request.
- **Pin**: a topic or branch the owner keeps subscribed, with a **mask**,
  the form of its topic filter (see "Pins"). In Russian «Подписка».
- **Template**: a topic with named parts used by plugin rules (see
  "Payload views (plugins)"); neither a topic filter nor a search pattern.
- **Seen topic**: a topic the session has received at least one message
  for. Every count in the explorer counts seen topics; the app cannot know
  topics the broker has not sent.
- **Session**: one explorer connected to the broker of one connection.

## Where it lives

- A fourth connection kind, **MQTT**, next to Web, SSH and TCP, with the
  preset 1883. On the wire it is a plain TCP
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
- The bottom bar has four items (a fifth, "Assistant", is added while the assistant is on, see [contract.md](contract.md#optional-llm-assistant)). **Sessions** lists terminal and MQTT
  sessions together, each with its connection's name and kind.
- Like the terminal, an explorer session lives in the app, not the screen:
  leaving keeps it connected (the tree keeps filling), with the notice
  "N sessions keep running: Sessions tab"; "Explore" on the same connection
  returns to its one session; sessions close only deliberately, from the
  Sessions tab (✕ on its row) or the explorer's "⋮", with Undo (UI rules):
  the session ends when Undo is gone.
- Counts of sessions include both kinds: the notification ("N sessions"),
  the "keeps running" notice and Disconnect's "N sessions will close"
  warning.

## Connecting

- Broker: `127.0.0.1:<local port>` of the tunnel. Client id
  `frpjump-<random>`.
- **Version.** MQTT 5 first. The app falls back to 3.1.1 when the broker
  answers CONNECT with CONNACK "unsupported protocol version" or closes the
  connection before any CONNACK; the session then stays on 3.1.1 until it
  closes and shows "MQTT 3.1.1" in its details.
- **Session state.** Nothing is kept on the broker: MQTT 5 with clean start
  and session expiry 0, 3.1.1 with clean session. Keep alive 30 s.
- **Login**. Anonymous by default. When the broker refuses with
  "not authorized" or "bad user name or password", a login dialog like
  SSH's asks for user and password, with **Remember** (per connection,
  sealed like SSH passwords). A remembered login is tried first; refused,
  the dialog opens again with the user filled in and "Wrong user or
  password". Broker acceptance is independent of saving or removing the
  login on the phone: a local storage/Keystore error leaves the accepted
  connection open and subscriptions active. A separate warning says that
  saving was not confirmed or the previous login may still be stored.
  Its Retry repeats only that captured storage choice, without reconnecting
  or sending credentials to the broker again. A newer typed login or close
  invalidates an older retry. Automatic reconnect keeps the in-memory login
  and does not repeatedly retry a failed storage write. A file read IO error
  is not cached as an empty map and must not erase other saved entries;
  malformed stored JSON falls back to no saved logins.

## Incoming-message limits

Settings → MQTT incoming limits edits three per-explorer limits and the
overflow action. Values are saved only after validation and a successful
storage write; the form stays open on failure. Units in the form are bytes.

| Limit | Default | Valid range |
| --- | --- | --- |
| Incoming payload | 1,048,576 bytes (1 MiB) | 1,024–16,777,216 bytes |
| Waiting and processing data | 8,388,608 bytes (8 MiB) | Up to 268,435,456 bytes, at least payload limit +256 |
| Waiting and processing messages | 10,000 | 1–100,000 |

One message is charged its declared payload bytes, twice the UTF-16 length
of its topic, and a fixed 256-byte charge. Topic text must also fit. Capacity
is reserved before the adapter requests a payload byte-array copy and is
held through processing, including rejection of stale queued work. Messages
submitted with an already revoked producer lease are ignored before copying.
Previously admitted work remains charged and is not committed after ownership
changes. Reservations are not reset on reconnect while queued arrays still exist.

**Drop incoming** is the default: reject the whole incoming message and keep
receiving. **Stop session** stops that explorer and requires manual Retry;
it does not start an overflow/reconnect loop. Rejected-message feedback is
shown above every explorer tab: values may be missing or outdated. Payloads
are never truncated. A failed scheduler handover releases its reservation;
a failed stop notification keeps copying disabled and can be retried by a
later event without queuing one control action per rejected message.

Limits are captured when an explorer session is created. Automatic or manual
reconnect does not change them. To apply settings to an existing explorer,
close its session and create a new one; leaving the screen keeps the old
session. Invalid new setting fields fall back independently of unrelated
preferences. An inconsistent payload/queue pair falls back together while
valid count and action preferences survive.

This is an application handover budget, not a total-heap measurement or a
packet-size guarantee for the HiveMQ decoder, which may have allocated a
packet before the callback. Accumulated tree/history bytes have a separate owned-storage budget below.
Outgoing Publish, plugin readonly
rules, SSH paste and clipboard policies are unchanged.

## Stored-data limits

Settings → MQTT storage configures the owned cache of each new explorer:

| Limit | Default | Valid range |
| --- | --- | --- |
| Owned names, nodes and history | 33,554,432 bytes (32 MiB) | 4,096–268,435,456 bytes |
| Seen topics | 100,000 | 1–1,000,000 |
| Values kept per topic | 20 | 1–200 |

The same form chooses **reject the new value and continue** (default) or
**stop session; retry manually**. Settings are validated before the write;
the form waits for commit and stays open on failure. Invalid new fields or
unknown action values fall back independently without resetting unrelated
preferences. Existing explorers retain their captured configuration, also
on reconnect. Close a session and let its Undo window expire before opening
a new one to apply changed settings; merely leaving the screen does not.

Accounting charges 256 bytes for the root and each missing node, twice the
UTF-16 length of each newly retained level name, and 64 bytes plus payload
for each stored history value. A receive plans its final cost under the
tree lock before copying payload or creating nodes. Normal history-count
rotation credits entries that will leave the tail. If it does not fit, the
whole incoming value is rejected: no new branches, changed current value,
history eviction or ancestor statistics are committed. Equal retained
refreshes are budget-neutral: original time and before-session status are
preserved while retained/QoS marks refresh. Clear returns
the cache to the root charge. No payload is truncated, no global LRU or
extra history wipe is performed, and no depth limit is added.

Storage-byte rejection is distinct from the topic-count limit. An active
snapshot ends with a storage-limit reason, not a quiet/complete result;
plugin discovery must not infer that all metadata was read. After a cache
rejection, every explorer tab shows its count and current owned accounting. A cache-stop
failure offers explicit Refresh tree before manual Retry; refresh is also
enabled in that state, not in ended sessions or unrelated failures. Clear
and Retry do not hide historical rejection counts. Drop keeps live/pinned
subscriptions working, so smaller updates that fit can still arrive.

These are logical per-session owned-data charges, not an exact heap bound.
UI reader snapshots, transient copies and the MQTT library decoder are not
covered by this counter; budgets of simultaneously open sessions add up.

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
  without `$SYS` leaves the branch empty ("No messages"). While nothing
  of `$SYS` is in the tree its row says why on the meta line: "not
  subscribed" (no pin names it; a tap opens and subscribes it), "no
  messages" (a pin names it, nothing came), "no access" (the broker
  refused a filter under it); once its topics are seen it reads as any
  branch (age · N topics).
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
  order, then the plugins' subscriptions; copies through the others are
  dropped. (The client library does
  not let the app set subscription identifiers; the result is the same.) Under 3.1.1 a copy is a
  message identical to the one just received for that topic (payload,
  retain, QoS), arriving before any other message for that topic, at most
  one fewer than the number of matching subscriptions. A copy is never
  shown, counted or kept in history.
- **Limits.** Defaults are 100 000 topics and 20 history values each,
  together with the owned-storage budget described above; all are
  configurable. At the topic-count limit new topics stop being added while
  known ones may update if their history fits. Count and byte rejection
  have distinct snapshot reasons. "Refresh tree" clears the owned cache.

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
  A filter a broader pin already delivers is not pinned again, alike in
  the three places: the Pin sheet shows that mask off with "Already
  pinned (through <pin>)", the search's button is off with the same
  words, and the Subscriptions tab's "+" refuses it so.
  Also typed as a topic filter on the Subscriptions tab (see there).
- A typed topic filter is checked before it is saved: not empty, no NUL,
  at most 65,535 UTF-8 bytes; `#` only as a whole level and only the last
  one; `+` only as a whole level. The
  field says which rule failed. The owner is told that a filter whose
  first level is a wildcard (`+/…`, `#`) never matches `$` topics.
- Pins are kept per connection on the phone, also after the session and
  the tunnel are closed, and are subscribed whenever a session of that
  connection opens. Pinned topics carry a pin mark in the tree and remain
  visible on the Subscriptions tab.
- Unpinning changes only the phone's list, so it works in a closed
  session too (on a card, in the search, on the Subscriptions tab): the
  next session subscribes without it. Pinning and changing a mask need
  the broker and are off in a closed session, saying "The session is
  closed".

## Subscriptions tab

The explorer's top bar has the device as its title, on up to two lines
and drawn smaller rather than cut when longer, and "Port <n> · <MQTT
version or state>" under it. Under it two tabs, **Tree · Subscriptions**, drawn as
the app's segmented switch; the Wiren Board plugin adds a third, **WB**
(see "Payload views (plugins)"). The tab is kept
across rotation; Back on Subscriptions returns to Tree; the search (a mode
of the tree) hides the tabs and opens on Tree. Opening a level from
anywhere (a card's Open branch, a typed path) shows Tree. The connection
banners (log in, a failure with Retry, closed with the tunnel, refused
`#`) show on both tabs.

- **Rows**: every pin of the connection in the order made, without those
  waiting for Undo. Each row: the filter in monospace, then
  - a filter without wildcards (one topic): its value in one line, as in
    the tree, or "No messages"; then its age, "before connecting" by the
    tree's rules;
  - a filter with `+` or `#`: how many seen topics it matches in the tree
    (MQTT matching: `a/#` counts `a`, a first-level wildcard skips `$`
    topics, `*` and `?` are plain characters) or "No topics yet", then
    the age of the newest change among them ("before connecting" when
    all came from before the session).

  Rows are read once a second only while the tab is shown, by counting
  in the tree without building the topics' paths.
- **Tap**:
  - one topic: its card, over the list (closing it returns to the list);
  - a filter with a fixed start (`/devices/battery/#`, `<t>/+`): that
    branch opened in the tree; when wildcards go on below
    (`/devices/+/controls/K1`), the rest of the filter is also searched
    there as a pattern (`+/controls/K1` in `/devices`);
  - a filter starting with a wildcard (`+/controls/#`, `#`): the tree's
    search with it as a pattern over the whole tree;
  - a filter with `+` or `#` that also holds `*` or `?` (plain characters
    to MQTT, wildcards to the search, which would find more than the
    filter delivers) is never searched: its branch up to the first level
    holding a wildcard or either character opens (the root when that is
    the first level). Without `+` and `#` it is one topic, its card.
- **Unpin**: a swipe to the left reveals ✕ (as on the Devices screen);
  "⋮" on the row holds **Unpin** too; TalkBack gets "Unpin <filter>" as
  an action of the row. Gone at once, Undo on the shared notice, as on a
  card.
- **Change mask…** ("⋮", and a TalkBack action) for a pin a card can name
  (`<t>`, `<t>/+`, `<t>/#`): the Pin sheet titled "Change mask",
  "Replaces <filter>"; the masks pinned already are marked and not
  offered, nor a mask another pin already delivers (as on a card; the
  pin being replaced does not count); a pick replaces the pin in its
  place ("Pinned: <filter>" notice). Other filters have no mask to
  change.
- **+** (a floating button, as Files' upload): "Subscribe to a filter", a
  field taken as is. Checked as "Pins" says, the field saying which rule
  failed ("+ and # take a whole level.", "# can only be the last level");
  a filter already pinned ("Already subscribed.") or covered by a broader
  pin ("Already pinned (through <pin>)", as the search says) is not
  pinned again. Under the field, while it applies: a first-level wildcard
  never matches `$` topics; `*` and `?` are plain characters here.
  Pinned, the dialog closes with "Pinned: <filter>". The session closing
  while the dialog or the mask sheet is open closes it with "The session
  is closed"; nothing is pinned and no "Pinned" notice shows.
- **Empty**: "No subscriptions yet", what a subscription gives and the
  three ways to make one (a topic's card, a search pattern, +).
- **Closed session**: the stored pins stay listed with what the tree last
  held; taps still go to the card, branch or search; Unpin works (see
  "Pins"); Change mask… is off with "The session is closed" under it; +
  is muted and a tap says "The session is closed".

## The tree

Search, pattern matching, subscription summaries and convention value
collection traverse the tree with explicit iterator frames, not recursive
calls per topic level. One working path is grown and restored during the
walk; result paths are copied only for matches, and summaries count without
building result paths. This adds no topic-depth limit and does not change
the existing topic count or history limits. Case-sensitive exact pattern
levels still use direct child lookup, and the root-only `$` wildcard rule
and relative-path matching semantics are unchanged.

- A path bar on top as in Files, but drawn as a plain breadcrumb on the
  ground (no field outline), shown below the root only (at the root it
  would hold the house alone), and its root is a house icon, read out
  as "Root" (the whole tree; a `/` would read as a real topic prefix
  here), then one part per level. The empty first level, under which every
  topic starting with `/` lives, is the part `/` (read out as "leading
  slash") with no separator after it, so `/devices/wb-mr6c_1` reads
  `⌂ /devices / wb-mr6c_1`, each of `⌂`, `/`, `devices` and `wb-mr6c_1` a
  part of its own; an empty level further down is `(empty)`. The card of
  the empty first level shows and copies its path as `/`, publishes from
  `/`, and pins only as `/+` or `/#` (its own text is empty, no topic). Tap a part to go there; tap the path, or
  **Go to topic** in the top bar's menu (also at the root), to type
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
    notice). When the filter is a pin itself, the button is **Unsubscribe
    from this filter**, which unpins it as Unpin on a card does (gone at
    once, Undo on the shared notice), so a pin made here, which no card's
    mask can name, is never stuck. When only a broader pin delivers all it
    does (`/devices/#` for `/devices/+/controls/#`), the button is off with
    "Already pinned (through <pin>)"; in a closed session Subscribe is off
    with "The session is closed", Unsubscribe still works. A
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
- the last values with times (default 20, configurable), newest first;
- copy path and copy value, labelled for screen readers; **Open branch
  (N)** for a node with children; **Pin…** / **Unpin**;
- **Publish…**, the card's main filled button.

Text and JSON copy the raw payload text, Hex copies hex pairs. A copy is
limited to 65,536 characters, never splitting a surrogate or hex pair;
truncation is reported. JSON card detection is limited to 65,536 bytes and
64 levels of nesting; larger or deeper UTF-8 values remain Text.

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
  branch without a value, or "Publish here…", the focus is on the topic.
  Text / JSON selects the input format; JSON is validated and can be formatted. Recent payloads sent to this topic are one tap away.
  JSON validation accepts a single JSON value, including a scalar, up to
  65,536 UTF-8 bytes and 64 nesting levels; outside those limits use Text.
- **Retain** and **QoS** (0/1/2); with Retain on and an empty value the
  sheet warns in one line that it deletes the topic's retained value.
  Retain, QoS and format are remembered per topic after a successful or
  unconfirmed send; without a remembered choice: retain off, QoS 1, Text.
- When enabled, the Status and command topics plugin can offer a different
  target, described below. Otherwise the value goes to the topic field.
  Generic Publish does not enforce WB readonly or hidden metadata, even
  for a control's `/on` topic.
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

## Retained topics and deleting

Under MQTT 5, Retain As Published preserves the publisher's flag. A
non-empty retained message sets the retained mark; an empty retained
message clears it and shows "(cleared)". An empty non-retained message is
an ordinary `(empty value)`.

Under 3.1.1 live delivery lacks the publisher's retain flag, so the mark is
sticky: a non-empty retained delivery sets it, subsequent non-empty live
values keep it, and an empty message clears it. An empty message counts as
cleared when it carries retain or the topic was marked.

An equal retained value re-delivered on subscribe refreshes retained/QoS
marks but is not a change: no extra history entry or highlight. MQTT cannot
distinguish that from an equal retained re-publish; both are treated alike.
Moving branches and reconnecting can cause these refreshes. Dedicated
Delete actions and a Feed UI are not available; see the retained constraints
below. Generic Publish can send an empty retained message with its warning.

## A session's life

- **Connection state.** The top bar's subtitle says it: "Port N ·
  <state>", with a small spinner while connecting or reconnecting. A
  banner under the tabs repeats a state only when it adds what one line
  cannot hold: the broker asking to log in (Log in), a failure's reason
  (Retry), a session closed with its tunnel. With the search bar up (no
  subtitle) the banner also says connecting, reconnecting and closed.
- **Tunnel drop.** The subtitle says "Reconnecting…", the explorer keeps the
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
  path bar with the count ("Reading the tree… 1 234 topics").
- Rare actions live in "⋮": Plugins…, Refresh tree, Close
  session.
- Failures, in place of the tree, each with **Retry**:
  - connect refused: "No broker answers on port 1883 of <device>";
  - login refused: the login dialog, as above;
  - no common version: "The broker refused MQTT 5 and 3.1.1".

## Payload views (plugins)

Text, JSON and Hex are built-in payload views. The shipped plugins are
**Status and command topics** (rules for any vendor) and **Wiren Board**
(a devices view). Both default on at app level. With both off the generic
explorer remains functional, and no plugin changes Tree.

Settings → **Plugins** provides app-level on/off switches and lists
connection exceptions. A connection's **Plugins…** sheet opens from its
edit sheet or the explorer's "⋮"; its own on/off setting wins over the app
setting. Resetting to **As in Settings** removes the exception. Presets and
own rules are currently edited in that connection sheet while Status and
command topics is on there, but those edits are **app-wide**, not local to
that connection. The planned move of these editors to Settings and the WB
title-language/show-hidden settings UI are not available.

The app-owned plugin observer applies effective on/off settings to sessions
with or without an explorer screen. Off drops plugin filters, not cached
topics; on re-reads retained metadata as needed. Turning WB off returns its
open tab to Tree; turning it on does not take the screen. Already-sent
writes cannot be recalled. The shared [plugin settings and compatibility
contract](contract.md#12-mqtt-plugins-in-settings) owns defaults, overrides,
store schema, migration, downgrade limits and deletion scope, including
conditional off-state Settings usability/retention and live-setting semantics.
Stored WB language/hidden fields retain their compatibility meaning; their
existence is not a claim that their settings UI has shipped.

Plugin rule descriptions are declarative data, not downloaded executable
code. Importing arbitrary plugin descriptions, filter-selected replacement
payload renderers and moving Text/JSON/Hex onto common plugin plumbing are
not available capabilities. If filter-selected payload views are introduced,
the first matching topic filter must select the view for the card and tree
row. Shipped or imported descriptions must remain declarative JSON data
(filters and fields), not downloaded executable code.

### Status and command topics

Which topic a value is sent to, for any vendor, by rules. A preset is
only a set of rules shipped with the app; vendor knowledge is nowhere
else in this plugin.

- **A rule** is a status template and, optionally, a command template,
  plus optional exclusions and a name:
  - two templates: the command goes to another topic
    (`{x...}/state` → `{x...}/set`);
  - one template: the topic is its own command topic (a value sent to it
    is the command). Such a rule also ends the search, so a later,
    broader rule (a preset) does not offer a wrong command topic for it.
- **Template syntax**: levels split by `/` as in topics; a level is
  literal text, `{name}` (exactly one level), or `{name...}` (one or more
  levels; at most one per template, at any position). A template holds at
  least one literal level with text (an empty level does not count, so
  `/{x...}` is refused) and no MQTT wildcards. A command template equal to
  the status one is refused (leave it empty: the topic takes its own
  commands); a stored rule that no longer parses is skipped. A template starting with
  `/` has an empty first level and matches only topics that have one. The
  command template uses only names of the status template.
- **Matching**: without `{name...}` the topic has as many levels as the
  template and each level matches in turn (literal: equal text, case
  sensitive; `{name}`: any one level, which may be empty). With
  `{name...}` the levels before it match the topic's first levels one by
  one, the levels after it match the topic's last levels one by one, and
  `{name...}` takes what is left in between, which must be at least one
  level. So `{x...}/state` matches `home/light/state` with `x` =
  `home/light`, and `{x...}/set` then gives `home/light/set`;
  `home/{x...}/state` matches `home/a/b/state` with `x` = `a/b`.
- **Exclusions**: each a template, matched against the topic like the
  status template (for a one-template rule too). A topic matching any
  exclusion is skipped by that rule only: the search goes on with the
  next rule.
- **Order**: rules are tried top to bottom, the first match wins; the
  owner's own rules come first, then the enabled presets in their shipped
  order, most specific first:
  1. Wiren Board: `/devices/{d}/controls/{c}` →
     `/devices/{d}/controls/{c}/on` (the command topic only; the devices
     view is the Wiren Board plugin);
  2. Zigbee2MQTT (friendly names may hold `/`): `zigbee2mqtt/{d...}` →
     `zigbee2mqtt/{d...}/set`, except `zigbee2mqtt/bridge`,
     `zigbee2mqtt/bridge/{rest...}`, `zigbee2mqtt/{d...}/set`,
     `zigbee2mqtt/{d...}/get` and `zigbee2mqtt/{d...}/availability`. A
     base topic other than `zigbee2mqtt` needs an own rule;
  3. Tasmota, relays only: `stat/{d}/POWER` → `cmnd/{d}/POWER`, and the
     same for `POWER1` … `POWER8` (nine rules); `RESULT`, `STATUS*` and
     the rest of `stat/` are not matched;
  4. a generic "state/set" pair: `{x...}/state` → `{x...}/set`, except
     `zigbee2mqtt/bridge/state`.
- **Presets on**: at first all presets are off. The Wiren
  Board preset turns on by itself, once for the app, the first time any
  connection shows the WB tab (see below) while this plugin is on for
  that connection. Turned off by hand afterwards, it stays off. Presets
  are app-level ([plugin settings](contract.md#12-mqtt-plugins-in-settings)).
- **In the publish sheet**: when a rule matches the sheet's topic, a
  switch **Status topic / Command topic** («Топик статуса / Командный
  топик») above Send, defaulting to Command topic, with the rule that
  matched in one muted line under it (its name, else its templates) and
  the topic the value goes to. The switch can be changed for this send. A
  one-template rule shows no switch, only the line ("Commands go to this
  topic: <rule>"). No rule matching: no switch, no line; the value goes to
  the topic in the field. Editing the topic re-matches. With Command topic
  chosen and Retain on, one line warns: "A retained command is sent to
  the device again on every reconnect".
- **Setting up**: in the connection's **Plugins…** sheet, the presets
  are app-wide switches, and
  own rules are a list with **+** (status template, optional command
  template, optional exclusions, optional name; checked as above, the
  field saying which rule failed), reordered by drag, deleted with Undo. A
  new device kind needs a rule, not a plugin. Presets and own rules apply
  to every connection with the plugin on. Deleting the connection where
  a rule was added does not delete the rule.

### Wiren Board

A devices view of the Wiren Board MQTT convention
(`/devices/<d>/controls/<c>` with meta), for the owner's daily jobs on a
controller: see the devices, read values with their units, switch and set.
The compatibility baseline is `wirenboard/conventions` README, version
1.5.0 (2026-09-02), with the legacy one-field forms below. For `range`,
the type section's default max 255 is used, not the meta section's 10^9.
Current views use the app's language and omit hidden controls. The stored
language/show-hidden settings below retain their required meaning for the
deferred settings capability; this is not a claim that those overrides
are currently applied.

- **The tab.** With the plugin on, the explorer's tabs become **Tree ·
  Subscriptions · WB** (one short label in every language, so the three
  stay one line and one height) as soon as the session
  sees a control's meta (below); before that, and on a broker without the
  convention, there is no third tab. The three tabs share the segmented
  switch's width equally; «WB» never wraps, and only at very large font
  sizes may another label wrap to two lines rather than being cut. Turning
  the plugin off, in Settings or for the connection, removes the tab until
  it is turned on again.
- **Opening on it.** A connection whose last session showed the tab (kept
  per connection) opens the explorer on WB devices, which reads "Reading
  devices…" until the snapshot ends. A first session opens on Tree and
  the tab appears without switching to it, so nothing moves under the
  finger.
- **Navigation.** The tab follows the tab rules of "Subscriptions tab"
  (kept across rotation; the search shows Tree; the connection banners
  show on it). It has two levels of its own: the devices list and a
  device, opened inside the tab with the device's title in a header under
  the tabs (no arrow of its own: one Back on screen). Back in a device,
  the top bar's arrow as well as the system's, returns to the list; Back
  on the list returns to Tree. The Tree's path is never changed by the
  tab. A control's card opens over the tab; its **Open branch** shows
  Tree, as from anywhere.
- **Subscriptions.** While the tab shows, the app also holds
  `/devices/+/meta/#` and `/devices/+/controls/+/meta/#`, so titles and
  error marks stay live (meta changes rarely and is nearly all in the
  snapshot). On the devices list the session's open branch stays the
  Tree's path, live as before. An open device is the session's open
  branch, `/devices/<d>/#`; going back to the list or to Tree subscribes
  the Tree's path again.
  In the dedupe order of "Subscriptions" the plugin's subscriptions come
  after the pins. The app-owned observer holds metadata filters without a
  screen once metadata is seen, or when a refused/incomplete snapshot may
  have missed it. Only a quiet completed snapshot without metadata permits
  treating the convention as absent; a storage limit does not.
- **Meta.** A control's meta is one JSON topic `<control>/meta`: `type`,
  `units`, `min`, `max`, `precision`, `order`, `readonly` (default false),
  `hidden` (default false), `title`, `enum`. For backward compatibility
  drivers also publish one-field topics: the conventions name
  `meta/type`, `meta/min`, `meta/max`, `meta/order`, `meta/readonly`
  (`1`/`0`); live controllers also send `meta/precision` and
  `meta/units`. These seven are read; per field the JSON wins, a one-field
  topic fills what the JSON lacks. There is no `meta/title` topic. A
  device's meta is the JSON `/devices/<d>/meta` (`driver`, `title`); its
  older `/devices/<d>/meta/name` is the English title. Errors are only in
  the topics `<control>/meta/error` and `/devices/<d>/meta/error`, never
  in the JSON. An empty retained message (cleared) is the same as no
  topic; an empty or unparsable JSON is no meta.
- **What is listed.** A control is listed when it has meta: a non-empty
  JSON meta or any of the one-field topics. A device is listed when at
  least one of its controls is. A control's value topic not seen yet
  shows "No value". Devices without the convention are not listed; the
  Tree is unchanged.
- **Titles.** A title is a string, taken as is, or an object of language
  keys (`{"en": "Lamp", "ru": "Лампа"}`). The key used is the one for
  the language of the plugin's stored title-language setting, whose
  required default is the app's language
  (`ru-RU` → `ru`). Without that key, `en` is used, and without `en`,
  the first key in the JSON's order. An empty string counts as absent. No title: for a device
  `meta/name`, then the id; for a control the id. Enum titles follow the
  same rule.
- **Devices list.** By title in the tree's natural order. A row: the
  title, the device id under it in monospace, small; a red **!** when the
  device's `meta/error` or any shown control's is set (read out as "has
  errors"). A hidden control's error counts only while «Показывать
  скрытые» is on. A tap opens the device.
- **A device.** Its controls by `order` (no order: after the rest, by id
  in natural order). A control with `hidden` true is not shown unless
  stored show-hidden setting is on. When it is on, the control must be
  listed and marked «скрытый», and its error must count toward the device's
  "!". A hidden control exposed by supported show-hidden must work as any
  other control, respecting readonly and the normal exact client-owner and
  current-meta checks. This conditional requirement covers every supported
  writable type: `switch`, `wo-switch`, `pushbutton`, `range`, `rgb`, enum,
  numeric (`value`, typed controls and `local_time`) and text (`text`,
  `w1-id`) inputs. Hidden is not an additional permanent write prohibition
  for such an exposed control; read-only and non-writable types remain so.
  The stored setting defaults off. The language/show-hidden UI is deferred;
  these remain required settings semantics, not a claim that their controls
  or hidden-control writes are currently available. A row:
  - the title;
  - the value, with units and digits (below), or its enum title;
  - the control by type, on the right;
  - an error: a red mark with plain words under the title (below).
- **Copy on tap.** In every device (the «Система» device too: serial
  number, release name…) a row copies:
  - a tap on the title: the control in Wiren Board's short form,
    `<device>/<control>` (`wb-map6s_34/Irms 1`), as wb-rules and the web
    UI name it;
  - a tap on a value that is only shown (read-only, alarm): the value as
    published on the status topic, not rounded, without units, the enum
    key rather than its title (`0.50` shown as `0.5`). A read error («—»)
    copies nothing and says "Nothing copied: the value cannot be read
    (read error)"; no value yet and an empty value say so the same way;
  - a long press on the title or on such a value: the full topic
    (`/devices/wb-map6s_34/controls/Irms 1`).
  Each copy is confirmed by the app's copy notice naming what was copied
  ("Copied to the clipboard: wb-map6s_34/Irms 1" / «В буфере обмена: …»),
  with **Card** («Карточка») on it, which opens the ordinary topic card of
  the control (history, copy, pin, publish): the title no longer opens it.
  A notice with a button stays the longest (6 s, against 2.5 s plain and
  4 s long), so there is time to tap Card. Widgets
  (switch, slider, button, colour, enum, input) keep their own tap; the
  title's tap and long press still copy on their rows. A copyable value
  is wider to the left only; rows keep their height.

  Writable controls have no value copy at all: switch, `wo-switch`,
  range, `pushbutton`, `rgb`, enum and the editable number and text
  fields alike; a tap on the widget is its own action (toggle, slide,
  press, picker, list, input), and no other path copies their value
  there (it is on the topic card). The title's tap and long press copy on
  every row.

  TalkBack, the same as on screen. On the title: the tap is "Copy
  control" («Копировать контрол», `wb_copy_control`), the long press
  "Copy topic" («Копировать топик», `wb_copy_topic`), each announced
  once, and a custom action "Open the card" («Открыть карточку»,
  `mqtt_open_card`). On a shown value (read-only, alarm): the tap is
  "Copy value" («Копировать значение», `wb_copy_value`), the long press
  "Copy topic".

- **Value display.**
  - Digits: with `precision`, the value rounded to the nearest multiple
    of it (half away from zero), computed in decimal arithmetic, never
    binary floating point, and shown without trailing zeros: never padded
    to the precision's decimals, so a value that already fits reads as
    published (`76.50263` with `1e-06`, `0.5` with `0.01`); without
    `precision`, the value as received. Inputs are rounded the same way.
  - Units: `units` from meta, else the units of a deprecated typed
    control (`temperature` deg C, `rel_humidity` %, RH,
    `atmospheric_pressure` mbar, `rainfall` mm/h, `wind_speed` m/s,
    `power` W, `power_consumption` kWh, `voltage` V, `water_flow` m^3/h,
    `water_consumption` m^3, `resistance` Ohm, `concentration` ppm,
    `heat_power` Gcal/h, `heat_energy` Gcal, `current` A, `pressure` bar,
    `lux` lx, `sound_level` dB). Units are ASCII on the wire and drawn
    with symbols: `deg C` °C, `deg` °, `%, RH` %RH, `^3` ³, `Ohm` Ω,
    `mOhm` mΩ; the rest as is.
  - Enum (`value` and `text`): when the value has an enum entry its title
    is shown instead (the raw value stays on the card). For `value` the
    keys are numbers, decimal or hex (`"0x10"`), matched numerically; for
    `text`, as strings.
- **Controls by type.** Read-only (`readonly` true or `1`; absent means
  writable) shows the value only, whatever the type:
  - `switch` (`0`/`1`): a toggle; on when the value is `1`, off for `0`,
    a missing or other value, which is also shown as text;
  - `wo-switch` (write-only: the driver does not read the state back): a
    toggle showing the last value sent in this session, else off;
  - `pushbutton`: a button with the title;
  - `range` (integers): a slider from `min` (default 0) to `max` (default
    255), step 1, the value next to it; sent once on release, not while
    dragging;
  - `rgb`: a colour swatch; a tap opens a colour picker;
  - `alarm` (`0`/`1`): an indicator, red when `1`; never a control;
  - with `enum`: a tap opens a list of the enum titles, the current one
    marked;
  - `value`, the typed controls and `local_time` (seconds, shown as a
    local date and time without time zone): the value; a tap
    opens a one-field numeric input, prefilled, selected whole, checked
    against `min`/`max` and rounded to `precision` before Send;
  - `text`, `w1-id`: the value; a tap opens a text input;
  - meta without a type, or a type not listed here: the value as text,
    read-only.
- **Writes** go to `<control>/on`, QoS 1, not retained, whatever the Status
  and command plugin says. Payloads:
  - `switch`, `wo-switch`: `1` to turn on, `0` to turn off (a value that
    is not `1` counts as off, so a tap sends `1`);
  - `pushbutton`: `1`;
  - `range`: the integer, in decimal;
  - `rgb`: `R;G;B`, three integers 0–255;
  - `value` and the others with a numeric input: the number in decimal,
    no exponent, rounded to `precision` with its digits; an enum pick
    sends its key as written in the meta;
  - `text`, `w1-id`: the text as typed.
- **Readonly boundary.** These checks apply only to WB plugin actions, not
  the generic Publish sheet, even for the same control's `/on` topic. A
  cached writable row does not authorize a send: the session actor rereads
  meta immediately before publishing. Read-only, missing, unsupported or
  changed meta rejects the old action. The current actor also rejects
  hidden meta, while the show-hidden override is unavailable. That current
  guard does not supersede the conditional normal-operation requirement
  for exposed hidden controls above. Supporting show-hidden therefore has
  an implementation/conditional-policy gap: its approved feature and
  code-audit decision must reconcile exposure with the guard, without
  bypassing readonly, exact owner/meta checks or stale-action invalidation.
  This reference neither loosens the current guard nor chooses a new ACL
  or permission policy. JSON/one-field precedence stays as described above.
  These are client-side plugin rules, not broker ACLs.
  Editors and gestures are discarded when their accepted MQTT client or
  control meta changes; an old callback cannot target a reconnect's client.
  A late answer owns its exact write, not just an equal timestamp/payload,
  before updating either its answer or the `wo-switch` sent value.
- **The answer** shows on the control itself, nothing else moves:
  - while sending, the control shows the asked value muted with a spinner
    and is not tappable;
  - the status topic (`<control>`) bringing a value equal to the asked one
    ends it: the control shows it as usual. Equal means: numbers
    (`switch`, `range`, `value`, typed, `local_time`, numeric enum keys)
    compared as numbers after rounding both to `precision`; `rgb` as three
    integers, spaces ignored; anything else as exact strings;
  - asking for the value the control already shows needs no echo (a
    driver need not re-publish an unchanged value): it is sent, and the
    broker's PUBACK ends it;
  - the broker refusing (MQTT 5 reason code): the control returns to the
    status value and says "Refused: <reason>" under the title, in the
    error colours, until the next change;
  - no equal value within 5 s of the PUBACK: the control returns to the
    status value and says "No answer from the device"; no PUBACK within
    10 s of sending: "No answer from the broker";
  - `pushbutton` and `wo-switch` have no status to wait for: the PUBACK
    ends them ("Accepted by the broker" / "Refused: …" for a moment, under
    3.1.1 "Sent, not confirmed").

  Screen readers get the same words. In a closed or reconnecting session
  the controls are off, saying so ("The session is closed",
  "Reconnecting…").
- **Errors** (`meta/error`, one or more of the letters `r`, `w`, `p`): a
  red mark on the control with plain words, one per letter: `r` "Read
  error" («ошибка чтения»; the value is the last good one and means
  nothing now, so «—» stands in its place, in a switch, slider or colour
  too, while the error is set; a switch's toggle is then neutral (off,
  muted) and still taps, an alarm is the muted ring), `w` "Write error" («ошибка записи»), `p`
  "Polled late" («опрос не успевает»), both keeping the value shown (it
  was read); an unknown letter shows as is. Any device-level
  `meta/error` shows the mark on the device's header and row as "The
  device does not answer" («устройство не отвечает»). The marks go when
  the error topic is emptied.

### Later plugins

Tasmota, Zigbee2MQTT and Home Assistant discovery **views** are not
available. The shipped Status and command presets are rules, not such views.

## Later: charts

Charts and longer numeric-only chart history are not available. Topic
history remains the bounded session history described above.

## Unavailable capabilities and retained constraints

Feed UI, dedicated retained Delete/Delete branch actions and broker TLS
were specified as staged delivery, but are not available now. Their
introduction needs a separate approved feature; the following previously
decided constraints are not revoked:

- **Feed:** branch changes newest first, relative path/time/value, pause
  with incoming count, search, topic-card navigation and latest pinned
  values. Equal retained re-delivery is not a change; the current history
  and highlight rules already preserve this distinction.
- **Delete:** remove the topic on screen with shared Undo, no extra
  confirmation, nothing sent before Undo expires; then an empty retained
  QoS 1 publish, answered as for Send. Branch deletion must take only topics
  delivered as retained by a fresh `<p>/#` re-read using the snapshot
  stopping rule, never arbitrary seen topics or command topics. Unsubscribe
  the branch during Undo and show the retained count to delete and the
  non-retained count left untouched. Undo restores it without a send.
  Progress and Stop must allow
  stopping before the next publication. A final re-read must distinguish
  deleted, MQTT 5 refusals and retained topics that came back; under 3.1.1
  a refused deletion cannot be claimed confirmed and can appear as came
  back. Partial or unknown outcomes must be reported truthfully.
- **TLS:** the specified 8883 preset enables TLS, with a connection switch
  for other ports; first-use trust and pinned broker identity follow the
  web certificate flow. A changed certificate requires the changed-identity
  dialog with both SHA-256 fingerprints; refusing trust leaves the broker
  unconnected with Retry. Pinned broker certificates must retain the original
  storage protection: sealed / app-private, as for SSH and web. These are
  conditional requirements, not current TLS negotiation or pinned-certificate
  storage.

## Stored on the phone

Tree payloads and history are volatile, but pins, rules and recent outgoing
payloads can contain private topic names and values and persist locally.
Publish memory keeps at most 100 topics per connection and 10 distinct
recent payloads per topic, with 65,536 payload characters in total per
connection. Payloads over 4,096 UTF-8 bytes are sent but not remembered;
topics over 1,024 UTF-8 bytes have no remembered entry. Oldest payloads of
least-recent topics leave first. A failed persistence write does not undo a
sent publish; its recent-send state can survive only in memory.

| What | Where |
| --- | --- |
| Pins with their masks; retain/QoS/format per topic and last payloads sent (per connection) | app-private storage |
| Plugins, app level: each plugin on or off; Status and command presets and own rules, and whether the Wiren Board preset was already turned on by itself; Wiren Board title language and show hidden ([plugin settings](contract.md#12-mqtt-plugins-in-settings)) | app-private storage, `mqtt_plugins_v2.json` |
| Plugins, per connection: each plugin's own on or off, if set; whether the WB tab was shown before (to open on it) ([plugin settings](contract.md#12-mqtt-plugins-in-settings)) | same file |
| Broker logins (only with Remember) | app-private storage; password sealed as for SSH |
| The tree and history | memory only, for the session |

## Related contracts

- [App, shared UI, tunnels and plugin settings](contract.md), especially
  [UI rules](contract.md#ui-rules) and
  [plugin compatibility](contract.md#12-mqtt-plugins-in-settings).
- [SSH persistence](ssh-persist-contract.md) for the related credential
  storage and shared session lifetime boundary; MQTT does not restore a
  broker session or its tree after process death.

## Open risks

- Broker transport currently uses plain MQTT over the local tunnel. It is
  not broker TLS, and a broker accepting anonymous clients provides no
  broker-login protection.

- Memory: ingress and owned-storage accounting are not an exact whole-app
  heap cap. Reader snapshots and MQTT decoder allocations are excluded;
  multiple sessions add their budgets, and high operator-selected limits
  can still exhaust a phone's memory.
- A busy broker over mobile data: the snapshot and large branches pull
  every message the broker sends, costing data and battery.
- The tunnel is a TCP port on 127.0.0.1 open to every app on the phone
  while it is up (`docs/contract.md`); a broker without a password is then
  open to them too.
