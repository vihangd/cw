# cw

A composable headless-CLI wrapper that runs across **Claude / Gemini / Codex / Qwen**
(and custom providers), with chain-as-a-first-class-primitive, DAG support,
fan-out, automatic provider fallback, a per-run audit log, replay, and prompt
evaluation.

> wraptc routes. simonw/llm prompts. **cw composes.**

Built in [Babashka](https://babashka.org), distributed via
[bbin](https://github.com/babashka/bbin).

---

## When to use it

`cw` is for composing **2–N LLM/shell steps into a unit** — ad-hoc on the
command line, or saved as a named workflow you can replay, eval, and graph.
Multi-provider support exists *in service of composition*: any step can be
Claude, Gemini, Codex, a custom provider, or a shell command.

If you just want to run one model headlessly, `alias cw='claude -p'` is
simpler. Reach for `cw` when you want to wire several calls (and shell steps)
into a pipeline with fallback, cost caps, an audit trail, and variance testing.

---

## Install

```bash
bbin install io.github.vihangd/cw --latest-sha
cw config init                                  # starter config.edn + prompts/ scaffold
cw doctor                                       # check provider CLIs + integrations
cw "hello"
```

Requires `bb` (Babashka ≥ 1.12) and `bbin`. Provider CLIs (`claude`,
`gemini`, `codex`, …) are only needed for the providers you actually use —
`cw doctor` reports which are present.

### Local development

```bash
bb test               # hermetic unit suite (no network) — 80 tests
bb install-local      # bbin install . --as cw
bb uninstall          # bbin uninstall cw
bb doctor             # run doctor from source
```

---

## Quick start

```bash
# One-shot
cw "summarize this in one sentence"
cw -p g "explain quantum tunneling"            # -p picks a provider (alias)
cw -p q "what's wrong with this regex?"        # qwen (alias q) — 4th provider
cat error.log | cw explain-error               # pipe stdin into a workflow
cat src/auth.py | cw -p q "explain this code"  # qwen for code, via stdin

# Machine-readable output — pipe ChainResult EDN through bb/jet/jq
cw --json "say hi" | bb -e '(:final-text (read-string (slurp *in*)))'

# Ad-hoc linear chain — {{prev}} threads the previous step's output
cw chain "name a primary color" "write a haiku about {{prev}}"
cw chain -p c "draft a tagline" -p g "critique: {{prev}}"   # per-step provider
cw chain "outline" "draft from {{prev}}" --max-cost-usd 0.05   # abort if over $0.05

# DAG chain (parallel branches + join) from a steps-file
cw chain --steps-file dag.edn

# Named workflows (defined in config)
git diff --staged | cw commit-msg
cw triage-issue 1234                           # positional → {{arg-1}}
cw spec "rate limiter for the API"             # arg → {{arg-1}}
git diff | cw security-review                   # stdin → adversarial STRIDE
cw git-ship "Add OAuth"                          # LLM commit → push → open PR
cw pr-review 1234                               # review → verdict → posts back
cw chain --steps-file gated.edn -y              # -y auto-approves any :gate steps

# Discover the shared library / inspect
cw list                                            # all workflows + docs
cw workflows ; cw roles ; cw skills ; cw phases

# Compare / fan-out / eval
cw compare "one prompt → every configured provider, in parallel"
cw compare --providers claude,gemini "compare these two only"
cw fanout --inputs 'docs/*.md' --prompt-file summarize.txt -p c
cw eval commit-msg --runs 5 --fixture sample.diff

# Audit + replay
cw runs list
cw runs show <run-id>
cw replay <run-id> --provider g                # same chain, different provider
cw replay <run-id> --step 2                    # re-run from step 2 (earlier
                                               #   outputs seeded from the log)

# Inspect
cw graph code-review-multi                      # → Mermaid flowchart
cw graph --all
cw --dry-run code-review-multi                  # render the plan, don't execute
cw doctor
```

---

## CLI surface

### Commands

| Command | Description |
|---|---|
| `cw [PROMPT]` | One-shot call to the default (or `-p`) provider |
| `cw <workflow> [ARGS…]` | Run a named workflow; `ARGS` fill `{{arg-N}}` |
| `cw chain P1 P2 [P3…]` | Ad-hoc **linear** chain |
| `cw chain -p c P1 -p g P2` | Per-step provider (repeatable) |
| `cw chain --steps-file F.edn` | Chain (linear or **DAG**) from an EDN file |
| `cw compare [PROMPT]` | Same prompt → every configured provider, in parallel |
| `cw fanout …` | One prompt/workflow → many inputs, bounded concurrency |
| `cw eval <workflow> --runs N` | Run a workflow N times, report variance |
| `cw replay <run-id>` | Re-run a logged chain (optionally altered) |
| `cw providers` | List configured providers |
| `cw list` / `cw workflows` | List configured workflows |
| `cw roles` / `skills` / `fragments` / `phases` | List the shared prompt/phase library |
| `cw graph <workflow>` / `--all` | Emit a Mermaid flowchart |
| `cw doctor` | Diagnose provider CLIs, integrations, config, state |
| `cw config path` / `init` | Print / write the config file |
| `cw runs list` / `show <id>` | Browse the audit log |

### Flags

Flags are **position-independent** — they may appear before or after the
subcommand (`cw --json chain …` and `cw chain … --json` both work). The one
exception is `chain`'s per-step `-p`, which is positional within the chain.

| Flag | Meaning |
|---|---|
| `-p, --provider <key\|alias>` | Provider (one-shot/workflow); repeatable in `chain` |
| `-m, --model <model>` | Override the model |
| `--max-turns N` | Provider max-turns (where supported) |
| `--timeout SECS` | Per-step wall-clock timeout |
| `--max-cost-usd FLOAT` | Abort the chain/fanout before exceeding this |
| `--schema <path>` | JSON-schema-constrained output (native for Codex, prompt-embedded otherwise) |
| `--no-fallback` | Disable automatic provider fallback for this run |
| `-y, --yes` | Auto-approve every gate step (non-interactive / "yolo") |
| `-v` / `-vv` | Verbosity (additive): step breadcrumbs / + intermediate text |
| `--cost` | Print cost to stderr even at verbosity 0 |
| `--json` | Emit the full Result/ChainResult as plain-map EDN to stdout |
| `--dry-run` | Print the rendered plan; execute nothing |
| `--no-log` | Skip the audit-log write for this run |
| `--steps-file <path>` | Chain plan (EDN) — linear or DAG |
| `--runs N` | `eval`: number of runs (default 5) |
| `--fixture <path>` | `eval`: file content used as `{{stdin}}` each run |
| `--inputs <glob>` | `fanout`: each matched file's content is one input |
| `--inputs-file <edn>` | `fanout`: an EDN vector of context maps |
| `--prompt-file <path>` | `fanout`: prompt template applied per input |
| `--workflow <name>` | `fanout`: run a named workflow per input |
| `--limit N` | `runs list`: how many to show (default 20) |
| `--provider` / `--model` / `--step N` | `replay`: override provider/model, or re-run from step N |
| `--all` | `graph`: every workflow |
| `--providers <p1,p2>` | `compare`: limit to a comma-separated subset of providers |
| `--result-codes` | Map `RESULT:` sentinel to exit codes (`BLOCKED`→2, `NOTHING_TO_DO`/`DONE`→0) |

### Provider aliases

`c` → claude (default) · `g` → gemini · `x` / `o` → codex · `q` → qwen ·
`ny` → nyma · `nls` → nyma-local (local model via Ollama-compatible endpoint).
Custom providers declare their own aliases in config.

---

## Output behaviour (Unix-correct)

- **stdout** = the final text you'd paste or pipe. Nothing else.
- **stderr** = breadcrumbs (`-v`), intermediate text (`-vv`), warnings, cost
  (`--cost` or `-v`). Silent at verbosity 0.
- `--json` = full result as **plain-map EDN** (records deep-converted) to
  stdout; stderr stays empty at verbosity 0.
- `--dry-run` = the rendered plan to stdout; no execution.
- **Exit codes**: `0` success · `1` the run failed (chain/step error) ·
  `2` usage/config error or unexpected exception.

---

## Template variables

Substituted in prompts and shell-step argv before each step runs:

| Variable | Expands to |
|---|---|
| `{{stdin}}` | The original input to the chain (constant across all steps) |
| `{{prev}}` | The immediately previous step's text (linear chains). In a DAG, falls back to `{{stdin}}` |
| `{{arg-N}}` | The N-th positional CLI argument, 1-indexed |
| `{{step-NAME}}` | The text of the step whose `:id` is `NAME` (linear **and** DAG) |

Linear chains can use `{{prev}}` for ergonomics; DAG chains use explicit
`{{step-NAME}}` because "previous" is ambiguous when branches run in parallel.

`{{fragment:NAME}}` is different: it is a **config-load-time** include
(expands a `:fragments` snippet while resolving a workflow), *not* a runtime
value. It is resolved before the four runtime variables above.

---

## Steps-file format

An EDN map `{:steps [ … ]}`. A chain is **linear** if no step declares
`:depends-on`, otherwise it's a **DAG** executed in topological waves
(steps within a wave run in parallel).

```clojure
{:steps
 [;; LLM step
  {:id        :draft                 ; optional; required to be referenced
   :provider  :claude                ; required for LLM steps
   :model     "claude-sonnet-4-5"    ; optional; provider default otherwise
   :prompt    "Draft a spec for: {{stdin}}"
   :allowed-tools [:read :grep]      ; coarse set; adapter translates per provider
   :max-turns 5
   :schema    "schemas/out.json"     ; optional
   :fallback  [:gemini :codex]}      ; try these if :provider fails

  ;; Shell step
  {:id :diff :cmd ["git" "diff" "--staged"] :timeout-ms 30000}

  ;; Gate step — human approval checkpoint (see Gates below)
  {:id :ok :gate "Post this review?\n\n{{step-review}}"}

  ;; DAG join — runs after both deps, in a later wave
  {:id :review
   :provider :claude
   :prompt "Reconcile:\n{{step-draft}}\n---\n{{step-diff}}"
   :depends-on [:draft :diff]}]}
```

`:depends-on []` marks a parallel root (runs in the first wave alongside other
rootless steps). Cyclic or unresolvable dependencies are rejected before
execution. Chains stop on the first failed step. `--max-cost-usd` aborts
mid-chain once accumulated cost exceeds the cap.

A step may carry **four kinds**: LLM (`:provider`/`:tier`/`:role`/`:skill`),
shell (`:cmd`), gate (`:gate`), or phase-splice (`:use`). `:tier`/`:role`/
`:skill`/`:use` and `{{fragment:…}}` are the shared-library keys (see the
**Shared prompt library** subsection under *Configuration*).

> **Scope:** the shared library + `:phases` are expanded for **named
> workflows** (`cw <workflow>`) and **`--steps-file`** plans. Ad-hoc
> `cw chain P1 P2 …` runs its prompts **as written** — `:role`/`:skill`/
> `:tier`/`:use`/`{{fragment:…}}` are not resolved there (give prompts
> explicitly). `--steps-file` is the way to get library expansion without
> defining a named workflow.

## Gates (human approval)

A step `{:id :ok :gate "Prompt? {{step-x}}"}` pauses the chain for a yes/no
confirmation before continuing — a checkpoint in front of a mutating step
(post a review, close an issue, push a branch). The prompt string supports
the usual `{{stdin}}`/`{{prev}}`/`{{step-*}}` substitution.

- The prompt is shown and the answer is read on the **controlling terminal
  (`/dev/tty`)** — *not* stdin, because stdin is the chain's piped data.
- Only `y`/`yes` (case-insensitive) approves. Anything else — including a
  blank line, EOF, **or no controlling terminal** — rejects. Gates **fail
  closed**: a gated workflow in a pipe or CI aborts (exit 1) rather than
  hang or silently proceed.
- `-y` / `--yes` auto-approves every gate (the non-interactive / "yolo"
  switch). This is how you run gated workflows headlessly on purpose.
- A gate is **transparent to data flow**: it is recorded in the run (and the
  audit log) but does not change `{{prev}}` or contribute to `{{step-*}}`,
  so inserting a gate never corrupts a pipeline.
- Rejection surfaces as a `:gate-rejected` error and stops the chain via the
  normal stop-on-first-failure path.

Gates are sequential checkpoints — use them in linear chains or as their own
DAG wave. A gate sharing a parallel wave with other steps will prompt while
those run; don't do that.

---

## Configuration

`cw config path` prints the location (`$CW_CONFIG` or
`~/.config/cw/config.edn`). Resolution is a deep merge:

```
baked-in defaults  ⊕  ~/.config/cw/config.edn  ⊕  ./.cw.edn (project)
```

Maps merge recursively; scalars and vectors from the right win; a `nil`
override preserves the left value. Config is structurally validated on every
run: every step has a kind (`:provider`/`:tier`/`:role`/`:skill` ·
`:cmd` · `:gate` · `:use`); `:depends-on`/`:id` resolve and `:id`s are
unique; every `:role`/`:skill`/`:tier`/`:use`/`{{fragment:…}}` reference
resolves; `{:file …}` prompt targets load; `:role/:skill/:tier` are not put
on a `:cmd`/`:gate` step; an LLM step (`:provider`/`:tier`) has at least one
of `:role`/`:skill`/`:prompt` (no silent blank-prompt calls); phases don't
nest (⇒ acyclic); each provider's `:pricing-key` has a `:pricing` entry. Any
failure aborts with the full list (exit 2).

### Shape

```clojure
{:default-provider    :claude
 :default-verbosity   0
 :max-cost-usd-per-run nil
 :log-runs?           true
 :log-dir             "~/.local/state/cw/runs"
 :state-file          "~/.local/state/cw/state.edn"
 :fanout-concurrency  4
 :blacklist-threshold 3                 ; consecutive errors before a cooldown

 :providers
 {:claude {:cmd "claude"
           :args ["-p" "--output-format" "json"]   ; NOTE: no --bare → RTK/lean-ctx work
           :prompt-via :arg                         ; :arg | :stdin
           :parser :claude-json                     ; :claude-json|:gemini-json|:codex-jsonl|:raw|:shell-cmd
           :model "claude-sonnet-4-5"
           :alias [:c]
           :supports-schema false
           :pricing-key "claude"}
  ;; … gemini, codex …
  }

 :workflows
 {:commit-msg
  {:doc "Commit message from staged diff"
   :stdin-cmd ["git" "diff" "--staged"]   ; produces {{stdin}} if nothing piped
   :steps [{:provider :claude
            :model "claude-haiku-4-5"
            :prompt "One-line commit message. Output only the message.\n\n{{stdin}}"
            :fallback [:gemini]}]}}

 :pricing
 {"claude" {"claude-sonnet-4-5" {:input 3.0 :output 15.0 :cached-input 0.30}}}}
```

### Provider keys

`:cmd` `:args` `:model` `:alias` `:parser` · `:prompt-via` (`:arg` puts the
prompt on argv, `:stdin` pipes it) · `:prompt-flag` (e.g. `"-p"` — emit the
prompt after this flag instead of as a bare positional) · `:model-flag`
(default `"--model"`) · `:supports-schema` + `:schema-flag` (native schema) ·
`:fallback` (default fallback chain) · `:daily-request-limit` +
`:reset-hour-utc` (credit tracking) · `:pricing-key` · `:tool-mapping`
(custom providers: map coarse tools like `:read` to CLI flags).

stdin precedence for a workflow: **piped stdin > `:stdin-cmd` > none.**

### Shared prompt library (`:roles` / `:skills` / `:fragments` / `:tiers` / `:phases`)

Workflows don't inline prompts — they compose from a config-level library, so
a persona is defined once and reused. Two reuse pillars, both resolved at
config-load (no runtime engine; fully backward compatible — a literal
`:prompt` with no refs behaves exactly as before):

**Prompt reuse.** A step references `:role` (persona) + `:skill` (task) +
`:tier` (provider/model); `{{fragment:NAME}}` embeds a snippet:

```clojure
:roles    {:critic "You are a seasoned adversarial reviewer. ..."}
:skills   {:adversarial-review {:file "prompts/adversarial-review.md"}}
:fragments {:verdict-tail "The VERY LAST line must be ### Verdict: ..."}
:tiers    {:cheap {:provider :claude :model "claude-haiku-4-5"}
           :think {:provider :claude :model "claude-sonnet-4-5"}}
;; step:
{:id :review :tier :think :role :critic :skill :adversarial-review
 :prompt "PR metadata:\n{{step-fetch}}\n\nDiff:\n{{step-diff}}"}
```

Composed `:prompt` = `role` + `skill` + step `:prompt`, joined by blank
lines, with `{{fragment:…}}` expanded. `:tier` fills `:provider`/`:model`
**unless the step sets them** (explicit > tier > provider default; CLI
`--provider/--model` still override last). A `:roles/:skills/:fragments`
value is an inline string **or** `{:file "prompts/x.md"}` (resolved from the
config dir, then bundled resources). `cw config init` scaffolds the
`prompts/` dir; `cw roles|skills|fragments` list the library.

**Step-sequence reuse (`:phases`).** A `{:use :phase}` step splices a named
phase's steps in place, ids prefixed `phase__id` (regex-safe; intra-phase
`{{step-…}}` refs and `:depends-on` are rewritten to match). cw's
declarative analogue of orca2's `:type :workflow` include — minus loops;
`validate` rejects nested phases (⇒ acyclic). `cw phases` lists them.

### Shipped workflows

| Workflow | Shape | Notes |
|---|---|---|
| `commit-msg` | 1 (cheap) | Conventional-commit from staged diff |
| `explain-error` | 1 (cheap) | Cause / Fix / Confidence; pipe-from-stderr |
| `summarize-translate` | 2 (claude→gemini) | `"<lang>"` arg (default French); cross-model |
| `triage-issue` | 4 (DAG) | `<N>`: classify → message → **acts** (gh comment/label/close) via verdict data-flow |
| `code-review-multi` | 4 (3 reviewers ∥ → merge+verdict) | DAG; severity-graded |
| `pr-review` | 6 (shell + LLM) | `<N>`: `gh` fetch → adversarial review → **verdict LLM → posts back** |
| `research` | 3 LLM | `"<topic>"`: literature review → analysis → report |
| `content` | 3 LLM | `"<topic>"`: research → draft → 4-pass edit |
| `git-ship` | `:use :git-ship` (7) | commit (LLM message, runs `git` itself) → push → open PR; arg = optional PR title |
| `security-review` | 1 LLM | stdin diff → adversarial STRIDE review |
| `threat-model` | 1 LLM | stdin → STRIDE threat model |
| `spec` | 1 LLM | `"<topic>"` → engineering spec |
| `impact` | 1 LLM | stdin diff → blast-radius analysis |
| `verify` | `:use :verify-gate` (2) | stdin diff/artifact → skeptical PASS/FAIL gate; **exit 1 on FAIL** |
| `git-ship-verified` | `:use :verify-gate` + `:use :git-ship` (9) | verify working-tree diff PASS, then ship; **FAIL ⇒ no commit** |
| `fix-ci` | 1 LLM | latest failed CI log (`gh run view`) → root cause + patch diff |
| `dep-audit` | 1 LLM | stdin manifest/lockfile → outdated + risk + bump plan |
| `doc-sync` | 1 LLM | stdin `git diff` → doc drift + patch diff |

`pr-review`/`research`/`content`/`git-ship`/`triage-issue` are ported from
orca2. `pr-review` recreates orca2's verdict-conditional post-back as **cw
data flow**: a `:verdict` LLM step emits the `gh` review flag as a value, and
a shell step passes it plus the review body as bash **positional args**
(`$1..$3`) — never spliced into the script — so arbitrary review content is
injection-inert (verified). GitHub-mutating workflows need an authenticated
`gh`; `git-ship`'s commit step needs claude with Bash tool access.

### Verify gate & the RESULT protocol

`:verify-gate` is a reusable phase (loop-harness pattern): a skeptical `:critic`
emits one `PASS`/`FAIL` token, and a shell step turns `FAIL` into a nonzero exit
that **stops the chain** (the verdict is passed as a bash positional `$1`, never
spliced — injection-inert). Use it standalone (`git diff | cw verify` → exit 1
on FAIL) or append `{:use :verify-gate}` as the tail of any write/generate
workflow so it refuses to "succeed" on unverified output.

The `{{fragment:result-line}}` snippet makes a prompt end with a machine-readable
`RESULT: DONE items=N | NOTHING_TO_DO | BLOCKED reason=…` line. With the opt-in
`--result-codes` flag, a successful run maps that sentinel to an exit code
(`NOTHING_TO_DO`/`DONE`→0, `BLOCKED`→2); without the flag, exit codes are
unchanged (legacy 0/1 on success/failure).

The shipped action workflows already emit it: `triage-issue` ends `RESULT: DONE
items=<issue>` (or `BLOCKED` on an unrecognized decision) and `pr-review` ends
`RESULT: DONE items=<pr>`. So a cron wrapper — `cw`'s no-daemon answer to
loop-harness's scheduler — can branch on the exit code:
`while read n; do cw triage-issue "$n" --result-codes || log-blocked "$n"; done`.

---

## Custom providers & parsers (no source edit)

Add a provider entirely in config. Pair it with `:parser :shell-cmd` plus a
`:parser-cmd` to post-process arbitrary CLI output:

```clojure
:providers
{:myllm {:cmd "myllm-cli"
         :args ["--format" "json"]
         :prompt-via :arg
         :parser :shell-cmd
         :parser-cmd ["jq" "-r" ".response"]   ; raw stdout piped through this
         :alias [:my]
         :pricing-key "myllm"}}
```

Built-in parsers: `:claude-json`, `:gemini-json`, `:codex-jsonl` (NDJSON
reduce), `:qwen-json` (JSON array of events with terminal `{type:"result"}`),
`:raw` (stdout *is* the answer), `:shell-cmd` (the extension point).

---

## Fallback, state & cost

- **Automatic fallback**: a step's `:fallback [provider…]` (or the provider's
  default) is tried on a *retryable* failure. Timeouts and non-zero exits are
  retryable; out-of-credits / rate-limit are retryable **and** blacklist the
  provider (for the day / an hour); bad-request and parse errors are not.
