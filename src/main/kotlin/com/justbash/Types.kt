package com.justbash

import com.justbash.fs.IFileSystem
import java.time.Instant

/**
 * Core data model: execution results, command interface, and command context.
 *
 * Port of just-bash `src/types.ts`. Uses Kotlin coroutines to match the
 * original TypeScript `Promise<ExecResult>` async model.
 */

/** Result of executing a command or script. */
data class ExecResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    /** Final environment variables after execution. */
    val env: Map<String, String> = emptyMap(),
    /** Explicit output shape metadata ("text" or "bytes"). */
    val stdoutKind: String? = null,
)

/** Result from Bash.exec() — always includes env. */
data class BashExecResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val env: Map<String, String> = emptyMap(),
    val stdoutKind: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
) {
    fun toExecResult(): ExecResult = ExecResult(stdout, stderr, exitCode, env, stdoutKind)
}

/**
 * Context provided to commands during execution.
 */
class CommandContext(
    /** Virtual filesystem interface for file operations. */
    val fs: IFileSystem,
    /** Current working directory. */
    val cwd: String,
    /** Environment variables. */
    val env: MutableMap<String, String>,
    /** Standard input as raw bytes. */
    var stdin: ByteArray = ByteArray(0),
    /** Execute a subcommand (e.g. for `xargs`, `bash -c`, `timeout`). */
    var exec: (suspend (String, CommandExecOptions) -> ExecResult)? = null,
    /** Whether xpg_echo shopt is enabled. */
    var xpgEcho: Boolean = false,
    /** Current command substitution nesting depth. */
    var substitutionDepth: Int = 0,
    /** Execution limits (fully resolved). */
    var limits: ExecutionLimits = ExecutionLimits(),
)

/** Options for exec calls within commands. */
data class CommandExecOptions(
    val env: Map<String, String> = emptyMap(),
    val replaceEnv: Boolean = false,
    val cwd: String,
    val stdin: String? = null,
    val args: List<String>? = null,
)

/** A registered command. */
interface Command {
    val name: String
    suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult
}

/** Registry of commands, keyed by name. */
typealias CommandRegistry = MutableMap<String, Command>

/**
 * Execution limits (subset relevant to the core port). The TypeScript original
 * has many more fields; we carry the ones that matter for bounded execution.
 */
data class ExecutionLimits(
    val maxCommandCount: Long = 100000,
    val maxCallDepth: Int = 200,
    val maxLoopIterations: Long = 1000000,
    val maxStringLength: Long = 64L * 1024 * 1024,
    val maxSourceBytes: Long = 8L * 1024 * 1024,
    val maxInputBytes: Long = 64L * 1024 * 1024,
    val maxFileSystemBytes: Long = 1024L * 1024 * 1024,
    val maxHeredocSize: Long = 1024 * 1024,
)
