# Data Model: Language Profile JSON

## Entities

### LanguageProfile

A set of regex patterns and mappings for language-specific preflight rules.

| Field | Type | Required | Used by |
|-------|------|----------|---------|
| `name` | string | yes | Display, logging |
| `file_extensions` | `[string]` | yes | Auto-detection |
| `import_pattern` | string (regex) | no | R3, R13 |
| `export_pattern` | string (regex) | no | R1 |
| `export_name_pattern` | string (regex) | no | R1 |
| `definition_pattern` | string (regex) | no | R1 |
| `module_to_path` | ModuleToPath | no | R13 |
| `manifest_files` | `[string]` (globs) | no | R14 |
| `manifest_module_pattern` | string (regex) | no | R14 |
| `wildcard` | string | no | R5 |

### ModuleToPath

Declarative transform from file path to module name.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `strip_prefixes` | `[string]` | yes | Prefixes to strip from file path (e.g. `["src/", "lib/"]`) |
| `separator` | string | yes | Path separator in file system (e.g. `"/"`) |
| `path_separator` | string | yes | Module separator in language (e.g. `"."` for Haskell, `"::"` for Rust) |
| `suffix` | string | yes | File extension to strip (e.g. `".hs"`, `".rs"`) |

### ProfileRegistry

Runtime structure holding loaded profiles.

| Field | Type | Description |
|-------|------|-------------|
| `profiles` | `{name -> LanguageProfile}` | Profiles indexed by name |
| `ext_index` | `{ext -> name}` | File extension to profile name mapping |

## Relationships

```
unsquash.edn --(selects)--> LanguageProfile
                                  |
                          (parameterizes)
                                  |
                           core.preflight
                          R1, R3, R5, R13, R14
```

## State Transitions

```
1. Load config (:language or :language-profile)
2. If explicit: load named profile or custom path
3. If absent: collect file extensions from diff → auto-detect
4. Validate profile (regex compilation, required fields)
5. Pass profile to discover-edges
6. Each rule checks: profile field present? → fire : skip
```

## Validation Rules

- `name` and `file_extensions` are always required
- All `*_pattern` fields must compile as Java regexes
- `module_to_path` if present must have all 4 sub-fields
- Missing optional fields mean the corresponding rule is silently skipped
- Invalid regex → loud error with field name
