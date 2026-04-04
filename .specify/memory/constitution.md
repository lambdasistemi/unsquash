# unsquash Constitution

## Core Principles

### I. Two-Phase Rewriting

The tool operates in two phases on a messy commit sequence:

**Phase 1 — Consolidate (smart squash).** LLM groups commits that belong together: a commit and its fixup, two commits touching the same concern, WIP noise. Squash each group, preserving original order. Order preservation guarantees compilability. The original commit boundaries carry signal about intent — never squash everything into one blob first, because that destroys information that makes phase 2 easier.

**Phase 2 — Unfold (decompose).** For each remaining commit that's too big, decompose into sub-commits using iterative unfolding from periphery to center: pure additions first, then internal rewrites (same API), then API changes (with callsite fallout), then deletions (always last). Each iteration shrinks the remaining diff.

The tool works on a single commit (skip phase 1) or a commit range / PR (full two-phase workflow).

### II. Mechanical Before Semantic

Hunk classification is mechanical wherever possible: pure additions, pure deletions, and unchanged-signature body edits can be identified by diff analysis alone. LLM is only invoked for semantic questions: "did this function's API change?", "which callsite hunks belong to this signature change?", "what's the dependency order?", and for phase 1 grouping decisions.

### III. Pluggable Components

Three pluggable axes:
- **LLM CLI** — any tool that accepts a prompt on stdin and returns structured output. No hardcoded API.
- **Compile oracle** — pluggable per language (GHC, cargo, go build, etc.). The oracle is the correctness proof.
- **VCS** — git for now, but the diff parsing should not assume git internals beyond unified diff format.

### IV. Babashka Orchestration

Babashka owns all state: the diff, the patch sets, the iteration loop, the git operations. The LLM never touches git directly. Babashka calls the LLM CLI as a function: context in, structured JSON out.

### V. Compile-Validated Commits

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

**Version**: 1.0.0 | **Ratified**: 2026-04-04
