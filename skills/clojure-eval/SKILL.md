---
name: clojure-eval
description: Evaluate Clojure code via nREPL using clj-nrepl-eval. Use this when you need to execute code, test code check if edited files compile, verify function behavior, or interact with a running REPL session.
---

# Clojure REPL Evaluation

## When to Use This Skill

Use this skill when you need to:
- **Verify that edited Clojure files compile and load correctly**
- Test function behavior interactively
- Check the current state of the REPL
- Debug code by evaluating expressions
- Require or load namespaces for testing
- Validate that code changes work before committing

## How It Works

The command is run with `devenv shell clj-nrepl-eval`. (The prefix `devenv shell` is on purpose and specific to this repository.)

The `devenv shell clj-nrepl-eval` command evaluates Clojure code against an nREPL server. **Session state persists between evaluations**, so you can require a namespace in one evaluation and use it in subsequent calls. Each host:port combination maintains its own session file.

## Instructions

### 0. Discover and select nREPL server

First, discover what nREPL servers are running in the current directory:

```bash
devenv shell clj-nrepl-eval --discover-ports
```

This will show all nREPL servers (Clojure, Babashka, shadow-cljs, etc.) running in the current project directory.

Verify that this port is the same as the one in the file .nrepl-port - if so, use it.

If no port is found or the port does not match the .nrepl-port file, use the question tool to ask the user what to do.

### 1. Evaluate Clojure Code

> Evaluation automatically connects to the given port

Use the `-p` flag to specify the port and pass your Clojure code.

**Recommended: Pass code as a command-line argument:**
```bash
devenv shell clj-nrepl-eval -p <PORT> "(+ 1 2 3)"
```

**For multiple expressions (single line):**
```bash
devenv shell clj-nrepl-eval -p <PORT> "(def x 10) (+ x 20)"
```

**Alternative: Using heredoc (may require permission approval for multiline commands):**
```bash
devenv shell clj-nrepl-eval -p <PORT> <<'EOF'
(def x 10)
(+ x 20)
EOF
```

**Alternative: Via stdin pipe:**
```bash
echo "(+ 1 2 3)" | devenv shell clj-nrepl-eval -p <PORT>
```

### 2. Display nREPL Sessions

**Discover all nREPL servers in current directory:**
```bash
devenv shell clj-nrepl-eval --discover-ports
```
Shows all running nREPL servers in the current project directory, including their type (clj/bb/basilisp) and whether they match the current working directory.

**Check previously connected sessions:**
```bash
devenv shell clj-nrepl-eval --connected-ports
```
Shows only connections you have made before (appears after first evaluation on a port).

### 3. Common Patterns

**Require a namespace (always use :reload to pick up changes):**
```bash
devenv shell clj-nrepl-eval -p <PORT> "(require '[my.namespace :as ns] :reload)"
```

**Test a function after requiring:**
```bash
devenv shell clj-nrepl-eval -p <PORT> "(ns/my-function arg1 arg2)"
```

**Check if a file compiles:**
```bash
devenv shell clj-nrepl-eval -p <PORT> "(require 'my.namespace :reload)"
```

**Multiple expressions:**
```bash
devenv shell clj-nrepl-eval -p <PORT> "(def x 10) (* x 2) (+ x 5)"
```

**Complex multiline code (using heredoc):**
```bash
devenv shell clj-nrepl-eval -p <PORT> <<'EOF'
(def x 10)
(* x 2)
(+ x 5)
EOF
```
*Note: Heredoc syntax may require permission approval.*

**With custom timeout (in milliseconds):**
```bash
devenv shell clj-nrepl-eval -p <PORT> --timeout 5000 "(long-running-fn)"
```

**Reset the session (clears all state):**
```bash
devenv shell clj-nrepl-eval -p <PORT> --reset-session
devenv shell clj-nrepl-eval -p <PORT> --reset-session "(def x 1)"
```

## Available Options

- `-p, --port PORT` - nREPL port (required)
- `-H, --host HOST` - nREPL host (default: 127.0.0.1)
- `-t, --timeout MILLISECONDS` - Timeout (default: 120000 = 2 minutes)
- `-r, --reset-session` - Reset the persistent nREPL session
- `-c, --connected-ports` - List previously connected nREPL sessions
- `-d, --discover-ports` - Discover nREPL servers in current directory
- `-h, --help` - Show help message

## Important Notes

- **Prefer command-line arguments:** Pass code as quoted strings: `devenv shell clj-nrepl-eval -p <PORT> "(+ 1 2 3)"` - works with existing permissions
- **Heredoc for complex code:** Use heredoc (`<<'EOF' ... EOF`) for truly multiline code, but note it may require permission approval
- **Sessions persist:** State (vars, namespaces, loaded libraries) persists across invocations until the nREPL server restarts or `--reset-session` is used
- **Automatic delimiter repair:** The tool automatically repairs missing or mismatched parentheses
- **Always use :reload:** When requiring namespaces, use `:reload` to pick up recent changes
- **Default timeout:** 2 minutes (120000ms) - increase for long-running operations
- **Input precedence:** Command-line arguments take precedence over stdin

## Typical Workflow

1. Discover nREPL servers: `devenv shell clj-nrepl-eval --discover-ports`
2. Use **AskUserQuestion** tool to prompt user to select a port
3. Require namespace:
   ```bash
   devenv shell clj-nrepl-eval -p <PORT> "(require '[my.ns :as ns] :reload)"
   ```
4. Test function:
   ```bash
   devenv shell clj-nrepl-eval -p <PORT> "(ns/my-fn ...)"
   ```
5. Iterate: Make changes, re-require with `:reload`, test again
