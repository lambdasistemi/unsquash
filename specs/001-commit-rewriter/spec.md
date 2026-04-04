# Feature Specification: Two-Phase Commit Rewriter

**Feature Branch**: `001-commit-rewriter`
**Created**: 2026-04-04
**Status**: Draft
**Input**: User description: "Two-phase commit rewriting: consolidate messy commits then unfold into clean logical sequence via dependency graph"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Unfold a single squashed commit (Priority: P1)

A developer has a single large commit (e.g. from squashing a messy PR) and wants to decompose it into a clean sequence of smaller commits, each addressing one concern, each compiling independently. The tool analyzes the diff, builds a dependency graph of hunks, discovers which hunks must co-occur (e.g. an import and its usage), identifies valid orderings, and produces a proposed commit sequence.

**Why this priority**: This is the core value proposition. Even without phase 1, unfolding a single commit is immediately useful.

**Independent Test**: Given a squashed commit containing a new type definition, a new function using that type, import additions, and dead code removal, the tool produces a commit sequence where types come before functions, imports travel with their usages, and deletions are last. Each commit compiles.

**Acceptance Scenarios**:

1. **Given** a single commit with mixed additions, modifications, and deletions, **When** the tool analyzes it, **Then** it produces a dependency graph where nodes are atomic units (co-occurring hunks) and edges are dependency relationships.
2. **Given** a dependency graph, **When** the tool computes valid orderings, **Then** every proposed ordering produces a sequence where each commit compiles independently (validated by the compile oracle).
3. **Given** a proposed ordering, **When** the user approves it, **Then** the tool produces the commit sequence on the current branch.
4. **Given** a hunk that belongs to two different atomic units, **When** the tool processes it, **Then** it synthesizes an intermediate code state (rewriting the hunk into two sequential changes) such that each change satisfies one concern and the final state matches the original.

---

### User Story 2 - Consolidate messy PR commits then unfold (Priority: P2)

A developer has a PR with N messy commits (WIP, fixups, back-and-forth) that all compile. Rather than squashing everything into one blob (losing information), the tool first groups related commits and squashes them selectively (preserving order), then unfolds any remaining large commits into clean sub-commits.

**Why this priority**: Phase 1 (consolidation) makes phase 2 (unfolding) much easier because the blobs are already thematically coherent. But it requires the full P1 capability plus grouping logic.

**Independent Test**: Given 10 messy commits where commits 3 and 7 fix the same thing, and commits 2, 5, 8 are WIP noise for the same feature, the tool groups them correctly, squashes each group (preserving order), then unfolds the resulting commits into a clean narrative.

**Acceptance Scenarios**:

1. **Given** a commit range from a PR, **When** the tool analyzes it, **Then** it proposes groups of commits that belong together (same concern, fixup pairs, WIP noise).
2. **Given** proposed groups, **When** the user approves, **Then** the tool squashes each group preserving original order, and the resulting sequence still compiles at each step.
3. **Given** consolidated commits, **When** any commit is still too large, **Then** the tool applies the unfolding algorithm (P1) to decompose it.

---

### User Story 3 - Interactive path selection (Priority: P3)

After the dependency graph is built, multiple valid topological orderings exist. The tool presents the options (or a recommended ordering with narrative reasoning) and the user chooses which path tells the best story for reviewers.

**Why this priority**: Different orderings serve different audiences. A type-first ordering helps reviewers understand the data model; a feature-first ordering shows end-to-end slices. The tool should support this choice.

**Independent Test**: Given a graph with two independent features A and B, the tool offers at least "A then B" and "B then A" as valid orderings, with narrative reasoning for each.

**Acceptance Scenarios**:

1. **Given** a dependency graph with multiple valid orderings, **When** the tool presents options, **Then** each option includes a brief narrative description of what story it tells.
2. **Given** user selection of an ordering, **When** applied, **Then** every commit in the sequence compiles and the final state matches the original.

---

### Edge Cases

