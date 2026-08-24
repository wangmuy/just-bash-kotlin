package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.CommandExecOptions
import com.justbash.ExecResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * timeout command — run a command with a time limit.
 *
 * Port of just-bash `timeout/timeout.ts`. Uses Kotlin coroutine `withTimeout`
 * to match the original TypeScript `AbortController`/`AbortSignal` semantics:
 * when the timeout fires, the coroutine is cancelled cooperatively, and the
 * command is stopped at the next suspension point (or at `ensureActive()`/
 * `yield()` checkpoints inside the interpreter).
 *
 * DURATION is a number with optional suffix: s (seconds, default), m (minutes),
 * h (hours), d (days). Fractional seconds are accepted.
 */
object TimeoutCommand : Command {
    override val name = "timeout"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        // ... (option parsing is unchanged, same as before) ...
        if (hasHelpFlag(args)) {
            return showHelp(
                "timeout", "run a command with a time limit",
                "timeout [OPTION] DURATION COMMAND [ARG]...",
                listOf(
                    "-k, --kill-after=DURATION  send KILL signal after DURATION (accepted, ignored)",
                    "-s, --signal=SIGNAL        specify signal to send (accepted, ignored)",
                    "    --preserve-status      exit with same status as COMMAND (accepted, ignored)",
                    "    --help                 display this help and exit",
                ),
            )
        }

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--preserve-status" || arg == "--foreground" -> i++
                arg == "-k" || arg == "--kill-after" || arg == "-s" || arg == "--signal" -> i += 2
                arg.startsWith("--kill-after=") || arg.startsWith("--signal=") -> i++
                arg.startsWith("--") && arg != "--" -> return unknownOption("timeout", arg)
                arg.startsWith("-") && arg.length > 1 && arg != "-" && !arg.startsWith("-s") && !arg.startsWith("-k") ->
                    return unknownOption("timeout", arg)
                else -> break
            }
        }

        val remaining = args.drop(i)
        if (remaining.isEmpty()) {
            return ExecResult(stderr = "timeout: missing operand\n", exitCode = 1)
        }

        val durationStr = remaining[0]
        val durationMs = parseDuration(durationStr)
        if (durationMs == null) {
            return ExecResult(stderr = "timeout: invalid time interval '$durationStr'\n", exitCode = 1)
        }

        val commandArgs = remaining.drop(1)
        if (commandArgs.isEmpty()) {
            return ExecResult(stderr = "timeout: missing operand\n", exitCode = 1)
        }

        if (ctx.exec == null) {
            return ExecResult(stderr = "timeout: exec not available\n", exitCode = 1)
        }

        val commandName = commandArgs[0]
        val restArgs = commandArgs.drop(1)
        val joined = if (restArgs.isEmpty()) commandName else commandArgs.joinToString(" ")

        // Use kotlinx.coroutines `withTimeout` for true cooperative cancellation,
        // matching the original just-bash `AbortController`/`AbortSignal` semantics.
        return try {
            withTimeout(durationMs) {
                ctx.exec!!(
                    joined,
                    CommandExecOptions(cwd = ctx.cwd, args = restArgs),
                )
            }
        } catch (e: TimeoutCancellationException) {
            ExecResult(
                stdout = "",
                stderr = "",
                exitCode = 124, // timeout exit code
            )
        } catch (e: Exception) {
            ExecResult(stderr = "timeout: ${e.message ?: "error"}\n", exitCode = 1)
        }
    }

    private fun parseDuration(str: String): Long? {
        val match = Regex("^(\\d+(?:\\.\\d+)?)([smhd]?)$").matchEntire(str.trim()) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].ifEmpty { "s" }
        val multiplier = when (unit) {
            "s" -> 1000.0
            "m" -> 60_000.0
            "h" -> 3_600_000.0
            "d" -> 86_400_000.0
            else -> 1000.0
        }
        return (value * multiplier).toLong()
    }
}