package com.justbash

import com.justbash.ast.ScriptNode
import com.justbash.commands.Registry
import com.justbash.fs.FsInit
import com.justbash.fs.IFileSystem
import com.justbash.fs.InMemoryFs
import com.justbash.interpreter.Interpreter
import com.justbash.interpreter.InterpreterState
import com.justbash.network.NetworkConfig
import com.justbash.network.SecureFetch
import com.justbash.network.createSecureFetch
import com.justbash.parser.parse

/**
 * Top-level Bash environment: parse → interpreter → commands.
 *
 * Mirrors the public API of just-bash `Bash.ts` in a simplified, synchronous
 * Kotlin form. Wraps an [InMemoryFs], [Interpreter], and the full command
 * registry.
 *
 * Usage:
 * ```
 * val bash = BashEnvironment()
 * val result = bash.exec("echo hello")
 * assertEquals("hello\n", result.stdout)
 * ```
 */
class BashEnvironment(
    /** Initial files to populate the virtual filesystem. */
    initialFiles: Map<String, Any> = emptyMap(),
    /** Initial environment variables. */
    env: Map<String, String> = emptyMap(),
    /** Initial working directory. */
    cwd: String = "/home/user",
    /** Execution limits. */
    limits: ExecutionLimits = ExecutionLimits(),
    /** Custom commands to register (overrides built-ins with the same name). */
    customCommands: List<Command> = emptyList(),
    /** Network configuration for commands like curl. Network disabled by default. */
    network: NetworkConfig? = null,
    /** Filesystem to use (defaults to InMemoryFs). */
    fsOverride: IFileSystem? = null,
) {
    val fs: IFileSystem = fsOverride ?: InMemoryFs(initialFiles)
    val commands: CommandRegistry = LinkedHashMap()
    val interpreter: Interpreter
    val state: InterpreterState
    /** Secure fetch function, if network is configured. */
    var secureFetch: (suspend (String, com.justbash.network.SecureFetchOptions) -> com.justbash.network.FetchResult)? = null

    init {
        val useDefaultLayout = cwd == "/home/user"
        // Only initialize synthetic filesystem structure for InMemoryFs
        if (fs is InMemoryFs) {
            FsInit.initFilesystem(fs, useDefaultLayout)
        }
        fs.mkdir(cwd, com.justbash.fs.MkdirOptions(recursive = true))

        // Register all core commands
        for (cmd in Registry.createCoreCommands()) {
            commands[cmd.name] = cmd
            val stub = "#!/bin/bash\n# Built-in command: ${cmd.name}\n"
            fs.writeFile("/bin/${cmd.name}", stub)
            fs.writeFile("/usr/bin/${cmd.name}", stub)
        }

        // Register custom commands (override built-ins with same name)
        for (cmd in customCommands) {
            commands[cmd.name] = cmd
            val stub = "#!/bin/bash\n# Built-in command: ${cmd.name}\n"
            try { fs.writeFile("/bin/${cmd.name}", stub) } catch (_: Exception) {}
            try { fs.writeFile("/usr/bin/${cmd.name}", stub) } catch (_: Exception) {}
        }

        // Create secure fetch if network is configured
        if (network != null) {
            (this as BashEnvironment).secureFetch = createSecureFetch(network!!)
        }

        state = InterpreterState(
            env = LinkedHashMap<String, String>().also { m ->
                m["HOME"] = if (useDefaultLayout) "/home/user" else "/"
                m["PATH"] = "/usr/bin:/bin"
                m["IFS"] = " \t\n"
                m["OSTYPE"] = "linux-gnu"
                m["MACHTYPE"] = "x86_64-pc-linux-gnu"
                m["HOSTTYPE"] = "x86_64"
                m["HOSTNAME"] = "localhost"
                m["PWD"] = cwd
                m["OLDPWD"] = cwd
                m["OPTIND"] = "1"
                m["?"] = "0"
                m.let { it as MutableMap<String, String> }
                for ((key, value) in env) { m[key] = value }
            },
            cwd = cwd,
            previousDir = cwd,
        )

        interpreter = Interpreter(
            fs = fs,
            commands = commands,
            state = state,
            limits = limits,
            wordExpander = null, // Use ExpansionPlaceholder (simple $VAR expansion)
            echoCommand = commands["echo"],
        )
    }

    /**
     * Parse and execute a bash script, returning the accumulated output.
     */
    suspend fun exec(commandLine: String): BashExecResult {
        return interpreter.exec(commandLine)
    }

    /**
     * Execute a pre-parsed AST.
     */
    suspend fun executeScript(ast: ScriptNode): BashExecResult {
        return interpreter.executeScript(ast)
    }

    /**
     * Parse only (no execution).
     */
    fun parse(source: String): ScriptNode {
        return parse(source)
    }

    /**
     * Read a file from the virtual filesystem.
     */
    fun readFile(path: String): String {
        return fs.readFile(fs.resolvePath(state.cwd, path))
    }

    /**
     * Write a file to the virtual filesystem.
     */
    fun writeFile(path: String, content: String) {
        fs.writeFile(fs.resolvePath(state.cwd, path), content)
    }

    /** Current working directory. */
    fun getCwd(): String = state.cwd

    /** Current environment as an immutable map. */
    fun getEnv(): Map<String, String> = LinkedHashMap(state.env)

    /**
     * Register a custom command, overriding any built-in with the same name.
     */
    fun registerCommand(command: Command) {
        commands[command.name] = command
    }
}