- What happens when a commit has no decomposition (single atomic change)? The tool recognizes this and leaves it as-is.
- What happens when the compile oracle fails on a proposed intermediate state? The tool adjusts the atomic unit boundaries and retries, or reports the failure with context.
- What happens when two hunks are mutually dependent but the tool didn't detect the co-occurrence? The compile oracle catches this — the commit won't compile — triggering a retry with merged atomic units.
- What happens when the diff contains only deletions? All deletions go in one commit, no ordering needed.
- What happens when git's hunk boundaries merge unrelated changes? The tool splits hunks at natural boundaries and detects multi-reason hunks.
- What happens when the LLM classifies a hunk inconsistently across rounds? Convergence detection stops iteration; remaining inconsistencies are flagged for user review.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST parse unified diffs and decompose them into individual hunks.
- **FR-002**: System MUST apply language-agnostic structural preflight (whitespace stripping, blank-line splitting, systematic patterns, proximity, new/deleted file detection, content moves) before any LLM call. System MAY load optional language plugins for additional deterministic edge discovery. The LLM semantic preflight (Layer 2) handles all language-specific pattern recognition by default.
- **FR-003**: System MUST discover two kinds of edges: directed dependency edges ("A must come before B") and undirected co-occurrence edges ("A and B must be in the same commit").
- **FR-004**: System MUST contract co-occurring hunks into atomic units, each with a semantic identity (e.g. "define:Foo", "use:Foo:ModuleX").
- **FR-005**: System MUST compute valid topological orderings of atomic units and present them to the user.
- **FR-006**: System MUST validate each commit in the proposed sequence using a compile oracle, confirming it compiles independently.
- **FR-007**: System MUST support synthesizing intermediate code states when a hunk belongs to multiple atomic units, producing sequential changes that each satisfy one concern.
- **FR-008**: System MUST support working on a single commit (skip consolidation) or a commit range (full two-phase workflow).
- **FR-009**: System MUST preserve the invariant that the final state after all commits equals the original diff — no code is gained or lost.
- **FR-010**: System MUST support pluggable compile oracles (different per language/project).
- **FR-011**: System MUST support pluggable LLM backends via a CLI interface (prompt in, structured response out).
- **FR-012**: System MUST support growing the preflight rule codebook over time — recurring patterns discovered by the LLM may be promoted to language plugin rules, expanding the deterministic layer for that language.

### Key Entities

- **Hunk**: A contiguous block of changes from a unified diff. May be split into sub-hunks if it contains multiple concerns.
- **Edge**: A relationship between hunks. Either a directed dependency ("must come before") or an undirected co-occurrence ("must be in same commit").
- **Atomic Unit**: A contracted group of co-occurring hunks with a semantic identity. The smallest unit that can form a commit.
- **Dependency Graph**: Nodes are atomic units, directed edges are dependency relationships. Valid commit sequences are topological sorts of this graph.
- **Compile Oracle**: An external tool that validates whether a code state compiles. Pluggable per language.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Given a squashed commit of up to 500 changed lines, the tool produces a valid commit sequence where each commit compiles, within 10 minutes (including oracle calls).
- **SC-002**: The structural preflight + LLM semantic preflight (Layers 1+2) correctly classify at least 80% of hunks before iterative refinement (Layer 3). With a language plugin active, the structural preflight alone (Layer 1) classifies at least 40%.
- **SC-003**: The final state after applying all produced commits is byte-identical to the original diff application.
- **SC-004**: For commit sequences of 5+ commits, the tool converges (zero reclassifications) within 4 LLM refinement rounds.
- **SC-005**: A developer unfamiliar with the original code can follow the produced commit sequence and understand the change rationale from commit messages alone.

## Assumptions

- The input always starts from a compiling state — all commits in a PR compile, or the squashed commit was applied to a compiling base.
- An LLM CLI tool is available that accepts prompts on stdin and returns structured JSON on stdout.
- A compile oracle is available for the target language and can be invoked as a shell command.
- Git is the version control system. The tool operates on unified diff format.
- The user has final approval over commit sequence ordering — the tool proposes, the user disposes.
- Internet connectivity is available for LLM API calls (unless using a local model).
