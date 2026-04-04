# Sequencer

**Module**: `core.sequencer`

The sequencer takes a topological ordering and applies each atomic unit as a git
commit, validating with the compile oracle and retrying on failure.

## Apply ordering

```mermaid
flowchart TD
    A["Ordered unit IDs"] --> B["Take next unit"]
    B --> C["Apply hunks<br/>sequentially"]
    C --> D["git add -A<br/>git commit"]
    D --> E["Run oracle"]
    E --> F{"Compiles?"}
    F -->|Yes| G["Record commit"]
    G --> H{"More units?"}
    H -->|Yes| B
    H -->|No| I["Verify final<br/>tree SHA"]
    F -->|No| J["Rollback:<br/>reset HEAD~1<br/>clean -fd"]
    J --> K{"Retries<br/>left?"}
    K -->|Yes| L["Retry strategy:<br/>merge or skip"]
    L --> B
    K -->|No| M["Report failure"]

    style I fill:#2e7d32,color:#fff
    style M fill:#c62828,color:#fff
```

### Signature

```clojure
(apply-ordering ordering graph oracle-fn dir original-tree-sha
  & {:keys [max-retries retry-fn llm-fn] :or {max-retries 3}})
```

| Parameter | Description |
|-----------|-------------|
| `ordering` | Sequence of atomic unit IDs (topological order) |
| `graph` | The contracted dependency graph |
| `oracle-fn` | Compile validation function |
| `dir` | Working directory |
| `original-tree-sha` | Expected tree SHA after all commits |
| `max-retries` | Maximum retry attempts (default: 3) |
| `retry-fn` | Custom retry strategy (default: merge with neighbor) |
| `llm-fn` | Optional LLM for commit message generation |

### Return value

```clojure
{:commits [{:sha "abc123"
            :message "feat: add Foo.Bar"
            :compiles true}
           ...]
 :final-state-matches true
 :retries 1}
```

## Sequential hunk application

**Function**: `apply-hunks-sequentially`

Rather than generating a single patch for all hunks in a unit, the sequencer applies
each hunk individually via `git apply`. This handles merged units where hunks from
the same file may have overlapping line ranges.

```mermaid
flowchart LR
    A["Unit hunks"] --> B["Sort: new files first,<br/>then by file + line"]
    B --> C["For each hunk:<br/>generate patch"]
    C --> D["git apply<br/>--allow-empty"]
```

**Sort order**: New file hunks (detected by `--- /dev/null` header) are applied first
since they create the file. Remaining hunks are sorted by file path and old-start line
to maintain natural application order.

## Applying a single unit

**Function**: `apply-atomic-unit`

```mermaid
flowchart TD
    A["Atomic unit"] --> B["Generate commit<br/>message"]
    B --> C["Apply hunks<br/>sequentially"]
    C --> D["git add -A"]
    D --> E["git commit -m ..."]
    E --> F["Run oracle"]
    F --> G{"Success?"}
    G -->|Yes| H["Return {:sha :message<br/>:compiles true}"]
    G -->|No| I["Return {:sha :message<br/>:compiles false<br/>:error stderr}"]
```

**Message generation priority**:

1. LLM-generated message (if `llm-fn` provided)
2. Mechanical message from `core.message/generate-message`

If a hunk fails to apply (e.g., patch conflict), the function cleans up with
`git checkout -- .` and `git clean -fd`, then returns a failure result.

## Retry strategy

When a commit fails oracle validation, the sequencer rolls back and decides how to
recover.

### Default: merge strategy

```mermaid
flowchart TD
    A["Unit X fails<br/>oracle"] --> B["Find merge<br/>candidate"]
    B --> C{"Same-file<br/>neighbor?"}
    C -->|Yes| D["Merge X with<br/>neighbor"]
    C -->|No| E["Merge with<br/>next unit"]
    D --> F["Update graph:<br/>combine nodes,<br/>remap edges"]
    F --> G["Retry merged<br/>unit"]
    E --> F
```

**`find-merge-candidate`**: Prefers units sharing files with the failed unit (more
likely to resolve the conflict). Falls back to the next unit in the ordering.

**`handle-merge`**: Combines two atomic units:

1. Union their hunks
2. Create a new node with combined identity
3. Remap all edges to the new node ID
4. Remove self-loops
5. Update the remaining ordering

### Skip strategy

If merge doesn't help or the user provides a custom `retry-fn`, a unit can be
skipped entirely (marked `:skipped true`).

### Retry budget

The `max-retries` counter is global across the entire ordering, not per-unit. A
total retry atom tracks attempts. When exhausted, the sequencer reports partial
results.

## Final state verification

After all units are applied, the sequencer compares the working tree's SHA against
the expected `original-tree-sha`:

```clojure
(let [final-tree (git dir "rev-parse" "HEAD^{tree}")]
  {:final-state-matches (= final-tree original-tree-sha)})
```

A mismatch indicates lost or extra changes --- the unfold was not lossless. This is
the ultimate correctness check.

## Simple mode

`apply-ordering-simple` provides the original apply logic without retry:

- Apply each unit in order
- On failure, stop immediately
- No merge/skip strategies

Useful for testing or when the graph is known to be correct.
