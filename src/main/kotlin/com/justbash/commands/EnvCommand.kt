package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.CommandExecOptions
import com.justbash.ExecResult

/**
 * Port of just-bash `env/env.ts` (env and printenv).
 */
object EnvCommand : Command {
    override val name = "env"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "env", "run a program in a modified environment",
                "env [OPTION]... [NAME=VALUE]... [COMMAND [ARG]...]",
                listOf(
                    "-i, --ignore-environment  start with an empty environment",
                    "-u NAME, --unset=NAME     remove NAME from the environment",
                    "    --help                display this help and exit",
                ),
            )
        }

        var ignoreEnv = false
        val unsetVars = ArrayList<String>()
        val setVars = LinkedHashMap<String, String>()
        var commandStart = -1

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-i" || arg == "--ignore-environment" -> ignoreEnv = true
                arg == "-u" && i + 1 < args.size -> unsetVars.add(args[++i])
                arg.startsWith("-u") -> unsetVars.add(arg.substring(2))
                arg.startsWith("--unset=") -> unsetVars.add(arg.substring(8))
                arg.startsWith("--") && arg != "--" -> return unknownOption("env", arg)
                arg.startsWith("-") && arg != "-" -> {
                    for (c in arg.substring(1)) {
                        if (c != 'i' && c != 'u') {
                            return unknownOption("env", "-$c")
                        }
                    }
                    if (arg.contains('i')) ignoreEnv = true
                }
                arg.contains("=") && commandStart == -1 -> {
                    val eqIdx = arg.indexOf('=')
                    setVars[arg.substring(0, eqIdx)] = arg.substring(eqIdx + 1)
                }
                else -> {
                    commandStart = i
                    break
                }
            }
            i++
        }

        val newEnv = LinkedHashMap<String, String>()
        if (ignoreEnv) {
            newEnv.putAll(setVars)
        } else {
            newEnv.putAll(ctx.env)
            for (name in unsetVars) newEnv.remove(name)
            newEnv.putAll(setVars)
        }

        if (commandStart == -1) {
            val lines = newEnv.map { (k, v) -> "$k=$v" }
            return ExecResult(
                stdout = lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else "",
                stderr = "",
                exitCode = 0,
            )
        }

        val exec = ctx.exec
        if (exec == null) {
            return ExecResult(stdout = "", stderr = "env: command execution not supported in this context\n", exitCode = 1)
        }

        val cmdArgs = args.subList(commandStart, args.size)
        return exec(
            "command",
            CommandExecOptions(
                cwd = ctx.cwd,
                env = newEnv,
                replaceEnv = true,
                stdin = ctx.stdin.toString(Charsets.UTF_8),
                args = cmdArgs,
            ),
        )
    }
}

object PrintenvCommand : Command {
    override val name = "printenv"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "printenv", "print all or part of environment",
                "printenv [OPTION]... [VARIABLE]...",
                listOf("    --help       display this help and exit"),
            )
        }

        val vars = args.filter { !it.startsWith("-") }

        if (vars.isEmpty()) {
            val lines = ctx.env.map { (k, v) -> "$k=$v" }
            return ExecResult(
                stdout = lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else "",
                stderr = "",
                exitCode = 0,
            )
        }

        val lines = ArrayList<String>()
        var exitCode = 0
        for (varName in vars) {
            val value = ctx.env[varName]
            if (value != null) lines.add(value) else exitCode = 1
        }

        return ExecResult(
            stdout = lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else "",
            stderr = "",
            exitCode = exitCode,
        )
    }
}
