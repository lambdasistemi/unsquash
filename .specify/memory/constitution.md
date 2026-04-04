# unsquash Constitution

## Core Principles

### I. Two-Phase Rewriting

The tool operates in two phases on a messy commit sequence:

**Phase 1 — Consolidate (smart squash).** LLM groups commits that belong together: a commit and its fixup, two commits touching the same concern, WIP noise. Squash each group, preserving original order. Order preservation guarantees compilability. The original commit boundaries carry signal about intent — never squash everything into one blob first, because that destroys information that makes phase 2 easier.

**Phase 2 — Unfold (decompose).** For each remaining commit that's too big, decompose into sub-commits using iterative unfolding from periphery to center: pure additions first, then internal rewrites (same API), then API changes (with callsite fallout), then deletions (always last). Each iteration shrinks the remaining diff.

The tool works on a single commit (skip phase 1) or a commit range / PR (full two-phase workflow).

### II. The Output Is a Dependency Graph

The tool's core data structure is a **dependency graph** where:

- **Nodes** = hunks (or sub-hunks after splitting)
- **Edges** = two kinds:
  - **Depends on** (directed) — hunk B needs hunk A to exist first. A compiles without B, but not vice versa. Example: function definition A, then caller B.
  - **Co-occurs with** (undirected) — hunk A and hunk B are meaningless without each other. Neither compiles alone. Example: using `newThing` in code and `import Foo (newThing)`.

**Co-occurrence groups contract into atomic units.** Before any ordering, all co-occurring hunks merge into single graph nodes. Each atomic unit has a **semantic identity** — a named category describing what it represents:

- `define:Foo` = type definition + export + deriving instances
- `use:Foo:ModuleX` = import of `Foo` in module X + every usage of `Foo` in that module
- `api-change:bar:new-arg` = signature change + all callsite updates

The dependency edges between atomic units give the topological order. `use:Foo:ModuleX` **depends on** `define:Foo`. Different valid topological sorts produce different commit narratives — all compile, but tell different stories.

**The user picks the path.** The tool presents the valid orderings (or the LLM proposes one with narrative reasoning). The user approves. Then the chosen path flattens into commits — each atomic unit (or group of independent units) becomes one commit.

### III. Three-Layer Edge Discovery

Discovering the graph edges operates in three layers, each reducing work for the next:

**Layer 1 — Preflight (mechanical, no LLM).** Deterministic rules applied to raw diff structure. Each rule discovers edges between hunks. See `docs/hunk-zoo.md` for the full rule catalog (R1–R12). Examples:
- R1: Export of name defined in same diff → **co-occurrence** edge (export ↔ definition)
- R2: Import of name used only in new code → **co-occurrence** edge (import ↔ usage)
- R3: Import list is strict superset of old → **co-occurrence** edge (import ↔ usage)
- R5: Wildcard count change in pattern match → **co-occurrence** edge (pattern ↔ field addition)
- R8: Whitespace-only line change → **no edges** (strip before classification)

For high-confidence preflight edges (R4 trailing comma, R5 wildcards, R8 whitespace), the LLM may never need to see the hunks at all.

**Layer 2 — LLM confirmation.** For preflight-discovered edges where confidence is moderate, the LLM validates: "these two hunks were linked because the import list is a strict superset. Does that look right?"

**Layer 3 — LLM edge discovery with iterative refinement.** For hunks with no preflight edges, the LLM answers: "why does this change exist, and which other hunks is it related to?" The LLM discovers both dependency and co-occurrence edges.

The discovery runs in iterative rounds:
1. **Round 1**: LLM examines each unlinked hunk with surrounding code context and any preflight edges. Proposes edges — may be vague or inconsistent.
2. **Round 2+**: LLM sees all hunks again with the accumulated graph as context. Merges near-synonymous atomic units, discovers missed edges, reclassifies where needed. Explicitly asks: "are any existing atomic units actually the same thing?"
3. **Converge**: Stop when a full round produces zero new edges or reclassifications.

### III. LLM Synthesizes Intermediate States

When a hunk sits at the intersection of two edges (two reasons), it can't belong to just one atomic unit. The LLM can **rewrite it as two sequential hunks**, each satisfying one edge. This produces an intermediate code state that never existed in the original history — and that's fine. The goal is a reviewable narrative, not a reconstruction of what happened.

The constraint: the final state after applying both hunks must be identical to the original. The intermediate state is the LLM's invention, validated by the oracle.

Example: a function that gets both a new argument AND uses a new type becomes two commits — one for the type adoption, one for the API change. The LLM writes the intermediate version where only one change is applied.

### IV. Two-Tier Oracle: LSP + Full Build

The compile oracle has two tiers:

1. **LSP (fast, incremental)** — for the tight feedback loop during hunk splitting and intermediate state synthesis. Since we always start from a compiling state, LSP is quiet. The moment the LLM applies a change, LSP fires diagnostics within milliseconds — type errors, missing imports, unknown names. The LLM iterates with LSP until diagnostics are clean.
2. **Full build (slow, definitive)** — for final validation of each commit in the sequence. Catches everything LSP might miss: linker errors, Template Haskell, CPP, cross-module issues.

The LSP tier is a future enhancement. The basic tool works with just the full build oracle. LSP makes the LLM's code synthesis loop practical at scale.

### V. Hunk Boundaries Are Not Sacred

Git's diff algorithm optimizes for minimal output, not semantic meaning. It may merge unrelated changes into one hunk (e.g. a signature change and an adjacent new function) or cut at awkward boundaries. The tool must not treat git's hunk boundaries as ground truth.

Mitigations:
- **Split hunks at blank lines** — post-processing pass to break git's hunks at natural boundaries.
- **Use `patience` or `histogram` diff algorithm** — produces more semantically meaningful breaks than the default Myers algorithm.
- **Per-hunk multi-reason detection** — if the classifier assigns multiple reasons to a hunk, that hunk may need splitting.

Additionally, "pure additions" at the code level may appear as modifications in the diff: adding an import to an existing import list, adding to an export list, extending a deriving clause, adding a record field. These are **addition-enabling modifications** — they exist solely to make a new addition visible/usable. The classifier must recognize them as belonging to their addition's category, not as independent modifications.

### VI. Preflight Rules Are a Codebook

The preflight rules (R1–R12) form a growing codebook maintained in `docs/hunk-zoo.md`. Each rule is a pattern learned from real diffs that mechanically discovers edges (dependency or co-occurrence). When the LLM encounters a new recurring edge pattern during discovery, it should be promoted to a preflight rule — expanding the mechanical layer and reducing future LLM calls.

### VII. Pluggable Components

Three pluggable axes:
- **LLM CLI** — any tool that accepts a prompt on stdin and returns structured output. No hardcoded API.
- **Compile oracle** — pluggable per language (GHC, cargo, go build, etc.). The oracle is the correctness proof.
- **VCS** — git for now, but the diff parsing should not assume git internals beyond unified diff format.

### VIII. Babashka Orchestration

Babashka owns all state: the diff, the patch sets, the iteration loop, the git operations. The LLM never touches git directly. Babashka calls the LLM CLI as a function: context in, structured JSON out.

### IX. Compile-Validated Commits

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

**Version**: 1.4.0 | **Ratified**: 2026-04-04 | **Last Amended**: 2026-04-04
