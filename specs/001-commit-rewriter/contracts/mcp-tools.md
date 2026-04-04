# Contract: MCP Tools

The unsquash MCP server exposes tools via stdio JSON-RPC transport.

## Tool: unsquash-analyze

Build a dependency graph from a commit or commit range.

**Input**:
```json
{
  "ref": "HEAD~1..HEAD",
  "oracle_command": "cabal build all -O0",
  "llm_command": "llm chat -m claude-sonnet"
}
```

- `ref`: A git ref, commit SHA, or range (e.g. `abc123`, `HEAD~5..HEAD`, `main..feature`)
- `oracle_command`: The compile oracle command
- `llm_command`: The LLM CLI command

**Output**:
```json
{
  "graph": {
    "atomic_units": [
      {"id": "u1", "identity": "define:ConwayUtxosEnv", "hunk_count": 4},
      {"id": "u2", "identity": "use:ConwayUtxosEnv:Utxo", "hunk_count": 3},
      {"id": "u3", "identity": "removal:UTxOState-from-UTXOS-state", "hunk_count": 6}
    ],
    "edges": [
      {"from": "u2", "to": "u1", "kind": "depends", "reason": "u2 imports type defined in u1"}
    ],
    "stats": {
      "total_hunks": 35,
      "preflight_classified": 14,
      "llm_classified": 18,
      "unclassified": 3,
      "llm_rounds": 2
    }
  }
}
```

## Tool: unsquash-propose

Compute valid orderings for a previously analyzed graph.

**Input**:
```json
{
  "max_orderings": 3
}
```

**Output**:
```json
{
  "orderings": [
    {
      "sequence": ["u1", "u2", "u3"],
      "narrative": "Types first: define the new environment type, then adopt it in callers, then remove the old state dependency"
    },
    {
      "sequence": ["u1", "u3", "u2"],
      "narrative": "Cleanup first: define new type, remove old dependency, then wire in the new type"
    }
  ]
}
```

## Tool: unsquash-apply

Apply a chosen ordering, creating commits on the current branch.

**Input**:
```json
{
  "ordering_index": 0
}
```

**Output**:
```json
{
  "commits": [
    {"sha": "abc123", "message": "feat: add ConwayUtxosEnv type", "compiles": true},
    {"sha": "def456", "message": "refactor: adopt ConwayUtxosEnv in Utxo module", "compiles": true},
    {"sha": "ghi789", "message": "refactor: remove UTxOState from UTXOS state", "compiles": true}
  ],
  "final_state_matches": true
}
```

## Tool: unsquash-consolidate

Phase 1: smart squash of a commit range.

**Input**:
```json
{
  "ref": "main..feature",
  "llm_command": "llm chat -m claude-sonnet"
}
```

**Output**:
```json
{
  "groups": [
    {"shas": ["abc", "def"], "reason": "both modify the same function", "proposed_message": "refactor: update treasury donation logic"},
    {"shas": ["ghi"], "reason": "standalone change", "proposed_message": "feat: add ConwayUtxosEnv"}
  ],
  "original_count": 10,
  "proposed_count": 5
}
```

After user approval, the tool squashes each group.
