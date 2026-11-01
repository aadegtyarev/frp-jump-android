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

Each grammar comes from the upstream project below, under its license.

| File | Scope | Upstream | License |
|---|---|---|---|
| markdown | text.html.markdown | microsoft/vscode-markdown-tm-grammar (from textmate/markdown.tmbundle) | MIT; TextMate Bundle License |
| json | source.json | microsoft/vscode-JSON.tmLanguage | MIT |
| yaml | source.yaml | textmate/yaml.tmbundle (VS Code 1.90.0) | TextMate Bundle License |
| ini | source.ini | textmate/ini.tmbundle | TextMate Bundle License |
| shell | source.shell | jeff-hykin/better-shell-syntax | MIT |
| javascript | source.js | microsoft/TypeScript-TmLanguage (from textmate/javascript.tmbundle) | MIT; TextMate Bundle License |
| python, python-regexp | source.python | MagicStack/MagicPython | MIT |
| go | source.go | worlpaker/go-syntax | MIT |
| c | source.c | jeff-hykin/better-c-syntax (from textmate/c.tmbundle) | MIT; TextMate Bundle License |
| lua | source.lua | sumneko/lua.tmbundle | TextMate Bundle License |
| dockerfile | source.dockerfile | moby/moby | Apache-2.0 |
| sql | source.sql | microsoft/vscode-mssql | MIT |
| xml | text.xml | atom/language-xml | MIT |
| html | text.html.basic | textmate/html.tmbundle | TextMate Bundle License |
| css | source.css | microsoft/vscode-css | MIT |
| log | text.log | emilast/vscode-logfile-highlighter | MIT |

TextMate Bundle License: "Permission to copy, use, modify, sell and
distribute this software is granted. This software is provided "as is"
without express or implied warranty, and with no claim as to its
suitability for any purpose."
