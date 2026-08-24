package com.justbash.cli

import com.justbash.BashEnvironment
import com.justbash.ShellMetadata
import java.io.File

/**
 * just-bash CLI — a simulated bash environment for the JVM.
 *
 * Port of just-bash `src/cli/just-bash.ts`. Usage:
 * ```
 *   just-bash -c 'echo hello'
 *   echo 'echo hello' | just-bash
 *   just-bash script.sh
 *   just-bash -c 'ls' --json
 * ```
 *
 * The Kotlin port uses [BashEnvironment] (backed by [InMemoryFs]) rather than
 * a real-filesystem OverlayFs. `--root` is accepted for CLI compatibility but
 * currently has no effect on the in-memory filesystem.
 */
object JustBashCli {

    private data class CliOptions(
        var script: String? = null,
        var scriptFile: String? = null,
        var errexit: Boolean = false,
        var json: Boolean = false,
        var help: Boolean = false,
        var version: Boolean = false,
        var shell: Boolean = false,
        var readWrite: Boolean = false,
        var root: String = ".",
        var files: Map<String, String> = emptyMap(),
    )

    private fun printHelp() {
        println("""just-bash-kotlin - A simulated bash environment for AI agents

Usage:
  just-bash-kotlin [options] [script-file]
  just-bash-kotlin -c 'script' [options]
  echo 'script' | just-bash-kotlin [options]

Options:
  -c <script>       Execute the script from command line argument
  -e, --errexit     Exit immediately if a command exits with non-zero status
  --json            Output results as JSON (stdout, stderr, exitCode)
  --shell           Start interactive REPL shell (reads real FS, writes in-memory)
  --readwrite        Use ReadWriteFs (writes to real disk) instead of OverlayFs
  --root <path>     Root directory (accepted for compat; InMemoryFs ignores it)
  --file <path>=<vpath>  Mount a real file/dir into the virtual filesystem
  -h, --help        Show this help message
  -v, --version     Show version

Arguments:
  script-file       Script file to execute (read from the real filesystem)

Examples:
  just-bash-kotlin -c 'ls'
  echo 'echo hello' | just-bash-kotlin
  just-bash-kotlin ./script.sh
  just-bash-kotlin -c 'echo hello' --json
""".trimIndent())
    }

    private fun printVersion() {
        println("just-bash-kotlin ${ShellMetadata.BASH_VERSION}")
    }

    private fun parseArgs(args: Array<String>): CliOptions {
        val options = CliOptions()
        val positionals = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--" -> { positionals.addAll(args.drop(i + 1)); break }
                arg == "-h" || arg == "--help" -> { options.help = true; i++ }
                arg == "-v" || arg == "--version" -> { options.version = true; i++ }
                arg == "-c" -> {
                    if (i + 1 >= args.size) {
                        System.err.println("Error: -c requires a script argument"); exit(1)
                    }
                    options.script = args[i + 1]; i += 2
                }
                arg == "-e" || arg == "--errexit" -> { options.errexit = true; i++ }
                arg == "--json" -> { options.json = true; i++ }
                arg == "--shell" -> { options.shell = true; i++ }
                arg == "--readwrite" -> { options.readWrite = true; i++ }
                arg == "--root" -> {
                    if (i + 1 >= args.size) {
                        System.err.println("Error: --root requires a path argument"); exit(1)
                    }
                    options.root = args[i + 1]; i += 2
                }
                arg == "--file" -> {
                    if (i + 1 >= args.size) {
                        System.err.println("Error: --file requires a path argument"); exit(1)
                    }
                    val spec = args[i + 1]
                    val eq = spec.indexOf('=')
                    if (eq <= 0) {
                        System.err.println("Error: --file expects REAL=PATH"); exit(1)
                    }
                    val realPath = spec.substring(0, eq)
                    val vpath = spec.substring(eq + 1)
                    val content = try {
                        java.nio.file.Files.readString(java.nio.file.Paths.get(realPath))
                    } catch (e: Exception) {
                        System.err.println("Error: cannot read file: $realPath"); exit(1)
                        "" // unreachable
                    }
                    options.files = options.files + (vpath to content)
                    i += 2
                }
                arg.startsWith("-") && arg != "-" -> {
                    // Combined short flags: -ec, -h, -v
                    val flags = arg.substring(1)
                    var j = 0
                    while (j < flags.length) {
                        when (flags[j]) {
                            'e' -> options.errexit = true
                            'h' -> options.help = true
                            'v' -> options.version = true
                            'c' -> {
                                if (j != flags.length - 1 || i + 1 >= args.size) {
                                    System.err.println("Error: -c must be last in combined flags"); exit(1)
                                }
                                options.script = args[i + 1]; i++
                                break
                            }
                            else -> { System.err.println("Error: Unknown option: -${flags[j]}"); exit(1) }
                        }
                        j++
                    }
                    i++
                }
                else -> { positionals.add(arg); i++ }
            }
        }

        if (options.script != null && positionals.isNotEmpty()) {
            System.err.println("Error: script file cannot be combined with -c"); exit(1)
        }
        if (positionals.size > 1) {
            System.err.println("Error: unexpected extra positional argument"); exit(1)
        }
        if (positionals.isNotEmpty()) {
            options.scriptFile = positionals[0]
        }
        return options
    }

    private fun run(args: Array<String>): Int {
        val options = parseArgs(args)

        if (options.help) { printHelp(); return 0 }
        if (options.version) { printVersion(); return 0 }

        // REPL / interactive shell
        if (options.shell) {
            VirtualShell(
                root = options.root,
                files = options.files,
                readWrite = options.readWrite,
            ).run()
            return 0
        }

        val script: String = when {
            options.script != null -> options.script!!
            options.scriptFile != null -> {
                try {
                    File(options.scriptFile!!).readText()
                } catch (e: Exception) {
                    System.err.println("Error: Cannot read script file: ${options.scriptFile}")
                    System.err.println(e.message ?: "")
                    return 1
                }
            }
            System.`in`.available() > 0 -> {
                System.`in`.bufferedReader().readText()
            }
            else -> {
                printHelp()
                return 1
            }
        }

        if (script.isBlank()) {
            if (options.json) println("""{"stdout":"","stderr":"","exitCode":0}""")
            return 0
        }

        val bash = BashEnvironment(initialFiles = options.files)
        val effectiveScript = if (options.errexit) "set -e\n$script" else script

        try {
            val result = kotlinx.coroutines.runBlocking { bash.exec(effectiveScript) }
            if (options.json) {
                // JSON-escape stdout/stderr
                val json = """{"stdout":${quote(result.stdout)},"stderr":${quote(result.stderr)},"exitCode":${result.exitCode}}"""
                println(json)
            } else {
                if (result.stdout.isNotEmpty()) print(result.stdout)
                if (result.stderr.isNotEmpty()) System.err.print(result.stderr)
            }
            return result.exitCode
        } catch (e: Exception) {
            val errMsg = e.message ?: e.toString()
            if (options.json) {
                println("""{"stdout":"","stderr":${quote(errMsg)},"exitCode":1}""")
            } else {
                System.err.println(errMsg)
            }
            return 1
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun exit(code: Int): Nothing = kotlin.system.exitProcess(code)

    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args)
        if (code != 0) kotlin.system.exitProcess(code)
    }
}
