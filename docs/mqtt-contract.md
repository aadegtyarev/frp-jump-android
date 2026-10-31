# frp-jump for Android — MQTT explorer contract

Status: draft for the owner's review. The UI rules of `docs/contract.md`
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

## Where it lives

- On a TCP connection (any port; the presets mark 1883 as MQTT) the
  connection's main action is "MQTT" when its kind says so, as "Terminal"
  is for SSH; in "⋮" for any TCP connection. It opens the explorer for
  that tunnel, bringing the tunnel up first.
- Like the terminal, an explorer session lives in the app, not the screen:
  leaving keeps it connected (the tree keeps filling); "MQTT" on the same
  connection returns to it; sessions are listed with terminals' (a
  "Sessions" list) and close only deliberately.

## Connecting

- Broker: `127.0.0.1:<local port>` of the tunnel. MQTT 5, falling back to
  3.1.1 when the broker refuses 5: the current Wiren Board firmware on
  Debian 13 (trixie) ships Mosquitto 2.0.21, which speaks both, and older
  controllers (Debian 11, Mosquitto 2.0.20) too. Client id
  `frpjump-<random>`, clean session.
- Login: none by default; user and password when the broker asks
  (remembered per profile/device/port only with "Remember", sealed like
  SSH passwords). TLS (8883 or a switch) with the certificate pinned on
  first use, as for web pages.
- Subscriptions are kept small so the phone and the link stay light:
  - **Tree snapshot.** On connect the app subscribes to `#` for a few
    seconds, records the topic structure (above all the retained topics,
    which on a Wiren Board are nearly the whole tree) and unsubscribes.
    "Refresh tree" takes a new snapshot.
  - **The open branch.** Live values come only for the branch being
    viewed: the app is subscribed to `<current path>/#`, moving the
    subscription as the owner navigates.
  - **Subscriptions tab.** Topics the owner pinned, each with a chosen mask:
    the topic only, `+` one level below, or `#` the whole branch. They stay
    subscribed for the whole session and are kept per connection, also
    after the tunnel is closed. Pinned from a topic's or a branch's card
    ("Subscribe…", choose the mask) or typed as a filter.
  - Each subscription has a limit on topics and history; beyond it the
    screen says "showing the first N".
  - A broker that refuses `#` for the snapshot says so; the tree is then
    built from the pinned subscriptions and the open branch.

## The tree

- A path bar on top as in Files (`/` › `devices` › `wb-mr6c_1` ›
  `controls`): tap a part to go there, tap the path to type or paste one.
  Back goes up a level, then leaves.
- Rows are the children of the current level, sorted by name, numbers
  naturally (`K2` before `K10`):
  - a branch: name, number of topics under it, the newest change's age;
  - a topic with a value: name, the value shortened to one line, its age,
    a mark when retained; a value that just changed flashes briefly.
  - a node can be both (a value and children): shown as a branch with its
    value line.
- Search in the top bar filters the current level and below by path
  substring (`K1`, `meta/error`).
- The tree is the broker's: topics starting with `/` get an empty first
  level shown as `/`; `$SYS` is a branch like any other.
- Limits keep the phone responsive: up to 100 000 topics and 20 values of
  history per topic; beyond that the oldest topics stop being tracked and
  the screen says so.

## A topic

Tapping a topic (or "⋮" on a branch) opens its card, a bottom sheet:

- the full path (copy), the value in full: text, or JSON pretty-printed and
  foldable, or bytes as hex when not UTF-8; size; retained; QoS; time;
- the last values with times (up to 20), newest first;
- actions: **Publish here**, **Publish under…**, **Copy path**, **Copy
  value**, **Delete** (retained only), **Watch** (pin it to the feed).

## Publishing

One sheet, opened from a topic, from a branch, or from the "+" of the tree:

- **Topic:** prefilled with the selected path; "Publish under…" prefills
  `<path>/` with the cursor at the end for a new subtopic; any path can be
  typed or pasted. Wildcards are refused.
