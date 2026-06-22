---
name: git-commit-message
description: Write concise, well-structured git commit messages following conventional commits style. Use when user asks to commit changes or write a commit message.
---

# Git Commit Message Skill

You now have expertise in writing clear, structured git commit messages.

## Format

Follow Conventional Commits style:

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types

- `feat`: New feature for the user
- `fix`: Bug fix
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `docs`: Documentation only
- `test`: Adding or fixing tests
- `chore`: Maintenance tasks (deps, build, etc.)

### Subject Line Rules

- ≤ 70 chars
- Imperative mood: "add X" not "added X"
- No period at end
- Lowercase first letter (after type/scope prefix)

### Body Rules

- Wrap at 72 chars
- Explain **why**, not **what** (the code shows what)
- Use bullet points for multiple changes
- Reference issues with `#123`

## Examples

✅ Good:

```
feat(auth): add OAuth2 login flow

Users can now sign in via Google/GitHub. Previous email/password
flow is preserved as fallback. New endpoints:
- POST /auth/oauth/{provider}/start
- GET /auth/oauth/{provider}/callback

Closes #42
```

❌ Bad:

```
update auth code     ← 太模糊，没说改了什么
Fixed bug.           ← 缺 type、不知道是哪个 bug
WIP                  ← 半成品不该 commit
```

## Process

When user asks to commit:

1. Run `git diff --staged` to see what's actually changing
2. Identify the dominant change (one commit = one logical change)
3. Pick the right `type` based on what the diff shows
4. Write subject in imperative
5. Add body if change is non-obvious
6. Show user the message, confirm before running `git commit`
