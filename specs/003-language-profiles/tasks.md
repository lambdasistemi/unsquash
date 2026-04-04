# Tasks: Language Profile JSON

**Input**: Design documents from `/specs/003-language-profiles/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/language-profile-schema.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

---

## Phase 1: Setup

**Purpose**: Create the profile loading infrastructure

- [ ] T001 Create `lang/` directory at repo root
- [ ] T002 Create `src/core/profile.clj` with namespace declaration and require for cheshire, clojure.string

---

## Phase 2: Foundational (Profile Loading + Validation)

**Purpose**: Core profile infrastructure that all stories depend on

- [ ] T003 Implement `load-profile` in `src/core/profile.clj` — read JSON file, parse with cheshire, return map
- [ ] T004 Implement `validate-profile` in `src/core/profile.clj` — check required fields (`name`, `file_extensions`), compile all `*_pattern` fields as regex, validate `module_to_path` sub-fields, throw on invalid
- [ ] T005 Implement `file-path-to-module` in `src/core/profile.clj` — apply `module_to_path` transform: strip prefix, strip suffix, replace separator with path_separator
- [ ] T006 Implement `detect-language` in `src/core/profile.clj` — count file extensions in a hunk list, match against shipped profiles, return best match or nil
- [ ] T007 Implement `resolve-profile` in `src/core/profile.clj` — check `:language-profile` (custom path) > `:language` (shipped name) > auto-detect; return loaded+validated profile or nil
- [ ] T008 [P] Create `test/core/profile_test.clj` — tests for load, validate (good + bad), file-path-to-module, detect-language, resolve-profile

**Checkpoint**: Profile infrastructure ready — language-specific rules can be parameterized

---

## Phase 3: User Story 1 — Haskell zero-change experience (Priority: P1)

**Goal**: Extract hardcoded Haskell patterns into `lang/haskell.json`. Parameterize preflight rules. Existing tests pass unchanged.

**Independent Test**: Run `bb test` — all 36 tests pass with identical results.

### Implementation for User Story 1

- [ ] T009 [US1] Create `lang/haskell.json` with all fields from contracts/language-profile-schema.md — import_pattern, export_pattern, export_name_pattern, definition_pattern, module_to_path, manifest_files, manifest_module_pattern, wildcard
- [ ] T010 [US1] Refactor `discover-edges` in `src/core/preflight.clj` to accept an optional `profile` parameter (default nil)
- [ ] T011 [US1] Parameterize R1 (export + definition) — use `export_pattern`, `export_name_pattern`, `definition_pattern` from profile; skip if fields absent
- [ ] T012 [US1] Parameterize R3 (import superset) — use `import_pattern` from profile for `is-import-hunk?`; skip if field absent
- [ ] T013 [US1] Parameterize R5 (wildcard count) — use `wildcard` from profile instead of hardcoded `_`; skip if field absent
- [ ] T014 [US1] Parameterize R13 (cross-file import) — use `import_pattern` and `file-path-to-module` with profile's `module_to_path`; skip if fields absent
- [ ] T015 [US1] Parameterize R14 (cabal/manifest registration) — use `manifest_files` and `manifest_module_pattern` from profile; skip if fields absent
- [ ] T016 [US1] Update `cli.clj` — call `resolve-profile` with config and hunks, pass profile to `discover-edges`
- [ ] T017 [US1] Update `mcp/server.clj` — ensure profile flows through analyze pipeline
- [ ] T018 [US1] Run existing tests — verify all 36 pass with Haskell profile auto-detected

**Checkpoint**: Haskell users see zero behavioral change. All existing tests green.

---

## Phase 4: User Story 2 — Rust profile (Priority: P1)

**Goal**: Ship a Rust profile proving multi-language portability.

**Independent Test**: Synthetic Rust diff triggers correct R1, R13, R14 edges.

### Implementation for User Story 2

- [ ] T019 [P] [US2] Create `lang/rust.json` with Rust patterns from research.md — import_pattern (`^use`), export/definition patterns (`^pub`), module_to_path (`::`), manifest (Cargo.toml), wildcard (`_`)
- [ ] T020 [P] [US2] Create `test/fixtures/lang/` directory with synthetic Rust diff fixtures — new module file, `mod` declaration, `use` import, `Cargo.toml` entry
- [ ] T021 [US2] Add preflight tests in `test/core/preflight_test.clj` — load Rust profile, verify R1 (pub fn + pub use), R13 (mod + new file), R14 (Cargo.toml + new file) fire correctly
- [ ] T022 [US2] Add auto-detection test — diff with `.rs` files selects Rust profile

**Checkpoint**: Rust profile works end-to-end. Two shipped profiles prove portability.

---

## Phase 5: User Story 3 — Custom profile (Priority: P2)

**Goal**: Users can provide their own profile JSON with only the fields they need.

**Independent Test**: Minimal profile with only `import_pattern` + `module_to_path` — only R13 fires, all other language-specific rules skip.

### Implementation for User Story 3

- [ ] T023 [US3] Add test for partial profile — profile with only `import_pattern` and `module_to_path`, verify R13 fires, R1/R3/R5/R14 skip
- [ ] T024 [US3] Add test for `:language-profile` config — custom path loads correctly
- [ ] T025 [US3] Add test for no-profile fallback — diff with unknown extensions, only universal rules (R4, R6, R8) fire

**Checkpoint**: Custom profiles work. Graceful degradation verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and cleanup

- [ ] T026 [P] Add `docs/reference/language-profiles.md` to MkDocs site — profile schema, shipped profiles, how to create custom profiles
- [ ] T027 [P] Update `docs/algorithm/preflight.md` — document which rules are profile-driven vs universal
- [ ] T028 Update `mkdocs.yml` nav to include new language-profiles page
- [ ] T029 Update `docs/reference/configuration.md` — add `:language` and `:language-profile` config keys
- [ ] T030 Run `just ci` and `just build-docs` to verify everything passes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies
- **Foundational (Phase 2)**: Depends on Phase 1
- **US1 Haskell (Phase 3)**: Depends on Phase 2 — MUST complete before US2/US3
- **US2 Rust (Phase 4)**: Depends on Phase 3 (parameterized preflight)
- **US3 Custom (Phase 5)**: Depends on Phase 3 (parameterized preflight)
- **Polish (Phase 6)**: Depends on Phase 3+4

### User Story Dependencies

- **US1 (P1)**: Blocks US2 and US3 — the parameterization must land first
- **US2 (P1)**: Can start after US1, independent of US3
- **US3 (P2)**: Can start after US1, independent of US2
- **US2 and US3 can run in parallel** after US1 completes

### Within Each User Story

- T009 (haskell.json) before T010-T015 (rule parameterization)
- T010 (discover-edges signature) before T011-T015 (individual rules)
- T011-T015 can run in parallel (different rules, different code sections)
- T016-T017 (cli/mcp wiring) after T010-T015

### Parallel Opportunities

```
Phase 2: T003-T007 sequential (each builds on prior), T008 parallel with any
Phase 3: T011, T012, T013, T014, T015 in parallel after T010
Phase 4: T019, T020 in parallel; T021-T022 after both
Phase 6: T026, T027 in parallel
```

---

## Implementation Strategy

### MVP First (US1 Only)

1. Phase 1 + 2: Profile infrastructure
2. Phase 3: Haskell profile + parameterized preflight
3. **STOP and VALIDATE**: `bb test` — all 36 tests pass
4. This alone resolves the "false advertising" problem — the tool no longer hardcodes Haskell

### Incremental Delivery

1. US1 → Haskell works via profile (backward compat proven)
2. US2 → Rust profile (multi-language proven)
3. US3 → Custom profiles tested (extensibility proven)
4. Polish → Documentation updated

---

## Notes

- R4 (trailing comma), R6 (systematic removal), R8 (whitespace) are universal — never touch them
- Profile is passed as an optional parameter to `discover-edges` — nil means "no language-specific rules"
- All regex patterns must compile as Java regex (Babashka runs on JVM)
- Commit after each task or logical group
