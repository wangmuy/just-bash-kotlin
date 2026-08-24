package com.justbash.interpreter

import com.justbash.ast.*
import com.justbash.fs.IFileSystem

/**
 * Full conditional expression evaluation for `[[ ]]` and `[ ]` / `test`.
 *
 * Port of just-bash `src/interpreter/conditionals.ts` and
 * `src/interpreter/helpers/file-tests.ts`.
 */
object Conditionals {

    // ── file tests ────────────────────────────────────────────────────

    private val CHAR_DEVICES = setOf(
        "/dev/null", "/dev/zero", "/dev/random", "/dev/urandom",
        "/dev/tty", "/dev/stdin", "/dev/stdout", "/dev/stderr",
    )

    private val FILE_TEST_OPS = setOf(
        "-e", "-a", "-f", "-d", "-r", "-w", "-x", "-s",
        "-L", "-h", "-k", "-g", "-u", "-G", "-O",
        "-b", "-c", "-p", "-S", "-t", "-N", "-z", "-n",
    )

    private val BINARY_FILE_OPS = setOf("-nt", "-ot", "-ef")

    fun isFileTestOp(op: String): Boolean = op in FILE_TEST_OPS
    fun isBinaryFileTestOp(op: String): Boolean = op in BINARY_FILE_OPS

    fun evaluateFileTest(ctx: InterpreterContext, operator: String, operand: String): Boolean {
        val path = ctx.fs.resolvePath(ctx.state.cwd, operand)
        return when (operator) {
            "-e", "-a" -> ctx.fs.exists(path)
            "-f" -> try { ctx.fs.stat(path).isFile } catch (_: Exception) { false }
            "-d" -> try { ctx.fs.stat(path).isDirectory } catch (_: Exception) { false }
            "-r" -> try { (ctx.fs.stat(path).mode and 0x100) != 0 } catch (_: Exception) { false }
            "-w" -> try { (ctx.fs.stat(path).mode and 0x80) != 0 } catch (_: Exception) { false }
            "-x" -> try { (ctx.fs.stat(path).mode and 0x40) != 0 } catch (_: Exception) { false }
            "-s" -> try { ctx.fs.stat(path).size > 0 } catch (_: Exception) { false }
            "-L", "-h" -> try { ctx.fs.lstat(path).isSymbolicLink } catch (_: Exception) { false }
            "-k" -> try { (ctx.fs.stat(path).mode and 0x200) != 0 } catch (_: Exception) { false }
            "-g" -> try { (ctx.fs.stat(path).mode and 0x400) != 0 } catch (_: Exception) { false }
            "-u" -> try { (ctx.fs.stat(path).mode and 0x800) != 0 } catch (_: Exception) { false }
            "-G", "-O" -> ctx.fs.exists(path)
            "-b" -> false
            "-c" -> CHAR_DEVICES.contains(path)
            "-p" -> false
            "-S" -> false
            "-t" -> false
            "-N" -> ctx.fs.exists(path)
            else -> false
        }
    }

    fun evaluateBinaryFileTest(ctx: InterpreterContext, operator: String, left: String, right: String): Boolean {
        val leftPath = ctx.fs.resolvePath(ctx.state.cwd, left)
        val rightPath = ctx.fs.resolvePath(ctx.state.cwd, right)
        return when (operator) {
            "-nt" -> {
                val ls = try { ctx.fs.stat(leftPath) } catch (_: Exception) { null }
                val rs = try { ctx.fs.stat(rightPath) } catch (_: Exception) { null }
                ls != null && (rs == null || ls.mtime > rs.mtime)
            }
            "-ot" -> {
                val ls = try { ctx.fs.stat(leftPath) } catch (_: Exception) { null }
                val rs = try { ctx.fs.stat(rightPath) } catch (_: Exception) { null }
                rs != null && (ls == null || ls.mtime < rs.mtime)
            }
            "-ef" -> {
                try {
                    ctx.fs.realpath(leftPath) == ctx.fs.realpath(rightPath)
                } catch (_: Exception) { false }
            }
            else -> false
        }
    }

    // ── [[ ]] evaluation ──────────────────────────────────────────────

