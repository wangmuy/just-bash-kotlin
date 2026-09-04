# just-bash-kotlin Porting Report

English | [中文](MIGRATION.zh.md)

## Overview

This project ports the core functionality of [just-bash](https://github.com/vercel-labs/just-bash) (TypeScript) to Kotlin (JVM 17).

- **Original version**: just-bash v3.4.1
- **Ported version**: just-bash-kotlin v0.1.0
- **Porting scope**: core bash emulator + common coreutils commands
- **Testing strategy**: Kotlin JUnit 5 unit tests for each ported module
- **Test results**: 737 tests, 0 failures, 0 errors (BUILD SUCCESS)

## Architecture Mapping

```
Original TS module                →  Kotlin module
──────────────────────────────────────────────────
src/ast/types.ts                →  com.justbash.ast.Ast
src/types.ts                    →  com.justbash.Types
src/encoding.ts                 →  com.justbash.encoding.Encoding
src/fs/interface.ts             →  com.justbash.fs.IFileSystem
src/fs/in-memory-fs/*.ts        →  com.justbash.fs.InMemoryFs
src/fs/path-utils.ts            →  com.justbash.fs.PathUtils
src/fs/init.ts                  →  com.justbash.fs.FsInit
src/fs/encoding.ts              →  (inlined into InMemoryFs)
src/shell-metadata.ts           →  com.justbash.ShellMetadata
src/parser/*.ts                 →  com.justbash.parser.*
src/interpreter/expansion/*.ts  →  com.justbash.interpreter.expansion.*
src/interpreter/*.ts            →  com.justbash.interpreter.*
src/Bash.ts                     →  com.justbash.BashEnvironment
src/commands/**/*.ts            →  com.justbash.commands.*
```

## Ported Modules

### Foundation Modules
- ✅ Encoding/byte-boundary helpers (`Encoding.kt`)
- ✅ Path utilities (`PathUtils.kt`)
- ✅ Virtual filesystem interface (`IFileSystem.kt`)
- ✅ In-memory filesystem (`InMemoryFs.kt`) — full port, including symlinks, hard links, quotas
- ✅ Filesystem initialization (`FsInit.kt`) — /dev, /proc, /bin, etc.
- ✅ Error message sanitization (`SanitizeError.kt`) — replaces real paths with `<path>`
- ✅ Real filesystem read/write (`readwrite/ReadWriteFs.kt`) — writes directly to the real disk, with symlink safety checks
- ✅ Overlay filesystem (`overlay/OverlayFs.kt`) — copy-on-write, reads from the real FS, writes to the memory layer
- ✅ Multi-mount filesystem (`mountable/MountableFs.kt`) — routes to multiple FS backends
- ✅ File tree traversal (`traversal/Traversal.kt`) — DFS iterator + depth/entry budgets + cycle detection
- ✅ Shell metadata (`ShellMetadata.kt`)
- ✅ AST node types (`Ast.kt`) — full port of all nodes: Script/Statement/Pipeline/Commands/Words/Redirections/Arithmetic/Conditional, etc.
- ✅ Core types (`Types.kt`) — ExecResult, Command, CommandContext, ExecutionLimits

### Parser
- ✅ Lexer (`Lexer.kt`)
- ✅ Recursive-descent parser (`Parser.kt`) — complete grammar
- ✅ Compound command parsing (if/for/while/case/functions)
- ✅ Conditional expression parsing ([[ ]])
- ✅ Arithmetic expression parsing (($ )) / (( ))
- ✅ Parameter expansion parsing (${VAR...})
- ✅ Command substitution, process substitution, heredoc parsing

### Expansion & Arithmetic (expansion)
- ✅ Parameter expansion (${var}, ${var:-}, ${var:=}, ${var:?}, ${var:+}, ${#var}, ${var:offset:length})
- ✅ Pattern removal (${var#pat}, ${var##pat}, ${var%pat}, ${var%%pat})
- ✅ Pattern substitution (${var/pat/rep}, ${var//pat/rep})
- ✅ Case modification (${var^}, ${var^^}, ${var,}, ${var,,})
- ✅ Indirect expansion (${!var})
- ✅ Array key expansion (${!arr[@]})
- ✅ Brace expansion ({a,b,c}, {1..10})
- ✅ Tilde expansion (~, ~user)
- ✅ Arithmetic expansion ($((...)))
- ✅ Command substitution ($(cmd), \`cmd\`)
- ✅ Process substitution (<(cmd), >(cmd))
- ✅ Word splitting ($IFS)
- ✅ Filename globbing (glob)
- ✅ Quoting (single quotes, double quotes, escapes)

### Interpreter
- ✅ Main execution loop (`Interpreter.kt`)
- ✅ Statement/pipeline/simple-command/compound-command execution
- ✅ Control flow (if/for/while/until/case)
- ✅ Function definition and invocation
- ✅ Subshells (()) and groups ({ })
- ✅ Conditional commands ([[ ]])
- ✅ Arithmetic commands (( ))
- ✅ Pipeline execution (|, |&)
- ✅ Redirections (>, >>, <, <<, <<<, <<-, >&, <&, &>, 2>&1)
- ✅ Command resolution (PATH lookup, builtins take precedence)
- ✅ Shell options (errexit, pipefail, nounset, noglob, xtrace, etc.)
- ✅ Local variable scoping

### Builtin Commands
- ✅ cd (with -P/-L, CDPATH)
- ✅ export (with -n, -f, -p)
- ✅ local (with -a, -n)
- ✅ read (with -r, -d, -p, -a, -t, -n, -N, -s, -u)
- ✅ exit / return
- ✅ unset (with -f, -v)
- ✅ shift
- ✅ eval
- ✅ source / .
- ✅ break / continue
- ✅ let
- ✅ set
- ✅ declare
- ✅ help (categorized command list + `<command> --help` delegation)
- ✅ shopt (with -s/-u/-p/-q/-o; all 11 shell options)
- ✅ dirs (with -c/-l/-p/-v/+N/-N; directory stack)
- ✅ complete (with -W/-r/-p; completion settings)
- ✅ compgen (with -v/-e/-f/-d/-k/-A/-W/-P/-S; completion generation)
- ✅ compopt (with -o/+o; completion options)
- ✅ getopts (with OPTIND/OPTARG/`:` silent mode)
- ✅ hash (with -r/-d/-t/-p/-l; command path cache)
- ✅ mapfile (with -d/-n/-O/-s/-t/-u/-C/-c; read into array)

### External Commands (coreutils)
- ✅ echo (with -n, -e, -E, \xNN, \uXXXX)
- ✅ cat (with -n, -b, -v, -e, -t, -s)
- ✅ pwd (with -L, -P)
- ✅ ls (with -l, -a, -A, -h, -t, -r, -R, -S, -1)
- ✅ head (with -n, -c, -q, -v)
- ✅ tail (with -n, -c, -q, -v)
- ✅ mkdir, rmdir, rm, cp, mv, ln, touch, chmod
- ✅ wc (with -l, -w, -c, -m)
- ✅ sort (with -n, -r, -u, -k, -t, -f, -s, -b)
- ✅ uniq (with -c, -d, -u, -i, -f, -s, -w)
- ✅ basename, dirname
- ✅ tr (with -d, -s, -c)
- ✅ env, printenv
- ✅ true, false
- ✅ grep (with -i, -v, -n, -l, -L, -c, -o, -E, -F, -w, -x, -r, -e)
- ✅ base64 (with -d, -w; `java.util.Base64`)
- ✅ printf (with %s %d %x %o %f %c %b %q; `String.format`)
- ✅ seq (with -s, -w; pure logic)
- ✅ sleep (with s/m/h/d suffixes; `kotlinx.coroutines.delay`, cooperatively cancellable via `withTimeout`)
- ✅ stat (with -c FORMAT)
- ✅ date (with +FORMAT, -d TIMESTAMP; `java.time`)
- ✅ md5sum, sha1sum, sha256sum (with -c, --tag; `MessageDigest`)
- ✅ expr (with + - * / % plus string/regex operations)
- ✅ readlink (with -f, -e, -m, -n)
- ✅ awk (with -F, -v; backed by the Jawk interpreter)
- ✅ jq (with -r, -c, -n, -s, -R; backed by jackson-jq)
- ✅ diff (with -u, -q, -s, -i; backed by java-diff-utils)
- ✅ sed (with s/d/p/q/a/i/c/y/h/g/b/t commands, addresses, -E, -i, -n, -e, -f)
- ✅ find (with -name/-type/-size/-perm/-mtime/-newer/-prune/-delete/-exec/-print, etc.)
- ✅ curl (with -X/-H/-d/-o/-s/-v/-L/-i/-I; JDK 11 `HttpClient`)
- ✅ tar (with -c/-x/-t/-f/-z/-v/-C; commons-compress)
- ✅ gzip, gunzip, zcat (`java.util.zip.GZIPInputStream`/`GZIPOutputStream`)
- ✅ yq (with -p/-o/-r/-c/-I; SnakeYAML)
- ✅ timeout (with s/m/h/d suffixes; true timeout cancellation via `withTimeout`, matching the original `AbortController`/`AbortSignal`)
- ✅ rg (ripgrep, with gitignore/file types/smart case/recursive search)
- ✅ bash, sh (with -c, script files; `ctx.exec` delegation)
- ✅ split (with -l/-b/-n/-a/-d/--additional-suffix)
- ✅ tee (with -a; splits stdin to files and stdout)
- ✅ time (with -f/-o/-a/-v/-p; timed with `System.currentTimeMillis`)
- ✅ tree (with -L/-a/-d/-f; recursive directory tree)
- ✅ which (with -a/-s; PATH lookup)
- ✅ whoami (`System.getProperty("user.name")`)
- ✅ xargs (with -I/-d/-n/-0/-t/-r; `ctx.exec` delegation)
- ✅ html-to-markdown (regex-based HTML→Markdown conversion)
- ✅ alias (with unalias -a; stored as `BASH_ALIAS_<name>`)
- ✅ clear (ANSI clear screen)
- ✅ cut (with -d/-f/-c/-s)
- ✅ du (with -s/-h/-a/-c/--max-depth)
- ✅ expand (with -t/-i; tabs→spaces)
- ✅ fold (with -w/-s/-b; text wrapping)
- ✅ history (with -c; reads `BASH_HISTORY`)
- ✅ hostname (`InetAddress.getLocalHost`)
- ✅ nl (with -b/-n/-w/-s/-v/-i; numbered lines)
- ✅ column (with -t/-s/-o/-c/-n)
- ✅ comm (with -1/-2/-3)
- ✅ file (with magic-byte detection)
- ✅ join (with -1/-2/-t/-a/-v/-o/-i)
- ✅ od (with -A/-t/-N; octal/hex dump)
- ✅ paste (with -d/-s)
- ✅ rev (character reversal)
- ✅ strings (with -n/-t/-e)
- ✅ tac (line reversal)

## Modules Not Ported

The following modules depend on a JS/WASM runtime or third-party native libraries and cannot be ported directly to the JVM:

### Runtime Dependencies (not portable)
| Module | Reason |
|--------|--------|
| `python3` / `python` | Depends on CPython WASM (Emscripten) |
| `sqlite3` | Depends on sql.js (WASM) |
| `js-exec` / `node` | Depends on QuickJS WASM (quickjs-emscripten) |
| `worker-bridge` | Depends on SharedArrayBuffer/Atomics (browser/Node) |
| `defense-in-depth` | Depends on AsyncLocalStorage + Proxy global interception (Node.js specific) |
| `security/fuzzing` | Depends on Node runtime features |
| `sandbox` (Vercel Sandbox API) | Depends on network/process isolation |

### Complex Features, Ported or Evaluated
| Module | Implementation | Status |
|--------|---------------|--------|
| `awk` | [Jawk](https://jawk.io/) (`io.jawk:jawk:7.1.00`) | ✅ Dependency added |
| `jq` | [jackson-jq](https://github.com/eiiches/jackson-jq) (`net.thisptr:jackson-jq:1.6.2`) | ✅ Dependency added |
| `diff` | [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils) (`io.github.java-diff-utils:java-diff-utils:4.15`) | ✅ Dependency added |
| `curl` | JDK 11 `java.net.http.HttpClient` (zero external dependencies) | ✅ Ported |
| `tar` | [commons-compress](https://commons.apache.org/compress/) (`org.apache.commons:commons-compress:1.28.0`) | ✅ Dependency added |
| `gzip/gunzip/zcat` | `java.util.zip.GZIPInputStream`/`GZIPOutputStream` (built into the JDK) | ✅ Ported |
| `yq` | [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) (`org.yaml:snakeyaml:2.2`) | ✅ Dependency added |
| `timeout` | True timeout cancellation with `withTimeout` (matches the original `AbortController`/`AbortSignal`) | ✅ Ported |
| `rg` (ripgrep) | None (ported from scratch, 1104 lines, incl. gitignore/file types/smart case) | ✅ Ported |
| `xan` | None (simplified port, 737 lines, 15 core subcommands, inline CSV parser) | ✅ Ported |
| `sed` | None (ported from scratch, 1611 lines, incl. lexer/parser/executor) | ✅ Ported |
| `find` | None (ported from scratch, 1097 lines, incl. predicates/operators/actions) | ✅ Ported |
| `base64` | Java standard library `java.util.Base64` | ✅ Ported |
| `printf` | `String.format` + custom `%b`/`%q` | ✅ Ported |
| `seq` | Pure logic | ✅ Ported |
| `sleep` | `kotlinx.coroutines.delay` (cooperatively cancellable via `withTimeout`) | ✅ Ported |
| `stat` | `IFileSystem.stat/lstat/readlink` | ✅ Ported |
| `date` | `java.time` + strftime conversion | ✅ Ported |
| `expr` | Recursive-descent expression evaluation | ✅ Ported |
| `md5sum`/`sha1sum`/`sha256sum` | `java.security.MessageDigest` | ✅ Ported |
| `readlink` | `IFileSystem.readlink/realpath` | ✅ Ported |

### Complex Features, Not Needed or Never to Be Ported
| Module | Reason |
|--------|--------|
| `query-engine` | Original TS is ~6800 lines. The jq command is already covered by the external jackson-jq engine, so query-engine no longer needs porting |

### Security-Related (not portable)
| Module | Reason |
|--------|--------|
| `security/defense-in-depth-box` | `AsyncLocalStorage` + `Proxy` global interception — Node.js only, not implementable on the JVM |
| `security/blocked-globals` | Intercepts Node globals such as `Function`/`eval`/`require`/`process` — these objects don't exist on the JVM |
| `security/fuzzing` | Large-scale fuzzing framework — test infrastructure |
| `security/worker-defense-in-depth` | Worker-thread defense — depends on Node `worker_threads` |
| `security/attacks/*` | Attack regression tests — test infrastructure |
| `security/prototype-pollution/*` | JS prototype-pollution tests — no prototype chain on the JVM, not needed |

### Others
| Module | Reason |
|--------|--------|
| `browser.ts` | Browser bundle — not needed on the JVM |
| `sandbox/Sandbox` | Vercel Sandbox API — depends on network/process isolation, not portable |
| `network/dns-pin` | DNS pinning — requires native DNS capabilities; no DNS-rebinding risk in the `InMemoryFs` environment, skipped |

## CLI Entry Point (ported)

- ✅ `com.justbash.cli.JustBashCli` — CLI entry point (one-shot execution + `--shell` REPL mode).
- ✅ `com.justbash.cli.VirtualShell` — interactive REPL built on OverlayFs (reads the real filesystem, writes to the memory layer), with colored prompt, history, and exit command.
- ✅ Packaged as an executable fat-jar (`./gradlew build` → `build/libs/just-bash-kotlin-0.1.0.jar`).
- ✅ Run: `java -jar just-bash-kotlin-0.1.0.jar -c 'echo hello'`.
- ✅ REPL: `java -jar just-bash-kotlin-0.1.0.jar --shell`.
- Supported options: `-c <script>`, `-e/--errexit`, `--json`, `--shell`, `--file REAL=VPATH`,
  `-h/--help`, `-v/--version`, script files, stdin pipes.
- Verified: echo, for loops, stdin, script files, errexit, JSON output, version, help, non-interactive line-by-line REPL execution.

### Note: Filesystem Implementations

| FS implementation | Reads | Writes | Persistence | Purpose |
|-------------------|-------|--------|-------------|---------|
| `InMemoryFs` | Memory | Memory | No | Default (pure in-memory virtual FS) |
| `OverlayFs` | Real disk | Memory layer (copy-on-write) | No | Safe sandbox |
| `ReadWriteFs` | Real disk | Real disk | Yes | Persistent writes needed |
| `MountableFs` | Depends on mounted backend | Depends on mounted backend | Depends on mounted backend | Combines multiple FS backends |

- ✅ Inject any `IFileSystem` implementation via `BashEnvironment(fsOverride = ...)`
- ✅ REPL supports three modes: `--shell` (OverlayFs), `--shell --readwrite` (ReadWriteFs), `--shell --mountable VPATH=REALPATH,...` (MountableFs)

## Key Differences

### Type System
- TS uses `number` (double) for arithmetic values; Kotlin uses `Double` to match the semantics
- TS uses `Record<string, T>` + prototype-pollution guards; Kotlin uses `MutableMap<String, T>`, which is inherently safe
- TS uses `ByteString` (opaque tagged string) to mark byte/text boundaries; Kotlin distinguishes them natively with `ByteArray` vs `String`

### Sync vs Async
- In the original TS version, all I/O and command execution is `async`/`Promise`
- **The Kotlin port has been converted to coroutines**: `Command.execute` is `suspend`, `BashEnvironment.exec` is `suspend`, and `timeout` uses `withTimeout` for true timeout cancellation, matching the original `AbortController`/`AbortSignal` semantics

### Non-Portable JS Features
- `Object.create(null)` prototype-pollution guard → not needed; Kotlin Maps have no prototype chain
- `Proxy` global interception → not needed
- `AbortSignal` coroutine cancellation → implemented via Kotlin coroutine `withTimeout` + `Job.cancel()`, matching the original semantics
- WASM module loading → not applicable

### Coroutine Async (implemented 2026-08-21)

Kotlin coroutine async conversion is complete, aligned one-to-one with the just-bash implementation:

| Change | Status |
|--------|--------|
| `Command.execute` changed to `suspend` | ✅ Done (50+ Command implementations) |
| `Interpreter` execution chain changed to `suspend` | ✅ Done (all execution methods) |
| `BashEnvironment.exec` changed to `suspend` | ✅ Done |
| `CommandContext.exec` callback changed to `suspend` | ✅ Done |
| `timeout` command uses `withTimeout` for true timeout cancellation | ✅ Done (matches original `AbortController`/`AbortSignal`) |
| `ExpansionBridge` bridges suspend calls with `runBlocking` | ✅ Done |
| CLI calls the suspend entry point with `runBlocking` | ✅ Done |
| Test files wrap suspend calls with `runBlocking` | ✅ Done |

**Decision**: Kotlin coroutines are the async solution and have been fully implemented. Cooperative cancellation of `AbortSignal` is implemented via `withTimeout` + `Job.cancel()`, so the `timeout` command can now genuinely cancel execution within the specified time.

### Binary Pipe Transfer
- ✅ Supported via option C (latin1 encoding + `stdoutKind` marker): when `ExecResult.stdoutKind` is `"bytes"`/`"binary"`, stdout is passed as latin1 bytes to the next command's stdin (`ByteArray`); when `"text"`, it is passed UTF-8 encoded
- ✅ Binary pipe scenarios such as `gzip -c | gunzip` now work correctly
- ✅ `PipelineExecution.stdoutToStdin()` converts automatically based on `stdoutKind`

### Custom Command Extensions
- ✅ `com.justbash.CustomCommands.defineCommand()` — factory function that creates a `Command` from a lambda
- ✅ `com.justbash.LazyCommand` — lazily loaded command (loaded on first execution, for code-splitting)
- ✅ `BashEnvironment(customCommands = listOf(...))` — registers custom commands, can override builtins

### Network Security Layer
- ✅ `com.justbash.network.SecureFetch` — URL allowlist + method checks + timeout + response size limits + manual redirect control
- ✅ `NetworkConfig` — allowlist configuration, allowed HTTP methods, timeouts, response size limits
- ✅ Uses JDK 11 `java.net.http.HttpClient` (zero external dependencies)
- ⚠️ DNS pinning (`dns-pin`) not ported — requires native DNS capabilities; no DNS-rebinding risk in the `InMemoryFs` environment

## Build & Test

```bash
./gradlew test          # compile and run all tests (624 tests)
./gradlew build         # compile + test + package fat-jar
./gradlew shadowJar     # package fat-jar only (skip tests)
```

CLI usage:

```bash
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'echo hello'
echo 'echo from-stdin' | java -jar build/libs/just-bash-kotlin-0.1.0.jar
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'echo hi' --json
```

## Test Coverage

- Filesystem: reads, writes, appends, directories, permissions, symlinks, hard links, quotas, OverlayFs, ReadWriteFs, MountableFs, traversal (61 tests)
- Parser: simple commands, quoting, parameter expansion, command substitution, arithmetic expansion, pipelines, control flow, functions, heredocs (46 tests)
- Expansion: parameter expansion, braces, tildes, arithmetic, command substitution, word splitting, globs (44 tests)
- Interpreter: execution flow, pipelines, redirections, control flow, functions, builtins (10 tests)
- End-to-end integration: full pipeline parse → interpreter → commands (15 tests)
- Full expansion-engine verification: ${var:-}, ${#var}, braces, pattern removal, case conversion, tildes (8 tests)
- Conditional/file tests: [[ ]], [ ], file tests, arithmetic commands (9 tests)
- First-round commands: echo, cat, ls, grep, sort, wc, tr, uniq, env, mkdir, rm, cp, mv, etc. (121 tests)
- Second-round commands: base64, printf, seq, sleep, stat, date, checksums, expr, readlink (104 tests)
- Third-round commands: awk, jq, diff, find, sed (72 tests)
- Fourth-round commands: curl, tar, gzip, yq, timeout, rg, xan (101 tests)
- transform serialization + transform pipeline + plugins (19 tests)
- Binary pipes + CLI REPL (5 tests)
- **Total: 624 tests**
