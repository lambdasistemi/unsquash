# unsquash — build recipes

# Run all tests
test:
    bb test

# Full CI pipeline
ci: test

# Format check (placeholder — bb projects don't have a standard formatter yet)
format-check:
    @echo "No formatter configured yet"

# Format (placeholder)
format:
    @echo "No formatter configured yet"

# Lint (placeholder)
lint:
    @echo "No linter configured yet"

# Start MCP server
mcp:
    bb mcp

# Analyze a commit
analyze *ARGS:
    bb analyze {{ARGS}}

# Propose orderings
propose *ARGS:
    bb propose {{ARGS}}

# Apply ordering
apply *ARGS:
    bb apply {{ARGS}}

# Consolidate commit range
consolidate *ARGS:
    bb consolidate {{ARGS}}

# Build documentation site
build-docs:
    mkdocs build

# Serve documentation locally
serve-docs:
    mkdocs serve

# Deploy documentation to GitHub Pages
deploy-docs:
    mkdocs gh-deploy --force
