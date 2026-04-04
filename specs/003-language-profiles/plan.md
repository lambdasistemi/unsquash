# Implementation Plan: Language Profile JSON

**Branch**: `003-language-profiles` | **Date**: 2026-04-04 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-language-profiles/spec.md`

## Summary

Extract hardcoded Haskell regex patterns from `core.preflight` into JSON language profile files. Ship Haskell and Rust profiles. Parameterize rules R1, R3, R5, R13, R14 to read patterns from the active profile. Auto-detect language from file extensions in the diff. Universal rules (R4, R6, R8) remain unchanged.

## Technical Context

**Language/Version**: Clojure (Babashka)
**Primary Dependencies**: babashka, cheshire (JSON), clojure.string
**Storage**: JSON files in `lang/` directory
**Testing**: `bb test` (36 existing tests + new profile tests)
**Target Platform**: Any (Babashka runs on JVM/GraalVM)
**Project Type**: CLI tool / MCP server
**Constraints**: Profiles must be loadable by Babashka (`cheshire/parse-string` for JSON, `java.util.regex.Pattern` for regexes)
**Scale/Scope**: 5 rules to parameterize, 2 profiles to ship, ~150 lines of preflight.clj to refactor

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| III. Language-agnostic by default | **FIXES VIOLATION** | Current code hardcodes Haskell. This feature resolves the contradiction. |
| VI. Universal base + language plugins | **ALIGNS** | Profiles are the "language plugin" mechanism the constitution describes. |
| VII. Pluggable components | **ALIGNS** | JSON profiles add a fourth pluggable axis (language patterns). |
| VIII. Babashka orchestration | **PASS** | Babashka loads and applies profiles — no external process. |
| IX. Compile-validated commits | **N/A** | Profiles affect edge discovery, not commit validation. |

No violations. Gate passes.

## Project Structure

### Documentation (this feature)

```text
specs/003-language-profiles/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── language-profile-schema.md
└── tasks.md             # Phase 2 output
```

### Source Code (repository root)

```text
lang/                        # NEW: shipped language profiles
├── haskell.json
└── rust.json

src/
├── core/
│   ├── preflight.clj        # MODIFY: parameterize with profile
│   └── profile.clj          # NEW: profile loading, validation, auto-detection
├── cli.clj                  # MODIFY: pass profile to preflight
└── mcp/
    └── server.clj           # MODIFY: pass profile to analysis

test/
├── core/
│   ├── preflight_test.clj   # MODIFY: test with haskell profile explicitly
│   └── profile_test.clj     # NEW: profile loading, validation, auto-detection tests
└── fixtures/
    └── lang/                # NEW: test fixtures for Rust profile
```

**Structure Decision**: Profiles live in `lang/` at repo root (not `src/` — they're data, not code). A new `core.profile` module handles loading, validation, and auto-detection, keeping `core.preflight` focused on rule logic.

## Complexity Tracking

No constitution violations to justify.
