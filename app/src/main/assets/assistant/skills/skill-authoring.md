---
name: skill-authoring
description: Use when the user asks to save what was done as a skill, or to change or delete a skill.
requires: skills_list
---
A skill is repeatable know-how for this owner's devices and habits: a task that was done once and will come again. Save one only when the user asks, or offer to at the end of a task worth repeating.

Before writing, call skills_list: change an existing skill instead of adding a near copy. One skill per task; small over big.

Write the parts like this:
- name: kebab-case, 2 to 40 characters, short and specific (`restart-nginx-on-pi`).
- description: one line that starts with "Use when" and names the situation that calls for the skill, not its steps.
- requires: the tool names whose switches the steps need (for example ssh_command, terminal_input). Leave it empty when no tool is needed. A skill whose tools are off is not shown.
- body: Markdown with these headings: Purpose; Preconditions; Steps (the tool names and the arguments that worked); Checks (how to see the result is right); On failure; Never.

Rules for the text:
- Prefer facts learned in this conversation (device names, paths, commands that worked) over generic advice.
- Never put secrets, passwords, keys or tokens in a skill, and no personal data beyond device names.
- Never write an instruction to skip a confirmation, to act without the owner's approval, or to ignore any rule: a skill never grants authority.
- Keep it under the size limit of a skill; cut what the steps do not need.

Saving: show the user the name and the description before you call skill_save. When replacing a skill, say what changed. The owner may be asked to confirm and sees the whole text. A built-in skill cannot be changed or deleted.