    fun evaluateConditional(ctx: InterpreterContext, node: ConditionalExpressionNode): Boolean =
        when (node) {
            is CondOrNode -> evaluateConditional(ctx, node.left) || evaluateConditional(ctx, node.right)
            is CondAndNode -> evaluateConditional(ctx, node.left) && evaluateConditional(ctx, node.right)
            is CondNotNode -> !evaluateConditional(ctx, node.operand)
            is CondGroupNode -> evaluateConditional(ctx, node.expression)
            is CondBinaryNode -> evaluateBinary(ctx, node)
            is CondUnaryNode -> evaluateUnary(ctx, node)
            is CondWordNode -> {
                // Single word: true if non-empty
                val expanded = ctx.wordExpander.expandWord(node.word)
                expanded.isNotEmpty()
            }
        }

    private fun evaluateBinary(ctx: InterpreterContext, node: CondBinaryNode): Boolean {
        val left = ctx.wordExpander.expandWord(node.left)
        val right = ctx.wordExpander.expandWord(node.right)
        return when (node.operator) {
            "=", "==" -> left == right
            "!=" -> left != right
            "=~" -> regexMatch(left, right, ctx.state.shoptOptions.nocasematch)
            "-eq" -> numCmp(left, right) { a, b -> a == b }
            "-ne" -> numCmp(left, right) { a, b -> a != b }
            "-lt" -> numCmp(left, right) { a, b -> a < b }
            "-le" -> numCmp(left, right) { a, b -> a <= b }
            "-gt" -> numCmp(left, right) { a, b -> a > b }
            "-ge" -> numCmp(left, right) { a, b -> a >= b }
            "<" -> left < right
            ">" -> left > right
            "-nt", "-ot", "-ef" -> evaluateBinaryFileTest(ctx, node.operator, left, right)
            else -> false
        }
    }

    private fun evaluateUnary(ctx: InterpreterContext, node: CondUnaryNode): Boolean {
        val operand = ctx.wordExpander.expandWord(node.operand)
        return when (node.operator) {
            "-z" -> operand.isEmpty()
            "-n" -> operand.isNotEmpty()
            "-v" -> ctx.state.env.containsKey(operand)
            "-R" -> ctx.state.namerefs?.contains(operand) == true
            "-o" -> shoptOptionEnabled(ctx, operand)
            else -> if (isFileTestOp(node.operator)) evaluateFileTest(ctx, node.operator, operand)
            else false
        }
    }

    /** Check whether a shopt/set option is enabled by name. */
    private fun shoptOptionEnabled(ctx: InterpreterContext, name: String): Boolean = when (name) {
        "errexit" -> ctx.state.options.errexit
        "pipefail" -> ctx.state.options.pipefail
        "nounset" -> ctx.state.options.nounset
        "xtrace" -> ctx.state.options.xtrace
        "verbose" -> ctx.state.options.verbose
        "posix" -> ctx.state.options.posix
        "allexport" -> ctx.state.options.allexport
        "noclobber" -> ctx.state.options.noclobber
        "noglob" -> ctx.state.options.noglob
        "noexec" -> ctx.state.options.noexec
        "vi" -> ctx.state.options.vi
        "emacs" -> ctx.state.options.emacs
        "extglob" -> ctx.state.shoptOptions.extglob
        "dotglob" -> ctx.state.shoptOptions.dotglob
        "nullglob" -> ctx.state.shoptOptions.nullglob
        "failglob" -> ctx.state.shoptOptions.failglob
        "globstar" -> ctx.state.shoptOptions.globstar
        "nocaseglob" -> ctx.state.shoptOptions.nocaseglob
        "nocasematch" -> ctx.state.shoptOptions.nocasematch
        "expand_aliases" -> ctx.state.shoptOptions.expand_aliases
        "lastpipe" -> ctx.state.shoptOptions.lastpipe
        "xpg_echo" -> ctx.state.shoptOptions.xpg_echo
        else -> false
    }

    private fun numCmp(a: String, b: String, op: (Long, Long) -> Boolean): Boolean {
        val la = a.trim().toLongOrNull() ?: 0L
        val lb = b.trim().toLongOrNull() ?: 0L
        return op(la, lb)
    }

    private fun regexMatch(text: String, pattern: String, nocasematch: Boolean): Boolean {
        return try {
            val flags = if (nocasematch) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = Regex(pattern, flags)
            regex.containsMatchIn(text)
        } catch (_: Exception) {
            false
        }
    }

