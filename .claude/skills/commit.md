---
name: commit
description: >
  Create git commits following Conventional Commit style for this project.
  Use when the user asks to commit, save changes, or says "커밋", "커밋해줘", "/commit".
trigger: always
---

# Commit Skill — Conventional Commits (Lightweight)

## Commit Message Format

```
<type>: <short summary>
```

- **Subject line only** — add a body only when genuinely necessary
- Subject must be in **English**, body may be in Korean if needed
- Subject starts with **lowercase**, no trailing period
- Maximum **50 characters** for the subject line

## Types

| Type | When to use |
|---|---|
| `feat` | New feature, new module, new class |
| `fix` | Bug fix |
| `refactor` | Code restructuring without behavior change |
| `docs` | Documentation, comments, README, AGENT.md |
| `test` | Adding or updating tests |
| `chore` | Build config, dependencies, CI setup |
| `style` | Formatting, whitespace — no logic change |

## Scope (Optional)

Use scope only when the change is complex or targets a specific module:

```
feat(resolver): add inheritance chain resolution
fix(parser): handle generic type parameters
```

Primary scopes: `spec`, `parser`, `resolver`, `detector`, `report`, `cli`

## Rules

1. **One commit = one logical change** — do not bundle multiple features in one commit
2. **Commit only when the build passes** — never commit broken code
3. **Explain "why", not "what"** — the code shows what changed; the message explains why
4. Add a body only when:
   - There is a breaking change
   - The reason for the change cannot be inferred from the code alone

## Examples

```
feat: add jdbc spec extractor from openjdk source
feat(parser): add source file parsing with javaparser
fix(resolver): handle diamond inheritance correctly
refactor: extract method signature matching to separate class
docs: update roadmap in AGENT.md
chore: add javaparser dependency to build.gradle.kts
test: add connection interface analysis tests
```

## Anti-patterns

```
# Too vague
fix: fix bug
feat: add feature
chore: update

# Too verbose
feat: add the ability to parse java source files and extract method signatures from them

# Mixed language in subject
feat: spec 추출기 추가

# Multiple unrelated changes in one commit
feat: add parser and fix resolver and update docs
```

## Process

1. Review changes with `git status` and `git diff`
2. Choose the appropriate type
3. Write a concise English subject within 50 characters
4. Stage specific files only — avoid `git add -A`
5. Create the commit — do **not** add `Co-Authored-By` or any auto-signature lines
