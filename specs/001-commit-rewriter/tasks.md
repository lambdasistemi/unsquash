# Tasks: Two-Phase Commit Rewriter

**Input**: Design documents from `/specs/001-commit-rewriter/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup

**Purpose**: Project initialization, Nix dev shell, Babashka project structure

- [ ] T001 Create `flake.nix` with Babashka, git, and test runner in dev shell
- [ ] T002 Create `bb.edn` with project paths, dependencies (cheshire, babashka.process), and task definitions
- [ ] T003 Create `justfile` with recipes: test, ci, format, lint, format-check
- [ ] T004 [P] Create `.github/workflows/ci.yml` replacing the stub CI with real Babashka build gate
- [ ] T005 [P] Create `unsquash.edn` example config file at project root with oracle, llm, and diff settings
- [ ] T006 [P] Convert hunk zoo patterns from `docs/hunk-zoo.md` into test fixtures at `test/fixtures/hunk-zoo/` (one `.diff` file per pattern)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core data structures and diff parsing — all user stories depend on these

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T007 Implement unified diff parser in `src/core/diff_parser.clj` — parse `@@` headers, `+`/`-`/` ` lines, produce Hunk data structures per data-model.md
- [ ] T008 Implement hunk splitter in `src/core/hunk_splitter.clj` — split hunks at blank lines and top-level definition boundaries, produce sub-hunks
- [ ] T009 [P] Implement graph data structures in `src/core/graph.clj` — nodes (AtomicUnit), edges (depends/co-occurs), contraction of co-occurrence edges, topological sort
- [ ] T010 [P] Implement compile oracle interface in `src/oracle/compile.clj` — shell out to configured command, capture exit code and stderr, return OracleResult per data-model.md
- [ ] T011 [P] Implement LLM CLI interface in `src/llm/classifier.clj` — shell out to configured command, pass JSON on stdin per `contracts/llm-cli.md`, parse JSON response
- [ ] T012 Write tests for diff parser in `test/core/diff_parser_test.clj` using hunk-zoo fixtures from T006
- [ ] T013 Write tests for graph operations in `test/core/graph_test.clj` — contraction, toposort, cycle detection

**Checkpoint**: Core data structures work. Can parse diffs, build graphs, call oracle and LLM.

---

## Phase 3: User Story 1 — Unfold a single squashed commit (Priority: P1) 🎯 MVP

**Goal**: Given a single commit, build a dependency graph of hunks, contract co-occurrences into atomic units, compute valid orderings, validate with oracle, produce commit sequence.

**Independent Test**: Feed a squashed commit from the hunk zoo fixtures. Tool produces a multi-commit sequence where each commit compiles and the final state matches the original.

### Implementation for User Story 1

- [ ] T014 [US1] Implement preflight rules engine in `src/core/preflight.clj` — apply R1–R12 from hunk-zoo codebook to discover mechanical edges (both depends and co-occurs) between hunks
- [ ] T015 [US1] Write tests for preflight rules in `test/core/preflight_test.clj` — each R1–R12 rule gets a test case using hunk-zoo fixtures
- [ ] T016 [US1] Implement LLM edge discovery in `src/llm/classifier.clj` — construct prompts per `contracts/llm-cli.md` edge discovery request, parse response, add edges to graph
- [ ] T017 [US1] Implement iterative refinement loop in `src/llm/classifier.clj` — run rounds with accumulated graph context, detect convergence (zero new edges/reclassifications)
- [ ] T018 [US1] Implement sequencer in `src/core/sequencer.clj` — given a chosen topological ordering, apply atomic units as git commits via `git apply` + `git commit`, validate each with oracle
- [ ] T019 [US1] Implement final state validation in `src/core/sequencer.clj` — verify the tree after all commits is byte-identical to the original commit's tree (FR-009)
- [ ] T020 [US1] Implement CLI entry point for `analyze` command in `src/cli.clj` — parse args (--ref, --oracle, --llm), run full pipeline: parse diff → split hunks → preflight → LLM discovery → contract graph → output atomic units and edges
- [ ] T021 [US1] Implement CLI entry point for `propose` command in `src/cli.clj` — compute topological orderings from graph, format and display with narrative descriptions
- [ ] T022 [US1] Implement CLI entry point for `apply` command in `src/cli.clj` — take ordering index, run sequencer, output created commits
- [ ] T023 [US1] Write end-to-end integration test in `test/integration/end_to_end_test.clj` — create a git repo with a known squashed commit, run full pipeline, verify each produced commit compiles and final state matches

**Checkpoint**: Single-commit unfolding works end-to-end via CLI.

---

## Phase 4: User Story 2 — Consolidate messy PR commits then unfold (Priority: P2)

**Goal**: Given a commit range, group related commits (smart squash preserving order), then unfold each large commit using the P1 pipeline.

**Independent Test**: Create a branch with 10 messy commits. Tool groups them, squashes groups, then unfolds remaining large commits into a clean sequence.

### Implementation for User Story 2

- [ ] T024 [US2] Implement commit range parser in `src/consolidate/smart_squash.clj` — extract commit list from a git ref range, collect metadata (message, files, stat) per commit
- [ ] T025 [US2] Implement LLM commit grouping in `src/consolidate/smart_squash.clj` — construct prompt per `contracts/llm-cli.md` consolidation request, parse groups response
- [ ] T026 [US2] Implement order-preserving squash in `src/consolidate/smart_squash.clj` — for each approved group, squash commits while preserving relative order, verify sequence still compiles
- [ ] T027 [US2] Implement CLI entry point for `consolidate` command in `src/cli.clj` — parse args (--ref, --llm), run consolidation pipeline, display proposed groups, wait for approval
- [ ] T028 [US2] Wire consolidate → analyze pipeline in `src/cli.clj` — after consolidation, automatically offer to unfold any remaining large commits using the P1 pipeline
- [ ] T029 [US2] Write integration test in `test/integration/consolidate_test.clj` — create messy commit history, run consolidation, verify groups are correct and sequence compiles

**Checkpoint**: Full two-phase workflow works for commit ranges.

---

## Phase 5: User Story 3 — Interactive path selection (Priority: P3)

**Goal**: Present multiple valid orderings with narrative reasoning, let the user choose.

**Independent Test**: Given a graph with independent sub-graphs, the tool offers multiple orderings with different narratives.

### Implementation for User Story 3

- [ ] T030 [US3] Implement multi-ordering computation in `src/core/graph.clj` — generate up to N distinct topological sorts (not all — use heuristics: type-first, feature-first, cleanup-last)
- [ ] T031 [US3] Implement LLM narrative generation in `src/llm/classifier.clj` — for each ordering, ask LLM to generate a one-line narrative describing what story it tells
- [ ] T032 [US3] Implement interactive selection in `src/cli.clj` — display orderings with narratives, accept user choice via stdin, pass to sequencer
- [ ] T033 [US3] Write test for multi-ordering in `test/core/graph_test.clj` — graph with two independent subgraphs produces at least 2 valid orderings

**Checkpoint**: Users can choose between different commit narratives.

---

## Phase 6: MCP Server

**Purpose**: Expose the tool as an MCP server for Claude Code integration

- [ ] T034 [P] Implement MCP server stdio transport in `src/mcp/server.clj` — JSON-RPC over stdin/stdout, tool registration
- [ ] T035 Implement `unsquash-analyze` MCP tool in `src/mcp/server.clj` — wraps the analyze pipeline, returns graph JSON per `contracts/mcp-tools.md`
- [ ] T036 [P] Implement `unsquash-propose` MCP tool in `src/mcp/server.clj` — wraps propose, returns orderings JSON
- [ ] T037 [P] Implement `unsquash-apply` MCP tool in `src/mcp/server.clj` — wraps apply, returns commit list JSON
- [ ] T038 Implement `unsquash-consolidate` MCP tool in `src/mcp/server.clj` — wraps consolidate pipeline

**Checkpoint**: Tool works as an MCP server callable from Claude Code.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T039 [P] Implement LLM intermediate state synthesis in `src/llm/synthesizer.clj` — for multi-edge hunks, ask LLM to rewrite as sequential changes per `contracts/llm-cli.md` synthesis request (FR-007)
- [ ] T040 [P] Add `--diff-algorithm` flag to CLI in `src/cli.clj` — support patience/histogram diff algorithms via `git diff --diff-algorithm=`
- [ ] T041 [P] Update `README.md` with installation, usage, and configuration instructions
- [ ] T042 Run `specs/001-commit-rewriter/quickstart.md` validation — verify all documented commands work
- [ ] T043 Measure preflight rule coverage against hunk-zoo fixtures — target ≥40% (SC-002)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Foundational phase completion
- **US2 (Phase 4)**: Depends on US1 completion (reuses the unfold pipeline)
- **US3 (Phase 5)**: Depends on US1 completion (extends the propose step)
- **MCP (Phase 6)**: Depends on US1 completion (wraps existing pipelines)
- **Polish (Phase 7)**: Depends on US1 completion at minimum

### Within Each Phase

- Tasks marked [P] can run in parallel
- Tasks without [P] must run sequentially within their phase
- Tests can run after the code they test is complete

### Parallel Opportunities

- T003, T004, T005, T006 can all run in parallel (Phase 1)
- T009, T010, T011 can all run in parallel (Phase 2, after T007/T008)
- T012, T013 can run in parallel (Phase 2 tests)
- T035/T036/T037 can run in parallel once T034 is done (Phase 6)
- T039, T040, T041 can all run in parallel (Phase 7)

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Test with a real squashed commit
5. Ship as CLI tool — already useful

### Incremental Delivery

1. Setup + Foundational → Core works
2. Add US1 → Single-commit unfolding (MVP!)
3. Add MCP → Claude Code integration
4. Add US2 → Full two-phase workflow
5. Add US3 → Interactive path selection
6. Polish → Synthesis, docs, coverage

---

## Notes

- 43 total tasks
- Phase 1 (Setup): 6 tasks
- Phase 2 (Foundational): 7 tasks
- Phase 3 (US1 — MVP): 10 tasks
- Phase 4 (US2): 6 tasks
- Phase 5 (US3): 4 tasks
- Phase 6 (MCP): 5 tasks
- Phase 7 (Polish): 5 tasks
- Suggested MVP: Phases 1–3 (23 tasks)
