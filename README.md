# just-bash-kotlin

English | [中文](README.zh.md)

A Kotlin port of `just-bash` — a bash emulation environment with an in-memory virtual filesystem.

Original project (TypeScript): [vercel-labs/just-bash](https://github.com/vercel-labs/just-bash)

## Build & Test

```bash
./gradlew test          # compile and run all tests (624 tests)
./gradlew build         # compile + test + package fat-jar
./gradlew shadowJar     # package fat-jar only (skip tests)
```

## CLI Usage

### One-shot Execution

```bash
# Package
./gradlew build

# Execute scripts
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'echo "hello $(date)"'
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'ls -la'
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'echo hi' --json
java -jar build/libs/just-bash-kotlin-0.1.0.jar -e -c 'false; echo ok'  # errexit mode

# Piped input
echo 'echo hello' | java -jar build/libs/just-bash-kotlin-0.1.0.jar

# Script file
java -jar build/libs/just-bash-kotlin-0.1.0.jar script.sh
```

### Interactive REPL (OverlayFs / ReadWriteFs / MountableFs)

The REPL supports three filesystem backends:

- **OverlayFs** (default): **reads** the real filesystem, **writes** to an in-memory layer (copy-on-write), never touches the disk — ideal as a safe sandbox
- **ReadWriteFs** (`--readwrite`): **reads and writes** the real disk directly — for scenarios that require persistent writes
- **MountableFs** (`--mountable`): multiple mount points, mapping several real directories to different virtual paths

#### OverlayFs REPL (writes to memory layer, disk untouched)

```bash
# Start an interactive REPL (mount the real filesystem under the current directory, writes stay in memory)
java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell

# Specify the mount directory
java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --root /path/to/project

# Non-interactive piped input
echo 'echo hi' | java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell
```

**OverlayFs REPL example:**

```bash
$ java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --root /path/to/project

╔══════════════════════════════════════════════════════════════╗
║                    Virtual Shell v1.0                         ║
║            A simulated bash environment in Kotlin             ║
╚══════════════════════════════════════════════════════════════╝

Exploring: /path/to/project

Type help for available commands, exit to quit.
Reads from real filesystem, writes stay in memory (OverlayFs).

user@virtual:~$ ls
README.md  src/  build.gradle.kts  ...

user@virtual:~$ echo "hello" > /tmp/test.txt     # written to the memory layer, real disk unchanged
user@virtual:~$ cat /tmp/test.txt
hello

user@virtual:~$ gzip -c /tmp/test.txt | gunzip
hello

user@virtual:~$ exit
```

#### ReadWriteFs REPL (writes to the real disk)

```bash
# Start the REPL with writes going directly to the real disk
java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --readwrite --root /path/to/project

# Non-interactive piped input (writes to the real disk)
echo 'echo persisted > result.txt' | java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --readwrite --root /path/to/project
```

**ReadWriteFs REPL example:**

```bash
$ java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --readwrite --root /path/to/project

user@virtual:~$ echo "persisted" > result.txt    # real disk write
user@virtual:~$ cat result.txt
persisted

user@virtual:~$ ls result.txt                    # confirmed against the real disk file
result.txt

user@virtual:~$ exit
```

#### MountableFs REPL (multiple mount points)

```bash
# Mount multiple real directories at different virtual paths
java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell \
    --mountable "/mnt/data=./data,/mnt/logs=./logs"

# Each mount point uses ReadWriteFs (writes go directly to the real disk)
# Unmounted paths use InMemoryFs (in-memory)
```

**MountableFs REPL example:**

```bash
$ java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --mountable "/mnt/data=./data"

user@virtual:~$ cat /mnt/data/file.txt        # read from the real disk
hello from data

user@virtual:~$ echo "new" > /mnt/data/out.txt  # write to the real disk
user@virtual:~$ cat /mnt/data/out.txt
new

user@virtual:~$ echo "volatile" > /tmp/volatile.txt  # /tmp is unmounted → in-memory
user@virtual:~$ exit
```

**Comparison of the three modes:**

| Feature | OverlayFs (default) | ReadWriteFs (`--readwrite`) | MountableFs (`--mountable`) |
|---------|---------------------|-----------------------------|-----------------------------|
| Reads | Real filesystem | Real filesystem | Mounted paths: real disk; unmounted: memory |
| Writes | Memory layer (copy-on-write) | Real disk | Mounted paths: real disk; unmounted: memory |
| Persistence | No (lost on exit) | Yes | Mounted paths: yes; unmounted: no |
| Use cases | Safe sandbox, dry runs | Real file operations | Multi-directory isolation, mixed read/write |

## Directory Layout

```
src/main/kotlin/com/justbash/
  ast/          AST node types
  cli/          CLI entry point (JustBashCli + VirtualShell REPL)
  commands/     External command implementations (30+ commands)
  encoding/     Byte/text boundary helpers
  fs/           Virtual filesystem (InMemoryFs + OverlayFs + ReadWriteFs + MountableFs + Traversal)
  interpreter/  Interpreter + expansion engine + builtin commands
  network/      SecureFetch (URL-allowlisted HTTP client)
  parser/       Lexing and parsing (complete bash parser)
  transform/    AST serialization + transform pipeline + plugins
src/test/kotlin/com/justbash/   Kotlin unit tests (624 tests)
```

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1` | Coroutine async (suspend/timeout/AbortSignal) |
| `io.jawk:jawk:7.1.00` | awk command (Jawk interpreter) |
| `net.thisptr:jackson-jq:1.6.2` | jq command (jackson-jq) |
| `io.github.java-diff-utils:java-diff-utils:4.15` | diff command |
| `org.yaml:snakeyaml:2.2` | yq command (YAML↔JSON conversion) |
| `org.apache.commons:commons-compress:1.28.0` | tar command (compression/archiving) |

## Porting Status

See `MIGRATION.md`.
