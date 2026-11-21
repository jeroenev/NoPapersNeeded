# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the
label strings in this repo's GitHub tracker. The issue templates apply `bug` or `enhancement`;
those are categories, not triage states.

| Role              | Label in our tracker | Meaning                                  |
| ----------------- | -------------------- | ---------------------------------------- |
| `needs-triage`    | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`      | `question`           | Waiting on reporter for more information |
| `ready-for-agent` | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human` | `ready-for-human`    | Requires human implementation            |
| `wontfix`         | `wontfix`            | Will not be actioned                     |

`needs-triage`, `ready-for-agent` and `ready-for-human` do not exist on GitHub yet. Ask the
maintainer before creating one (`gh label create <name>`) the first time triage needs it.

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding
label string from this table.
