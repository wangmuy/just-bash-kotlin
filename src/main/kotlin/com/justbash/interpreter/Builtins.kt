package com.justbash.interpreter

import com.justbash.ExecResult
import com.justbash.ast.*

object Builtins {
    fun echo(ctx: InterpreterContext, args: List<String>): ExecResult {
        val stdout = args.joinToString(" ")
        return ExecResult(if (ctx.state.shoptOptions.xpg_echo) {
            stdout.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
        } else stdout, "", 0)
    }

    fun cd(ctx: InterpreterContext, args: List<String>): ExecResult {
        var target: String
        var physical = false
        var printPath = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--" -> { i++; break }
                "-L" -> { physical = false; i++ }
                "-P" -> { physical = true; i++ }
                else -> { if (args[i].startsWith("-") && args[i] != "-") { i++; continue }; break }
            }
        }
        val remaining = args.drop(i)
        if (remaining.isEmpty()) target = ctx.state.env["HOME"] ?: "/"
        else if (remaining[0] == "~") target = ctx.state.env["HOME"] ?: "/"
        else if (remaining[0] == "-") { target = ctx.state.previousDir; printPath = true }
        else target = remaining[0]

        if (!target.startsWith("/") && !target.startsWith("./") && !target.startsWith("../") && target != "." && target != "..") {
            val cdpath = ctx.state.env["CDPATH"]
            if (cdpath != null) {
                for (dir in cdpath.split(':').filter { it.isNotEmpty() }) {
                    val candidate = if (dir.startsWith("/")) "$dir/$target" else "${ctx.state.cwd}/$dir/$target"
                    try { val stat = ctx.fs.stat(candidate); if (stat.isDirectory) { target = candidate; printPath = true; break } } catch (e: Exception) {}
                }
            }
        }
        val pathToCheck = if (target.startsWith("/")) target else "${ctx.state.cwd}/$target"
        val parts = pathToCheck.split("/").filter { it.isNotEmpty() && it != "." }
        var currentPath = ""
        for (part in parts) {
            if (part == "..") currentPath = currentPath.split("/").dropLast(1).joinToString("/").ifEmpty { "/" }
            else {
                currentPath = if (currentPath.isNotEmpty()) "$currentPath/$part" else "/$part"
                try {
                    val stat = ctx.fs.stat(currentPath)
                    if (!stat.isDirectory) return Result.failure("bash: cd: $target: Not a directory\n")
                } catch (e: Exception) { return Result.failure("bash: cd: $target: No such file or directory\n") }
            }
        }
        var newDir = currentPath.ifEmpty { "/" }
        if (physical) try { newDir = ctx.fs.realpath(newDir) } catch (e: Exception) {}
        ctx.state.previousDir = ctx.state.cwd
        ctx.state.cwd = newDir
        ctx.state.env["PWD"] = newDir
        ctx.state.env["OLDPWD"] = ctx.state.previousDir
        return Result.success(if (printPath) "$newDir\n" else "")
    }

    fun export(ctx: InterpreterContext, args: List<String>): ExecResult {
        var unexport = false
        val processedArgs = ArrayList<String>()
        for (arg in args) { when (arg) { "-n" -> unexport = true; "-p", "--" -> {}; else -> processedArgs.add(arg) } }
        if (processedArgs.isEmpty() && !unexport) {
            val exported = ctx.state.exportedVars ?: LinkedHashSet()
            val sb = StringBuilder()
            for (name in exported.sorted()) { val v = ctx.state.env[name] ?: continue; sb.append("declare -x $name=\"$v\"\n") }
            return Result.success(sb.toString())
        }
        if (unexport) {
            for (arg in processedArgs) {
                val eq = arg.indexOf('='); val name: String; val value: String?
                if (eq >= 0) { name = arg.substring(0, eq); value = arg.substring(eq + 1) } else { name = arg; value = null }
                if (!name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) return Result.failure("bash: export: `$arg': not a valid identifier\n", 1)
                if (value != null) { Readonly.checkReadonlyError(ctx, name); ctx.state.env[name] = value }
                Readonly.unmarkExported(ctx, name)
            }
            return Result.ok()
        }
        var stderr = ""; var exitCode = 0
        for (arg in processedArgs) {
            var name: String; var value: String? = null; var isAppend = false
            val appendMatch = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\+=.*").find(arg)
            if (appendMatch != null) { name = appendMatch.groupValues[1]; value = arg.substring(appendMatch.groupValues[1].length + 2); isAppend = true }
            else if (arg.contains('=')) { val eq = arg.indexOf('='); name = arg.substring(0, eq); value = arg.substring(eq + 1) }
            else { name = arg }
            if (!name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) { stderr += "bash: export: `$arg': not a valid identifier\n"; exitCode = 1; continue }
            if (value != null) { Readonly.checkReadonlyError(ctx, name); if (isAppend) { val existing = ctx.state.env[name] ?: ""; ctx.state.env[name] = existing + value } else ctx.state.env[name] = value }
            else { if (!ctx.state.env.containsKey(name)) ctx.state.env[name] = "" }
            Readonly.markExported(ctx, name)
        }
        return Result.result("", stderr, exitCode)
    }

    fun local(ctx: InterpreterContext, args: List<String>): ExecResult {
        if (ctx.state.localScopes.isEmpty()) return Result.failure("bash: local: can only be used in a function\n")
        val currentScope = ctx.state.localScopes[ctx.state.localScopes.size - 1]
        var stderr = ""; var exitCode = 0
        var declareArray = false; var declareNameref = false
        val processedArgs = ArrayList<String>()
        for (arg in args) {
            when {
                arg == "-a" -> declareArray = true
                arg == "-n" -> declareNameref = true
                arg.startsWith("-") && !arg.contains('=') -> { for (flag in arg.drop(1)) when (flag) { 'a' -> declareArray = true; 'n' -> declareNameref = true } }
                else -> processedArgs.add(arg)
            }
        }
        if (processedArgs.isEmpty()) {
            val sb = StringBuilder()
            for (name in currentScope.keys.sorted()) { val v = ctx.state.env[name] ?: continue; sb.append("$name=$v\n") }
            return Result.result(sb.toString(), "", 0)
        }
        for (arg in processedArgs) {
            var name: String; var value: String? = null; var isAppend = false
            val appendMatch = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\+=.*").find(arg)
            if (appendMatch != null) { name = appendMatch.groupValues[1]; value = arg.substring(appendMatch.groupValues[1].length + 2); isAppend = true }
            else if (arg.contains('=')) { val eq = arg.indexOf('='); name = arg.substring(0, eq); value = arg.substring(eq + 1) }
            else { name = arg }
            if (!name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) { stderr += "bash: local: `$arg': not a valid identifier\n"; exitCode = 1; continue }
            Readonly.checkReadonlyError(ctx, name)
            if (!currentScope.containsKey(name)) currentScope[name] = ctx.state.env[name]
            if (value != null) { if (isAppend) { val existing = ctx.state.env[name] ?: ""; ctx.state.env[name] = existing + value } else ctx.state.env[name] = value }
            else ctx.state.env.remove(name)
            if (ctx.state.localVarDepth == null) ctx.state.localVarDepth = LinkedHashMap()
            ctx.state.localVarDepth!![name] = ctx.state.callDepth
        }
        return Result.result("", stderr, exitCode)
    }

    fun read(ctx: InterpreterContext, args: List<String>, stdin: ByteArray): ExecResult {
        var raw = false; var delimiter = "\n"; var arrayName: String? = null
        val varNames = ArrayList<String>(); var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg.startsWith("-") && arg.length > 1 && arg != "--") {
                for (j in 1..<arg.length) when (arg[j]) {
                    'r' -> raw = true
                    'd' -> { if (j + 1 < arg.length) delimiter = arg.substring(j + 1) else if (i + 1 < args.size) delimiter = args[++i]; break }
                    'a' -> { if (j + 1 < arg.length) arrayName = arg.substring(j + 1) else if (i + 1 < args.size) arrayName = args[++i]; break }
                }; i++
            } else if (arg == "--") { i++; varNames.addAll(args.drop(i)); break }
            else { varNames.add(arg); i++ }
        }
        if (varNames.isEmpty() && arrayName == null) varNames.add("REPLY")
        val stdinStr = stdin.toString(Charsets.UTF_8)
        val effectiveStdin = if (stdinStr.isNotEmpty()) stdinStr else ctx.state.groupStdin ?: ""
        val delimIdx = effectiveStdin.indexOf(delimiter)
        val line = if (delimIdx >= 0) effectiveStdin.substring(0, delimIdx) else effectiveStdin
        val consumed = if (delimIdx >= 0) delimIdx + delimiter.length else effectiveStdin.length
        if (ctx.state.groupStdin != null && stdin.isEmpty()) ctx.state.groupStdin = effectiveStdin.substring(consumed)
        val foundDelim = delimIdx >= 0
        if (arrayName != null) {
            Arrays.clearArray(ctx, arrayName)
            val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (j in words.indices) Arrays.setArrayElement(ctx, arrayName, j, words[j])
        } else {
            val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (j in varNames.indices) {
                when {
                    j < varNames.size - 1 -> ctx.state.env[varNames[j]] = words.getOrElse(j) { "" }
                    j == varNames.size - 1 -> ctx.state.env[varNames[j]] = words.drop(j).joinToString(" ")
                    else -> ctx.state.env[varNames[j]] = ""
                }
            }
        }
        return Result.result("", "", if (foundDelim) 0 else 1)
    }

    fun exit(ctx: InterpreterContext, args: List<String>): Nothing {
        val exitCode = if (args.isEmpty()) ctx.state.lastExitCode else {
            val arg = args[0]
            if (arg.isEmpty() || !arg.matches(Regex("-?\\d+"))) throw ExitError(2, "", "bash: exit: $arg: numeric argument required\n")
            val parsed = arg.toLong(); ((parsed % 256) + 256).toInt() % 256
        }
        throw ExitError(exitCode)
    }

    fun `return`(ctx: InterpreterContext, args: List<String>): ExecResult {
        if (ctx.state.callDepth == 0 && ctx.state.sourceDepth == 0) return Result.failure("bash: return: can only `return' from a function or sourced script\n")
        val exitCode = if (args.isEmpty()) ctx.state.lastExitCode else {
            val arg = args[0]; val n = arg.toIntOrNull()
            if (arg.isEmpty() || n == null || !arg.matches(Regex("-?\\d+"))) return Result.failure("bash: return: $arg: numeric argument required\n", 2)
            ((n % 256) + 256) % 256
        }
        throw ReturnError(exitCode)
    }

    fun unset(ctx: InterpreterContext, args: List<String>): ExecResult {
        var mode = "both"; var stderr = ""; var exitCode = 0
        for (arg in args) {
            when (arg) { "-v" -> { mode = "variable"; continue }; "-f" -> { mode = "function"; continue } }
            if (mode == "function") { ctx.state.functions.remove(arg); continue }
            if (!arg.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                val arrayMatch = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\[(.+)\\]$").find(arg)
                if (arrayMatch != null) {
                    val arrayName = arrayMatch.groupValues[1]; val indexExpr = arrayMatch.groupValues[2]
                    if (Readonly.isReadonly(ctx, arrayName)) { stderr += "bash: unset: $arrayName: cannot unset: readonly variable\n"; exitCode = 1; continue }
                    if (indexExpr == "@" || indexExpr == "*") { Arrays.deleteArray(ctx, arrayName); ctx.state.env.remove(arrayName) }
                    else Arrays.deleteArrayElement(ctx, arrayName, indexExpr.toIntOrNull() ?: indexExpr)
                    continue
                }
                stderr += "bash: unset: `$arg': not a valid identifier\n"; exitCode = 1; continue
            }
            if (Readonly.isReadonly(ctx, arg)) { stderr += "bash: unset: $arg: cannot unset: readonly variable\n"; exitCode = 1; continue }
            Arrays.deleteArray(ctx, arg); ctx.state.env.remove(arg); ctx.state.exportedVars?.remove(arg); ctx.state.functions.remove(arg)
        }
        return Result.result("", stderr, exitCode)
    }

    fun shift(ctx: InterpreterContext, args: List<String>): ExecResult {
        var n = 1
        if (args.isNotEmpty()) { val parsed = args[0].toIntOrNull(); if (parsed == null || parsed < 0) { val err = "bash: shift: ${args[0]}: numeric argument required\n"; if (ctx.state.options.posix) throw PosixFatalError(1, "", err); return Result.failure(err) }; n = parsed }
        val currentCount = (ctx.state.env["#"] ?: "0").toInt()
        if (n > currentCount) { val err = "bash: shift: shift count out of range\n"; if (ctx.state.options.posix) throw PosixFatalError(1, "", err); return Result.failure(err) }
        if (n == 0) return Result.ok()
        val params = (1..currentCount).map { ctx.state.env[it.toString()] ?: "" }
        for (i in 1..currentCount) ctx.state.env.remove(i.toString())
        val newParams = params.drop(n)
        for (i in newParams.indices) ctx.state.env[(i + 1).toString()] = newParams[i]
        ctx.state.env["#"] = newParams.size.toString(); ctx.state.env["@"] = newParams.joinToString(" ")
        return Result.ok()
    }

    suspend fun eval(ctx: InterpreterContext, args: List<String>, stdin: ByteArray = ByteArray(0), stdinRedirected: Boolean = false): ExecResult {
        var evalArgs = args
        if (evalArgs.isNotEmpty() && evalArgs[0] == "--") evalArgs = evalArgs.drop(1)
        if (evalArgs.isEmpty()) return Result.ok()
        val command = evalArgs.joinToString(" ").trim()
        if (command.isEmpty()) return Result.ok()
        val savedGroupStdin = ctx.state.groupStdin
        val ownsStdin = stdinRedirected || stdin.isNotEmpty()
        if (ownsStdin) ctx.state.groupStdin = stdin.toString(Charsets.UTF_8)
        try { return ctx.execString(command) }
        catch (e: BreakError) { throw e } catch (e: ContinueError) { throw e }
        catch (e: ReturnError) { throw e } catch (e: ExitError) { throw e }
        catch (e: ParseException) { return Result.failure("bash: eval: ${e.message}\n") }
        finally { if (ownsStdin || (savedGroupStdin != null && ctx.state.groupStdin == null)) ctx.state.groupStdin = savedGroupStdin }
    }

    suspend fun source(ctx: InterpreterContext, args: List<String>): ExecResult {
        var sourceArgs = args
        if (sourceArgs.isNotEmpty() && sourceArgs[0] == "--") sourceArgs = sourceArgs.drop(1)
        if (sourceArgs.isEmpty()) return Result.failure("bash: source: filename argument required\n", 2)
        val filename = sourceArgs[0]
        var content: String? = null
        if (filename.contains("/")) { val directPath = ctx.fs.resolvePath(ctx.state.cwd, filename); try { content = ctx.fs.readFile(directPath) } catch (e: Exception) {} }
        else {
            val pathEnv = ctx.state.env["PATH"] ?: ""
            for (dir in pathEnv.split(':').filter { it.isNotEmpty() }) {
                val candidate = ctx.fs.resolvePath(ctx.state.cwd, "$dir/$filename")
                try { val stat = ctx.fs.stat(candidate); if (!stat.isDirectory) { content = ctx.fs.readFile(candidate); break } } catch (e: Exception) {}
            }
            if (content == null) { val directPath = ctx.fs.resolvePath(ctx.state.cwd, filename); try { content = ctx.fs.readFile(directPath) } catch (e: Exception) {} }
        }
        if (content == null) return Result.failure("bash: $filename: No such file or directory\n", 1)
        val savedPositional = LinkedHashMap<String, String?>()
        if (sourceArgs.size > 1) {
            for (i in 1..9) savedPositional[i.toString()] = ctx.state.env[i.toString()]
            savedPositional["#"] = ctx.state.env["#"]; savedPositional["@"] = ctx.state.env["@"]
            val scriptArgs = sourceArgs.drop(1)
            ctx.state.env["#"] = scriptArgs.size.toString(); ctx.state.env["@"] = scriptArgs.joinToString(" ")
            for (i in scriptArgs.indices) { if (i < 9) ctx.state.env[(i + 1).toString()] = scriptArgs[i] }
            for (i in scriptArgs.size + 1..9) ctx.state.env.remove(i.toString())
        }
        val savedSource = ctx.state.currentSource
        ctx.state.sourceDepth++; ctx.state.currentSource = filename
        try { return ctx.execString(content) }
        catch (e: ExitError) { throw e }
        catch (e: ReturnError) { return Result.result(e.stdout, e.stderr, e.exitCode) }
        catch (e: ParseException) { return Result.failure("bash: $filename: ${e.message}\n") }
        finally {
            ctx.state.sourceDepth--; ctx.state.currentSource = savedSource
            if (sourceArgs.size > 1) { for ((k, v) in savedPositional) { if (v == null) ctx.state.env.remove(k) else ctx.state.env[k] = v } }
        }
    }

    fun break_(ctx: InterpreterContext, args: List<String>): ExecResult {
        if (ctx.state.loopDepth == 0) { if (ctx.state.parentHasLoopContext) throw SubshellExitError(); return Result.ok() }
        if (args.size > 1) throw ExitError(1, "", "bash: break: too many arguments\n")
        var levels = 1
        if (args.isNotEmpty()) { val n = args[0].toIntOrNull(); if (n == null || n < 1) throw ExitError(128, "", "bash: break: ${args[0]}: numeric argument required\n"); levels = n }
        throw BreakError(levels)
    }

    fun continue_(ctx: InterpreterContext, args: List<String>): ExecResult {
        if (ctx.state.loopDepth == 0) { if (ctx.state.parentHasLoopContext) throw SubshellExitError(); return Result.ok() }
        if (args.size > 1) throw ExitError(1, "", "bash: continue: too many arguments\n")
        var levels = 1
        if (args.isNotEmpty()) { val n = args[0].toIntOrNull(); if (n == null || n < 1) throw ExitError(1, "", "bash: continue: ${args[0]}: numeric argument required\n"); levels = n }
        throw ContinueError(levels)
    }

    fun let(ctx: InterpreterContext, args: List<String>): ExecResult {
        if (args.isEmpty()) return Result.failure("bash: let: expression expected\n")
        val expr = args.joinToString(" ")
        val result = try { val resolved = resolveArithmetic(expr, ctx.state.env); evaluateArithmeticExpr(resolved) } catch (e: Exception) { return Result.failure("bash: let: ${e.message}\n") }
        return Result.result("", "", if (result == 0L) 1 else 0)
    }

    fun set(ctx: InterpreterContext, args: List<String>): ExecResult {
        if (args.isEmpty()) {
            val sb = StringBuilder()
            for ((key, value) in ctx.state.env) { if (key.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) sb.append("$key=$value\n") }
            return Result.success(sb.toString())
        }
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "-e" -> { ctx.state.options.errexit = true; i++ }
                "+e" -> { ctx.state.options.errexit = false; i++ }
                "-u" -> { ctx.state.options.nounset = true; i++ }
                "+u" -> { ctx.state.options.nounset = false; i++ }
                "-x" -> { ctx.state.options.xtrace = true; i++ }
                "+x" -> { ctx.state.options.xtrace = false; i++ }
                "-o" -> { if (i + 1 < args.size) { when (args[i + 1]) { "errexit" -> ctx.state.options.errexit = true; "pipefail" -> ctx.state.options.pipefail = true; "nounset" -> ctx.state.options.nounset = true; "posix" -> ctx.state.options.posix = true }; i += 2 } else i++ }
                "+o" -> { if (i + 1 < args.size) { when (args[i + 1]) { "errexit" -> ctx.state.options.errexit = false; "pipefail" -> ctx.state.options.pipefail = false; "nounset" -> ctx.state.options.nounset = false; "posix" -> ctx.state.options.posix = false }; i += 2 } else i++ }
                "--" -> { setPositionalParams(ctx, args.drop(i + 1)); return Result.ok() }
                else -> { if (arg.startsWith("-")) { setPositionalParams(ctx, args.drop(i)); return Result.ok() } else { setPositionalParams(ctx, args.drop(i)); return Result.ok() } }
            }
        }
        return Result.ok()
    }

    private fun setPositionalParams(ctx: InterpreterContext, params: List<String>) {
        var i = 1; while (ctx.state.env.containsKey(i.toString())) { ctx.state.env.remove(i.toString()); i++ }
        for (j in params.indices) ctx.state.env[(j + 1).toString()] = params[j]
        ctx.state.env["#"] = params.size.toString(); ctx.state.env["@"] = params.joinToString(" ")
    }

    fun declare(ctx: InterpreterContext, args: List<String>): ExecResult {
        var declareArray = false; var declareAssoc = false; var declareReadonly = false; var declareExport = false
        var printMode = false; var declareInteger = false; var declareGlobal = false
        val processedArgs = ArrayList<String>()
        for (arg in args) {
            when (arg) {
                "-a" -> declareArray = true; "-A" -> declareAssoc = true; "-r" -> declareReadonly = true
                "-x" -> declareExport = true; "-p" -> printMode = true; "-i" -> declareInteger = true; "-g" -> declareGlobal = true
                "--" -> { processedArgs.addAll(args.drop(args.indexOf(arg) + 1)); break }
                else -> {
                    if (arg.startsWith("-") && arg.length > 1 && !arg.contains('=')) {
                        for (flag in arg.drop(1)) when (flag) { 'a' -> declareArray = true; 'A' -> declareAssoc = true; 'r' -> declareReadonly = true; 'x' -> declareExport = true; 'p' -> printMode = true; 'i' -> declareInteger = true; 'g' -> declareGlobal = true }
                    } else processedArgs.add(arg)
                }
            }
        }
        if (printMode && processedArgs.isEmpty()) { val sb = StringBuilder(); for ((key, value) in ctx.state.env) { if (key.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) sb.append("declare -- $key=$value\n") }; return Result.success(sb.toString()) }
        if (processedArgs.isEmpty() && !printMode) { val sb = StringBuilder(); for ((key, value) in ctx.state.env) { if (key.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) sb.append("$key=$value\n") }; return Result.success(sb.toString()) }
        val isInsideFunction = ctx.state.localScopes.isNotEmpty(); val createLocal = isInsideFunction && !declareGlobal
        var stderr = ""; var exitCode = 0
        for (arg in processedArgs) {
            var name: String; var value: String? = null
            if (arg.contains('=')) { val eq = arg.indexOf('='); name = arg.substring(0, eq); value = arg.substring(eq + 1) } else name = arg
            if (!name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) { stderr += "bash: declare: `$arg': not a valid identifier\n"; exitCode = 1; continue }
            Readonly.checkReadonlyError(ctx, name)
            if (createLocal) { val currentScope = ctx.state.localScopes[ctx.state.localScopes.size - 1]; if (!currentScope.containsKey(name)) currentScope[name] = ctx.state.env[name] }
            if (declareAssoc) { if (ctx.state.associativeArrays == null) ctx.state.associativeArrays = LinkedHashSet(); ctx.state.associativeArrays!!.add(name); Arrays.setArrayKind(ctx, name, "associative") }
            if (declareArray) Arrays.setArrayKind(ctx, name, "indexed")
            if (value != null) ctx.state.env[name] = value
            else { if (ctx.state.declaredVars == null) ctx.state.declaredVars = LinkedHashSet(); ctx.state.declaredVars!!.add(name) }
            if (declareReadonly) Readonly.markReadonly(ctx, name)
            if (declareExport) Readonly.markExported(ctx, name)
        }
        return Result.result("", stderr, exitCode)
    }

    fun test(ctx: InterpreterContext, args: List<String>): ExecResult {
        return Conditionals.evaluateTest(ctx, args)
    }

    suspend fun command(ctx: InterpreterContext, args: List<String>, stdin: ByteArray = ByteArray(0)): ExecResult {
        var cmdArgs = args; var useDefaultPath = false; var i = 0
        while (i < cmdArgs.size) {
            val opt = cmdArgs[i]
            if (opt == "--") { cmdArgs = cmdArgs.drop(i + 1); break }
            if (opt.startsWith("-")) { for (c in opt.drop(1)) when (c) { 'p' -> useDefaultPath = true }; i++; if (i >= cmdArgs.size) break; cmdArgs = cmdArgs.drop(i); break } else break
        }
        if (cmdArgs.isEmpty()) return Result.ok()
        val cmdName = cmdArgs[0]; val rest = cmdArgs.drop(1)
        val resolved = CommandResolution.resolveCommand(ctx, cmdName, if (useDefaultPath) "/usr/bin:/bin" else null)
        return when (resolved) {
            is CommandResolution.Resolved.Cmd -> resolved.cmd.execute(rest, com.justbash.CommandContext(ctx.fs, ctx.state.cwd, ctx.state.env))
            is CommandResolution.Resolved.Script -> ctx.executeUserScript(resolved.path, rest, stdin)
            else -> Result.failure("bash: command: $cmdName: command not found\n", 127)
        }
    }

    fun exec(ctx: InterpreterContext, args: List<String>): ExecResult = if (args.isEmpty()) Result.ok() else Result.ok()

    private fun resolveArithmetic(expr: String, env: Map<String, String>): String =
        Regex("\\$?([a-zA-Z_][a-zA-Z0-9_]*)").replace(expr) { m -> env[m.groupValues[1]] ?: "0" }

    private fun evaluateArithmeticExpr(expr: String): Long {
        val tokens = tokenizeArith(expr); return evalArithTokens(tokens)
    }

    private fun tokenizeArith(expr: String): List<String> {
        val tokens = ArrayList<String>(); var i = 0
        while (i < expr.length) {
            when { expr[i] == ' ' || expr[i] == '\t' -> i++; expr[i] in "+-*/%()" -> { tokens.add(expr[i].toString()); i++ }; expr[i].isDigit() || (expr[i] == '-' && i + 1 < expr.length && expr[i + 1].isDigit()) -> { val sb = StringBuilder(); if (expr[i] == '-') { sb.append('-'); i++ }; while (i < expr.length && expr[i].isDigit()) { sb.append(expr[i]); i++ }; tokens.add(sb.toString()) }; else -> i++ }
        }
        return tokens
    }

    private fun evalArithTokens(tokens: List<String>): Long {
        var i = 0; val afterMul = ArrayList<String>()
        while (i < tokens.size) { when (tokens[i]) { "*" -> { val l = afterMul.removeAt(afterMul.size - 1).toLong(); val r = tokens[++i].toLong(); afterMul.add((l * r).toString()) }; "/" -> { val l = afterMul.removeAt(afterMul.size - 1).toLong(); val r = tokens[++i].toLong(); afterMul.add(if (r != 0L) (l / r).toString() else "0") }; "%" -> { val l = afterMul.removeAt(afterMul.size - 1).toLong(); val r = tokens[++i].toLong(); afterMul.add(if (r != 0L) (l % r).toString() else "0") }; else -> afterMul.add(tokens[i]) }; i++ }
        var result = if (afterMul.isNotEmpty()) afterMul[0].toLong() else 0L; i = 1
        while (i < afterMul.size) { when (afterMul[i]) { "+" -> result += afterMul[i + 1].toLong(); "-" -> result -= afterMul[i + 1].toLong() }; i += 2 }
        return result
    }

    val BASIC_BUILTINS = setOf("echo", "cd", "export", "local", "read", "exit", "return", "unset", "shift", "eval", "source", ".", "break", "continue", "let", "set", "declare", "typeset", "readonly", ":", "true", "false", "command", "builtin", "exec", "wait", "type", "test", "[", "shopt", "dirs", "complete", "compgen", "compopt", "getopts", "hash", "mapfile")
}
