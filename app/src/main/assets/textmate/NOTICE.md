# TextMate grammars

The viewer's grammars are the ones Visual Studio Code ships with its
built-in language extensions, taken from
https://github.com/microsoft/vscode/tree/1.142.0/extensions (MIT,
Copyright (c) Microsoft Corporation) and stored here compacted; YAML is
the textmate grammar VS Code shipped up to 1.90.0, as the later one
needs regex features joni (tm4e's engine) lacks. Changes, each so that
joni compiles the grammar (checked by GrammarsTest):

- capture entries that are not rules, which VS Code ignores (two stray
  keys in XML) are left out;
- log: the five single-letter level patterns (`V`, `D`, `I`, `W`, `E`
  after a variable-length look-behind) are left out, and the
  `dd/mm/yyyy` date's look-behind `(?<=(^|\s))` is `(?:^|(?<=\s))`;
- CSS: the selector look-behinds' `(^|[^\:])\s` and escape-sequence
  alternatives are split into fixed-length ones;
- JavaScript: `await\s+using` in one look-behind is `await\susing`;
- Markdown: the strikethrough's closing `(?!(?<=_\1)\w)` is left out.

Each grammar comes from the upstream project below (at the commit VS
Code's `cgmanifest.json` pins), under its license; the license texts are
in `assets/licenses/`. VS Code itself: Copyright (c) Microsoft
Corporation, MIT.

| File | Scope | Upstream (commit) | Copyright | License |
|---|---|---|---|---|
| markdown | text.html.markdown | microsoft/vscode-markdown-tm-grammar (0812fc4b), from textmate/markdown.tmbundle (11cf7646) | Copyright (c) Microsoft 2018; Copyright (c) markdown.tmbundle authors | MIT; TextMate Bundle License |
| json | source.json | microsoft/vscode-JSON.tmLanguage (9bd83f1c) | Copyright (c) 2015 simonzack, zertosh, benvie | MIT |
| yaml | source.yaml | textmate/yaml.tmbundle (e54ceae3), as in VS Code 1.90.0 | Copyright (c) textmate-yaml.tmbundle project authors | TextMate Bundle License |
| ini | source.ini | textmate/ini.tmbundle (2af0cbb0) | Copyright (c) textmate-ini.tmbundle project authors | TextMate Bundle License |
| shell | source.shell | jeff-hykin/better-shell-syntax (35020b0b) | Copyright (c) 2019 Jeff Hykin | MIT |
| javascript | source.js | microsoft/TypeScript-TmLanguage (3133e3d9), from textmate/javascript.tmbundle (fccf0af0) | Copyright (c) Microsoft Corporation; Copyright (c) textmate-javascript.tmbundle project authors | MIT; TextMate Bundle License |
| python, python-regexp | source.python | MagicStack/MagicPython (7d0f2b22) | Copyright (c) 2015-present MagicStack Inc. | MIT |
| go | source.go | worlpaker/go-syntax (c74e22eb) | Copyright (c) 2023 Furkan Ozalp | MIT |
| c | source.c | jeff-hykin/better-c-syntax (34712a61), from textmate/c.tmbundle (60daf83b) | Copyright (c) 2019 Jeff Hykin; Copyright (c) textmate-c.tmbundle authors | MIT; TextMate Bundle License |
| lua | source.lua | sumneko/lua.tmbundle (b295d83b) | Copyright (c) 2022 最萌小汐 | MIT (its LICENSE; VS Code's manifest says TextMate Bundle License) |
| dockerfile | source.dockerfile | moby/moby (bea959c7) | Copyright 2012-2017 Docker, Inc. | Apache-2.0, with the NOTICE below |
| sql | source.sql | microsoft/vscode-mssql (684648ee) | Copyright (c) Microsoft Corporation | MIT |
| xml | text.xml | atom/language-xml (7bc75dfe) | Copyright (c) 2014 GitHub Inc. | MIT |
| html | text.html.basic | textmate/html.tmbundle (0c3d5ee5) | Copyright (c) textmate-html.tmbundle project authors | TextMate Bundle License |
| css | source.css | microsoft/vscode-css (de9e6bee) | Copyright (c) Microsoft Corporation | MIT |
| log | text.log | emilast/vscode-logfile-highlighter (396c8552) | Copyright (c) 2015 emilast | MIT |

The commits are the ones VS Code's `cgmanifest.json` pins for each
upstream repository; three files name another commit in their own
`version` field, the one the file was last converted from: javascript
(TypeScript-TmLanguage 48f60869), dockerfile (moby c2029cb2) and
python-regexp (MagicPython c9b3409d).

## moby NOTICE (Dockerfile grammar)

    Docker
    Copyright 2012-2017 Docker, Inc.

    This product includes software developed at Docker, Inc. (https://www.docker.com).

    This product contains software (https://github.com/creack/pty) developed
    by Keith Rarick, licensed under the MIT License.

The TextMate Bundle License is in `assets/licenses/TextMate-Bundle.txt`.
