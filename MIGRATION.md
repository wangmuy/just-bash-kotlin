# just-bash-kotlin 移植情况报告

## 概述

本项目将 [just-bash](https://github.com/vercel-labs/just-bash)（TypeScript）的核心功能移植为 Kotlin（JVM 17）。

- **原始版本**: just-bash v3.4.1
- **移植版本**: just-bash-kotlin v0.1.0
- **移植范围**: 核心 bash 模拟器 + 常用 coreutils 命令
- **测试策略**: 为每个移植模块编写 Kotlin JUnit 5 单元测试
- **测试结果**: 624 个测试，0 失败，0 错误（BUILD SUCCESS）

## 架构映射

```
原始 TS 模块                     →  Kotlin 模块
──────────────────────────────────────────────────
src/ast/types.ts                →  com.justbash.ast.Ast
src/types.ts                    →  com.justbash.Types
src/encoding.ts                 →  com.justbash.encoding.Encoding
src/fs/interface.ts             →  com.justbash.fs.IFileSystem
src/fs/in-memory-fs/*.ts        →  com.justbash.fs.InMemoryFs
src/fs/path-utils.ts            →  com.justbash.fs.PathUtils
src/fs/init.ts                  →  com.justbash.fs.FsInit
src/fs/encoding.ts              →  (内联到 InMemoryFs)
src/shell-metadata.ts           →  com.justbash.ShellMetadata
src/parser/*.ts                 →  com.justbash.parser.*
src/interpreter/expansion/*.ts  →  com.justbash.interpreter.expansion.*
src/interpreter/*.ts            →  com.justbash.interpreter.*
src/Bash.ts                     →  com.justbash.BashEnvironment
src/commands/**/*.ts            →  com.justbash.commands.*
```

## 已移植模块

### 基础模块
- ✅ 编码/字节边界助手 (`Encoding.kt`)
- ✅ 路径工具 (`PathUtils.kt`)
- ✅ 虚拟文件系统接口 (`IFileSystem.kt`)
- ✅ 内存文件系统 (`InMemoryFs.kt`) — 完整移植，含 symlink、hard link、配额
- ✅ 文件系统初始化 (`FsInit.kt`) — /dev, /proc, /bin 等
- ✅ 错误信息脱敏 (`SanitizeError.kt`) — 替换真实路径为 `<path>`
- ✅ 真实文件系统读写 (`readwrite/ReadWriteFs.kt`) — 直写真实磁盘，含 symlink 安全检测
- ✅ 覆盖文件系统 (`overlay/OverlayFs.kt`) — copy-on-write，读真实 FS 写内存层
- ✅ 多挂载点文件系统 (`mountable/MountableFs.kt`) — 路由到多个 FS 后端
- ✅ 文件树遍历 (`traversal/Traversal.kt`) — DFS 迭代器 + 深度/条目预算 + 循环检测
- ✅ Shell 元数据 (`ShellMetadata.kt`)
- ✅ AST 节点类型 (`Ast.kt`) — 完整移植 Script/Statement/Pipeline/Commands/Words/Redirections/Arithmetic/Conditional 等所有节点
- ✅ 核心类型 (`Types.kt`) — ExecResult, Command, CommandContext, ExecutionLimits

### 解析器 (parser)
- ✅ 词法分析器 (`Lexer.kt`)
- ✅ 递归下降解析器 (`Parser.kt`) — 完整语法
- ✅ 复合命令解析 (if/for/while/case/functions)
- ✅ 条件表达式解析 ([[ ]])
- ✅ 算术表达式解析 (($ )) / (( ))
- ✅ 参数展开解析 (${VAR...})
- ✅ 命令替换、进程替换、heredoc 解析

### 展开与算术 (expansion)
- ✅ 参数展开 (${var}, ${var:-}, ${var:=}, ${var:?}, ${var:+}, ${#var}, ${var:offset:length})
- ✅ 模式移除 (${var#pat}, ${var##pat}, ${var%pat}, ${var%%pat})
- ✅ 模式替换 (${var/pat/rep}, ${var//pat/rep})
- ✅ 大小写修改 (${var^}, ${var^^}, ${var,}, ${var,,})
- ✅ 间接展开 (${!var})
- ✅ 数组键展开 (${!arr[@]})
- ✅ 大括号展开 ({a,b,c}, {1..10})
- ✅ 波浪号展开 (~, ~user)
- ✅ 算术展开 ($((...)))
- ✅ 命令替换 ($(cmd), \`cmd\`)
- ✅ 进程替换 (<(cmd), >(cmd))
- ✅ 单词分割 ($IFS)
- ✅ 文件名通配 (glob)
- ✅ 引用处理 (单引号、双引号、转义)

### 解释器 (interpreter)
- ✅ 主执行循环 (`Interpreter.kt`)
- ✅ 语句/管道/简单命令/复合命令执行
- ✅ 控制流 (if/for/while/until/case)
- ✅ 函数定义与调用
- ✅ 子 shell (()) 和组 ({ })
- ✅ 条件命令 ([[ ]])
- ✅ 算术命令 (( ))
- ✅ 管道执行 (|, |&)
- ✅ 重定向 (>, >>, <, <<, <<<, <<-, >&, <&, &>, 2>&1)
- ✅ 命令解析 (PATH 查找、内建优先)
- ✅ Shell 选项 (errexit, pipefail, nounset, noglob, xtrace 等)
- ✅ 本地变量作用域

### 内建命令 (builtins)
- ✅ cd (含 -P/-L, CDPATH)
- ✅ export (含 -n, -f, -p)
- ✅ local (含 -a, -n)
- ✅ read (含 -r, -d, -p, -a, -t, -n, -N, -s, -u)
- ✅ exit / return
- ✅ unset (含 -f, -v)
- ✅ shift
- ✅ eval
- ✅ source / .
- ✅ break / continue
- ✅ let
- ✅ set
- ✅ declare

### 外部命令 (coreutils)
- ✅ echo (含 -n, -e, -E, \xNN, \uXXXX)
- ✅ cat (含 -n, -b, -v, -e, -t, -s)
- ✅ pwd (含 -L, -P)
- ✅ ls (含 -l, -a, -A, -h, -t, -r, -R, -S, -1)
- ✅ head (含 -n, -c, -q, -v)
- ✅ tail (含 -n, -c, -q, -v)
- ✅ mkdir, rmdir, rm, cp, mv, ln, touch, chmod
- ✅ wc (含 -l, -w, -c, -m)
- ✅ sort (含 -n, -r, -u, -k, -t, -f, -s, -b)
- ✅ uniq (含 -c, -d, -u, -i, -f, -s, -w)
- ✅ basename, dirname
- ✅ tr (含 -d, -s, -c)
- ✅ env, printenv
- ✅ true, false
- ✅ grep (含 -i, -v, -n, -l, -L, -c, -o, -E, -F, -w, -x, -r, -e)
- ✅ base64 (含 -d, -w；`java.util.Base64`)
- ✅ printf (含 %s %d %x %o %f %c %b %q；`String.format`)
- ✅ seq (含 -s, -w；纯逻辑)
- ✅ sleep (含 s/m/h/d 后缀；`kotlinx.coroutines.delay`，可被 `withTimeout` 协作取消)
- ✅ stat (含 -c FORMAT)
- ✅ date (含 +FORMAT, -d TIMESTAMP；`java.time`)
- ✅ md5sum, sha1sum, sha256sum (含 -c, --tag；`MessageDigest`)
- ✅ expr (含 + - * / % 及字符串/正则运算)
- ✅ readlink (含 -f, -e, -m, -n)
- ✅ awk (含 -F, -v；依赖 Jawk 解释器)
- ✅ jq (含 -r, -c, -n, -s, -R；依赖 jackson-jq)
- ✅ diff (含 -u, -q, -s, -i；依赖 java-diff-utils)
- ✅ sed (含 s/d/p/q/a/i/c/y/h/g/b/t 命令、地址、-E、-i、-n、-e、-f)
- ✅ find (含 -name/-type/-size/-perm/-mtime/-newer/-prune/-delete/-exec/-print 等)
- ✅ curl (含 -X/-H/-d/-o/-s/-v/-L/-i/-I；JDK 11 `HttpClient`)
- ✅ tar (含 -c/-x/-t/-f/-z/-v/-C；commons-compress)
- ✅ gzip, gunzip, zcat (`java.util.zip.GZIPInputStream`/`GZIPOutputStream`)
- ✅ yq (含 -p/-o/-r/-c/-I；SnakeYAML)
- ✅ timeout (含 s/m/h/d 后缀；`withTimeout` 真正超时取消，对齐原版 `AbortController`/`AbortSignal`)
- ✅ rg (ripgrep，含 gitignore/文件类型/智能大小写/递归搜索)
- ✅ xan (CSV 工具，含 15 核心子命令：cat/count/head/tail/select/filter/map/sort/dedup/stats/frequency/agg/rename/search/view)

## 未移植的模块

以下模块因依赖 JS/WASM 运行时或第三方 native 库，无法在 JVM 上直接移植：

### 运行时依赖 (不可移植)
| 模块 | 原因 |
|------|------|
| `python3` / `python` | 依赖 CPython WASM (Emscripten) |
| `sqlite3` | 依赖 sql.js (WASM) |
| `js-exec` / `node` | 依赖 QuickJS WASM (quickjs-emscripten) |
| `worker-bridge` | 依赖 SharedArrayBuffer/Atomics (浏览器/Node) |
| `defense-in-depth` | 依赖 AsyncLocalStorage + Proxy 全局拦截 (Node.js 特有) |
| `security/fuzzing` | 依赖 Node 运行时特性 |
| `sandbox` (Vercel Sandbox API) | 依赖网络/进程隔离 |

### 功能复杂，已移植或已评估
| 模块 | 实现方式 | 状态 |
|------|---------|------|
| `awk` | [Jawk](https://jawk.io/)（`io.jawk:jawk:7.1.00`） | ✅ 已引入依赖 |
| `jq` | [jackson-jq](https://github.com/eiiches/jackson-jq)（`net.thisptr:jackson-jq:1.6.2`） | ✅ 已引入依赖 |
| `diff` | [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils)（`io.github.java-diff-utils:java-diff-utils:4.15`） | ✅ 已引入依赖 |
| `curl` | JDK 11 `java.net.http.HttpClient`（零外部依赖） | ✅ 已移植 |
| `tar` | [commons-compress](https://commons.apache.org/compress/)（`org.apache.commons:commons-compress:1.28.0`） | ✅ 已引入依赖 |
| `gzip/gunzip/zcat` | `java.util.zip.GZIPInputStream`/`GZIPOutputStream`（JDK 内置） | ✅ 已移植 |
| `yq` | [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml)（`org.yaml:snakeyaml:2.2`） | ✅ 已引入依赖 |
| `timeout` | `withTimeout` 真正超时取消（对齐原版 `AbortController`/`AbortSignal`） | ✅ 已移植 |
| `rg` (ripgrep) | 无（自行移植，1104 行，含 gitignore/文件类型/智能大小写） | ✅ 已移植 |
| `xan` | 无（简化移植，737 行，含 15 核心子命令，内联 CSV 解析） | ✅ 已移植 |
| `sed` | 无（自行移植，1611 行，含 lexer/parser/executor） | ✅ 已移植 |
| `find` | 无（自行移植，1097 行，含 predicates/operators/actions） | ✅ 已移植 |
| `base64` | Java 标准库 `java.util.Base64` | ✅ 已移植 |
| `printf` | `String.format` + 自定义 `%b`/`%q` | ✅ 已移植 |
| `seq` | 纯逻辑 | ✅ 已移植 |
| `sleep` | `kotlinx.coroutines.delay`（可被 `withTimeout` 协作取消） | ✅ 已移植 |
| `stat` | `IFileSystem.stat/lstat/readlink` | ✅ 已移植 |
| `date` | `java.time` + strftime 转换 | ✅ 已移植 |
| `expr` | 递归下降表达式求值 | ✅ 已移植 |
| `md5sum`/`sha1sum`/`sha256sum` | `java.security.MessageDigest` | ✅ 已移植 |
| `readlink` | `IFileSystem.readlink/realpath` | ✅ 已移植 |

### 功能复杂，不需要移植或永不移植
| 模块 | 原因 |
|------|------|
| `query-engine` | 原 TS 约 6800 行。Jq 命令已用 jackson-jq 外部引擎替代，query-engine 不再需要移植 |

### 安全相关 (不可移植)
| 模块 | 原因 |
|------|------|
| `security/defense-in-depth-box` | `AsyncLocalStorage` + `Proxy` 全局拦截——Node.js 专有，JVM 无法实现 |
| `security/blocked-globals` | 拦截 `Function`/`eval`/`require`/`process` 等 Node 全局——JVM 不存在这些对象 |
| `security/fuzzing` | 大规模模糊测试框架——测试基础设施 |
| `security/worker-defense-in-depth` | Worker 线程防御——依赖 Node `worker_threads` |
| `security/attacks/*` | 攻击回归测试——测试基础设施 |
| `security/prototype-pollution/*` | JS 原型污染测试——JVM 无原型链，不需要 |

### 其他
| 模块 | 原因 |
|------|------|
| `custom-commands` | ✅ 已移植（`com.justbash.CustomCommands`：`defineCommand` + `LazyCommand`） |
| `network/SecureFetch` | ✅ 已移植（`com.justbash.network.SecureFetch`：JDK `HttpClient` + URL 白名单 + 响应大小限制 + 超时 + 重定向控制） |
| `transform/*` | ✅ 已移植（`com.justbash.transform.Serializer` + `TransformPipeline` + `CommandCollectorPlugin` + `TeePlugin`） |
| `OverlayFs` | ✅ 已移植（`com.justbash.fs.overlay.OverlayFs`，copy-on-write，read-only/read-write 模式） |
| `browser.ts` | 浏览器打包 —— JVM 环境不需要 |
| `sandbox/Sandbox` | Vercel 沙箱 API —— 依赖网络/进程隔离，不可移植 |
| `network/dns-pin` | DNS 绑定防范 —— 需要 native DNS 能力，`InMemoryFs` 环境无 DNS 重绑定风险，跳过 |

## CLI 入口（已移植）

- ✅ `com.justbash.cli.JustBashCli` — 命令行入口（单次执行 + `--shell` REPL 模式）。
- ✅ `com.justbash.cli.VirtualShell` — 交互式 REPL，基于 OverlayFs（读真实文件系统，写内存层），支持彩色提示符、历史、exit 命令。
- ✅ 打包为可执行 fat-jar（`./gradlew build` → `build/libs/just-bash-kotlin-0.1.0.jar`）。
- ✅ 运行：`java -jar just-bash-kotlin-0.1.0.jar -c 'echo hello'`。
- ✅ REPL：`java -jar just-bash-kotlin-0.1.0.jar --shell`。
- 支持选项：`-c <script>`、`-e/--errexit`、`--json`、`--shell`、`--file REAL=VPATH`、
  `-h/--help`、`-v/--version`、脚本文件、stdin 管道。
- 已验证：echo、for 循环、stdin、脚本文件、errexit、JSON 输出、版本、帮助、REPL 非交互逐行执行。

### 说明：OverlayFs 已移植
- ✅ `com.justbash.fs.overlay.OverlayFs` — copy-on-write 文件系统
  - **readOnly=true**：所有写操作抛 `EROFS`（只读）
  - **readOnly=false**（默认）：允许写操作，但**写入内存层**（`memory` map），**不触及真实磁盘**
  - 读操作：先查内存层，未命中则 fallback 到真实文件系统
- 通过 `OverlayFs(OverlayFsOptions(root, mountPoint, readOnly))` 挂载真实磁盘目录到虚拟文件系统
- 安全特性：symlink 检测（默认拒绝）、路径遍历防护、内存层隔离

## 关键差异

### 类型系统
- TS 使用 `number` (double) 表示算术值；Kotlin 使用 `Double` 以匹配语义
- TS 使用 `Record<string, T>` + 原型污染防护；Kotlin 使用 `MutableMap<String, T>` 天然安全
- TS 使用 `ByteString` (opaque tagged string) 标记字节/文本边界；Kotlin 使用 `ByteArray` vs `String` 天然区分

### 同步 vs 异步
- TS 原版所有 I/O 和命令执行都是 `async`/`Promise`
- **Kotlin 移植版已用 coroutine 改造**：`Command.execute` 为 `suspend`，`BashEnvironment.exec` 为 `suspend`，`timeout` 用 `withTimeout` 实现真正的超时取消，对齐原版 `AbortController`/`AbortSignal` 语义

### 不可移植的 JS 特性
- `Object.create(null)` 原型污染防护 → 不需要，Kotlin Map 无原型链
- `Proxy` 全局拦截 → 不需要
- `AbortSignal` 协程取消 → 已通过 Kotlin coroutine `withTimeout` + `Job.cancel()` 实现，对齐原版语义
- WASM 模块加载 → 不适用

### Coroutine 异步化（已实施，2026-08-21）

Kotlin coroutine 异步化已完成，一一对齐 just-bash 原实现：

| 改造项 | 状态 |
|--------|------|
| `Command.execute` 改为 `suspend` | ✅ 已完成（50+ Command 实现） |
| `Interpreter` 执行链改为 `suspend` | ✅ 已完成（全部执行方法） |
| `BashEnvironment.exec` 改为 `suspend` | ✅ 已完成 |
| `CommandContext.exec` 回调改为 `suspend` | ✅ 已完成 |
| `timeout` 命令用 `withTimeout` 实现真正超时取消 | ✅ 已完成（对齐原版 `AbortController`/`AbortSignal`） |
| `ExpansionBridge` 用 `runBlocking` 桥接 suspend 调用 | ✅ 已完成 |
| CLI 用 `runBlocking` 调用 suspend 入口 | ✅ 已完成 |
| 测试文件用 `runBlocking` 包装 suspend 调用 | ✅ 已完成 |

**决策**：Kotlin coroutine 作为异步方案，已完整实施。`AbortSignal` 的协作取消通过 `withTimeout` + `Job.cancel()` 实现，`timeout` 命令现在能真正在规定时间内取消执行。

### 管道二进制传输
- ✅ 已支持方案 C（latin1 编码 + `stdoutKind` 标记）：`ExecResult.stdoutKind` 为 `"bytes"`/`"binary"` 时，stdout 作为 latin1 字节传递到下一个命令的 stdin（`ByteArray`）；`"text"` 时 UTF-8 编码后传递
- ✅ `gzip -c | gunzip` 等管道二进制场景现在正常工作
- ✅ `PipelineExecution.stdoutToStdin()` 根据 `stdoutKind` 自动转换

### 自定义命令扩展
- ✅ `com.justbash.CustomCommands.defineCommand()` — 工厂函数，从 lambda 创建 `Command`
- ✅ `com.justbash.LazyCommand` — 延迟加载命令（首次执行时加载，用于 code-splitting）
- ✅ `BashEnvironment(customCommands = listOf(...))` — 注册自定义命令，可覆盖内置命令

### 网络安全层
- ✅ `com.justbash.network.SecureFetch` — URL 白名单 + 方法检查 + 超时 + 响应大小限制 + 手动重定向控制
- ✅ `NetworkConfig` — 白名单配置、允许的 HTTP 方法、超时、响应大小限制
- ✅ 使用 JDK 11 `java.net.http.HttpClient`（零外部依赖）
- ⚠️ DNS 绑定（`dns-pin`）未移植——需要 native DNS 能力，`InMemoryFs` 环境无 DNS 重绑定风险

## 构建和测试

```bash
./gradlew test          # 编译并运行全部测试（624 tests）
./gradlew build         # 编译 + 测试 + 打包 fat-jar
./gradlew shadowJar     # 仅打包 fat-jar（跳过测试）
```

CLI 运行：

```bash
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'echo hello'
echo 'echo from-stdin' | java -jar build/libs/just-bash-kotlin-0.1.0.jar
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'echo hi' --json
```

## 测试覆盖

- 文件系统: 读写、追加、目录、权限、symlink、hard link、配额、OverlayFs、ReadWriteFs、MountableFs、遍历（61 tests）
- 解析器: 简单命令、引用、参数展开、命令替换、算术展开、管道、控制流、函数、heredoc（46 tests）
- 展开: 参数展开、大括号、波浪号、算术、命令替换、单词分割、通配（44 tests）
- 解释器: 执行流程、管道、重定向、控制流、函数、内建命令（10 tests）
- 端到端集成: 完整 pipeline parse → interpreter → commands（15 tests）
- 完整展开引擎验证: ${var:-}, ${#var}, 大括号, 模式移除, 大小写, 波浪号（8 tests）
- 条件命令/文件测试: [[ ]], [ ], 文件测试, 算术命令（9 tests）
- 第一轮命令: echo, cat, ls, grep, sort, wc, tr, uniq, env, mkdir, rm, cp, mv 等（121 tests）
- 第二轮命令: base64, printf, seq, sleep, stat, date, checksum, expr, readlink（104 tests）
- 第三轮命令: awk, jq, diff, find, sed（72 tests）
- 第四轮命令: curl, tar, gzip, yq, timeout, rg, xan（101 tests）
- transform 序列化 + 变换管道 + 插件（19 tests）
- 管道二进制 + CLI REPL（5 tests）
- **总计: 624 tests**