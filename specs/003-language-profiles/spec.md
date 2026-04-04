# Feature Specification: Language Profile JSON

**Feature Branch**: `003-language-profiles`
**Created**: 2026-04-04
**Status**: Draft
**Input**: GitHub issue #73 — Extract hardcoded Haskell patterns into language profile JSON

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Haskell user: zero-change experience (Priority: P1)

A Haskell developer using unsquash today continues to get the same preflight edge discovery results without any configuration change. The tool ships a Haskell profile that is loaded by default when it detects `.hs` files in the diff.

**Why this priority**: Must not break existing users. Backward compatibility is the gate for every other story.

**Independent Test**: Run the existing test suite with the Haskell profile loaded. All 36 tests pass with identical results.

**Acceptance Scenarios**:

1. **Given** a diff containing `.hs` files and no `:language` config, **When** preflight runs, **Then** the Haskell profile is auto-detected and all R1, R3, R5, R13, R14 rules fire with the same results as before.
2. **Given** `unsquash.edn` with `:language "haskell"`, **When** preflight runs, **Then** the Haskell profile is loaded explicitly and results are identical.

---

### User Story 2 - Rust user: provide custom profile (Priority: P1)

A Rust developer creates a language profile for their project. They configure `:language "rust"` or place a `rust.json` profile, and preflight rules R1, R3, R13, R14 detect Rust-specific patterns (use/mod declarations, Cargo.toml module paths).

**Why this priority**: Proves the system is actually language-agnostic, not just abstractly so.

**Independent Test**: Craft a synthetic Rust diff with `use`, `mod`, and `Cargo.toml` changes. Verify correct edges are discovered.

**Acceptance Scenarios**:

1. **Given** a Rust profile and a diff adding `mod foo;` in `lib.rs` and creating `src/foo.rs`, **When** preflight runs, **Then** R13 detects the cross-file dependency.
2. **Given** a Rust profile and a diff adding `pub fn bar` and `pub use crate::bar` in a re-export, **When** preflight runs, **Then** R1 detects the export/definition co-occurrence.
3. **Given** a diff with `.rs` files and no explicit `:language` config, **When** preflight runs, **Then** the Rust profile is auto-detected from file extensions.

---

### User Story 3 - Custom language: user-provided profile (Priority: P2)

A developer working in a language without a shipped profile writes their own JSON file and points to it via `:language-profile "path/to/my-lang.json"`. Only the patterns they define fire; missing concepts are silently skipped.

**Why this priority**: Extensibility for any language without waiting for upstream profiles.

**Independent Test**: Create a minimal profile with only `import_pattern` defined. Verify only import-related rules fire, others are skipped.

**Acceptance Scenarios**:

1. **Given** a profile with only `import_pattern` and `module_to_path`, **When** preflight runs on a diff matching those patterns, **Then** R13 fires but R1, R3, R5, R14 are skipped.
2. **Given** `:language-profile "custom.json"` in config, **When** preflight runs, **Then** the custom profile is loaded from that path.

---

### Edge Cases

- What happens when a diff contains files from multiple languages (e.g. `.hs` and `.cabal` and `.rs`)? Auto-detection picks the dominant language by file count; the user can override via config.
- What happens when a profile JSON is malformed? The tool fails loudly with a clear error message naming the invalid field.
- What happens when no profile matches and none is configured? Universal rules (R4, R6, R8) still fire; language-specific rules (R1, R3, R5, R13, R14) are skipped entirely.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Language profiles MUST be JSON files with a defined schema.
- **FR-002**: The tool MUST ship profiles for at least Haskell and one other language (Rust or TypeScript).
- **FR-003**: Preflight rules R1, R3, R5, R13, R14 MUST read patterns from the active profile instead of hardcoded regexes.
- **FR-004**: Rules MUST gracefully skip when their required profile field is absent (e.g. no `wildcard` key means R5 doesn't fire).
- **FR-005**: Users MUST be able to specify `:language "name"` in `unsquash.edn` to select a shipped profile.
- **FR-006**: Users MUST be able to specify `:language-profile "path/to/file.json"` for custom profiles.
- **FR-007**: The tool MUST auto-detect the language from file extensions in the diff when no explicit config is provided.
- **FR-008**: Universal rules (R4, R6, R8) MUST remain unaffected — they work on any diff without a profile.
- **FR-009**: Existing tests MUST pass unchanged with the Haskell profile loaded.
- **FR-010**: The profile schema MUST be documented in the MkDocs site.

### Key Entities

- **LanguageProfile**: A set of regex patterns and mappings for a specific language. Fields: name, file extensions, import/export/definition patterns, module-to-path mapping, manifest patterns, wildcard character.
- **ProfileRegistry**: The set of shipped profiles under `lang/`, indexed by name and file extension.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All 36 existing tests pass without modification when using the Haskell profile.
- **SC-002**: At least 4 new tests exercise a non-Haskell profile (Rust or TypeScript).
- **SC-003**: Preflight on a diff with no matching profile produces zero false-positive language-specific edges.
- **SC-004**: A user can add support for a new language by creating one JSON file and setting one config key — no code changes required.

## Assumptions

- Profile JSON files are small (under 1KB) and loaded once per analysis run.
- Regex patterns use Java/Clojure regex syntax since the tool runs on Babashka.
- The `module_to_path` mapping covers the common case (separator + suffix) but exotic layouts (e.g. Go's package-per-directory without dots) may need a more expressive mapping in future.
- Profile auto-detection is best-effort; explicit config always wins.
