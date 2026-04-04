# unsquash Constitution

## Core Principles

### I. Two-Phase Rewriting

The tool operates in two phases on a messy commit sequence:

**Phase 1 — Consolidate (smart squash).** LLM groups commits that belong together: a commit and its fixup, two commits touching the same concern, WIP noise. Squash each group, preserving original order. Order preservation guarantees compilability. The original commit boundaries carry signal about intent — never squash everything into one blob first, because that destroys information that makes phase 2 easier.

**Phase 2 — Unfold (decompose).** For each remaining commit that's too big, decompose into sub-commits using iterative unfolding from periphery to center: pure additions first, then internal rewrites (same API), then API changes (with callsite fallout), then deletions (always last). Each iteration shrinks the remaining diff.

The tool works on a single commit (skip phase 1) or a commit range / PR (full two-phase workflow).

### II. Three-Layer Classification

The hunk classifier operates in three layers, each reducing work for the next:

**Layer 1 — Preflight (mechanical, no LLM).** Deterministic rules applied to raw diff structure. Each rule tags hunks with a candidate category and confidence level. Hunks matching these rules are pre-tagged before the LLM sees them. See `docs/hunk-zoo.md` for the full rule catalog (R1–R12). Examples:
- R1: Export of name defined in same diff → `addition:wiring`
- R3: Import list is strict superset of old → `addition:wiring`
- R5: Wildcard count change in pattern match → `addition:wiring:mechanical`
- R8: Whitespace-only line change → `formatting` (strip before classification)

For high-confidence preflight tags (R4 trailing comma, R5 wildcards, R8 whitespace), the LLM may never need to see the hunk at all.

**Layer 2 — LLM confirmation.** For preflight-tagged hunks where confidence is moderate, the LLM validates: "this hunk was pre-classified as `addition:wiring` because the import list is a strict superset. Does that look right?" Cheap, narrow question.

**Layer 3 — LLM classification with iterative refinement.** For untagged hunks or low-confidence preflight, the LLM answers: "why does this change exist?" Each hunk gets tagged with a reason (e.g. "adds constructor `Foo` to type `Bar`", "caller of `baz` updated for new argument `quux`"). Reasons are not predefined categories — they emerge from the code.

The classifier runs in iterative rounds:
1. **Round 1**: LLM classifies each hunk with surrounding code context and any preflight tags. Produces initial reasons — may be vague or inconsistent.
2. **Round 2+**: LLM sees all hunks again with the accumulated category list as context. Collapses duplicates, merges near-synonymous reasons, reclassifies where needed. Explicitly asks: "are any existing categories actually the same thing?"
3. **Converge**: Stop when a full round produces zero reclassifications.

Hunks sharing the same root cause form a natural commit cluster. Some hunks may have multiple reasons — these are candidates for sub-hunk splitting.

### III. Hunk Boundaries Are Not Sacred

Git's diff algorithm optimizes for minimal output, not semantic meaning. It may merge unrelated changes into one hunk (e.g. a signature change and an adjacent new function) or cut at awkward boundaries. The tool must not treat git's hunk boundaries as ground truth.

Mitigations:
- **Split hunks at blank lines** — post-processing pass to break git's hunks at natural boundaries.
- **Use `patience` or `histogram` diff algorithm** — produces more semantically meaningful breaks than the default Myers algorithm.
- **Per-hunk multi-reason detection** — if the classifier assigns multiple reasons to a hunk, that hunk may need splitting.

Additionally, "pure additions" at the code level may appear as modifications in the diff: adding an import to an existing import list, adding to an export list, extending a deriving clause, adding a record field. These are **addition-enabling modifications** — they exist solely to make a new addition visible/usable. The classifier must recognize them as belonging to their addition's category, not as independent modifications.

### IV. Preflight Rules Are a Codebook

The preflight rules (R1–R12) form a growing codebook maintained in `docs/hunk-zoo.md`. Each rule is a pattern learned from real diffs. When the LLM encounters a new recurring pattern during classification, it should be promoted to a preflight rule — expanding the mechanical layer and reducing future LLM calls.

### V. Pluggable Components

Three pluggable axes:
- **LLM CLI** — any tool that accepts a prompt on stdin and returns structured output. No hardcoded API.
- **Compile oracle** — pluggable per language (GHC, cargo, go build, etc.). The oracle is the correctness proof.
- **VCS** — git for now, but the diff parsing should not assume git internals beyond unified diff format.

### VI. Babashka Orchestration

Babashka owns all state: the diff, the patch sets, the iteration loop, the git operations. The LLM never touches git directly. Babashka calls the LLM CLI as a function: context in, structured JSON out.

### VII. Compile-Validated Commits

Every unfolded commit must compile when prepended to the remaining stack. If it doesn't compile, the extraction was wrong — either too much or too little was extracted. The tool retries by adjusting the hunk assignment (LLM-assisted if needed). Deletions are exempt from compile checks (they're always last).

## Commit Discipline

- **StGit** for all commit management during development
- Each commit addresses a single concern
- Conventional Commits format: `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `test:`
- History is for the reviewer, not the author — no fixup commits, no WIP noise
- When working with the AI assistant: always squash on merge, keep PRs focused on one concern

## Development Workflow

- **Nix-first**: `flake.nix` provides all build tools, CI and local use the same shell
- **PR-only**: never push directly to main
- **Local CI before push**: `just ci` must pass before pushing
- **Branch naming**: `feat/`, `fix/`, `refactor/`, `docs/`, `chore/`
- **Worktrees**: one branch per worktree, main stays on main
- **Labels and assignment**: every PR labeled and assigned
- **Linear history**: rebase merge on main

## Quality Gates

- All unfolded commits must pass the compile oracle (except trailing deletions)
- Hunk classification must be deterministic for mechanical categories
- LLM prompts must have defined JSON schemas for responses
- The tool must fail loudly on unclassifiable hunks rather than silently misclassify

## Governance

Constitution supersedes all other practices. Amendments require documentation and user approval.

**Version**: 1.2.0 | **Ratified**: 2026-04-04 | **Last Amended**: 2026-04-04
