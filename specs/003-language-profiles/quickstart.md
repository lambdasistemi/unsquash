# Quickstart: Language Profiles

## Using a shipped profile

Set the language in `unsquash.edn`:

```edn
{:language "haskell"}
```

Or let auto-detection pick the profile from file extensions in the diff (default behavior when `:language` is omitted).

## Creating a custom profile

1. Create a JSON file (e.g. `my-lang.json`):

```json
{
  "name": "my-lang",
  "file_extensions": [".ml"],
  "import_pattern": "^open\\s+(\\w+)",
  "module_to_path": {
    "strip_prefixes": ["lib/"],
    "separator": "/",
    "path_separator": ".",
    "suffix": ".ml"
  }
}
```

2. Point to it in `unsquash.edn`:

```edn
{:language-profile "my-lang.json"}
```

3. Run unsquash as usual — only rules matching your defined fields will fire.

## Shipped profiles

| Profile | File | Extensions | Rules covered |
|---------|------|-----------|---------------|
| Haskell | `lang/haskell.json` | `.hs`, `.lhs` | R1, R3, R5, R13, R14 |
| Rust | `lang/rust.json` | `.rs` | R1, R3, R5, R13, R14 |