- **State** (`state-file`): per-provider request counts (with daily reset by
  `:reset-hour-utc`) and a consecutive-error blacklist
  (`:blacklist-threshold`). Writes are atomic (temp file + `ATOMIC_MOVE`).
- **Cost**: computed from token usage via `:pricing`; `nil` when unknown
  (never a fake `0.0`). `--max-cost-usd` aborts before overrun.

## Audit log & replay

Every chain run writes one EDN file to
`~/.local/state/cw/runs/<date>/<run-id>.edn` (timestamp, command, plan, full
per-step results). Disable with `:log-runs? false` or `--no-log`.

- `cw runs list [--limit N]` — newest first; corrupt files are skipped.
- `cw runs show <id>` — the full logged record.
- `cw replay <id>` — re-run the exact plan (new run-id; original preserved).
  `--provider g` / `--model M` override every LLM step; `--step N` re-runs
  from step N with earlier outputs seeded from the log (cheap A/B of a tail).

## Eval

`cw eval <workflow> --runs N [--fixture F]` runs the workflow N times and
reports unique-output count, cost/duration mean/min/max/stdev, and a pairwise
bag-of-words **Jaccard** similarity matrix — enough to see "all runs identical"
vs "high variance" and tune the prompt.

---

## Evidence-driven implementation notes