    // ── test / [ handler ──────────────────────────────────────────────

    /**
     * Evaluates the arguments as a `test` or `[` command.
     * Returns an [ExecResult] with exit code 0 (true) or 1 (false).
     */
    fun evaluateTest(ctx: InterpreterContext, args: List<String>): com.justbash.ExecResult {
        // Expand each arg through the word expander
        val expanded = args.map { arg ->
            // Build a simple WordNode with a LiteralPart for evaluation
            val word = WordNode(mutableListOf(LiteralPart(arg)))
            ctx.wordExpander.expandWord(word)
        }

        val result = when (expanded.size) {
            0 -> false
            1 -> expanded[0].isNotEmpty()
            2 -> {
                // Check for unary operators
                when (expanded[0]) {
                    "-z" -> expanded[1].isEmpty()
                    "-n" -> expanded[1].isNotEmpty()
                    "-v" -> ctx.state.env.containsKey(expanded[1])
                    "-R" -> ctx.state.namerefs?.contains(expanded[1]) == true
                    "-o" -> shoptOptionEnabled(ctx, expanded[1])
                    else -> if (isFileTestOp(expanded[0])) {
                        evaluateFileTest(ctx, expanded[0], expanded[1])
                    } else {
                        // First arg is `!` (negation) or just a string
                        expanded[0].isNotEmpty()
                    }
                }
            }
            3 -> {
                // Binary operators
                when (expanded[1]) {
                    "=", "==" -> expanded[0] == expanded[2]
                    "!=" -> expanded[0] != expanded[2]
                    "-eq" -> numCmp(expanded[0], expanded[2]) { a, b -> a == b }
                    "-ne" -> numCmp(expanded[0], expanded[2]) { a, b -> a != b }
                    "-lt" -> numCmp(expanded[0], expanded[2]) { a, b -> a < b }
                    "-le" -> numCmp(expanded[0], expanded[2]) { a, b -> a <= b }
                    "-gt" -> numCmp(expanded[0], expanded[2]) { a, b -> a > b }
                    "-ge" -> numCmp(expanded[0], expanded[2]) { a, b -> a >= b }
                    "-nt", "-ot", "-ef" -> evaluateBinaryFileTest(ctx, expanded[1], expanded[0], expanded[2])
                    else -> {
                        // Special case: `!` negation
                        if (expanded[0] == "!") {
                            !evaluateSimpleTest(ctx, expanded.subList(1, expanded.size))
                        } else if (isFileTestOp(expanded[0])) {
                            evaluateFileTest(ctx, expanded[0], expanded[1])
                        } else {
                            false
                        }
                    }
                }
            }
            4 -> {
                // `!` prefix + binary op
                if (expanded[0] == "!") {
                    !evaluateSimpleTest(ctx, expanded.subList(1, expanded.size))
                } else if (expanded[1] == "-a" || expanded[1] == "-o") {
                    // Logical AND/OR: arg1 -a arg2 -o arg3
                    var result = expanded[0].isNotEmpty()
                    var i = 1
                    while (i < expanded.size - 1) {
                        val op = expanded[i]
                        val next = expanded[i + 1].isNotEmpty()
                        result = when (op) {
                            "-a" -> result && next
                            "-o" -> result || next
                            else -> result
                        }
                        i += 2
                    }
                    result
                } else {
                    false
                }
            }
            else -> {
                // Handle `!` prefix for larger arg lists
                if (expanded[0] == "!") {
                    !evaluateSimpleTest(ctx, expanded.subList(1, expanded.size))
                } else {
                    // Try `-a`/`-o` chains
                    var result = expanded[0].isNotEmpty()
                    var i = 1
                    while (i < expanded.size - 1) {
                        val op = expanded[i]
                        val next = expanded[i + 1].isNotEmpty()
                        result = when (op) {
                            "-a" -> result && next
                            "-o" -> result || next
                            else -> result
                        }
                        i += 2
                    }
                    result
                }
            }
        }
        return com.justbash.interpreter.Result.testResult(result)
    }

    private fun evaluateSimpleTest(ctx: InterpreterContext, args: List<String>): Boolean {
        return evaluateTest(ctx, args).exitCode == 0
    }
}