# Language Profiles

Language profiles provide regex patterns for language-specific preflight rules.
They make unsquash work across any programming language without code changes.

## How it works

Preflight rules fall into two categories:

| Type | Rules | Profile needed? |
|------|-------|-----------------|
| Universal | R4 (trailing comma), R6 (systematic removal), R8 (whitespace) | No |
| Language-specific | R1 (export+definition), R3 (import superset), R5 (wildcard), R13 (cross-file import), R14 (manifest registration) | Yes |

When a profile is active, language-specific rules use its regex patterns.
When no profile is active, only universal rules fire.

## Shipped profiles

| Profile | File | Extensions | Rules | Manifest first |
|---------|------|-----------|-------|:--------------:|
| Haskell | `lang/haskell.json` | `.hs`, `.lhs` | R1, R3, R5, R13, R14, R15 | yes |
| Rust | `lang/rust.json` | `.rs` | R1, R3, R5, R13, R14, R15 | yes |
| Go | `lang/go.json` | `.go` | R1, R3, R13, R14, R15 | yes |
| Java | `lang/java.json` | `.java` | R1, R3, R13, R14, R15 | yes |
| C# | `lang/csharp.json` | `.cs` | R1, R3, R13, R14, R15 | yes |
| Python | `lang/python.json` | `.py` | R1, R3, R13, R14 | no |
| TypeScript | `lang/typescript.json` | `.ts`, `.tsx` | R1, R3, R13, R14 | no |
| Ruby | `lang/ruby.json` | `.rb` | R1, R3, R13, R14 | no |

## Configuration

### Auto-detection (default)

When no language is configured, unsquash counts file extensions in the diff
and selects the shipped profile with the highest match. This works for
mono-language diffs.

### Explicit selection

```edn
{:language "haskell"}
```

### Custom profile

```edn
{:language-profile "path/to/my-lang.json"}
```

Priority: `:language-profile` > `:language` > auto-detect.

## Profile schema

```json
{
  "name": "my-language",
  "file_extensions": [".ml"],

  "import_pattern": "^open\\s+(\\w+)",
  "export_pattern": "^val\\s+\\w+\\s*:",
  "export_name_pattern": "[a-z_][a-zA-Z0-9_]*",
  "definition_pattern": "^let\\s+(\\w+)",

  "module_to_path": {
    "strip_prefixes": ["lib/", "src/"],
    "separator": "/",
    "path_separator": ".",
    "suffix": ".ml"
  },

  "manifest_files": ["dune-project"],
  "manifest_module_pattern": "\\(name\\s+(\\w+)\\)",

  "wildcard": "_",
  "manifest_first": true
}
```

### Required fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Profile name (for logging) |
| `file_extensions` | `[string]` | Extensions for auto-detection (e.g. `[".hs"]`) |

### Optional fields

Each optional field enables specific preflight rules. Omitting a field
means that rule is silently skipped.

| Field | Type | Enables | Description |
|-------|------|---------|-------------|
| `import_pattern` | regex | R3, R13 | Matches import statements; capture group 1 = module name |
| `export_pattern` | regex | R1 | Matches export/visibility declarations |
| `export_name_pattern` | regex | R1 | Extracts individual names from export lists |
| `definition_pattern` | regex | R1 | Matches top-level definitions; capture group 1 = name |
| `module_to_path` | object | R13, R14 | Maps file paths to module names (see below) |
| `manifest_files` | `[string]` | R14 | Glob patterns for package manifests |
| `manifest_module_pattern` | regex | R14 | Extracts module names from manifest files |
| `wildcard` | string | R5 | Wildcard character in pattern matches |
| `manifest_first` | boolean | R15 | Force manifest changes before source in ordering |

### Module-to-path transform

The `module_to_path` object converts file paths to module names:

| Sub-field | Type | Description |
|-----------|------|-------------|
| `strip_prefixes` | `[string]` | Prefixes to strip (first match wins) |
| `separator` | string | File system path separator |
| `path_separator` | string | Language module separator |
| `suffix` | string | File extension to strip |

**Example** (Haskell): `src/Foo/Bar.hs` → strip `src/` → strip `.hs` → replace `/` with `.` → `Foo.Bar`

**Example** (Rust): `src/foo/bar.rs` → strip `src/` → strip `.rs` → replace `/` with `::` → `foo::bar`

## Creating a new profile

1. Create a JSON file following the schema above
2. Include only the fields relevant to your language
3. Test regex patterns compile correctly (Java regex syntax)
4. Place in `lang/` for shipping, or anywhere for custom use
5. Set `:language-profile` in `unsquash.edn` to point to it

!!! tip
    Start with just `import_pattern` and `module_to_path` — this enables R13
    (cross-file import dependency), which is the highest-value rule for most
    languages.
