# unsquash

MCP tool that rewrites messy commit histories into clean, reviewable sequences.

## What it does

Given a messy PR (WIP commits, fixups, back-and-forth) or a single squashed commit, unsquash produces a logically ordered commit sequence where each commit compiles independently.

**Two phases:**

1. **Consolidate** — smart-squash related commits preserving order (commit + its fixup, WIP noise). Original commit boundaries carry intent signal — never squash everything first.
2. **Unfold** — decompose large commits by building a dependency graph of hunks, contracting co-occurring changes into atomic units, and computing valid topological orderings.

**Three-layer edge discovery:**

1. **Preflight** — mechanical rules (R1–R12) detect obvious relationships without LLM
2. **LLM confirmation** — validate moderate-confidence preflight edges
3. **LLM discovery** — iterative refinement for remaining hunks, converging when stable

The compile oracle validates every step. The user picks the commit narrative from valid orderings.

## Stack

- **Babashka** — orchestration, diff parsing, graph construction, git operations
- **LLM CLI** (pluggable) — semantic edge discovery, commit grouping, intermediate state synthesis
- **Compile oracle** (pluggable) — per-language build validation (GHC, cargo, go, tsc, etc.)

## Status

Design phase complete. See [issue tracker](https://github.com/lambdasistemi/unsquash/issues) for implementation progress.

## Documentation

- [Constitution](/.specify/memory/constitution.md) — project principles
- [Feature spec](/specs/001-commit-rewriter/spec.md) — what and why
- [Implementation plan](/specs/001-commit-rewriter/plan.md) — how
- [Data model](/specs/001-commit-rewriter/data-model.md) — core entities
- [Contracts](/specs/001-commit-rewriter/contracts/) — LLM CLI, compile oracle, MCP tools
- [Hunk zoo](/docs/hunk-zoo.md) — catalog of problematic diff patterns with pre-classification rules
