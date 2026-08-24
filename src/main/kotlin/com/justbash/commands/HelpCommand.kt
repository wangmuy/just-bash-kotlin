package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * help command — display available commands or help for a specific command.
 *
 * Port of just-bash `commands/help/help.ts`.
 */
object HelpCommand : Command {
    override val name = "help"

    private val categories = linkedMapOf(
        "File operations" to listOf(
            "ls", "cat", "head", "tail", "wc", "touch", "mkdir", "rm", "cp", "mv", "ln", "chmod", "stat", "readlink",
        ),
        "Text processing" to listOf(
            "grep", "sed", "awk", "sort", "uniq", "tr", "tee", "diff",
        ),
        "Search" to listOf("find", "rg"),
        "Navigation & paths" to listOf("pwd", "basename", "dirname"),
        "Environment & shell" to listOf(
            "echo", "printf", "env", "printenv", "true", "false", "help",
        ),
        "Data processing" to listOf(
            "xargs", "jq", "base64", "date", "yq", "xan",
        ),
        "Network" to listOf("curl"),
        "Archives" to listOf("tar", "gzip", "gunzip", "zcat"),
        "Misc" to listOf("expr", "seq", "sleep", "timeout", "readlink", "md5sum", "sha1sum", "sha256sum"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (args.contains("--help") || args.contains("-h")) {
            return ExecResult(
                stdout = """help - display available commands

Usage: help [command]

Options:
  -h, --help    Show this help message

If a command name is provided, shows help for that command.
Otherwise, lists all available commands.
""",
                exitCode = 0,
            )
        }

        // If a command name is provided, delegate to that command's --help
        if (args.isNotEmpty() && ctx.exec != null) {
            val cmdName = args[0]
            return ctx.exec!!(
                cmdName,
                com.justbash.CommandExecOptions(
                    cwd = ctx.cwd,
                    args = listOf("--help"),
                ),
            )
        }

        // List all available commands (grouped by category)
        val commandSet = LinkedHashSet<String>()
        // The interpreter doesn't currently expose getRegisteredCommands() in CommandContext,
        // so we build a reasonable list from the known categories.
        for (cmds in categories.values) {
            commandSet.addAll(cmds)
        }

        val sb = StringBuilder()
        sb.append("Available commands:\n\n")

        for ((category, cmds) in categories) {
            val available = cmds.filter { it in commandSet }
            if (available.isNotEmpty()) {
                sb.append("  $category:\n")
                sb.append("    ${available.joinToString(", ")}\n\n")
            }
        }

        sb.append("Use '<command> --help' for details on a specific command.\n")
        sb.append("\n")
        return ExecResult(stdout = sb.toString(), exitCode = 0)
    }
}