package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.CommandExecOptions
import com.justbash.ExecResult
import com.justbash.fs.DirentEntry
import com.justbash.fs.FsStat
import com.justbash.fs.RmOptions
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Port of just-bash `commands/find/` (types.ts, parser.ts, matcher.ts,
 * find.ts), merged into a single file.
 *
 * Faithful semantics: -name/-iname/-path/-ipath/-regex/-iregex predicates,
 * -type, -empty, -mtime, -newer, -size, -perm predicates; -a/-o/-not/! and
 * parentheses with proper precedence; -print/-print0/-printf/-ls/-delete/-exec
 * actions; -maxdepth/-mindepth/-depth/-prune traversal controls. Synchronous
 * (no coroutines) and bounded by maxCallDepth / maxLoopIterations.
 */

// ---------------------------------------------------------------------------
// Types (port of types.ts)
// ---------------------------------------------------------------------------

private enum class SizeUnit { C, K, M, G, B }

private enum class Comparison { EXACT, MORE, LESS }

private enum class PermMatchType { EXACT, ALL, ANY }

private sealed class Expression {
    data class Name(val pattern: String, val ignoreCase: Boolean = false) : Expression()
    data class PathM(val pattern: String, val ignoreCase: Boolean = false) : Expression()
    data class RegEx(val pattern: String, val ignoreCase: Boolean = false) : Expression()
    data class TypeOf(val fileType: Char) : Expression()
    data object Empty : Expression()
    data class MTime(val days: Long, val comparison: Comparison) : Expression()
    data class Newer(val refPath: String) : Expression()
    data class SizeOf(val value: Long, val unit: SizeUnit, val comparison: Comparison) : Expression()
    data class Perm(val mode: Int, val matchType: PermMatchType) : Expression()
    data object Prune : Expression()
    data class Action(val action: FindAction) : Expression()
    data class Not(val expr: Expression) : Expression()
    data class And(val left: Expression, val right: Expression) : Expression()
    data class Or(val left: Expression, val right: Expression) : Expression()
}

private sealed class FindAction {
    data class Exec(val command: List<String>, val batchMode: Boolean) : FindAction()
    data object Print : FindAction()
    data object Print0 : FindAction()
    data class Printf(val format: String) : FindAction()
    data object Delete : FindAction()
    data object Ls : FindAction()
}

private class EvalContext(
    val name: String,
    val relativePath: String,
    val isFile: Boolean,
    val isDirectory: Boolean,
    val isEmpty: Boolean,
    val mtime: Long,
    val size: Long,
    val mode: Int,
    val newerRefTimes: MutableMap<String, Long>,
    val depth: Int = 0,
    val startingPoint: String = "",
)

private class EvalResult(
    var matches: Boolean,
    var pruned: Boolean = false,
    var printed: Boolean = false,
    var actions: MutableList<FindAction>? = null,
)

