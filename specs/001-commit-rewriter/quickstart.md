# Quickstart: unsquash

## Prerequisites

- Git
- Babashka (`bb`)
- An LLM CLI tool (e.g. `llm`, `claude`)
- A compile oracle for your language (e.g. `cabal build all -O0`)

## Usage via MCP (Claude Code)

Add to your MCP config:

```json
{
  "mcpServers": {
    "unsquash": {
      "command": "bb",
      "args": ["-m", "unsquash.mcp.server"],
      "cwd": "/path/to/unsquash"
    }
  }
}
```

Then in Claude Code:

1. **Analyze** a commit: "unsquash-analyze HEAD~1..HEAD with oracle 'cabal build all -O0'"
2. **Review** the dependency graph and atomic units
3. **Propose** orderings: "unsquash-propose with max 3 options"
4. **Choose** an ordering
5. **Apply**: "unsquash-apply ordering 0"

## Usage via CLI

```bash
# Single commit
bb -m unsquash.cli analyze --ref HEAD --oracle "cabal build all -O0" --llm "llm chat -m claude-sonnet"

# Commit range (full two-phase)
bb -m unsquash.cli consolidate --ref main..feature --llm "llm chat -m claude-sonnet"
bb -m unsquash.cli analyze --ref HEAD --oracle "cabal build all -O0" --llm "llm chat -m claude-sonnet"
bb -m unsquash.cli propose --max 3
bb -m unsquash.cli apply --ordering 0
```

## Configuration

Create `unsquash.edn` in the project root:

```edn
{:oracle {:command "cabal build all -O0"
          :timeout 120}
 :llm {:command "llm chat -m claude-sonnet"}
 :diff {:algorithm "patience"
        :split-at-blank-lines true}}
```