- **Payload:** a text field; a format switch Text / JSON (JSON is
  validated and can be formatted); the last payloads sent to this topic
  are one tap away.
- **Retain** and **QoS** (0/1/2), remembered per topic.
- **Status or command topic.** A connection may set, in its settings, where
  a value goes by default: to the topic itself, or to a command topic next
  to it given by a rule (a suffix such as `/on`, as Wiren Board uses). The
  sheet then has a switch "Status topic / Command topic" set by the rule
  and changeable for this send. A payload view (plugin) may define the
  status/command pair itself and then overrides the rule.
- Send gives feedback where it was tapped (sent, or the broker's refusal);
  if the topic is in the tree its new value shows up there.

## Feed

The branch's live changes as a list, newest on top: time, path relative to
the branch, value. Pause (the list stops, counting what arrives), filter by
path substring, tap an entry to open its topic. Watched topics are pinned
on top. This is the "what is going on" view; the tree is the "what is
there" view.

## Retained topics and deleting

- Retained topics are marked in the tree and the card.
- **Delete** on a retained topic publishes an empty retained message to it
  (the MQTT way to remove it), after a confirm.
- **Delete branch** on a branch removes every retained topic under it:
  the confirm says how many ("Delete 37 retained topics under
  /devices/wb-old?"), deletes them one by one with progress, and says how
  many went. Topics that are not retained cannot be deleted and are said
  so (they come back with the next message).

## Payload views (plugins)

- A payload view turns a topic's raw bytes into what the card and the
  tree row show. Built in: Text, JSON, Hex.
- Views are chosen by topic filter (first match wins), so a later view for
  a convention (Wiren Board `/devices/+/controls/+`, Zigbee2MQTT, Home
  Assistant discovery) plugs in by declaring its filters and how it shows
  a value and its metadata. The explorer never depends on a view.
- First plugin form: declarative (a JSON description of filters and
  fields shipped with the app or imported), not downloaded code: Android
  forbids loading foreign code into an app of this kind safely.

## Later: charts

Not in the first stages, but the data model keeps room for it: numeric
values of chosen topics are collected while the session runs and drawn as
charts over time, several on one chart with their own scales, to see how
parameters depend on each other (a room's temperature against a valve's
position, a current against a relay). The topic history then needs a
longer, numeric-only buffer per charted topic; nothing else in the
explorer changes for it.

## Stored on the phone

| What | Where |
| --- | --- |
| Pinned subscriptions with their masks, the command-topic rule, retain/QoS per topic, last payloads sent (per connection) | app-private storage |
| Broker passwords (only with Remember), pinned broker certificates | sealed / app-private, as for SSH and web |
| The tree and history | memory only, for the session |

## Delivery

1. Connect, tree snapshot, the open branch live, path bar, search, topic
   card, copy.
2. Publish (here / under / any path), Text/JSON, retain, QoS, recent
   payloads.
3. Feed with pause, filter and watched topics.
4. Retained delete for a topic and a branch.
5. Login and TLS; the Subscriptions tab with masks; the command-topic
   rule.
6. Payload views as plugins (declarative), first plugin when wanted.
7. Later: charts of chosen numeric topics, overlaid.

## Acceptance (a Wiren Board on the tunnel)

- The tree shows `/devices/*/controls/*` and `/devices/*/meta` with live
  values; `wb-mr6c_1/controls/K1` changes as the relay switches.
- Publish `1` to `/devices/wb-mr6c_1/controls/K1/on` switches the relay;
  the tree shows the new state.
- The feed of `/devices/wb-msw-v3_21` shows temperature updates; pause
  stops it and counts.
- A retained branch of a removed device is deleted with one confirm and
  is gone after a reconnect.
- Leaving and returning keeps the session; the tree is not rebuilt.

## Decided

- MQTT 5 with a 3.1.1 fallback, after the brokers on the owner's
  controllers (Mosquitto 2.0.20 and 2.0.21).
