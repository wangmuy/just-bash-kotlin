package com.justbash.interpreter

import com.justbash.ExecResult
import com.justbash.ast.CommandNode
import com.justbash.ast.PipelineNode

object PipelineExecution {
    suspend fun executePipeline(ctx: InterpreterContext, node: PipelineNode, executeCommand: suspend (CommandNode, ByteArray) -> ExecResult): ExecResult {
        var stdin = ByteArray(0)
        var lastResult: ExecResult = Result.ok()
        var pipefailExitCode = 0
        val pipestatusExitCodes = ArrayList<Int>()
        var accumulatedStderr = ""
        val isMultiCommand = node.commands.size > 1
        val savedLastArg = ctx.state.lastArg

        for (i in node.commands.indices) {
            val command = node.commands[i]
            val isLast = i == node.commands.size - 1
            val runsInSubshell = isMultiCommand && (!isLast || !ctx.state.shoptOptions.lastpipe)
            val savedEnv = if (runsInSubshell) LinkedHashMap(ctx.state.env) else null
            val savedArrays = if (runsInSubshell) ctx.state.arrays?.let { LinkedHashMap(it) } else null

            ctx.state.commandCount++

            var result: ExecResult = try {
                executeCommand(command, stdin)
            } catch (e: Throwable) {
                if (savedEnv != null) {
                    ctx.state.env.clear(); ctx.state.env.putAll(savedEnv); ctx.state.arrays = savedArrays
                }
                when (e) {
                    is ExitError -> if (node.commands.size > 1) ExecResult(e.stdout, e.stderr, e.exitCode) else throw e
                    is ErrexitError -> if (node.commands.size > 1) ExecResult(e.stdout, e.stderr, e.exitCode) else throw e
                    is BadSubstitutionError -> ExecResult(e.stdout, e.stderr, 1)
                    else -> throw e
                }
            }

            if (savedEnv != null) {
                ctx.state.env.clear(); ctx.state.env.putAll(savedEnv); ctx.state.arrays = savedArrays
            }

            pipestatusExitCodes.add(result.exitCode)
            if (result.exitCode != 0) pipefailExitCode = result.exitCode

            if (!isLast) {
                stdin = stdoutToStdin(result)
                accumulatedStderr += result.stderr
                lastResult = ExecResult("", "", result.exitCode)
            } else lastResult = result
        }

        if (accumulatedStderr.isNotEmpty()) {
            lastResult = ExecResult(lastResult.stdout, accumulatedStderr + lastResult.stderr, lastResult.exitCode)
        }

        if (node.commands.size > 1) {
            Arrays.clearArray(ctx, "PIPESTATUS")
            for (i in pipestatusExitCodes.indices) Arrays.setArrayElement(ctx, "PIPESTATUS", i, pipestatusExitCodes[i].toString())
        }

        if (ctx.state.options.pipefail && pipefailExitCode != 0) {
            lastResult = ExecResult(lastResult.stdout, lastResult.stderr, pipefailExitCode)
        }

        if (node.negated) {
            lastResult = ExecResult(lastResult.stdout, lastResult.stderr, if (lastResult.exitCode == 0) 1 else 0)
        }

        if (isMultiCommand && !ctx.state.shoptOptions.lastpipe) ctx.state.lastArg = savedLastArg

        return lastResult
    }
}

/**
 * Convert a command's stdout to bytes for the next command in a pipeline.
 * - `stdoutKind == "bytes"`: each char is a latin1-encoded byte (char.code = byte value)
 * - `stdoutKind == "text"` (default): UTF-8 encode the string
 */
internal fun stdoutToStdin(result: ExecResult): ByteArray {
    return if (result.stdoutKind == "bytes" || result.stdoutKind == "binary") {
        ByteArray(result.stdout.length) { i -> result.stdout[i].code.toByte() }
    } else {
        result.stdout.toByteArray(Charsets.UTF_8)
    }
}
