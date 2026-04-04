# Data Model: Two-Phase Commit Rewriter

## Core Entities

### Hunk

A contiguous block of changes from a unified diff.

| Field | Type | Description |
|-------|------|-------------|
| id | string | Unique identifier (e.g. `file:line-range`) |
| file | string | Path to the changed file |
| old-start | int | Starting line in the original file |
| old-count | int | Number of lines in original |
| new-start | int | Starting line in the new file |
| new-count | int | Number of lines in new file |
| lines | list of Line | The diff lines (context, add, remove) |
| sub-hunks | list of Hunk | Optional: result of splitting at natural boundaries |

### Line

| Field | Type | Description |
|-------|------|-------------|
| type | enum | `:context`, `:add`, `:remove` |
| content | string | The line content (without `+`/`-`/` ` prefix) |
| line-no | int | Line number in the relevant file |

### Edge

A relationship between two hunks or atomic units.

| Field | Type | Description |
|-------|------|-------------|
| from | string | Source node id |
| to | string | Target node id |
| kind | enum | `:depends` (directed) or `:co-occurs` (undirected) |
| reason | string | Why this edge exists (e.g. "import enables usage") |
| confidence | enum | `:preflight` (mechanical) or `:llm` (discovered) |
| rule | string | Preflight rule id if applicable (e.g. "R1", "R3") |

### AtomicUnit

A contracted group of co-occurring hunks.

| Field | Type | Description |
|-------|------|-------------|
| id | string | Unique identifier |
| identity | string | Semantic name (e.g. "define:Foo", "use:Foo:ModuleX") |
| hunks | set of Hunk | All hunks in this unit |
| original-edges | set of Edge | The co-occurrence edges that formed this unit |

### DependencyGraph

| Field | Type | Description |
|-------|------|-------------|
| nodes | map of id→AtomicUnit | All atomic units |
| edges | set of Edge | Directed dependency edges between atomic units |
| orderings | list of list | Valid topological sorts (computed lazily) |

### CommitPlan

| Field | Type | Description |
|-------|------|-------------|
| ordering | list of AtomicUnit | Chosen topological order |
| commits | list of Commit | The planned commit sequence |
| narrative | string | LLM-generated description of what story this ordering tells |

### Commit

| Field | Type | Description |
|-------|------|-------------|
| atomic-units | list of AtomicUnit | Units included in this commit |
| message | string | Proposed commit message |
| patch | string | The unified diff for this commit |
| compiles | boolean | Whether the oracle validated this commit |

### OracleResult

| Field | Type | Description |
|-------|------|-------------|
| success | boolean | Whether compilation succeeded |
| stderr | string | Error output if failed |
| duration-ms | int | How long the oracle took |

## State Transitions

```
Input (commit range or single commit)
  │
  ├─[Phase 1: if commit range]─→ ConsolidatedCommits
  │                                    │
  │                                    ▼
  └─[Phase 2: for each commit]──→ RawHunks
                                       │
                                       ▼ (split at boundaries)
                                  SplitHunks
                                       │
                                       ▼ (preflight rules)
                                  PreflightGraph (partial edges)
                                       │
                                       ▼ (LLM edge discovery)
                                  FullGraph (all edges)
                                       │
                                       ▼ (contract co-occurrences)
                                  ContractedGraph (atomic units)
                                       │
                                       ▼ (topological sort)
                                  ValidOrderings
                                       │
                                       ▼ (user selects)
                                  CommitPlan
                                       │
                                       ▼ (apply + validate)
                                  CommitSequence (done)
```

## Validation Rules

- Every AtomicUnit must have at least one hunk
- Every co-occurrence edge must be between hunks in the same AtomicUnit after contraction
- Every dependency edge must be between different AtomicUnits
- No cycles in the dependency graph (would make toposort impossible)
- The union of all hunks across all AtomicUnits must equal the original diff (FR-009)
- Each commit in the final sequence must pass the compile oracle (FR-006)