A couple of places where the implementation diverges from what naive specs
of the underlying CLIs would suggest:

1. **Gemini `:prompt-via`** — the LLD specifies `:stdin`; real
   `gemini --help` shows `-p/--prompt` is *required* to enter headless mode
   (a bare positional / stdin-only call stays interactive and hangs). The
   default config uses `:prompt-via :arg` + `:prompt-flag "-p"`. Gemini also
   needs working auth (`GEMINI_API_KEY` or OAuth).
2. **`:bbin/bin` lives in `bb.edn`** (not only `deps.edn`) — bbin's local-dir
   install reads it there; the remote git install reads `deps.edn`. Both
   carry it.

## Known limitations (v1)

- **Codex parser is UNVERIFIED.** The `codex` CLI was unavailable during
  development, so `:codex-jsonl` is tested only against a hand-authored
  fixture. Validate against real `codex exec --json` before relying on it;
  adjust `cw.parse/codex-reduce` if the event shapes differ.
- **Gemini parser** was likewise validated against a hand-authored fixture
  (no local API key); the **claude** fixture is real/live-captured.
- No real-time streaming — final output only.
- No cross-provider session continuity.
- DAG is wave-based (topological levels), not a true scheduler.
- No iteration (`for-each`) or revision loops — gates exist, but loops do not.
  Iterate at the top level with `fanout` over a per-item workflow instead.
