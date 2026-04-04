# Phase 1: Consolidation

**Module**: `consolidate.smart-squash`

Phase 1 groups related commits and squashes each group while preserving chronological
order. This reduces noise before Phase 2 (unfold) decomposes the remaining large
commits.

## Why not squash everything?

Original commit boundaries carry intent signal. A developer who made 5 commits had
reasons for each boundary --- even if the messages are "wip" and "fix typo". Squashing
everything into one blob destroys information that makes Phase 2 easier.

Smart-squash preserves signal by only grouping commits that genuinely belong together:

```mermaid
flowchart LR
    subgraph before ["Before (7 commits)"]
        C1["feat: add Foo"]
        C2["wip"]
        C3["fix typo in Foo"]
        C4["feat: add Bar"]
        C5["fix: Bar import"]
        C6["docs: update README"]
        C7["chore: bump deps"]
    end

    subgraph after ["After (4 commits)"]
        G1["feat: add Foo"]
        G2["feat: add Bar"]
        G3["docs: update README"]
        G4["chore: bump deps"]
    end

    C1 --> G1
    C2 --> G1
    C3 --> G1
    C4 --> G2
    C5 --> G2
    C6 --> G3
    C7 --> G4

    style G1 fill:#1565c0,color:#fff
    style G2 fill:#6a1b9a,color:#fff
    style G3 fill:#2e7d32,color:#fff
    style G4 fill:#e65100,color:#fff
```

## Pipeline

```mermaid
flowchart TD
    A["Commit range<br/>(e.g. main..feature)"] --> B["Parse commits:<br/>SHA, message, files, stat"]
    B --> C{"LLM<br/>configured?"}
    C -->|Yes| D["LLM groups<br/>related commits"]
    C -->|No| E["Each commit<br/>is its own group"]
    D --> F["For each group"]
    E --> F
    F --> G{"Single<br/>commit?"}
    G -->|Yes| H["Keep as-is"]
    G -->|No| I["git reset --soft<br/>to parent"]
    I --> J["git commit<br/>with proposed message"]
    J --> K["Oracle validates"]
```

## Commit parsing

**Function**: `parse-commit-range(dir, ref-range)`

Extracts metadata from each commit in the range:

```clojure
{:sha     "abc123"
 :message "wip: trying something"
 :files   ["src/Foo.hs" "src/Bar.hs"]
 :stat    "+20 -5"}
```

Uses three git commands per commit:

| Command | Extracts |
|---------|----------|
| `git log --format=%H\|%s --reverse` | SHA and message |
| `git diff-tree --no-commit-id -r --name-only` | Changed files |
| `git diff --stat sha~1..sha` | Insertions/deletions summary |

## LLM grouping

**Function**: `group-commits(llm-fn, commits)`

The LLM receives all commits with their metadata and returns groupings:

### Request

```json
{
  "system": "You are a commit history analyst...",
  "commits": [
    {"sha": "abc123", "message": "wip", "files": [...], "stat": "+20 -5"},
    {"sha": "def456", "message": "fix typo", "files": [...], "stat": "+1 -1"}
  ]
}
```

### Response

```json
{
  "groups": [
    {
      "shas": ["abc123", "def456"],
      "reason": "Both touch Foo.hs, second fixes typo in first",
      "proposed_message": "feat: add Foo module"
    }
  ]
}
```

### Grouping patterns the LLM identifies

| Pattern | Example | Action |
|---------|---------|--------|
| Commit + fixup | "add Foo" + "fix Foo import" | Squash |
| WIP sequence | "wip" + "wip2" + "still wip" | Squash, use final meaningful message |
| Same concern | Two commits both adding to Bar | Squash |
| Independent | Unrelated changes | Keep separate |

## Squashing

**Function**: `squash-group(dir, shas, message)`

For groups with multiple commits:

1. `git reset --soft <parent-of-first>` --- unstages all commits but keeps changes
2. `git commit -m "<proposed_message>"` --- creates single commit with group's changes

Order within the group is preserved by the chronological SHA ordering, and since all
changes are soft-reset and recommitted together, the result is equivalent to applying
them sequentially.

## Without LLM

When no LLM is configured, each commit becomes its own group (no squashing). The
tool degrades gracefully --- Phase 2 still works on individual commits.

## Output

Consolidation returns the grouping plan for review:

```json
{
  "groups": [...],
  "original_count": 7,
  "proposed_count": 4
}
```

The user reviews before applying. In the MCP workflow, `unsquash-consolidate`
returns this plan; a separate call applies it.
