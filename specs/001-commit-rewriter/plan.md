# Implementation Plan: Two-Phase Commit Rewriter

**Branch**: `001-commit-rewriter` | **Date**: 2026-04-04 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-commit-rewriter/spec.md`

## Summary

Build an MCP tool that takes a messy commit sequence (or single squashed commit) and produces a clean, logically ordered commit sequence. Phase 1 consolidates related commits via smart squash. Phase 2 unfolds large commits by building a dependency graph of hunks, contracting co-occurring hunks into atomic units, computing topological orderings, and validating each commit compiles via a pluggable oracle. Babashka orchestrates everything; LLM handles semantic edge discovery; compile oracle validates.

## Technical Context

**Language/Version**: Clojure (Babashka) — JVM-less, fast startup, good for scripting + data manipulation
**Primary Dependencies**: babashka (CLI orchestration), clojure.data (diff structures), cheshire (JSON), babashka.process (shell-out to LLM CLI and oracle)
**Storage**: N/A — operates on git working trees, no persistent storage
**Testing**: bb test runner, plus integration tests using real git repos with known diffs from the hunk zoo
**Target Platform**: Linux (primary), macOS (secondary). Runs where bb and git are available.
**Project Type**: CLI tool + MCP server
**Performance Goals**: Process a 500-line diff within 10 minutes including oracle calls (SC-001)
**Constraints**: LLM calls are the bottleneck — minimize round-trips. Structural preflight + LLM semantic preflight should classify 80%+ of hunks before iterative refinement (SC-002). With a language plugin, structural preflight alone should handle 40%+.
**Scale/Scope**: Single-user CLI tool. Diffs up to ~2000 lines. Not designed for monorepo-scale changes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Two-Phase Rewriting | PASS | Plan implements both phases as specified |
| II. Dependency Graph | PASS | Core data structure is the graph with directed + co-occurrence edges |
| III. LLM Synthesizes Intermediate States | PASS | FR-007 covers this; deferred to later iteration |
| IV. Two-Tier Oracle | PASS | Full build oracle first; LSP tier is future enhancement |
| V. Hunk Boundaries Not Sacred | PASS | Hunk splitting at blank lines + patience diff in plan |
| VI. Preflight Rules | PASS | Universal structural rules (language-agnostic) + optional language plugins. R1–R5,R9 moved to Haskell plugin. |
| VII. Pluggable Components | PASS | LLM CLI, oracle, VCS all pluggable via config |
| VIII. Babashka Orchestration | PASS | bb owns all state, LLM is a function |
| IX. Compile-Validated Commits | PASS | Oracle validates each commit in sequence |
| StGit for development | PASS | Will use stgit for our own commit management |
| Nix-first CI | PASS | flake.nix provides bb + deps, CI uses nix develop |

No violations. Gate passes.

## Project Structure

### Documentation (this feature)

```text
specs/001-commit-rewriter/
├── plan.md
├── research.md
├── data-model.md
├── contracts/
│   ├── llm-cli.md
│   ├── compile-oracle.md
│   └── mcp-tools.md
└── tasks.md
```

### Source Code (repository root)

```text
src/
├── core/
│   ├── diff_parser.clj       # Unified diff → hunk data structures
│   ├── hunk_splitter.clj     # Split hunks at natural boundaries
│   ├── preflight.clj         # Universal structural edge discovery (language-agnostic)
│   ├── graph.clj             # Dependency graph: nodes, edges, contraction, toposort
│   └── sequencer.clj         # Flatten graph into commit sequence + apply
├── llm/
│   ├── classifier.clj        # LLM edge discovery: prompt construction, response parsing
│   ├── semantic_preflight.clj # LLM semantic preflight (Layer 2): single-pass edge discovery
│   └── synthesizer.clj       # LLM intermediate state generation (FR-007)
├── plugins/
│   └── haskell.clj           # Haskell language plugin: R1–R5, R9 rules
├── oracle/
│   └── compile.clj           # Compile oracle interface + pluggable backends
├── consolidate/
│   └── smart_squash.clj      # Phase 1: group + squash related commits
├── mcp/
│   └── server.clj            # MCP server: tool definitions, request handling
└── cli.clj                   # CLI entry point

test/
├── fixtures/
│   └── hunk-zoo/             # Test diffs from docs/hunk-zoo.md
├── core/
│   ├── diff_parser_test.clj
│   ├── preflight_test.clj
│   └── graph_test.clj
└── integration/
    └── end_to_end_test.clj   # Full pipeline on real git repos

docs/
└── hunk-zoo.md               # Catalog of problematic diff patterns (exists)

bb.edn                        # Babashka project config
flake.nix                     # Nix dev shell with bb, git, test deps
justfile                      # Build recipes: test, ci, format, lint
```

**Structure Decision**: Single Babashka project. Flat `src/` with domain-based namespaces. No web frontend, no database. MCP server is a thin layer over the core library.

## Complexity Tracking

No constitution violations — table not needed.
