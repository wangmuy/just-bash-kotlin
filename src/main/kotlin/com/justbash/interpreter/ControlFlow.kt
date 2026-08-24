package com.justbash.interpreter

import com.justbash.ExecResult
import com.justbash.ast.*

object ControlFlow {

    private suspend fun executeStatements(ctx: InterpreterContext, statements: List<StatementNode>): ExecResult {
        var stdout = ""
        var stderr = ""
        var exitCode = 0
        for (stmt in statements) {
            val r = ctx.executeStatement(stmt)
            stdout += r.stdout
            stderr += r.stderr
            exitCode = r.exitCode
        }
        return ExecResult(stdout, stderr, exitCode)
    }

    suspend fun executeIf(ctx: InterpreterContext, node: IfNode): ExecResult {
        var stdout = ""
        var stderr = ""
        for (clause in node.clauses) {
            val savedInCondition = ctx.state.inCondition
            ctx.state.inCondition = true
            val condResult = try { executeStatements(ctx, clause.condition) } finally { ctx.state.inCondition = savedInCondition }
            stdout += condResult.stdout
            stderr += condResult.stderr
            if (condResult.exitCode == 0) {
                val body = executeStatements(ctx, clause.body)
                return ExecResult(stdout + body.stdout, stderr + body.stderr, body.exitCode)
            }
        }
        node.elseBody?.let {
            val body = executeStatements(ctx, it)
            return ExecResult(stdout + body.stdout, stderr + body.stderr, body.exitCode)
        }
        return ExecResult(stdout, stderr, 0)
    }

    suspend fun executeFor(ctx: InterpreterContext, node: ForNode): ExecResult {
        var stdout = ""
        var stderr = ""
        var exitCode = 0
        var iterations = 0L
        if (!node.variable.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
            return Result.failure("bash: `${node.variable}': not a valid identifier\n")
        }
        val words: List<String> = when {
            node.words == null -> (ctx.state.env["@"] ?: "").split(' ').filter { it.isNotEmpty() }
            node.words!!.isEmpty() -> emptyList()
            else -> node.words!!.flatMap { w -> ctx.wordExpander.expandWordWithGlob(w) }
        }
        ctx.state.loopDepth++
        try {
            outer@ for (value in words) {
                iterations++
                if (iterations > ctx.limits.maxLoopIterations) {
                    throwExecutionLimit("for loop: too many iterations (${ctx.limits.maxLoopIterations})", "iterations")
                }
                ctx.state.env[node.variable] = value
                try {
                    for (stmt in node.body) {
                        val r = ctx.executeStatement(stmt)
                        stdout += r.stdout; stderr += r.stderr; exitCode = r.exitCode
                    }
                } catch (e: Throwable) {
                    val a = LoopAction.of(e, stdout, stderr, ctx.state.loopDepth)
                    stdout = a.stdout; stderr = a.stderr
                    when (a.action) {
                        "break" -> break@outer
                        "continue" -> continue@outer
                        "error" -> return ExecResult(stdout, stderr, a.exitCode)
                        "rethrow" -> throw a.error!!
                    }
                }
            }
        } finally { ctx.state.loopDepth-- }
        return ExecResult(stdout, stderr, exitCode)
    }

    suspend fun executeWhile(ctx: InterpreterContext, node: WhileNode, stdin: ByteArray = ByteArray(0)): ExecResult =
        executeWhileUntil(ctx, node.condition, node.body, until = false)

    suspend fun executeUntil(ctx: InterpreterContext, node: UntilNode, stdin: ByteArray = ByteArray(0)): ExecResult =
        executeWhileUntil(ctx, node.condition, node.body, until = true)

