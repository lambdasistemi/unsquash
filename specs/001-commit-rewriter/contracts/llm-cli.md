# Contract: LLM CLI Interface

The LLM is an external process invoked by babashka. It receives a prompt and returns structured JSON.

## Invocation

```
<llm-command> [args from config]
```

Stdin receives a JSON object. Stdout returns a JSON object. Exit code 0 = success.

## Edge Discovery Request

```json
{
  "system": "You are a code change classifier...",
  "prompt": "Given these hunks and the current graph state, discover edges...",
  "hunks": [
    {
      "id": "src/Foo.hs:42-50",
      "file": "src/Foo.hs",
      "diff": "- old line\n+ new line",
      "context": "surrounding code for understanding"
    }
  ],
  "existing_edges": [
    {"from": "h1", "to": "h2", "kind": "co-occurs", "reason": "..."}
  ],
  "existing_units": [
    {"id": "u1", "identity": "define:Foo", "hunk_ids": ["h1", "h3"]}
  ],
  "response_schema": {
    "type": "object",
    "properties": {
      "edges": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "from": {"type": "string"},
            "to": {"type": "string"},
            "kind": {"enum": ["depends", "co-occurs"]},
            "reason": {"type": "string"}
          }
        }
      },
      "reclassifications": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "hunk_id": {"type": "string"},
            "new_unit": {"type": "string"},
            "reason": {"type": "string"}
          }
        }
      }
    }
  }
}
```

## Edge Discovery Response

```json
{
  "edges": [
    {"from": "h5", "to": "h1", "kind": "depends", "reason": "h5 uses type Foo defined in h1"},
    {"from": "h6", "to": "h5", "kind": "co-occurs", "reason": "h6 is the import enabling h5's usage of Foo"}
  ],
  "reclassifications": []
}
```

## Intermediate State Synthesis Request

```json
{
  "system": "You are a code rewriter...",
  "prompt": "This hunk belongs to two atomic units. Rewrite it as two sequential changes...",
  "hunk": {
    "id": "src/Foo.hs:42-50",
    "diff": "...",
    "context": "..."
  },
  "unit_a": {"identity": "use:Bar:Foo", "reason": "adoption of Bar type"},
  "unit_b": {"identity": "api-change:process:new-arg", "reason": "new Logger argument"},
  "order": "unit_a first, then unit_b",
  "response_schema": {
    "type": "object",
    "properties": {
      "hunk_a": {"type": "string", "description": "diff for unit_a"},
      "hunk_b": {"type": "string", "description": "diff for unit_b"},
      "explanation": {"type": "string"}
    }
  }
}
```

## Consolidation Grouping Request (Phase 1)

```json
{
  "system": "You are a commit history analyst...",
  "prompt": "Group these commits by shared concern...",
  "commits": [
    {"sha": "abc123", "message": "wip", "files": ["src/Foo.hs"], "stat": "+20 -5"},
    {"sha": "def456", "message": "fix typo", "files": ["src/Foo.hs"], "stat": "+1 -1"}
  ],
  "response_schema": {
    "type": "object",
    "properties": {
      "groups": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "shas": {"type": "array", "items": {"type": "string"}},
            "reason": {"type": "string"},
            "proposed_message": {"type": "string"}
          }
        }
      }
    }
  }
}
```
