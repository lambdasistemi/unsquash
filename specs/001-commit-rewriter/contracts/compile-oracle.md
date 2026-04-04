# Contract: Compile Oracle

The compile oracle is an external command that validates whether the current working tree compiles.

## Configuration

```json
{
  "oracle": {
    "command": "cabal build all -O0",
    "timeout_seconds": 120
  }
}
```

## Invocation

```bash
cd <working-tree> && <command>
```

No arguments. The oracle compiles whatever is in the working tree.

## Response

| Exit Code | Meaning |
|-----------|---------|
| 0 | Compilation succeeded |
| non-zero | Compilation failed |

Stderr contains compiler error messages. These are captured and may be passed to the LLM for retry logic (e.g. adjusting atomic unit boundaries).

## Examples

| Language | Command |
|----------|---------|
| Haskell | `cabal build all -O0` |
| Rust | `cargo check` |
| Go | `go build ./...` |
| TypeScript | `npx tsc --noEmit` |
| Python | `python -m py_compile <files>` (or mypy) |
| Nix | `nix build --quiet` |

## Future: LSP Tier

The LSP tier (not in v1) would use a running language server to get incremental diagnostics instead of a full build. Same exit-code semantics: zero diagnostics = pass.
