---
name: code-review
description: Perform thorough code reviews focused on bugs, security, and maintainability. Use when user asks to review code, audit a file, or check for issues.
---

# Code Review Skill

You now have expertise in conducting structured code reviews.

## Review Priorities (in order)

### 1. Correctness Bugs

Highest priority. Look for:

- **Off-by-one errors**: loops, array indexing, string slicing
- **Null / Optional / undefined handling**: forgotten null checks, NPE risks
- **Concurrency**: race conditions, missing locks, shared mutable state
- **Resource leaks**: unclosed streams, connections, file handles
- **Type confusion**: casting without validation, generic unchecked operations

### 2. Security

- **Injection**: SQL injection, command injection, path traversal
- **Authentication**: missing auth, weak password handling, token in logs
- **Authorization**: missing access checks, IDOR (insecure direct object reference)
- **Sensitive data**: secrets in code, logs, or error messages

### 3. Maintainability

- **Naming**: variables/methods/classes with unclear names
- **Function size**: > 50 lines is a smell
- **Duplication**: same logic in 2+ places
- **Dead code**: unreached branches, unused parameters

### 4. Design / Style (lowest priority — don't nitpick)

- Consistency with surrounding code
- Comments explaining *why* not *what*
- Test coverage for new logic

## Output Format

For each issue found:

```
**[Severity]** File:Line — Issue title

Brief explanation (1-2 sentences). Why is this a problem?

Suggested fix:
\`\`\`<lang>
// fixed code or guidance
\`\`\`
```

Severity levels:
- 🔴 **Critical**: bug or security issue, will cause incidents
- 🟡 **Warning**: maintainability or correctness risk
- 🟢 **Note**: style / minor improvement

## Process

When user asks to review:

1. Use `glob` or `bash find` to identify the target files
2. Use `read_file` to read each file (with `limit` for large files)
3. For each file, walk through the priorities above in order
4. Group findings by severity
5. End with a 1-line summary: "X critical, Y warnings, Z notes"

## Anti-Patterns to Avoid

- ❌ Commenting on every line (drowns the real issues)
- ❌ Style nits (whitespace, line length) on a security review
- ❌ "Could be more elegant" without showing how
- ❌ Reviewing without reading the surrounding context
