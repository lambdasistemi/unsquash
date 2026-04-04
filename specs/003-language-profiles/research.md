# Research: Language Profile JSON

## R1: What patterns does each preflight rule need from a profile?

**Decision**: Each language-specific rule maps to specific profile fields.

| Rule | Required profile fields | Optional |
|------|------------------------|----------|
| R1 (export + definition) | `export_pattern`, `definition_pattern` | `export_name_pattern` |
| R3 (import superset) | `import_pattern` | — |
| R5 (wildcard count) | `wildcard` | — |
| R13 (cross-file import) | `import_pattern`, `module_to_path` | — |
| R14 (manifest registration) | `manifest_files`, `manifest_module_pattern` | — |

**Rationale**: Minimal set — each field serves exactly one rule (some shared). If a field is missing, that rule is skipped.

## R2: How should module-to-path mapping work?

**Decision**: A declarative transform object with `strip_prefixes`, `separator`, `replacement`, and `suffix`.

```json
{
  "strip_prefixes": ["src/", "lib/", "test/"],
  "separator": "/",
  "path_separator": ".",
  "suffix": ".hs"
}
```

The transform: strip matching prefix → replace `separator` with `path_separator` → strip `suffix` → result is module name.

Example: `src/Foo/Bar.hs` → strip `src/` → `Foo/Bar.hs` → replace `/` with `.` → `Foo.Bar.hs` → strip `.hs` → `Foo.Bar`.

**Alternatives considered**:
- Regex with capture groups: too complex for users to write correctly.
- Callback function: not expressible in JSON.
- Hardcoded per-language: defeats the purpose.

## R3: How should auto-detection work?

**Decision**: Count file extensions in the diff. The profile whose `file_extensions` covers the most files wins. Ties broken by profile load order (shipped profiles first).

**Rationale**: Simple, fast, correct for the common case (mono-language diffs). Multi-language diffs are rare and covered by explicit `:language` config.

**Alternatives considered**:
- First-file-wins: fragile (cabal file often comes first in Haskell diffs).
- Require explicit config: bad UX for the common case.

## R4: Rust profile patterns

**Decision**: Ship `lang/rust.json` with these patterns:

| Concept | Rust syntax | Regex |
|---------|------------|-------|
| Import | `use crate::foo::Bar;` | `^use\s+([\w:]+)` |
| Export/visibility | `pub fn`, `pub struct`, etc. | `^pub\s+(?:fn\|struct\|enum\|trait\|type\|mod)\s+(\w+)` |
| Definition | `fn name`, `struct Name` | `^(?:pub\s+)?(?:fn\|struct\|enum\|trait\|type)\s+(\w+)` |
| Module-to-path | `crate::foo::bar` → `src/foo/bar.rs` | `{"strip_prefixes": ["src/"], "separator": "/", "path_separator": "::", "suffix": ".rs"}` |
| Manifest | `Cargo.toml` | `Cargo.toml` |
| Manifest module | `path = "src/foo.rs"` | `path\s*=\s*"([^"]+)"` |
| Wildcard | `_` | `_` |

**Rationale**: Covers the same concepts as Haskell. R13 (cross-file import) is the most valuable rule for Rust since `mod foo;` declarations depend on the file existing.

## R5: Profile validation

**Decision**: Validate on load. Check:
1. `name` and `file_extensions` are required (minimum viable profile).
2. All `*_pattern` fields must be valid Java regexes (try `re-pattern`, catch on invalid).
3. `module_to_path` if present must have `separator`, `path_separator`, `suffix`.

Fail loudly with field name and error message.

**Rationale**: Fail-fast prevents mysterious "why didn't R13 fire?" debugging sessions.
