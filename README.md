# just-bash-kotlin

`just-bash` 的 Kotlin 移植版本 —— 一个带内存虚拟文件系统的 bash 模拟环境。

原始项目（TypeScript）：[vercel-labs/just-bash](https://github.com/vercel-labs/just-bash)

## 构建与测试

```bash
./gradlew test          # 编译并运行全部测试（624 tests）
./gradlew build         # 编译 + 测试 + 打包 fat-jar
./gradlew shadowJar     # 仅打包 fat-jar（跳过测试）
```

## CLI 运行

### 单次执行

```bash
# 打包
./gradlew build

# 执行脚本
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'echo "hello $(date)"'
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'ls -la'
java -jar build/libs/just-bash-kotlin-0.1.0.jar -c 'echo hi' --json
java -jar build/libs/just-bash-kotlin-0.1.0.jar -e -c 'false; echo ok'  # errexit 模式

# 管道输入
echo 'echo hello' | java -jar build/libs/just-bash-kotlin-0.1.0.jar

# 脚本文件
java -jar build/libs/just-bash-kotlin-0.1.0.jar script.sh
```

### 交互式 REPL（OverlayFs / ReadWriteFs / MountableFs）

REPL 支持三种文件系统后端：

- **OverlayFs**（默认）：**读**真实文件系统，**写**内存层（copy-on-write），不触及磁盘 —— 适合安全沙箱
- **ReadWriteFs**（`--readwrite`）：直接**读写**真实磁盘 —— 适合需要持久化写操作的场景
- **MountableFs**（`--mountable`）：多挂载点，将多个真实目录挂载到不同虚拟路径

#### OverlayFs REPL（写内存层，不触磁盘）

```bash
# 启动交互式 REPL（在当前目录挂载真实文件系统，写内存层）
java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell

# 指定挂载目录
java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --root /path/to/project

# 非交互管道输入
echo 'echo hi' | java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell
```

**OverlayFs REPL 示例：**

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

user@virtual:~$ echo "hello" > /tmp/test.txt     # 写入内存层，真实磁盘不变
user@virtual:~$ cat /tmp/test.txt
hello

user@virtual:~$ gzip -c /tmp/test.txt | gunzip
hello

user@virtual:~$ exit
```

#### ReadWriteFs REPL（写真实磁盘）

```bash
# 启动 REPL，写操作直接触及真实磁盘
java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --readwrite --root /path/to/project

# 非交互管道输入（写真实磁盘）
echo 'echo persisted > result.txt' | java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --readwrite --root /path/to/project
```

**ReadWriteFs REPL 示例：**

```bash
$ java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --readwrite --root /path/to/project

user@virtual:~$ echo "persisted" > result.txt    # 真实磁盘写入
user@virtual:~$ cat result.txt
persisted

user@virtual:~$ ls result.txt                    # 真实磁盘文件确认
result.txt

user@virtual:~$ exit
```

#### MountableFs REPL（多挂载点）

```bash
# 将多个真实目录挂载到不同虚拟路径
java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell \
    --mountable "/mnt/data=./data,/mnt/logs=./logs"

# 每个挂载点使用 ReadWriteFs（写直接触及真实磁盘）
# 未挂载的路径使用 InMemoryFs（内存）
```

**MountableFs REPL 示例：**

```bash
$ java -jar build/libs/just-bash-kotlin-0.1.0.jar --shell --mountable "/mnt/data=./data"

user@virtual:~$ cat /mnt/data/file.txt        # 读真实磁盘
hello from data

user@virtual:~$ echo "new" > /mnt/data/out.txt  # 写真实磁盘
user@virtual:~$ cat /mnt/data/out.txt
new

user@virtual:~$ echo "volatile" > /tmp/volatile.txt  # /tmp 未挂载 → 内存
user@virtual:~$ exit
```

**三种模式对比：**

| 特性 | OverlayFs（默认） | ReadWriteFs（`--readwrite`） | MountableFs（`--mountable`） |
|------|------------------|---------------------------|---------------------------|
| 读操作 | 真实文件系统 | 真实文件系统 | 挂载路径：真实磁盘；未挂载：内存 |
| 写操作 | 内存层（copy-on-write） | 真实磁盘 | 挂载路径：真实磁盘；未挂载：内存 |
| 持久化 | 否（退出后丢失） | 是 | 挂载路径：是；未挂载：否 |
| 适用场景 | 安全沙箱、试运行 | 实际文件操作 | 多目录隔离、混合读写 |

## 目录结构

```
src/main/kotlin/com/justbash/
  ast/          AST 节点类型
  cli/          CLI 入口（JustBashCli + VirtualShell REPL）
  commands/     外部命令实现（30+ 命令）
  encoding/     字节/文本边界助手
  fs/           虚拟文件系统（InMemoryFs + OverlayFs + ReadWriteFs + MountableFs + Traversal）
  interpreter/  解释器 + 展开引擎 + 内建命令
  network/      SecureFetch（URL 白名单 HTTP 客户端）
  parser/       词法与语法分析（完整 bash 解析器）
  transform/    AST 序列化 + 变换管道 + 插件
src/test/kotlin/com/justbash/   Kotlin 单元测试（624 tests）
```

## 依赖

| 依赖 | 用途 |
|------|------|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1` | 协程异步（suspend/timeout/AbortSignal） |
| `io.jawk:jawk:7.1.00` | awk 命令（Jawk 解释器） |
| `net.thisptr:jackson-jq:1.6.2` | jq 命令（jackson-jq） |
| `io.github.java-diff-utils:java-diff-utils:4.15` | diff 命令 |
| `org.yaml:snakeyaml:2.2` | yq 命令（YAML↔JSON 转换） |
| `org.apache.commons:commons-compress:1.28.0` | tar 命令（压缩/归档） |

## 移植情况

详见 `MIGRATION.md`。