    private suspend fun executeWhileUntil(ctx: InterpreterContext, condition: List<StatementNode>, body: List<StatementNode>, until: Boolean): ExecResult {
        var stdout = ""
        var stderr = ""
        var exitCode = 0
        var iterations = 0L
        ctx.state.loopDepth++
        try {
            outer@ while (true) {
                iterations++
                if (iterations > ctx.limits.maxLoopIterations) {
                    throwExecutionLimit("while loop: too many iterations (${ctx.limits.maxLoopIterations})", "iterations")
                }
                val savedInCondition = ctx.state.inCondition
                ctx.state.inCondition = true
                var condExit = 0
                var shouldBreak = false
                var shouldContinue = false
                try {
                    for (stmt in condition) {
                        val r = ctx.executeStatement(stmt)
                        stdout += r.stdout; stderr += r.stderr; condExit = r.exitCode
                    }
                } catch (e: BreakError) {
                    stdout += e.stdout; stderr += e.stderr
                    if (e.levels > 1 && ctx.state.loopDepth > 1) { e.levels--; e.stdout = stdout; e.stderr = stderr; ctx.state.inCondition = savedInCondition; throw e }
                    shouldBreak = true
                } catch (e: ContinueError) {
                    stdout += e.stdout; stderr += e.stderr
                    if (e.levels > 1 && ctx.state.loopDepth > 1) { e.levels--; e.stdout = stdout; e.stderr = stderr; ctx.state.inCondition = savedInCondition; throw e }
                    shouldContinue = true
                } finally { ctx.state.inCondition = savedInCondition }
                if (shouldBreak) break@outer
                if (shouldContinue) continue@outer
                val conditionTrue = condExit == 0
                if (if (until) conditionTrue else !conditionTrue) break@outer
                try {
                    for (stmt in body) {
                        val r = ctx.executeStatement(stmt)
                        stdout += r.stdout; stderr += r.stderr; exitCode = r.exitCode
                    }
                } catch (e: Throwable) {
                    val a = LoopAction.of(e, stdout, stderr, ctx.state.loopDepth)
                    stdout = a.stdout; stderr = a.stderr
                    when (a.action) {
                        "break" -> break@outer
                        "continue" -> continue@outer
                        "error" -> return ExecResult(stdout, stderr, a.exitCode)
                        "rethrow" -> throw a.error!!
                    }
                }
            }
        } finally { ctx.state.loopDepth-- }
        return ExecResult(stdout, stderr, exitCode)
    }

    suspend fun executeCase(ctx: InterpreterContext, node: CaseNode): ExecResult {
        var stdout = ""
        var stderr = ""
        var exitCode = 0
        val value = ctx.wordExpander.expandWord(node.word)
        var fallThrough = false
        for (item in node.items) {
            var matched = fallThrough
            if (!fallThrough) {
                for (pattern in item.patterns) {
                    val patternStr = ctx.wordExpander.expandWord(pattern)
                    if (simpleGlobMatch(value, patternStr, ctx.state.shoptOptions.nocasematch)) { matched = true; break }
                }
            }
            if (matched) {
                val bodyResult = executeStatements(ctx, item.body)
                stdout = bodyResult.stdout; stderr = bodyResult.stderr; exitCode = bodyResult.exitCode
                when (item.terminator) {
                    ";;" -> break
                    ";&" -> fallThrough = true
                    else -> fallThrough = false
                }
            } else fallThrough = false
        }
        return ExecResult(stdout, stderr, exitCode)
    }

    private fun simpleGlobMatch(value: String, pattern: String, nocase: Boolean): Boolean {
        val v = if (nocase) value.lowercase() else value
        val p = if (nocase) pattern.lowercase() else pattern
        val regex = StringBuilder("^")
        for (c in p) when (c) {
            '*' -> regex.append(".*")
            '?' -> regex.append('.')
            '[', ']', '\\', '^', '$', '.', '|', '(', ')', '+', '{', '}' -> regex.append('\\').append(c)
            else -> regex.append(c)
        }
        regex.append("$")
        return Regex(regex.toString()).matches(v)
    }
}

class LoopAction(val stdout: String, val stderr: String, val action: String, val exitCode: Int, val error: Throwable?) {
    companion object {
        fun of(error: Throwable, stdout: String, stderr: String, loopDepth: Int): LoopAction {
            return when (error) {
                is BreakError -> {
                    val so = stdout + error.stdout; val se = stderr + error.stderr
                    if (error.levels > 1 && loopDepth > 1) {
                        error.levels--; error.stdout = so; error.stderr = se
                        LoopAction(so, se, "rethrow", 1, error)
                    } else LoopAction(so, se, "break", 0, null)
                }
                is ContinueError -> {
                    val so = stdout + error.stdout; val se = stderr + error.stderr
                    if (error.levels > 1 && loopDepth > 1) {
                        error.levels--; error.stdout = so; error.stderr = se
                        LoopAction(so, se, "rethrow", 1, error)
                    } else LoopAction(so, se, "continue", 0, null)
                }
                is ReturnError, is ErrexitError, is ExitError, is ExecutionLimitError -> {
                    (error as ControlFlowError).stdout = stdout + (error as ControlFlowError).stdout
                    (error as ControlFlowError).stderr = stderr + (error as ControlFlowError).stderr
                    LoopAction(stdout, stderr, "rethrow", 1, error)
                }
                else -> LoopAction(stdout, "$stderr${error.message ?: "Error"}\n", "error", 1, null)
            }
        }
    }
}
