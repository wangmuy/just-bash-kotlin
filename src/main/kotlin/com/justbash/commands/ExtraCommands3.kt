package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult

/**
 * Port of just-bash `expr/expr.ts` — evaluate expressions.
 */
object ExprCommand : Command {
    override val name = "expr"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (args.isEmpty()) {
            return ExecResult(stdout = "", stderr = "expr: missing operand\n", exitCode = 2)
        }
        try {
            val result = evaluateExpr(args)
            val exitCode = if (result == "0" || result == "") 1 else 0
            return ExecResult(stdout = "$result\n", stderr = "", exitCode = exitCode)
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            return ExecResult(stdout = "", stderr = "expr: $msg\n", exitCode = 2)
        }
    }

    private fun evaluateExpr(args: List<String>): String {
        if (args.size == 1) return args[0]
        val evaluator = ExprEvaluator(args)
        return evaluator.evaluate()
    }
}

/**
 * Recursive-descent expr evaluator.
 * Precedence (lowest to highest): |  &  = != < <= > >=  + -  * / %  :
 */
private class ExprEvaluator(private val args: List<String>) {
    private var i = 0

    fun evaluate(): String {
        val result = parseOr()
        if (i != args.size) throw IllegalArgumentException("syntax error")
        return result
    }

    private fun parseOr(): String {
        var left = parseAnd()
        while (i < args.size && args[i] == "|") {
            i++
            val right = parseAnd()
            if (left != "0" && left != "") return left
            left = right
        }
        return left
    }

    private fun parseAnd(): String {
        var left = parseComparison()
        while (i < args.size && args[i] == "&") {
            i++
            val right = parseComparison()
            if (left == "0" || left == "" || right == "0" || right == "") {
                left = "0"
            }
        }
        return left
    }

    private fun parseComparison(): String {
        var left = parseAddSub()
        while (i < args.size) {
            val op = args[i]
            if (op == "=" || op == "!=" || op == "<" || op == ">" || op == "<=" || op == ">=") {
                i++
                val right = parseAddSub()
                val leftNum = left.toIntOrNull()
                val rightNum = right.toIntOrNull()
                val isNumeric = leftNum != null && rightNum != null

                val result = when (op) {
                    "=" -> if (isNumeric) leftNum == rightNum else left == right
                    "!=" -> if (isNumeric) leftNum != rightNum else left != right
                    "<" -> if (isNumeric) leftNum!! < rightNum!! else left < right
                    ">" -> if (isNumeric) leftNum!! > rightNum!! else left > right
                    "<=" -> if (isNumeric) leftNum!! <= rightNum!! else left <= right
                    else -> if (isNumeric) leftNum!! >= rightNum!! else left >= right
                }
                left = if (result) "1" else "0"
            } else {
                break
            }
        }
        return left
    }

    private fun parseAddSub(): String {
        var left = parseMulDiv()
        while (i < args.size) {
            val op = args[i]
            if (op == "+" || op == "-") {
                i++
                val right = parseMulDiv()
                val leftNum = left.toIntOrNull()
                val rightNum = right.toIntOrNull()
                if (leftNum == null || rightNum == null) {
                    throw IllegalArgumentException("non-integer argument")
                }
                left = (if (op == "+") leftNum + rightNum else leftNum - rightNum).toString()
            } else {
                break
            }
        }
        return left
    }

    private fun parseMulDiv(): String {
        var left = parseMatch()
        while (i < args.size) {
            val op = args[i]
            if (op == "*" || op == "/" || op == "%") {
                i++
                val right = parseMatch()
                val leftNum = left.toIntOrNull()
                val rightNum = right.toIntOrNull()
                if (leftNum == null || rightNum == null) {
                    throw IllegalArgumentException("non-integer argument")
                }
                if ((op == "/" || op == "%") && rightNum == 0) {
                    throw IllegalArgumentException("division by zero")
                }
                left = when (op) {
                    "*" -> (leftNum * rightNum).toString()
                    "/" -> (leftNum / rightNum).toString()
                    else -> (leftNum % rightNum).toString()
                }
            } else {
                break
            }
        }
        return left
    }

    private fun parseMatch(): String {
        var left = parsePrimary()
        while (i < args.size && args[i] == ":") {
            i++
            val pattern = parsePrimary()
            val regex = Regex("^$pattern")
            val match = regex.find(left)
            if (match != null) {
                val groups = match.groupValues
                left = if (groups.size > 1 && groups[1].isNotEmpty()) {
                    groups[1]
                } else {
                    match.value.length.toString()
                }
            } else {
                left = "0"
            }
        }
        return left
    }

    private fun parsePrimary(): String {
        if (i >= args.size) throw IllegalArgumentException("syntax error")

        val token = args[i]

        when (token) {
            "match" -> {
                i++
                val str = parsePrimary()
                val pattern = parsePrimary()
                val regex = Regex(pattern)
                val match = regex.find(str)
                return if (match != null) {
                    val groups = match.groupValues
                    if (groups.size > 1 && groups[1].isNotEmpty()) groups[1] else match.value.length.toString()
                } else "0"
            }
            "substr" -> {
                i++
                val str = parsePrimary()
                val pos = parsePrimary().toIntOrNull()
                    ?: throw IllegalArgumentException("non-integer argument")
                val len = parsePrimary().toIntOrNull()
                    ?: throw IllegalArgumentException("non-integer argument")
                // expr uses 1-based indexing
                val start = (pos - 1).coerceAtLeast(0)
                val end = (start + len).coerceAtMost(str.length)
                return if (start >= str.length) "" else str.substring(start, end)
            }
            "index" -> {
                i++
                val str = parsePrimary()
                val chars = parsePrimary()
                val charSet = chars.toSet()
                for (j in str.indices) {
                    if (str[j] in charSet) return (j + 1).toString() // 1-based
                }
                return "0"
            }
            "length" -> {
                i++
                val str = parsePrimary()
                return str.length.toString()
            }
            "(" -> {
                i++
                val result = parseOr()
                if (i >= args.size || args[i] != ")") {
                    throw IllegalArgumentException("syntax error")
                }
                i++
                return result
            }
            else -> {
                i++
                return token
            }
        }
    }
}

