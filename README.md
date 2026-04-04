# diff-peel

MCP tool for peeling squashed diffs into logical commit sequences.

Given a squashed diff, mechanically decomposes it into an ordered series of commits:

1. **Pure additions** — new types, functions, modules
2. **Internal rewrites** — same API, different body
3. **API changes** — signature change + all callsite updates
4. **Deletions** — dead code removal

Each peeled commit compiles independently. A compile oracle validates each step.

## Stack

- **Babashka** — orchestration, diff parsing, git operations
- **LLM CLI** — for semantic classification of hard hunks
- **Compile oracle** — pluggable per language (GHC, cargo, etc.)
