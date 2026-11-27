# Agent skills

Skills this repository's agents use, in the cross-agent `.agents/skills` layout: the one
Antigravity, the `skills` CLI and this harness all read. Nothing here is specific to one client.

- [ui-ux-pro-max](skills/ui-ux-pro-max) — UI/UX design intelligence: 141 UX guidelines with
  rationale, 194 product palettes, 51 active styles, 74 font pairings, motion presets, and stack
  tables (`jetpack-compose` included). Searchable offline:

  ```sh
  python3 .agents/skills/ui-ux-pro-max/scripts/search.py "bottom navigation" --domain ux
  python3 .agents/skills/ui-ux-pro-max/scripts/search.py "state hoisting" --stack jetpack-compose
  python3 .agents/skills/ui-ux-pro-max/scripts/search.py "expressive dark" --design-system
  ```

  Vendored from [`nextlevelbuilder/ui-ux-pro-max-skill`](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill)
  under the MIT licence in its own `LICENSE`; only `SKILL.md`, `references/`, `data/` and
  `scripts/` are kept, which is what the skill reads at run time.

## Why `.agents` and not `.claude`

`.claude/skills` is one client's directory. A skill put there works for that client and for
nothing else, so it has to be copied again for the next agent that works here. The `.agents`
layout is the cross-agent one, so a skill installed once is found by every agent — which is the
whole point of a skill being a file rather than a feature of one tool.

`docs/ux-fixes.md` §11 records the pass this skill drove.
