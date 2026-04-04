# Configuration

unsquash is configured via `unsquash.edn` in the project root. Copy
`unsquash.example.edn` to get started:

```bash
cp unsquash.example.edn unsquash.edn
```

## Full configuration

```edn
{;; Compile oracle — validates the working tree compiles.
 ;; Exit code 0 = success. Stderr captured for retry logic.
 :oracle {:command "cabal build all -O0"
          :timeout 120}

 ;; LLM CLI — accepts JSON on stdin, returns JSON on stdout.
 ;; Any LLM CLI works: llm, claude, curl wrapper, local model.
 :llm {:command "llm chat -m claude-sonnet"}

 ;; Diff settings
 :diff {:algorithm "patience"        ;; patience | histogram | myers
        :split-at-blank-lines true}  ;; split hunks at blank lines

 ;; Maximum LLM refinement rounds before stopping
 :max-rounds 4

 ;; Maximum topological orderings to compute
 :max-orderings 3}
```

## Settings reference

### Oracle

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:oracle :command` | string | `"true"` | Shell command to validate compilation |
| `:oracle :timeout` | int | `120` | Timeout in seconds |

The oracle runs in the working directory after each commit. Exit code 0 means success.
Stderr is captured and may be passed to the LLM for retry decisions.

**Common oracle commands**:

| Language | Command |
|----------|---------|
| Haskell | `cabal build all -O0` |
| Rust | `cargo check` |
| Go | `go build ./...` |
| TypeScript | `npx tsc --noEmit` |
| Python | `python -m py_compile *.py` |
| Any | `true` (skip validation) |

### LLM

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:llm :command` | string | (none) | LLM CLI command |

When not set, unsquash operates in LLM-free mode:

- Edge discovery uses only preflight rules (R1--R14)
- Commit messages are generated mechanically
- Consolidation keeps each commit as its own group

The LLM CLI must accept JSON on stdin and return JSON on stdout. See
[Contracts](../architecture/contracts.md#llm-cli) for the request/response schema.

### Diff

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:diff :algorithm` | string | (git default) | Diff algorithm: `patience`, `histogram`, `myers` |
| `:diff :split-at-blank-lines` | boolean | `true` | Split hunks at blank-line boundaries |

!!! tip "Algorithm recommendation"
    Use `patience` or `histogram` for codebases where function signatures are unique
    anchors (Haskell, Rust, Go). These produce more semantically meaningful hunk
    boundaries than the default Myers algorithm.

### Iteration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `:max-rounds` | int | `4` | Maximum LLM refinement rounds |
| `:max-orderings` | int | `3` | Maximum topological sorts to compute |

More rounds improve graph quality but increase LLM cost. In practice, most diffs
converge within 2--3 rounds.

## CLI overrides

CLI arguments override config file values:

```bash
# Override oracle
bb analyze --ref HEAD --oracle "cargo check"

# Override diff algorithm
bb analyze --ref HEAD --diff-algorithm histogram

# Override LLM
bb analyze --ref HEAD --llm "llm chat -m gpt-4o"
```

## MCP integration

When using unsquash as an MCP server, the config file is the primary configuration
source. Tool parameters override config values where applicable.

```json
{
  "mcpServers": {
    "unsquash": {
      "command": "nix",
      "args": ["develop", "--quiet", "--command", "bb", "mcp"],
      "cwd": "/path/to/project-with-unsquash-edn"
    }
  }
}
```

The MCP server reads `unsquash.edn` from its working directory on startup.
