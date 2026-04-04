# Research: Two-Phase Commit Rewriter

## R1: Babashka for MCP servers

**Decision**: Use Babashka as the MCP server runtime.

**Rationale**: Babashka provides fast startup (<50ms), excellent shell interop via `babashka.process`, native JSON handling via `cheshire`, and the full power of Clojure's data manipulation. MCP servers are long-running processes that handle JSON-RPC — bb handles this well via stdin/stdout.

**Alternatives considered**:
- Node.js — heavier runtime, would work but adds npm dependency chain
- Python — viable, but Clojure's data-oriented approach fits graph manipulation better
- Haskell — too heavy for a scripting tool; compile times would slow development

## R2: Unified diff parsing strategy

**Decision**: Parse unified diffs into structured hunk data using regex-based parser in bb.

**Rationale**: Unified diff format is well-specified. A hunk is: file path, line ranges, and a sequence of context/add/remove lines. No external library needed — regex parsing of `@@` headers and `+`/`-`/` ` prefixes is straightforward in Clojure.

**Alternatives considered**:
- `git diff --json` — doesn't exist; git has no native JSON diff output
- External diff parser library — adds dependency for a simple parsing task
- `diff-so-fancy` or `delta` — rendering tools, not parsers

## R3: LLM CLI interface

**Decision**: The LLM is invoked as an external CLI process. Babashka shells out, passes the prompt on stdin (or as a file argument), receives structured JSON on stdout.

**Rationale**: Maximum pluggability. Any LLM CLI works: `llm` (Simon Willison), `claude` CLI, `curl` to an API, or a local model wrapper. The tool doesn't care which model or provider.

**Protocol**: stdin receives a JSON object with `system`, `prompt`, and `schema` fields. stdout returns a JSON object conforming to `schema`. Exit code 0 = success.

**Alternatives considered**:
- Direct API calls — would hardcode to one provider
- MCP sub-server — over-engineering for prompt→response
- Embedded LLM — impractical for the required reasoning quality

## R4: Compile oracle interface

**Decision**: The oracle is an external command specified in config. It receives no arguments — it compiles whatever is in the working tree. Exit code 0 = compiles, non-zero = fails. Stderr contains error messages for LLM feedback.

**Rationale**: Simplest possible interface. `cabal build all -O0`, `cargo check`, `go build ./...`, `npm run build` — all follow this pattern. The oracle doesn't need to know about the diff or the tool's state.

**Alternatives considered**:
- Passing specific files to compile — too language-specific
- Parsing compiler output — useful for LSP tier (future), not needed for basic oracle

## R5: Graph representation

**Decision**: Clojure maps and sets. Nodes are maps with `:id`, `:hunks`, `:identity` (semantic name). Edges are sets of `[from to :depends]` or `[a b :co-occurs]` tuples.

**Rationale**: Clojure's immutable data structures make graph transformations safe. Contraction (merging co-occurring nodes) is a reduce over the edge set. Topological sort is a standard algorithm on adjacency maps.

**Alternatives considered**:
- External graph library (loom) — overkill, toposort is ~20 lines
- Adjacency matrix — less readable for small graphs

## R6: Existing tools overlap

**Decision**: No existing tool to integrate. Build from scratch.

**Rationale**: Research (see MEMORY.md) confirmed that no existing tool combines: (1) periphery-to-center ordering, (2) compile validation at each step, (3) co-occurrence detection, (4) intermediate state synthesis. git-absorb works in the opposite direction. Academic tools (Atomizer, UTango) don't validate compilability. Commercial tools (GitKraken, GitButler) are manual/GUI.

## R7: MCP server protocol

**Decision**: Implement MCP server using stdio transport (stdin/stdout JSON-RPC).

**Rationale**: Stdio is the simplest transport and works with Claude Code natively. The server exposes tools that Claude Code can call. No HTTP server needed.

**Tools exposed**:
- `unsquash-analyze` — build dependency graph from a commit or range
- `unsquash-propose` — compute and present valid orderings
- `unsquash-apply` — apply chosen ordering, creating commits
- `unsquash-consolidate` — phase 1: smart squash of commit range