/**
 * Port of just-bash `readlink/readlink.ts` — print resolved symbolic links
 * or canonical file names.
 */
object ReadlinkCommand : Command {
    override val name = "readlink"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "readlink", "print resolved symbolic links or canonical file names",
                "readlink [OPTIONS] FILE...",
                listOf(
                    "-f      canonicalize by following every symlink in every component",
                    "-e, --canonicalize-existing  canonicalize, error if doesn't exist",
                    "-m, --canonicalize-missing    canonicalize, missing components allowed",
                    "-n, --no-newline              do not output the trailing newline",
                    "    --help                     display this help and exit",
                ),
            )
        }

        var canonicalize = false
        var canonicalizeExisting = false
        var canonicalizeMissing = false
        var noNewline = false
        var argIdx = 0

        while (argIdx < args.size && args[argIdx].startsWith("-")) {
            when (val arg = args[argIdx]) {
                "-f", "--canonicalize" -> {
                    canonicalize = true
                    argIdx++
                }
                "-e", "--canonicalize-existing" -> {
                    canonicalizeExisting = true
                    argIdx++
                }
                "-m", "--canonicalize-missing" -> {
                    canonicalizeMissing = true
                    argIdx++
                }
                "-n", "--no-newline" -> {
                    noNewline = true
                    argIdx++
                }
                "--" -> {
                    argIdx++
                    break
                }
                else -> return unknownOption("readlink", arg)
            }
        }

        val files = args.subList(argIdx, args.size)

        if (files.isEmpty()) {
            return ExecResult(stdout = "", stderr = "readlink: missing operand\n", exitCode = 1)
        }

        val resolveMode = if (canonicalizeExisting) "existing"
            else if (canonicalizeMissing) "missing"
            else if (canonicalize) "canonicalize"
            else "plain"

        val results = ArrayList<String>()
        var anyError = false

        for (file in files) {
            val filePath = ctx.fs.resolvePath(ctx.cwd, file)

            try {
                val out = when (resolveMode) {
                    "plain" -> ctx.fs.readlink(filePath)
                    "canonicalize" ->
                        // Follow all symlinks, return resolved path even if not a symlink
                        resolveAllSymlinks(filePath, ctx)
                    // canonicalize, error if doesn't exist
                    "existing" -> ctx.fs.realpath(filePath)
                    // canonicalize, missing components allowed
                    "missing" -> resolveMissingAllowed(filePath, ctx)
                    else -> filePath
                }
                results.add(out)
            } catch (e: Exception) {
                when (resolveMode) {
                    "plain", "existing" -> anyError = true
                    else -> results.add(filePath) // -f / -m: fall back to the literal path
                }
            }
        }

        var stdout = results.joinToString("\n")
        if (stdout.isNotEmpty() && !noNewline) stdout += "\n"

        return ExecResult(stdout = stdout, stderr = "", exitCode = if (anyError) 1 else 0)
    }

    private fun resolveAllSymlinks(path: String, ctx: CommandContext): String {
        var current = path
        val seen = LinkedHashSet<String>()

        while (true) {
            if (seen.contains(current)) break // circular symlink
            seen.add(current)

            try {
                val target = ctx.fs.readlink(current)
                current = if (target.startsWith("/")) {
                    target
                } else {
                    val dir = current.substringBeforeLast("/", "/")
                    ctx.fs.resolvePath(dir, target)
                }
            } catch (_: Exception) {
                // Not a symlink or doesn't exist — we've reached the end
                break
            }
        }
        return current
    }

    private fun resolveMissingAllowed(path: String, ctx: CommandContext): String {
        // Walk up until we find a component that exists
        val components = path.split("/").filter { it.isNotEmpty() }
        val isAbsolute = path.startsWith("/")

        // Find the longest existing prefix
        var existingPrefix = if (isAbsolute) "/" else "."
        var remaining = ArrayList<String>()

        for (i in components.indices) {
            val testPath = if (isAbsolute) {
                "/" + components.subList(0, i + 1).joinToString("/")
            } else {
                components.subList(0, i + 1).joinToString("/")
            }
            val resolved = ctx.fs.resolvePath(ctx.cwd, testPath)
            if (ctx.fs.exists(resolved)) {
                existingPrefix = resolved
                remaining = ArrayList(components.subList(i + 1, components.size))
            } else {
                remaining = ArrayList(components.subList(i, components.size))
                break
            }
        }

        // Resolve existing prefix through symlinks
        var result = try {
            resolveAllSymlinks(existingPrefix, ctx)
        } catch (_: Exception) {
            existingPrefix
        }

        // Append remaining components
        for (comp in remaining) {
            result = if (result.endsWith("/")) "$result$comp" else "$result/$comp"
        }

        return result
    }
}