private class ParseResult(
    val expr: Expression?,
    val pathIndex: Int,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Glob and regex helpers
// ---------------------------------------------------------------------------

private fun globToRegex(pattern: String, ignoreCase: Boolean): Pattern {
    val sb = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append(".")
            '[' -> {
                var j = i + 1
                while (j < pattern.length && pattern[j] != ']') j++
                if (j >= pattern.length) sb.append("\\[")
                else {
                    sb.append(pattern.substring(i, j + 1))
                    i = j
                }
            }
            '.', '+', '^', '$', '{', '}', '(', ')', '|', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append("$")
    val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
    return Pattern.compile(sb.toString(), flags)
}

private fun matchGlob(name: String, pattern: String, ignoreCase: Boolean): Boolean =
    globToRegex(pattern, ignoreCase).matcher(name).matches()

private fun compileUserRegex(pattern: String, ignoreCase: Boolean): Pattern? = try {
    val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
    Pattern.compile(pattern, flags)
} catch (_: PatternSyntaxException) {
    null
}

private fun isHexChar(c: Char): Boolean =
    (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

// ---------------------------------------------------------------------------
// Parser (port of parser.ts)
// ---------------------------------------------------------------------------

private sealed class Token {
    class Expr(val expr: Expression) : Token()
    class Op(val op: String) : Token()
    data object Not : Token()
    data object LParen : Token()
    data object RParen : Token()
}

private object ExpressionParser {

    fun parse(args: List<String>, startIndex: Int): ParseResult {
        val tokens = ArrayList<Token>()
        var i = startIndex

        fun missingArgument(predicate: String): ParseResult =
            ParseResult(null, i, "find: missing argument to `$predicate'\n")

        fun invalidArgument(predicate: String, value: String): ParseResult =
            ParseResult(null, i, "find: invalid argument `$value' to `$predicate'\n")

        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "(" || arg == "\\(" -> tokens.add(Token.LParen)
                arg == ")" || arg == "\\)" -> tokens.add(Token.RParen)
                arg == "-name" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    tokens.add(Token.Expr(Expression.Name(args[++i])))
                }
                arg == "-iname" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    tokens.add(Token.Expr(Expression.Name(args[++i], ignoreCase = true)))
                }
                arg == "-path" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    tokens.add(Token.Expr(Expression.PathM(args[++i])))
                }
                arg == "-ipath" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    tokens.add(Token.Expr(Expression.PathM(args[++i], ignoreCase = true)))
                }
                arg == "-regex" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    tokens.add(Token.Expr(Expression.RegEx(args[++i])))
                }
                arg == "-iregex" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    tokens.add(Token.Expr(Expression.RegEx(args[++i], ignoreCase = true)))
                }
                arg == "-type" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    val fileType = args[++i]
                    if (fileType == "f" || fileType == "d") {
                        tokens.add(Token.Expr(Expression.TypeOf(fileType[0])))
                    } else {
                        return ParseResult(null, i, "find: Unknown argument to -type: $fileType\n")
                    }
                }
                arg == "-empty" -> tokens.add(Token.Expr(Expression.Empty))
                arg == "-mtime" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    val mtimeArg = args[++i]
                    var comparison = Comparison.EXACT
                    var daysStr = mtimeArg
                    if (mtimeArg.startsWith("+")) { comparison = Comparison.MORE; daysStr = mtimeArg.substring(1) }
                    else if (mtimeArg.startsWith("-")) { comparison = Comparison.LESS; daysStr = mtimeArg.substring(1) }
                    if (!daysStr.matches(Regex("\\d+"))) return invalidArgument(arg, mtimeArg)
                    val days = daysStr.toLongOrNull() ?: return invalidArgument(arg, mtimeArg)
                    tokens.add(Token.Expr(Expression.MTime(days, comparison)))
                }
                arg == "-newer" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    tokens.add(Token.Expr(Expression.Newer(args[++i])))
                }
                arg == "-size" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    val sizeArg = args[++i]
                    var comparison = Comparison.EXACT
                    var sizeStr = sizeArg
                    if (sizeArg.startsWith("+")) { comparison = Comparison.MORE; sizeStr = sizeArg.substring(1) }
                    else if (sizeArg.startsWith("-")) { comparison = Comparison.LESS; sizeStr = sizeArg.substring(1) }
                    val m = Regex("^(\\d+)([ckMGb])?$").matchEntire(sizeStr)
                    if (m == null) return invalidArgument(arg, sizeArg)
                    val value = m.groupValues[1].toLongOrNull() ?: return invalidArgument(arg, sizeArg)
                    val unit: SizeUnit = when (m.groupValues[2]) {
                        "c" -> SizeUnit.C
                        "k" -> SizeUnit.K
                        "M" -> SizeUnit.M
                        "G" -> SizeUnit.G
                        else -> SizeUnit.B
                    }
                    tokens.add(Token.Expr(Expression.SizeOf(value, unit, comparison)))
                }
                arg == "-perm" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    val permArg = args[++i]
                    var matchType = PermMatchType.EXACT
                    var modeStr = permArg
                    if (permArg.startsWith("-")) { matchType = PermMatchType.ALL; modeStr = permArg.substring(1) }
                    else if (permArg.startsWith("/")) { matchType = PermMatchType.ANY; modeStr = permArg.substring(1) }
                    if (!modeStr.matches(Regex("[0-7]{1,4}"))) return invalidArgument(arg, permArg)
                    val mode = modeStr.toInt(8)
                    tokens.add(Token.Expr(Expression.Perm(mode, matchType)))
                }
                arg == "-prune" -> tokens.add(Token.Expr(Expression.Prune))
                arg == "-not" || arg == "!" -> tokens.add(Token.Not)
                arg == "-o" || arg == "-or" -> tokens.add(Token.Op("or"))
                arg == "-a" || arg == "-and" -> tokens.add(Token.Op("and"))
                arg == "-maxdepth" || arg == "-mindepth" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    i++
                }
                arg == "-depth" -> { /* handled separately */ }
                arg == "-follow" -> { /* symlinks never followed (POSIX -P default) */ }
                arg == "-mount" -> { /* no separate mounts in the VFS */ }
                arg == "-exec" || arg == "-ok" -> {
                    val commandParts = ArrayList<String>()
                    i++
                    while (i < args.size && args[i] != ";" && args[i] != "+") {
                        commandParts.add(args[i]); i++
                    }
                    if (i >= args.size) return ParseResult(null, i, "find: missing argument to `$arg'\n")
                    if (commandParts.isEmpty()) return missingArgument(arg)
                    val batchMode = args[i] == "+"
                    if (batchMode && (commandParts.last() != "{}" ||
                            commandParts.count { it == "{}" } != 1)) {
                        return invalidArgument(arg, "+")
                    }
                    tokens.add(Token.Expr(Expression.Action(FindAction.Exec(commandParts, batchMode))))
                }
                arg == "-print" -> tokens.add(Token.Expr(Expression.Action(FindAction.Print)))
                arg == "-print0" -> tokens.add(Token.Expr(Expression.Action(FindAction.Print0)))
                arg == "-printf" -> {
                    if (i + 1 >= args.size) return missingArgument(arg)
                    tokens.add(Token.Expr(Expression.Action(FindAction.Printf(args[++i]))))
                }
                arg == "-ls" -> tokens.add(Token.Expr(Expression.Action(FindAction.Ls)))
                arg == "-delete" -> tokens.add(Token.Expr(Expression.Action(FindAction.Delete)))
                arg.startsWith("-") -> return ParseResult(null, i, "find: unknown predicate '$arg'\n")
                else -> return ParseResult(null, i, "find: paths must precede expression: `$arg'\n")
            }
            i++
        }

        if (tokens.isEmpty()) return ParseResult(null, i)

        val result = buildTree(tokens)
        if (result.error != null) return ParseResult(null, i, result.error)
        return ParseResult(result.expr, i)
    }

    private class TreeResult(val expr: Expression?, val error: String? = null)

    private fun buildTree(tokens: List<Token>): TreeResult = TreeBuilder(tokens).build()

    private class TreeBuilder(private val tokens: List<Token>) {
        private var pos = 0
        private var error: String? = null

        fun build(): TreeResult {
            val expr = parseOr()
            if (error == null && pos < tokens.size) {
                error = if (tokens[pos] is Token.RParen) "find: unexpected `)'\n" else "find: invalid expression\n"
            }
            if (error == null && expr != null && containsNegatedDelete(expr, false)) {
                error = "find: refusing to evaluate `-delete' under negation\n"
            }
            return TreeResult(if (error == null) expr else null, error)
        }

        private fun parseOr(): Expression? {
            var left = parseAnd() ?: return null
            while (pos < tokens.size) {
                val token = tokens[pos]
                if (token is Token.Op && token.op == "or") {
                    pos++
                    val right = parseAnd()
                    if (right == null) { error = "find: expected an expression after `-o'\n"; return null }
                    left = Expression.Or(left, right)
                } else break
            }
            return left
        }

        private fun parseAnd(): Expression? {
            var left = parseNot() ?: return null
            while (pos < tokens.size) {
                val token = tokens[pos]
                if (token is Token.Op && token.op == "and") {
                    pos++
                    val right = parseNot()
                    if (right == null) { error = "find: expected an expression after `-a'\n"; return null }
                    left = Expression.And(left, right)
                } else if (token is Token.Expr || token is Token.Not || token is Token.LParen) {
                    val right = parseNot() ?: return left
                    left = Expression.And(left, right)
                } else break
            }
            return left
        }

        private fun parseNot(): Expression? {
            if (pos < tokens.size && tokens[pos] is Token.Not) {
                pos++
                val expr = parseNot()
                if (expr == null) { error = "find: expected an expression after `!'\n"; return null }
                return Expression.Not(expr)
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Expression? {
            if (pos >= tokens.size) return null
            val token = tokens[pos]
            if (token is Token.LParen) {
                pos++
                val expr = parseOr()
                if (expr == null || pos >= tokens.size || tokens[pos] !is Token.RParen) {
                    error = "find: missing closing `)'\n"; return null
                }
                pos++
                return expr
            }
            if (token is Token.Expr) { pos++; return token.expr }
            return null
        }

        private fun containsNegatedDelete(expr: Expression, negated: Boolean): Boolean = when (expr) {
            is Expression.Action -> negated && expr.action is FindAction.Delete
            is Expression.Not -> containsNegatedDelete(expr.expr, !negated)
            is Expression.And -> containsNegatedDelete(expr.left, negated) || containsNegatedDelete(expr.right, negated)
            is Expression.Or -> containsNegatedDelete(expr.left, negated) || containsNegatedDelete(expr.right, negated)
            else -> false
        }
    }

    }

// ---------------------------------------------------------------------------
// Matcher (port of matcher.ts)
// ---------------------------------------------------------------------------

private object Matcher {

    fun evaluate(expr: Expression, ctx: EvalContext): EvalResult {
        return when (expr) {
        is Expression.Name -> {
            val extMatch = Regex("^\\*(\\.[a-zA-Z0-9]+)$").matchEntire(expr.pattern)
            if (extMatch != null) {
                val requiredExt = extMatch.groupValues[1]
                when {
                    expr.ignoreCase && !ctx.name.lowercase().endsWith(requiredExt.lowercase()) -> EvalResult(false)
                    !expr.ignoreCase && !ctx.name.endsWith(requiredExt) -> EvalResult(false)
                    else -> EvalResult(true)
                }
            } else EvalResult(matchGlob(ctx.name, expr.pattern, expr.ignoreCase))
        }
        is Expression.PathM -> {
            val pattern = expr.pattern
            val path = ctx.relativePath
            val segments = pattern.split("/")
            for (idx in 0 until segments.size - 1) {
                val seg = segments[idx]
                if (seg.isNotEmpty() && seg != "." && seg != ".." &&
                    !seg.contains("*") && !seg.contains("?") && !seg.contains("[")
                ) {
                    val requiredSegment = "/$seg/"
                    if (expr.ignoreCase) {
                        if (!path.lowercase().contains(requiredSegment.lowercase())) return EvalResult(false)
                    } else {
                        if (!path.contains(requiredSegment)) return EvalResult(false)
                    }
                }
            }
            val extMatch = Regex("\\*(\\.[a-zA-Z0-9]+)$").find(pattern)
            if (extMatch != null) {
                val requiredExt = extMatch.groupValues[1]
                if (expr.ignoreCase) {
                    if (!path.lowercase().endsWith(requiredExt.lowercase())) return EvalResult(false)
                } else {
                    if (!path.endsWith(requiredExt)) return EvalResult(false)
                }
            }
            EvalResult(matchGlob(path, pattern, expr.ignoreCase))
        }
        is Expression.RegEx -> {
            val regex = compileUserRegex(expr.pattern, expr.ignoreCase)
            if (regex == null) EvalResult(false) else EvalResult(regex.matcher(ctx.relativePath).find())
        }
        is Expression.TypeOf -> when (expr.fileType) {
            'f' -> EvalResult(ctx.isFile)
            'd' -> EvalResult(ctx.isDirectory)
            else -> EvalResult(false)
        }
        is Expression.Empty -> EvalResult(ctx.isEmpty)
        is Expression.MTime -> {
            val now = System.currentTimeMillis()
            val fileAgeDays = (now - ctx.mtime).toDouble() / (1000.0 * 60 * 60 * 24)
            val matches = when (expr.comparison) {
                Comparison.MORE -> fileAgeDays > expr.days
                Comparison.LESS -> fileAgeDays < expr.days
                Comparison.EXACT -> Math.floor(fileAgeDays).toLong() == expr.days
            }
            EvalResult(matches)
        }
        is Expression.Newer -> {
            val refMtime = ctx.newerRefTimes[expr.refPath]
            if (refMtime == null) EvalResult(false) else EvalResult(ctx.mtime > refMtime)
        }
        is Expression.SizeOf -> {
            val multiplier: Long = when (expr.unit) {
                SizeUnit.C -> 1L
                SizeUnit.K -> 1024L
                SizeUnit.M -> 1024L * 1024L
                SizeUnit.G -> 1024L * 1024L * 1024L
                SizeUnit.B -> 512L
            }
            val targetBytes = expr.value * multiplier
            val matches = when (expr.comparison) {
                Comparison.MORE -> ctx.size > targetBytes
                Comparison.LESS -> ctx.size < targetBytes
                Comparison.EXACT ->
                    if (expr.unit == SizeUnit.B) Math.ceil(ctx.size.toDouble() / 512.0).toLong() == expr.value
                    else ctx.size == targetBytes
            }
            EvalResult(matches)
        }
        is Expression.Perm -> {
            val fileMode = ctx.mode and 0x1FF
            val targetMode = expr.mode and 0x1FF
            val matches = when (expr.matchType) {
                PermMatchType.EXACT -> fileMode == targetMode
                PermMatchType.ALL -> (fileMode and targetMode) == targetMode
                PermMatchType.ANY -> (fileMode and targetMode) != 0
            }
            EvalResult(matches)
        }
        is Expression.Prune -> EvalResult(true, pruned = true)
        is Expression.Action -> {
            val r = EvalResult(true)
            r.actions = mutableListOf(expr.action)
            r
        }
        is Expression.Not -> {
            val inner = evaluate(expr.expr, ctx)
            EvalResult(!inner.matches, pruned = inner.pruned, printed = false, actions = inner.actions)
        }
        is Expression.And -> {
            val left = evaluate(expr.left, ctx)
            if (!left.matches) EvalResult(false, pruned = left.pruned, printed = false, actions = left.actions)
            else {
                val right = evaluate(expr.right, ctx)
                EvalResult(
                    right.matches, pruned = left.pruned || right.pruned,
                    printed = left.printed || right.printed,
                    actions = combine(left.actions, right.actions),
                )
            }
        }
        is Expression.Or -> {
            val left = evaluate(expr.left, ctx)
            if (left.matches) left
            else {
                val right = evaluate(expr.right, ctx)
                EvalResult(
                    right.matches, pruned = left.pruned || right.pruned,
                    printed = right.printed,
                    actions = combine(left.actions, right.actions),
                )
            }
        }
        }
    }

    private fun combine(a: MutableList<FindAction>?, b: MutableList<FindAction>?): MutableList<FindAction>? {
        if (a == null && b == null) return null
        val out = ArrayList<FindAction>()
        if (a != null) out.addAll(a)
        if (b != null) out.addAll(b)
        return out
    }

    fun needsStat(expr: Expression?): Boolean = when (expr) {
        null -> false
        is Expression.Name, is Expression.PathM, is Expression.RegEx, is Expression.TypeOf,
        is Expression.Prune, is Expression.Action -> false
        is Expression.Empty, is Expression.MTime, is Expression.Newer, is Expression.SizeOf,
        is Expression.Perm -> true
        is Expression.Not -> needsStat(expr.expr)
        is Expression.And -> needsStat(expr.left) || needsStat(expr.right)
        is Expression.Or -> needsStat(expr.left) || needsStat(expr.right)
    }

    fun needsEmpty(expr: Expression?): Boolean = when (expr) {
        null -> false
        is Expression.Empty -> true
        is Expression.Not -> needsEmpty(expr.expr)
        is Expression.And -> needsEmpty(expr.left) || needsEmpty(expr.right)
        is Expression.Or -> needsEmpty(expr.left) || needsEmpty(expr.right)
        else -> false
    }

    fun hasPrune(expr: Expression?): Boolean = when (expr) {
        null -> false
        is Expression.Prune -> true
        is Expression.Not -> hasPrune(expr.expr)
        is Expression.And -> hasPrune(expr.left) || hasPrune(expr.right)
        is Expression.Or -> hasPrune(expr.left) || hasPrune(expr.right)
        else -> false
    }

    fun collectNewerRefs(expr: Expression?): List<String> {
        val refs = ArrayList<String>()
        fun collect(e: Expression?) {
            when (e) {
                null -> {}
                is Expression.Newer -> refs.add(e.refPath)
                is Expression.Not -> collect(e.expr)
                is Expression.And -> { collect(e.left); collect(e.right) }
                is Expression.Or -> { collect(e.left); collect(e.right) }
                else -> {}
            }
        }
        collect(expr)
        return refs
    }

    fun collectActions(expr: Expression?): List<FindAction> = when (expr) {
        null -> emptyList()
        is Expression.Action -> listOf(expr.action)
        is Expression.Not -> collectActions(expr.expr)
        is Expression.And -> collectActions(expr.left) + collectActions(expr.right)
        is Expression.Or -> collectActions(expr.left) + collectActions(expr.right)
        else -> emptyList()
    }
}

// ---------------------------------------------------------------------------
// Find command (port of find.ts)
// ---------------------------------------------------------------------------

object FindCommand : Command {
    override val name = "find"

    private val optHelp = listOf(
        "-name PATTERN    file name matches shell pattern PATTERN",
        "-iname PATTERN   like -name but case insensitive",
        "-path PATTERN    file path matches shell pattern PATTERN",
        "-ipath PATTERN   like -path but case insensitive",
        "-regex PATTERN   file path matches regular expression PATTERN",
        "-iregex PATTERN  like -regex but case insensitive",
        "-type TYPE       file is of type: f (regular file), d (directory)",
        "-empty           file is empty or directory is empty",
        "-mtime N         file's data was modified N*24 hours ago",
        "-newer FILE      file was modified more recently than FILE",
        "-size N[ckMGb]   file uses N units of space (c=bytes, k=KB, M=MB, G=GB, b=512B blocks)",
        "-perm MODE       file's permission bits are exactly MODE (octal)",
        "-perm -MODE      all permission bits MODE are set",
        "-perm /MODE      any permission bits MODE are set",
        "-maxdepth LEVELS descend at most LEVELS directories",
        "-mindepth LEVELS do not apply tests at levels less than LEVELS",
        "-depth           process directory contents before directory itself",
        "-prune           do not descend into this directory",
        "-not, !          negate the following expression",
        "-a, -and         logical AND (default)",
        "-o, -or          logical OR",
        "-exec CMD {} ;   execute CMD on each file ({} is replaced by filename)",
        "-exec CMD {} +   execute CMD with multiple files at once",
        "-print           print the full file name (default action)",
        "-print0          print the full file name followed by a null character",
        "-printf FORMAT   print FORMAT with directives: %f %h %p %P %s %d %m %M %t",
        "-ls              list current file in ls -dils format",
        "-delete          delete found files/directories",
        "    --help       display this help and exit",
    )

    private class LoopLimitExceeded : RuntimeException()

    private data class EffectData(
        val path: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        val mode: Int,
        val isDirectory: Boolean,
        val depth: Int,
        val startingPoint: String,
    )

    private class Effect(val action: FindAction, val data: EffectData)

    private fun childRelPath(parentRel: String, childName: String): String = when {
        parentRel == "." -> childName
        parentRel == "/" -> "/$childName"
        parentRel.endsWith("/") -> "$parentRel$childName"
        else -> "$parentRel/$childName"
    }

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "find", "search for files in a directory hierarchy",
                "find [path...] [expression]", optHelp,
            )
        }

        val searchPaths = ArrayList<String>()
        var maxDepth: Long? = null
        var minDepth: Long? = null
        var depthFirst = false

        var expressionStart = args.size
        for (i in args.indices) {
            val arg = args[i]
            if (arg.startsWith("-") || arg == "(" || arg == "\\(" || arg == ")" ||
                arg == "\\)" || arg == "!") {
                expressionStart = i
                break
            }
            searchPaths.add(arg)
        }
        if (searchPaths.isEmpty()) searchPaths.add(".")

        var i = expressionStart
        while (i < args.size) {
            val arg = args[i]
            if (arg == "-exec" || arg == "-ok") {
                i++
                while (i < args.size && args[i] != ";" && args[i] != "+") i++
            } else if (arg == "-maxdepth" || arg == "-mindepth") {
                val value = if (i + 1 < args.size) args[i + 1] else null
                if (value == null || !value.matches(Regex("\\d+"))) {
                    return ExecResult(
                        stderr = if (value == null) "find: missing argument to `$arg'\n"
                        else "find: invalid argument `$value' to `$arg'\n",
                        exitCode = 1,
                    )
                }
                val depth = value.toLongOrNull()
                if (depth == null) return ExecResult(stderr = "find: invalid argument `$value' to `$arg'\n", exitCode = 1)
                if (arg == "-maxdepth") maxDepth = depth else minDepth = depth
                i++
            } else if (arg == "-depth") {
                depthFirst = true
            }
            i++
        }

        val parsed = ExpressionParser.parse(args, expressionStart)
        if (parsed.error != null) return ExecResult(stderr = parsed.error, exitCode = 1)
        val expr = parsed.expr

        val expressionActions = Matcher.collectActions(expr)
        val hasAnyAction = expressionActions.isNotEmpty()
        if (expressionActions.any { it is FindAction.Delete }) depthFirst = true

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = 0

        val needsEmpty = Matcher.needsEmpty(expr)

        val newerRefTimes = HashMap<String, Long>()
        for (refPath in Matcher.collectNewerRefs(expr)) {
            val refFullPath = ctx.fs.resolvePath(ctx.cwd, refPath)
            try {
                newerRefTimes[refPath] = ctx.fs.stat(refFullPath).mtime.toEpochMilli()
            } catch (_: Exception) {
                // reference file doesn't exist -> -newer always false
            }
        }

        var loopIterations = 0L

        fun checkLimit() {
            loopIterations++
            if (loopIterations > ctx.limits.maxLoopIterations) throw LoopLimitExceeded()
        }

        for (rawSearchPath in searchPaths) {
            val searchPath = if (rawSearchPath.length > 1 && rawSearchPath.endsWith("/")) {
                rawSearchPath.dropLast(1)
            } else rawSearchPath
            val basePath = ctx.fs.resolvePath(ctx.cwd, searchPath)

            try {
                ctx.fs.lstat(basePath)
            } catch (_: Exception) {
                stderr.append("find: $searchPath: No such file or directory\n")
                exitCode = 1
                continue
            }

            val effects = ArrayList<Effect>()

            fun walk(absPath: String, relPath: String, name: String, depth: Long) {
                checkLimit()
                if (depth > (maxDepth ?: Long.MAX_VALUE)) return
                if (depth >= ctx.limits.maxCallDepth) return

                val stat: FsStat
                val isFile: Boolean
                val isDirectory: Boolean
                try {
                    stat = ctx.fs.lstat(absPath)
                    isFile = stat.isFile
                    isDirectory = stat.isDirectory
                } catch (_: Exception) {
                    return
                }

                val shouldDescend = isDirectory && depth < (maxDepth ?: Long.MAX_VALUE)
                val shouldReadDir = isDirectory && (shouldDescend || needsEmpty)
                val childrenEntries: List<DirentEntry> =
                    if (shouldReadDir) {
                        try { ctx.fs.readdirWithFileTypes(absPath) } catch (_: Exception) { emptyList() }
                    } else emptyList()

                val isEmpty = when {
                    isFile -> stat.size == 0L
                    isDirectory -> shouldReadDir && childrenEntries.isEmpty()
                    else -> false
                }

                val atOrBeyondMinDepth = minDepth == null || depth >= (minDepth ?: 0L)

                var matches = true
                var actions: MutableList<FindAction>? = null
                var pruned = false
                if (expr != null) {
                    val evalCtx = EvalContext(
                        name = name,
                        relativePath = relPath,
                        isFile = isFile,
                        isDirectory = isDirectory,
                        isEmpty = isEmpty,
                        mtime = stat.mtime.toEpochMilli(),
                        size = stat.size,
                        mode = stat.mode,
                        newerRefTimes = newerRefTimes,
                        depth = depth.toInt(),
                        startingPoint = searchPath,
                    )
                    val result = Matcher.evaluate(expr, evalCtx)
                    matches = result.matches
                    pruned = result.pruned
                    actions = result.actions
                }

                val effectiveActions: List<FindAction> = when {
                    !atOrBeyondMinDepth -> emptyList()
                    matches && hasAnyAction -> actions ?: emptyList()
                    matches -> listOf(FindAction.Print)
                    else -> emptyList()
                }

                val data = EffectData(
                    relPath, name, stat.size, stat.mtime.toEpochMilli(), stat.mode,
                    isDirectory, depth.toInt(), searchPath,
                )

                if (!depthFirst) {
                    for (a in effectiveActions) effects.add(Effect(a, data))
                }

                if (shouldDescend && !pruned) {
                    for (entry in childrenEntries) {
                        val childAbs = if (absPath == "/") "/${entry.name}" else "$absPath/${entry.name}"
                        walk(childAbs, childRelPath(relPath, entry.name), entry.name, depth + 1)
                    }
                }

                if (depthFirst) {
                    for (a in effectiveActions) effects.add(Effect(a, data))
                }
            }

            val relBase = if (searchPath == ".") "." else searchPath
            val baseName = if (searchPath == "/") "/" else searchPath.substringAfterLast("/")
            walk(basePath, relBase, baseName, 0L)

            // Execute collected effects.
            val batchExecPaths = LinkedHashMap<List<String>, ArrayList<String>>()
            for (effect in effects) {
                checkLimit()
                when (val action = effect.action) {
                    is FindAction.Print -> stdout.append(effect.data.path).append('\n')
                    is FindAction.Print0 -> stdout.append(effect.data.path).append('\u0000')
                    is FindAction.Printf -> stdout.append(formatPrintf(action.format, effect.data))
                    is FindAction.Ls -> stdout.append(formatLs(effect.data))
                    is FindAction.Delete -> {
                        val fullPath = ctx.fs.resolvePath(ctx.cwd, effect.data.path)
                        try {
                            ctx.fs.rm(fullPath, RmOptions(recursive = false))
                        } catch (e: Exception) {
                            stderr.append("find: cannot delete '${effect.data.path}': ${e.message}\n")
                            exitCode = 1
                        }
                    }
                    is FindAction.Exec -> {
                        val exec = ctx.exec
                        if (exec == null) {
                            return ExecResult(
                                stdout = "",
                                stderr = "find: -exec not supported in this context\n",
                                exitCode = 1,
                            )
                        }
                        if (action.batchMode) {
                            batchExecPaths.getOrPut(action.command) { ArrayList() }.add(effect.data.path)
                        } else {
                            val cmdWithFile = action.command.map { part -> if (part == "{}") effect.data.path else part }
                            val result = exec(cmdWithFile[0], CommandExecOptions(
                                cwd = ctx.cwd,
                                args = cmdWithFile.drop(1),
                            ))
                            stdout.append(result.stdout)
                            stderr.append(result.stderr)
                            if (result.exitCode != 0) exitCode = result.exitCode
                        }
                    }
                }
            }

            // Flush batch -exec invocations.
            for ((command, paths) in batchExecPaths) {
                checkLimit()
                val exec = ctx.exec
                if (exec == null || paths.isEmpty()) continue
                val cmdWithFiles = ArrayList<String>()
                for (part in command) {
                    if (part == "{}") cmdWithFiles.addAll(paths) else cmdWithFiles.add(part)
                }
                val result = exec(cmdWithFiles[0], CommandExecOptions(
                    cwd = ctx.cwd,
                    args = cmdWithFiles.drop(1),
                ))
                stdout.append(result.stdout)
                stderr.append(result.stderr)
                if (result.exitCode != 0) exitCode = result.exitCode
            }
        }

        return ExecResult(stdout = stdout.toString(), stderr = stderr.toString(), exitCode = exitCode)
    }

    // ------------------------------------------------------------------ //
    // printf helpers (port of printf/escapes.ts and find.ts formatting)
    // ------------------------------------------------------------------ //

    private fun printfNeedsStat(format: String): Boolean {
        val withoutPercent = format.replace("%%", "")
        return Regex("%[-+]?[0-9]*\\.?[0-9]*(s|m|M|t|T)").containsMatchIn(withoutPercent)
    }

    private fun processEscapes(str: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < str.length) {
            if (str[i] == '\\' && i + 1 < str.length) {
                val next = str[i + 1]
                when (next) {
                    'n' -> { result.append('\n'); i += 2 }
                    't' -> { result.append('\t'); i += 2 }
                    'r' -> { result.append('\r'); i += 2 }
                    '\\' -> { result.append('\\'); i += 2 }
                    'a' -> { result.append('\u0007'); i += 2 }
                    'b' -> { result.append('\b'); i += 2 }
                    'f' -> { result.append('\u000c'); i += 2 }
                    'v' -> { result.append('\u000b'); i += 2 }
                    'e', 'E' -> { result.append('\u001b'); i += 2 }
                    '0', '1', '2', '3', '4', '5', '6', '7' -> {
                        var j = i + 1
                        var octal = ""
                        while (j < str.length && j < i + 4 && str[j] in '0'..'7') {
                            octal += str[j]; j++
                        }
                        val code = octal.toIntOrNull(8) ?: 0
                        result.append((code and 0xFF).toChar())
                        i = j
                    }
                    'x' -> {
                        val bytes = ArrayList<Int>()
                        var j = i
                        while (j + 3 < str.length && str[j] == '\\' && str[j + 1] == 'x' &&
                            isHexChar(str[j + 2]) && isHexChar(str[j + 3])) {
                            bytes.add(str.substring(j + 2, j + 4).toInt(16))
                            j += 4
                        }
                        if (bytes.isNotEmpty()) {
                            val arr = ByteArray(bytes.size) { bytes[it].toByte() }
                            result.append(String(arr, Charsets.UTF_8))
                            i = j
                        } else {
                            result.append('\\'); i++
                        }
                    }
                    'u' -> {
                        var hex = ""
                        var j = i + 2
                        while (j < str.length && j < i + 6 && isHexChar(str[j])) { hex += str[j]; j++ }
                        if (hex.isNotEmpty()) {
                            result.appendCodePoint(hex.toInt(16)); i = j
                        } else { result.append("\\u"); i += 2 }
                    }
                    'U' -> {
                        var hex = ""
                        var j = i + 2
                        while (j < str.length && j < i + 10 && isHexChar(str[j])) { hex += str[j]; j++ }
                        if (hex.isNotEmpty()) {
                            result.appendCodePoint(hex.toInt(16)); i = j
                        } else { result.append("\\U"); i += 2 }
                    }
                    else -> { result.append(str[i]); i++ }
                }
            } else {
                result.append(str[i]); i++
            }
        }
        return result.toString()
    }

    private class WidthPrecision(val width: Int, val precision: Int, val consumed: Int)

    private fun parseWidthPrecision(format: String, startIndex: Int): WidthPrecision {
        var i = startIndex
        var width = 0
        var precision = -1
        var leftJustify = false
        if (i < format.length && format[i] == '-') { leftJustify = true; i++ }
        while (i < format.length && format[i].isDigit()) { width = width * 10 + (format[i] - '0'); i++ }
        if (i < format.length && format[i] == '.') {
            i++
            precision = 0
            while (i < format.length && format[i].isDigit()) { precision = precision * 10 + (format[i] - '0'); i++ }
        }
        if (leftJustify && width > 0) width = -width
        return WidthPrecision(width, precision, i - startIndex)
    }

    private fun applyWidth(value: String, width: Int, precision: Int): String {
        var result = value
        if (precision >= 0 && result.length > precision) result = result.take(precision)
        val absWidth = Math.abs(width)
        if (absWidth > result.length) {
            result = if (width < 0) result.padEnd(absWidth, ' ') else result.padStart(absWidth, ' ')
        }
        return result
    }

    private fun formatPrintf(format: String, data: EffectData): String {
        val processed = processEscapes(format)
        val output = StringBuilder()
        var i = 0
        while (i < processed.length) {
            if (processed[i] == '%' && i + 1 < processed.length) {
                i++
                if (processed[i] == '%') { output.append('%'); i++; continue }
                val wp = parseWidthPrecision(processed, i)
                i += wp.consumed
                if (i >= processed.length) { output.append('%'); break }
                val directive = processed[i]
                val value: String
                when (directive) {
                    'f' -> { value = data.name; i++ }
                    'h' -> {
                        val lastSlash = data.path.lastIndexOf('/')
                        value = if (lastSlash > 0) data.path.substring(0, lastSlash) else "."; i++
                    }
                    'p' -> { value = data.path; i++ }
                    'P' -> {
                        val sp = data.startingPoint
                        value = when {
                            data.path == sp -> ""
                            data.path.startsWith("$sp/") -> data.path.substring(sp.length + 1)
                            sp == "." && data.path.startsWith("./") -> data.path.substring(2)
                            else -> data.path
                        }; i++
                    }
                    's' -> { value = data.size.toString(); i++ }
                    'd' -> { value = data.depth.toString(); i++ }
                    'm' -> { value = (data.mode and 0x1FF).toString(8); i++ }
                    'M' -> { value = formatMode(data.mode, data.isDirectory); i++ }
                    't' -> { value = formatCtimeDate(data.mtime); i++ }
                    'T' -> {
                        if (i + 1 < processed.length) {
                            value = formatTimeDirective(data.mtime, processed[i + 1])
                            i += 2
                        } else { value = "%T"; i++ }
                    }
                    else -> {
                        output.append('%')
                        if (wp.width != 0 || wp.precision != -1) {
                            output.append(wp.width).append('.').append(wp.precision)
                        }
                        output.append(directive)
                        i++
                        continue
                    }
                }
                output.append(applyWidth(value, wp.width, wp.precision))
            } else {
                output.append(processed[i]); i++
            }
        }
        return output.toString()
    }

    private fun formatLs(data: EffectData): String {
        // GNU find -ls prints: inode blocks mode links owner group size date name
        // We render a stable, simplified row that still includes the key fields.
        val size = data.size
        val name = data.path
        return "$size 0 ${formatMode(data.mode, data.isDirectory)} 1 root root $size $name"
    }

    private fun formatMode(mode: Int, isDirectory: Boolean): String {
        val typeChar = if (isDirectory) "d" else "-"
        val perms = buildString {
            append(if (mode and 0x100 != 0) "r" else "-")
            append(if (mode and 0x80 != 0) "w" else "-")
            append(if (mode and 0x40 != 0) "x" else "-")
            append(if (mode and 0x20 != 0) "r" else "-")
            append(if (mode and 0x10 != 0) "w" else "-")
            append(if (mode and 0x8 != 0) "x" else "-")
            append(if (mode and 0x4 != 0) "r" else "-")
            append(if (mode and 0x2 != 0) "w" else "-")
            append(if (mode and 0x1 != 0) "x" else "-")
        }
        return typeChar + perms
    }

    private fun formatCtimeDate(mtime: Long): String {
        val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val instant = java.time.Instant.ofEpochMilli(mtime)
        val dt = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val day = days[dt.dayOfWeek.value % 7]
        val month = months[dt.monthValue - 1]
        val dayNum = dt.dayOfMonth.toString().padStart(2, ' ')
        val hours = dt.hour.toString().padStart(2, '0')
        val mins = dt.minute.toString().padStart(2, '0')
        val secs = dt.second.toString().padStart(2, '0')
        return "$day $month $dayNum $hours:$mins:$secs ${dt.year}"
    }

    private fun formatTimeDirective(mtime: Long, format: Char): String {
        val instant = java.time.Instant.ofEpochMilli(mtime)
        val dt = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        return when (format) {
            '@' -> (mtime.toDouble() / 1000.0).toString()
            'Y' -> dt.year.toString()
            'm' -> dt.monthValue.toString().padStart(2, '0')
            'd' -> dt.dayOfMonth.toString().padStart(2, '0')
            'H' -> dt.hour.toString().padStart(2, '0')
            'M' -> dt.minute.toString().padStart(2, '0')
            'S' -> dt.second.toString().padStart(2, '0')
            'T' -> "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}:${dt.second.toString().padStart(2, '0')}"
            'F' -> "${dt.year}-${dt.monthValue.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')}"
            else -> "%T$format"
        }
    }
}