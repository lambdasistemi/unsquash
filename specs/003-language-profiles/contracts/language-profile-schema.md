# Contract: Language Profile JSON Schema

## File location

Shipped profiles: `lang/<name>.json`
Custom profiles: any path referenced by `:language-profile` in `unsquash.edn`

## Schema

```json
{
  "name": "haskell",
  "file_extensions": [".hs", ".lhs"],

  "import_pattern": "^import\\s+(?:qualified\\s+)?([A-Z][\\w.]*)",
  "export_pattern": "^module\\s+\\S+\\s*\\(",
  "export_name_pattern": "^\\s+([a-zA-Z][\\w']*)",
  "definition_pattern": "^([a-z][\\w']*)\\s+::",

  "module_to_path": {
    "strip_prefixes": ["src/", "lib/", "test/", "test/spec/"],
    "separator": "/",
    "path_separator": ".",
    "suffix": ".hs"
  },

  "manifest_files": ["*.cabal"],
  "manifest_module_pattern": "^\\s+([A-Z][\\w.]*)",

  "wildcard": "_"
}
```

## Field semantics

### `import_pattern`

Regex with one capture group. Applied to each add-line to detect import statements.
Capture group 1 = the imported module/symbol name.

Used by: R3 (import superset detection), R13 (cross-file import dependency).

### `export_pattern`

Regex with zero capture groups. Applied to hunk lines to detect whether a hunk modifies an export/visibility list.

Used by: R1 (export co-occurs with definition).

### `export_name_pattern`

Regex with one capture group. Applied to add-lines within export hunks to extract individual exported names.

Used by: R1 (matching exported names to definitions).

### `definition_pattern`

Regex with one capture group. Applied to add-lines to detect top-level definitions.
Capture group 1 = the defined name.

Used by: R1 (matching definitions to exports).

### `module_to_path`

Declarative transform converting a file path to a module name.

**Algorithm**:
1. Try each `strip_prefixes` in order; use first match
2. Replace `suffix` with empty string
3. Replace `separator` with `path_separator`
4. Result = module name

**Example** (Haskell): `src/Foo/Bar.hs` → strip `src/` → `Foo/Bar.hs` → strip `.hs` → `Foo/Bar` → replace `/` with `.` → `Foo.Bar`

**Example** (Rust): `src/foo/bar.rs` → strip `src/` → `foo/bar.rs` → strip `.rs` → `foo/bar` → replace `/` with `::` → `foo::bar`

Used by: R13 (map file path to module name for import matching), R14 (map file path to module name for manifest matching).

### `manifest_files`

List of glob patterns matching package manifest files.

Used by: R14 (identify which hunks are in manifest files).

### `manifest_module_pattern`

Regex with one capture group. Applied to add-lines in manifest hunks to extract registered module names.

Used by: R14 (match manifest module registration to new module files).

### `wildcard`

Single character used as wildcard/placeholder in pattern matches.

Used by: R5 (detect wildcard count changes).

## Configuration

In `unsquash.edn`:

```edn
{;; Option 1: Select a shipped profile by name
 :language "haskell"

 ;; Option 2: Point to a custom profile file
 :language-profile "path/to/my-lang.json"

 ;; Option 3: Omit both — auto-detect from file extensions in diff
 }
```

Priority: `:language-profile` > `:language` > auto-detect.

## Validation

On load, the profile is validated:

1. `name` must be a non-empty string
2. `file_extensions` must be a non-empty array of strings starting with `.`
3. All `*_pattern` fields must compile as Java regex (`re-pattern`)
4. `module_to_path` if present must contain all four keys
5. `manifest_files` if present must be a non-empty array of strings

Invalid profiles produce a loud error naming the invalid field.
