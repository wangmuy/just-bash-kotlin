package com.justbash.interpreter

import com.justbash.*
import com.justbash.ast.*
import com.justbash.fs.IFileSystem
import com.justbash.parser.parse

class Interpreter(
    val ctx: InterpreterContext,
    private val echoCommand: Command? = null,
) {
    constructor(
        fs: IFileSystem,
        commands: CommandRegistry,
        state: InterpreterState = InterpreterState(),
        limits: ExecutionLimits = ExecutionLimits(),
        wordExpander: WordExpander? = null,
        echoCommand: Command? = null,
    ) : this(InterpreterContext(state, fs, commands, limits, wordExpander ?: ExpansionPlaceholder(state.env)), echoCommand) {
        ctx.executeStatement = { this.executeStatement(it) }
        ctx.executeCommand = { cmd, stdin, owned -> this.executeCommand(cmd, stdin) }
        ctx.executeScript = { val r = this.executeScript(it); ExecResult(r.stdout, r.stderr, r.exitCode, r.env, r.stdoutKind) }
        ctx.execString = { this.execString(it) }
        ctx.findCommandInPath = { CommandResolution.findCommandInPath(ctx, it) }
        ctx.buildExportedEnv = { this.buildExportedEnv() }
        ctx.executeUserScript = { path, args, stdin -> this.executeUserScript(path, args, stdin) }
        // If no explicit expander was provided, wire the FULL expansion engine
        // (bridge to com.justbash.interpreter.expansion) so `${var:-}`, brace
        // expansion, pattern removal, etc. all work end-to-end.
        if (wordExpander == null) {
            ctx.wordExpander = ExpansionBridge(ctx, fs)
        }
    }

    suspend fun executeScript(node: ScriptNode): BashExecResult {
        var exitCode = 0
        var stdout = ""
        var stderr = ""
        for (statement in node.statements) {
            try {
                val result = executeStatement(statement)
                stdout += result.stdout; stderr += result.stderr; exitCode = result.exitCode
                ctx.state.lastExitCode = exitCode; ctx.state.env["?"] = exitCode.toString()
            } catch (e: ExitError) {
                stdout += e.stdout; stderr += e.stderr; exitCode = e.exitCode
                ctx.state.lastExitCode = exitCode; ctx.state.env["?"] = exitCode.toString()
                return BashExecResult(stdout, stderr, exitCode, env = buildEnvRecord(), metadata = emptyMap())
            } catch (e: ErrexitError) {
                stdout += e.stdout; stderr += e.stderr; exitCode = e.exitCode
                ctx.state.lastExitCode = exitCode; ctx.state.env["?"] = exitCode.toString()
                return BashExecResult(stdout, stderr, exitCode, env = buildEnvRecord(), metadata = emptyMap())
            } catch (e: NounsetError) {
                stdout += e.stdout; stderr += e.stderr; exitCode = 1
                ctx.state.lastExitCode = exitCode; ctx.state.env["?"] = exitCode.toString()
                return BashExecResult(stdout, stderr, exitCode, env = buildEnvRecord(), metadata = emptyMap())
            } catch (e: BreakError) {
                if (ctx.state.loopDepth > 0) { e.stdout = stdout + e.stdout; e.stderr = stderr + e.stderr; throw e }
                stdout += e.stdout; stderr += e.stderr; continue
            } catch (e: ContinueError) {
                if (ctx.state.loopDepth > 0) { e.stdout = stdout + e.stdout; e.stderr = stderr + e.stderr; throw e }
                stdout += e.stdout; stderr += e.stderr; continue
            } catch (e: ReturnError) { e.stdout = stdout + e.stdout; e.stderr = stderr + e.stderr; throw e }
            catch (e: ArithmeticError) { stderr += e.stderr; exitCode = 1; ctx.state.lastExitCode = exitCode; ctx.state.env["?"] = exitCode.toString(); continue }
            catch (e: ExecutionLimitError) { throw e }
        }
        return BashExecResult(stdout, stderr, exitCode, env = buildEnvRecord(), metadata = emptyMap())
    }

    suspend fun exec(commandLine: String): BashExecResult {
        val ast = parse(commandLine)
        return executeScript(ast)
    }

    internal suspend fun execString(source: String): ExecResult {
        val ast = parse(source)
        val result = executeScript(ast)
        return ExecResult(result.stdout, result.stderr, result.exitCode, result.env, result.stdoutKind)
    }

    private suspend fun executeStatement(node: StatementNode): ExecResult {
        if (ctx.state.options.noexec) return Result.ok()
        ctx.state.errexitSafe = false
        var stdout = ""; var stderr = ""; var exitCode = 0
        var lastExecutedIndex = -1; var lastPipelineNegated = false

        for (i in node.pipelines.indices) {
            val pipeline = node.pipelines[i]
            val operator = if (i > 0) node.operators.getOrNull(i - 1) else null
            if (operator == "&&" && exitCode != 0) continue
            if (operator == "||" && exitCode == 0) continue
            val result = executePipeline(pipeline)
            stdout += result.stdout; stderr += result.stderr; exitCode = result.exitCode
            lastExecutedIndex = i; lastPipelineNegated = pipeline.negated
            ctx.state.lastExitCode = exitCode; ctx.state.env["?"] = exitCode.toString()
        }

        val wasShortCircuited = lastExecutedIndex < node.pipelines.size - 1
        val innerWasSafe = ctx.state.errexitSafe
        ctx.state.errexitSafe = wasShortCircuited || lastPipelineNegated || innerWasSafe

        if (ctx.state.options.errexit && exitCode != 0 && lastExecutedIndex == node.pipelines.size - 1 && !lastPipelineNegated && !ctx.state.inCondition && !innerWasSafe) {
            throw ErrexitError(exitCode, stdout, stderr)
        }
        return ExecResult(stdout, stderr, exitCode)
    }

    private suspend fun executePipeline(node: PipelineNode): ExecResult =
        PipelineExecution.executePipeline(ctx, node) { cmd, stdin -> executeCommand(cmd, stdin) }

    private suspend fun executeCommand(node: CommandNode, stdin: ByteArray): ExecResult = when (node) {
        is SimpleCommandNode -> executeSimpleCommand(node, stdin)
        is IfNode -> ControlFlow.executeIf(ctx, node)
        is ForNode -> ControlFlow.executeFor(ctx, node)
        is WhileNode -> ControlFlow.executeWhile(ctx, node, stdin)
        is UntilNode -> ControlFlow.executeUntil(ctx, node, stdin)
        is CaseNode -> ControlFlow.executeCase(ctx, node)
        is SubshellNode -> executeSubshell(node, stdin)
        is GroupNode -> executeGroup(node, stdin)
        is FunctionDefNode -> Functions.executeFunctionDef(ctx, node)
        is ArithmeticCommandNode -> executeArithmeticCommand(node)
        is ConditionalCommandNode -> executeConditionalCommand(node)
        else -> throw IllegalArgumentException("Unsupported command node: ${node::class.simpleName}")
    }

    private suspend fun executeSimpleCommand(node: SimpleCommandNode, stdin: ByteArray): ExecResult {
        try { return executeSimpleCommandInner(node, stdin) }
        catch (e: GlobError) { return Result.failure(e.stderr) }
    }

    private suspend fun executeSimpleCommandInner(node: SimpleCommandNode, stdin: ByteArray): ExecResult {
        if (node.line != 0) ctx.state.currentLine = node.line
        val nameNode = node.name
        val tempAssignments = LinkedHashMap<String, String?>()
        for (assignment in node.assignments) {
            val name = assignment.name
            val valueNode = assignment.value
            val value = if (valueNode != null) ctx.wordExpander.expandWord(valueNode) else ""
            if (nameNode != null) { tempAssignments[name] = ctx.state.env[name]; ctx.state.env[name] = value }
            else ctx.state.env[name] = value
        }
        if (nameNode == null) {
            if (node.redirections.isNotEmpty()) {
                val txn = RedirectionTransaction(ctx, node.redirections, RedirectionPolicy.Bare)
                val prepared = txn.prepare(stdin); txn.finish()
                if (prepared.error != null) return prepared.error!!
                return Result.ok()
            }
            return Result.ok()
        }
        val commandName = ctx.wordExpander.expandWord(nameNode)
        val args = node.args.map { ctx.wordExpander.expandWord(it) }
        if (commandName.isEmpty()) {
            if (args.isNotEmpty()) return runCommand(args[0], args.drop(1), stdin)
            return Result.failure("bash: : command not found\n", 127)
        }
        if (commandName == "exec" && args.isEmpty()) {
            val txn = RedirectionTransaction(ctx, node.redirections, RedirectionPolicy.Persistent)
            val prepared = txn.prepare(stdin); txn.finish()
            if (prepared.error != null) return prepared.error!!
            return Result.ok()
        }
        val txn = RedirectionTransaction(ctx, node.redirections, RedirectionPolicy.Scoped)
        val prepared = txn.prepare(stdin)
        val effectiveStdin = prepared.stdin ?: stdin
        val result = try { runCommand(commandName, args, effectiveStdin) } catch (e: BreakError) { throw e } catch (e: ContinueError) { throw e }
        val redirected = Redirections.applyRedirections(ctx, result, node.redirections, prepared)
        txn.finish()
        if (nameNode != null) {
            for ((name, oldValue) in tempAssignments) {
                if (oldValue == null) ctx.state.env.remove(name) else ctx.state.env[name] = oldValue
            }
        }
        ctx.state.lastArg = if (args.isNotEmpty()) args.last() else commandName
        return redirected
    }

    private suspend fun runCommand(commandName: String, args: List<String>, stdin: ByteArray = ByteArray(0), skipFunctions: Boolean = false): ExecResult {
        if (!skipFunctions) {
            val func = ctx.state.functions[commandName]
            if (func != null) return Functions.callFunction(ctx, func, args, stdin)
        }
        return dispatchBuiltin(commandName, args, stdin)
    }

    private fun createCommandContext(stdin: ByteArray = ByteArray(0)): CommandContext {
        return CommandContext(
            ctx.fs,
            ctx.state.cwd,
            ctx.state.env,
            stdin = stdin,
            exec = { cmd, opts ->
                val savedCwd = ctx.state.cwd
                if (opts.cwd != savedCwd) {
                    ctx.state.cwd = opts.cwd
                }
                try {
                    val fullCmd = if (opts.args?.isNotEmpty() == true) {
                        "$cmd ${opts.args.joinToString(" ")}"
                    } else {
                        cmd
                    }
                    val result = this.execString(fullCmd)
                    result
                } finally {
                    ctx.state.cwd = savedCwd
                }
            },
        )
    }

    private suspend fun dispatchBuiltin(commandName: String, args: List<String>, stdin: ByteArray = ByteArray(0)): ExecResult = when (commandName) {
        "echo" -> if (echoCommand != null) echoCommand.execute(args, createCommandContext(stdin)) else Builtins.echo(ctx, args)
        "cd" -> Builtins.cd(ctx, args)
        "export" -> Builtins.export(ctx, args)
        "local" -> Builtins.local(ctx, args)
        "read" -> Builtins.read(ctx, args, stdin)
        "exit" -> Builtins.exit(ctx, args)
        "return" -> Builtins.`return`(ctx, args)
        "unset" -> Builtins.unset(ctx, args)
        "shift" -> Builtins.shift(ctx, args)
        "eval" -> Builtins.eval(ctx, args, stdin)
        "source", "." -> Builtins.source(ctx, args)
        "break" -> Builtins.break_(ctx, args)
        "continue" -> Builtins.continue_(ctx, args)
        "let" -> Builtins.let(ctx, args)
        "set" -> Builtins.set(ctx, args)
        "declare", "typeset" -> Builtins.declare(ctx, args)
        "readonly" -> Builtins.declare(ctx, listOf("-r") + args)
        ":", "true" -> Result.ok()
        "false" -> Result.testResult(false)
        "command" -> Builtins.command(ctx, args, stdin)
        "builtin" -> if (args.isEmpty()) Result.ok() else dispatchBuiltin(args[0], args.drop(1), stdin)
        "exec" -> Builtins.exec(ctx, args)
        "wait" -> Result.ok()
        "type" -> Result.ok()
        "shopt" -> MoreBuiltins.shopt(ctx, args)
        "dirs" -> MoreBuiltins.dirs(ctx, args)
        "complete" -> MoreBuiltins.complete(ctx, args)
        "compgen" -> MoreBuiltins.compgen(ctx, args)
        "compopt" -> MoreBuiltins.compopt(ctx, args)
        "getopts" -> MoreBuiltins2.getopts(ctx, args)
        "hash" -> MoreBuiltins2.hash(ctx, args)
        "mapfile" -> MoreBuiltins2.mapfile(ctx, args, stdin.toString(Charsets.UTF_8))
        "test", "[" -> {
            val testArgs = if (commandName == "[") { if (args.lastOrNull() == "]") args.dropLast(1) else args } else args
            Builtins.test(ctx, testArgs)
        }
        else -> {
            val resolved = CommandResolution.resolveCommand(ctx, commandName)
            when (resolved) {
                is CommandResolution.Resolved.Cmd -> resolved.cmd.execute(args, createCommandContext(stdin))
                is CommandResolution.Resolved.Script -> ctx.executeUserScript(resolved.path, args, stdin)
                else -> Result.failure("bash: $commandName: command not found\n", 127)
            }
        }
    }

    private suspend fun executeSubshell(node: SubshellNode, stdin: ByteArray): ExecResult {
        val parentLoopDepth = ctx.state.loopDepth
        val savedState = ctx.state.cloneBasics()
        val savedFds = LinkedHashMap<Int, String?>()
        ctx.state.fileDescriptors?.forEach { (fd, raw) -> savedFds[fd] = raw }
        ctx.state.loopDepth = 0; ctx.state.parentHasLoopContext = parentLoopDepth > 0; ctx.state.bashPid = ctx.state.nextVirtualPid++
        try {
            var stdout = ""; var stderr = ""; var exitCode = 0
            for (stmt in node.body) {
                try {
                    val r = ctx.executeStatement(stmt); stdout += r.stdout; stderr += r.stderr; exitCode = r.exitCode
                } catch (e: SubshellExitError) { stdout += e.stdout; stderr += e.stderr; return ExecResult(stdout, stderr, 0) }
                catch (e: BreakError) { stdout += e.stdout; stderr += e.stderr; return ExecResult(stdout, stderr, 0) }
                catch (e: ContinueError) { stdout += e.stdout; stderr += e.stderr; return ExecResult(stdout, stderr, 0) }
                catch (e: ExitError) { stdout += e.stdout; stderr += e.stderr; return ExecResult(stdout, stderr, e.exitCode) }
                catch (e: ReturnError) { stdout += e.stdout; stderr += e.stderr; return ExecResult(stdout, stderr, e.exitCode) }
                catch (e: ErrexitError) { stdout += e.stdout; stderr += e.stderr; return ExecResult(stdout, stderr, e.exitCode) }
            }
            return ExecResult(stdout, stderr, exitCode)
        } finally {
            ctx.state.env.clear(); ctx.state.env.putAll(savedState.env)
            ctx.state.arrays = savedState.arrays; ctx.state.cwd = savedState.cwd; ctx.state.loopDepth = parentLoopDepth
            ctx.state.fileDescriptors = savedFds.filterValues { it != null }.mapValues { it.value!! }.toMutableMap() as? MutableMap<Int, String>
        }
    }

    private suspend fun executeGroup(node: GroupNode, stdin: ByteArray): ExecResult {
        var stdout = ""; var stderr = ""; var exitCode = 0
        for (stmt in node.body) {
            try {
                val r = ctx.executeStatement(stmt); stdout += r.stdout; stderr += r.stderr; exitCode = r.exitCode
            } catch (e: ControlFlowError) { e.stdout = stdout + e.stdout; e.stderr = stderr + e.stderr; throw e }
        }
        return ExecResult(stdout, stderr, exitCode)
    }

    private suspend fun executeUserScript(scriptPath: String, args: List<String>, stdin: ByteArray = ByteArray(0)): ExecResult {
        val content = try { ctx.fs.readFile(scriptPath) } catch (e: Exception) { return Result.failure("bash: $scriptPath: No such file or directory\n", 127) }
        val body = if (content.startsWith("#!")) { val idx = content.indexOf('\n'); if (idx >= 0) content.substring(idx + 1) else "" } else content
        val parentLoopDepth = ctx.state.loopDepth; val savedState = ctx.state.cloneBasics()
        ctx.state.loopDepth = 0; ctx.state.parentHasLoopContext = parentLoopDepth > 0; ctx.state.bashPid = ctx.state.nextVirtualPid++
        ctx.state.currentSource = scriptPath; ctx.state.env["0"] = scriptPath; ctx.state.env["#"] = args.size.toString(); ctx.state.env["@"] = args.joinToString(" ")
        for (i in args.indices) { if (i < 9) ctx.state.env[(i + 1).toString()] = args[i] }
        try {
            val ast = parse(body); val result = executeScript(ast)
            return ExecResult(result.stdout, result.stderr, result.exitCode, result.env, result.stdoutKind)
        } catch (e: ExitError) { return ExecResult(e.stdout, e.stderr, e.exitCode) }
        catch (e: ParseException) { return Result.failure("bash: $scriptPath: ${e.message}\n") }
        finally {
            ctx.state.env.clear(); ctx.state.env.putAll(savedState.env)
            ctx.state.arrays = savedState.arrays; ctx.state.cwd = savedState.cwd; ctx.state.loopDepth = parentLoopDepth
        }
    }

    private suspend fun executeArithmeticCommand(node: ArithmeticCommandNode): ExecResult {
        if (node.line != 0) ctx.state.currentLine = node.line
        return try {
            val result = evaluateArithmeticFull(node.expression)
            Result.testResult(result != 0L)
        } catch (e: Exception) { Result.failure("bash: arithmetic expression: ${e.message}\n") }
    }

    /** Use the full expansion arithmetic engine when the expander is a bridge. */
    private fun evaluateArithmeticFull(expr: com.justbash.ast.ArithmeticExpressionNode): Long {
        val expander = ctx.wordExpander
        if (expander is ExpansionBridge) {
            expander.sync()
            return com.justbash.interpreter.expansion.formatArith(
                com.justbash.interpreter.expansion.evaluateArithmetic(
                    expander.host, expr.expression, isExpansionContext = false
                )
            )
        }
        val text = expr.originalText ?: "0"
        return evaluateArithmeticSimple(text)
    }

    private fun evaluateArithmeticSimple(expr: String): Long {
        val resolved = Regex("\\$?([a-zA-Z_][a-zA-Z0-9_]*)").replace(expr) { m -> ctx.state.env[m.groupValues[1]] ?: "0" }
        return try { val tokens = tokenizeArith(resolved); evalArith(tokens) } catch (e: Exception) { 0L }
    }

    private suspend fun executeConditionalCommand(node: ConditionalCommandNode): ExecResult {
        if (node.line != 0) ctx.state.currentLine = node.line
        val savedInCondition = ctx.state.inCondition
        ctx.state.inCondition = true
        try {
            val result = Conditionals.evaluateConditional(ctx, node.expression)
            return Result.testResult(result)
        } finally {
            ctx.state.inCondition = savedInCondition
        }
    }

    private fun buildExportedEnv(): Map<String, String> {
        val exported = ctx.state.exportedVars ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (name in exported) { val value = ctx.state.env[name] ?: continue; result[name] = value }
        return result
    }

    private fun buildEnvRecord(): Map<String, String> = LinkedHashMap(ctx.state.env)

    private fun tokenizeArith(expr: String): List<String> {
        val tokens = ArrayList<String>(); var i = 0
        while (i < expr.length) {
            when { expr[i] == ' ' || expr[i] == '\t' -> i++; expr[i] in "+-*/%()" -> { tokens.add(expr[i].toString()); i++ }; expr[i].isDigit() || (expr[i] == '-' && i + 1 < expr.length && expr[i + 1].isDigit()) -> { val sb = StringBuilder(); if (expr[i] == '-') { sb.append('-'); i++ }; while (i < expr.length && expr[i].isDigit()) { sb.append(expr[i]); i++ }; tokens.add(sb.toString()) }; else -> i++ }
        }
        return tokens
    }

    private fun evalArith(tokens: List<String>): Long {
        var i = 0; val afterMul = ArrayList<String>()
        while (i < tokens.size) { when (tokens[i]) { "*" -> { val l = afterMul.removeAt(afterMul.size - 1).toLong(); val r = tokens[++i].toLong(); afterMul.add((l * r).toString()) }; "/" -> { val l = afterMul.removeAt(afterMul.size - 1).toLong(); val r = tokens[++i].toLong(); afterMul.add(if (r != 0L) (l / r).toString() else "0") }; "%" -> { val l = afterMul.removeAt(afterMul.size - 1).toLong(); val r = tokens[++i].toLong(); afterMul.add(if (r != 0L) (l % r).toString() else "0") }; else -> afterMul.add(tokens[i]) }; i++ }
        var result = if (afterMul.isNotEmpty()) afterMul[0].toLong() else 0L; i = 1
        while (i < afterMul.size) { when (afterMul[i]) { "+" -> result += afterMul[i + 1].toLong(); "-" -> result -= afterMul[i + 1].toLong() }; i += 2 }
        return result
    }
}
