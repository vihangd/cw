# Manual smoke tests

The unit suite (`bb test`, **78 tests / 256 assertions**) covers parse,
chain/DAG, workflow, state, replay, provider fallback, config + the shared
library expansion, gates, and the CLI arg layer hermetically. The checks
below are I/O- or env-heavy and verified by hand; most make real provider
calls (small cost).

Run from the repo root (`bb -m cw.main …`) or after `bb install-local`
(`cw …`).

## Core

| Command | Expected |
|---|---|
| `cw config init && cw doctor` | doctor: claude/gemini ✓; codex ✗ (not installed here) |
| `cw "say hello in 3 words"` | one-shot text on stdout, nothing on stderr |
| `cw -p g "hi"` | gemini path (needs `GEMINI_API_KEY` / OAuth) |
| `printf 'TOKEN' \| cw -m claude-haiku-4-5 "echo the token"` | stdin embedded |
| `cw -v chain "name a color" "haiku about {{prev}}"` | 2 steps, `→` breadcrumbs on stderr |
| `cw chain --steps-file test/dag.edn` | DAG: parallel wave then join |
| `cw compare "reply: ping"` | claude ✓, gemini/codex FAILED blocks (no crash) |
| `cw fanout --inputs '/tmp/fo/*.txt' --prompt-file p.txt -p c` | one block per input file |
| `cw eval commit-msg --runs 2 --fixture s.diff` | variance table + similarity matrix |
| `cw runs list` / `cw runs show <id>` | audit log listing / full record |
| `cw replay <id> --step 2` | re-runs from step 2, seeds `{{prev}}` from log |
| `cw graph triage-issue` / `cw graph --all` | Mermaid flowchart(s) |
| `cw --dry-run code-review-multi` | rendered plan, no execution |
| `cw --json "hi"` | full ChainResult EDN to stdout, empty stderr |

## Shared library + new workflows

| Command | Expected |
|---|---|
| `cw workflows` / `roles` / `skills` / `fragments` / `phases` | one-line previews of each library section |
| `cw spec "rate limit the API"` | spec doc from the architect/spec-writer skill |
| `git diff \| cw security-review` | adversarial STRIDE review of the diff |
| `cw threat-model < system.md` | STRIDE per category + top-3 risks |
| `cw impact < diff.patch` | blast-radius analysis |
| `cw git-ship "Add OAuth"` | LLM commit (uses claude `--allowedTools Read,Bash`) → push → open PR |
| `cw pr-review <N>` | gh fetch → adversarial review → verdict LLM → posts back to GitHub |

## Gates

Authoring a steps-file with a `:gate` step:

```clojure
;; /tmp/gated.edn
{:steps [{:id :draft  :cmd ["printf" "hello"]}
         {:id :ok :gate "ship {{prev}}?"}
         {:id :pub :cmd ["printf" "shipped:{{prev}}"]}]}
```

| Command | Expected |
|---|---|
| `cw chain --steps-file /tmp/gated.edn` (interactive terminal) | prompt on `/dev/tty`; `y`/`yes` continues, anything else aborts with `:gate-rejected` |
| `cw chain --steps-file /tmp/gated.edn` (piped / CI / no TTY) | exits 1 with `gate needs confirmation but no TTY and no --yes` |
| `cw chain --steps-file /tmp/gated.edn --yes` | gate auto-approved; final-text `shipped:hello` |
| `cw chain --steps-file /tmp/gated.edn -y` | same as `--yes` (short alias) |

## Verified during build

claude live: one-shot ✓ · `--json` ✓ · stdin embed ✓ · linear chain w/
`{{prev}}` ✓ · DAG waves + `{{step-NAME}}` ✓ · runs list/show ✓ · replay
`--step` ✓ · doctor ✓ · graph (linear + DAG) ✓ · dry-run ✓ · compare ✓ ·
eval (variance table) ✓ · fanout ✓ · commit-msg via shared library ✓ ·
git-ship `:use :git-ship` splice ✓.

gates: hermetic — `--yes` bypass, no-TTY fail-closed, DAG-position
transparency, recursive `{{fragment}}`, workflow-level ref rewrite after
`{:use}`, blank-prompt validate. Interactive TTY path not run (no terminal
in the unit-test sandbox).

gemini: not exercised live (local auth set to `gemini-api-key`, no key).

codex: not installed; `:codex-jsonl` parser unverified vs real CLI
(hand-authored fixture).