- No in-step retry — only cross-provider fallback.
- `eval` similarity is bag-of-words Jaccard (under-detects semantic equality).
- Unknown `--flags` fall through as prompt text (Unix-style; no typo error).

---

## Architecture

```
src/cw/
  main.clj       entry, position-independent arg parsing, dispatch, output
  config.clj     load/deep-merge/validate, provider & workflow resolution,
                 shared-library expansion (roles/skills/fragments/tiers/phases)
  provider.clj   build-command, run (timeout), fallback loop, error classify
  parse.clj      defmulti parser: claude-json / gemini-json / codex-jsonl /
                 raw / shell-cmd
  chain.clj      linear + DAG runner, template substitution, cost cap
  workflow.clj   workflow → chain (stdin precedence, arg binding)
  state.clj      atomic credit/blacklist state
  pricing.clj    token usage → USD (nil when unknown)
  log.clj        per-run EDN audit (write/list/load)
  replay.clj     replay with provider/model override + step slicing
  compare.clj    one prompt → all providers (parallel)
  fanout.clj     bounded-concurrency pool over many inputs
  eval.clj       N-run variance + Jaccard similarity
  mermaid.clj    workflow → Mermaid flowchart
  dryrun.clj     render a plan without executing
  doctor.clj     environment diagnostics
  verbosity.clj  stderr breadcrumb helpers
  result.clj     Result / ChainResult records
```

## Testing

`bb test` runs a hermetic suite (no network — provider behaviour is exercised
via local `true`/`false`/missing binaries and captured fixtures):
**80 tests, 290 assertions**. Coverage spans parsing, chain/DAG, workflows,
state, replay, the provider fallback loop, `build-command`/`classify`/
`retryable?`, config deep-merge/validate (incl. **blank-prompt rejection**
and `:file` classpath fallback), the shared-library expansion (role+skill+
tier composition, explicit-beats-tier, `:file`-backed prompts, **recursive
`{{fragment}}` with cycle bound**, `:phases` splice + id-prefix + cycle
rejection + **workflow-level `{{step-…}}` ref rewriting** after a `{:use}`,
**`:gate` inside a `:phases` entry**), gate decision + fail-closed behaviour,
the CLI arg layer (`extract-verbosity`/`flag-map`/`coerce-val`/
`split-chain-args`/`parse-global` — flag-position independence + chain
hard-stop), and the dryrun/log/mermaid/eval helpers. Network-/env-heavy
paths (live providers, `compare`, `fanout`) have manual smoke checks in
[`test/SMOKE.md`](test/SMOKE.md).

## License

See repository.
