package com.justbash.interpreter

import com.justbash.ast.*

interface WordExpander {
    fun expandWord(word: WordNode): String
    fun expandWordWithGlob(word: WordNode): List<String>
    fun expandRedirectTarget(word: WordNode): String
    fun isWordFullyQuoted(word: WordNode): Boolean
}

/**
 * Minimal word expander used until the full expansion bridge is wired in.
 *
 * Handles: literals, single/double quotes, escapes, simple `$VAR` parameter
 * expansion, `$((...))` integer arithmetic, and `$(...)` / backtick command
 * substitution (via an injectable exec callback).
 *
 * NOT yet handled (delegated to the full `com.justbash.interpreter.expansion`
 * package in a follow-up): `${var:-default}`, `${#var}`, brace expansion,
 * tilde, globbing, word splitting, indirect expansion, pattern removal.
 */
class ExpansionPlaceholder(
    private val env: Map<String, String>,
    private val execScript: ((String) -> String)? = null,
) : WordExpander {
    override fun expandWord(word: WordNode): String {
        return word.parts.joinToString("") { expandPart(it) }
    }
    override fun expandWordWithGlob(word: WordNode): List<String> = listOf(expandWord(word))
    override fun expandRedirectTarget(word: WordNode): String = expandWord(word)
    override fun isWordFullyQuoted(word: WordNode): Boolean =
        word.parts.all { it is SingleQuotedPart || it is EscapedPart }

    private fun expandPart(part: WordPart): String = when (part) {
        is LiteralPart -> part.value
        is SingleQuotedPart -> part.value
        is EscapedPart -> part.value
        is DoubleQuotedPart -> part.parts.joinToString("") { expandPart(it) }
        is ParameterExpansionPart -> {
            when (part.parameter) {
                "?" -> env["?"] ?: "0"
                "#" -> env["#"] ?: "0"
                "@" -> env["@"] ?: ""
                "*" -> env["*"] ?: ""
                "\$" -> env["\$"] ?: ""
                "0" -> env["0"] ?: ""
                else -> env[part.parameter] ?: ""
            }
        }
        is ArithmeticExpansionPart -> ArithEval.eval(part.expression, env)
        is CommandSubstitutionPart -> (execScript?.invoke(renderScript(part.body)) ?: "").trimEnd('\n')
        is GlobPart -> part.pattern  // return glob pattern literally (noglob-like)
        is TildeExpansionPart -> if (part.user == null) env["HOME"] ?: "/home/user" else "~${part.user}"
        else -> ""
    }

    private fun renderScript(node: ScriptNode): String =
        node.statements.joinToString("; ") { stmt ->
            stmt.pipelines.joinToString(" | ") { pipe ->
                pipe.commands.joinToString(" ") { cmd ->
                    when (cmd) {
                        is SimpleCommandNode -> (listOfNotNull(cmd.name?.let { wordToString(it) }) + cmd.args.map { wordToString(it) }).joinToString(" ")
                        else -> ""
                    }
                }
            }
        }

    private fun wordToString(word: WordNode): String = word.parts.joinToString("") { p ->
        when (p) {
            is LiteralPart -> p.value
            is SingleQuotedPart -> "'${p.value}'"
            is DoubleQuotedPart -> "\"${p.parts.joinToString("") { wordToStringPart(it) }}\""
            is ParameterExpansionPart -> "\$${p.parameter}"
            is EscapedPart -> "\\${p.value}"
            else -> ""
        }
    }

    private fun wordToStringPart(part: WordPart): String = when (part) {
        is LiteralPart -> part.value
        is SingleQuotedPart -> "'${part.value}'"
        is DoubleQuotedPart -> "\"${part.parts.joinToString("") { wordToStringPart(it) }}\""
        is ParameterExpansionPart -> "\$${part.parameter}"
        is EscapedPart -> "\\${part.value}"
        else -> ""
    }
}

/**
 * Self-contained integer arithmetic evaluator used by the placeholder.
 * Supports + - * / % unary -/+, parentheses, and integer literals with `$var`
 * references resolved from [env].
 */
object ArithEval {
    fun eval(node: ArithmeticExpressionNode, env: Map<String, String>): String {
        val text = node.originalText ?: render(node.expression, env)
        return try {
            evalText(text, env).toString()
        } catch (e: Exception) {
            "0"
        }
    }

    private fun render(e: ArithExpr, env: Map<String, String>): String = when (e) {
        is ArithNumberNode -> e.value.toString()
        is ArithVariableNode -> env[e.name] ?: "0"
        is ArithBinaryNode -> "(${render(e.left, env)}${e.operator}${render(e.right, env)})"
        is ArithUnaryNode -> (if (e.prefix) e.operator else "") + render(e.operand, env) + (if (!e.prefix) e.operator else "")
        is ArithGroupNode -> "(${render(e.expression, env)})"
        is ArithNestedNode -> render(e.expression, env)
        is ArithTernaryNode -> "(${render(e.condition, env)}?${render(e.consequent, env)}:${render(e.alternate, env)})"
        else -> "0"
    }

    fun evalText(input: String, env: Map<String, String>): Long {
        // Tokenize into a flat list, resolving variables inline.
        val tokens = tokenize(input, env)
        val pos = intArrayOf(0)
        val result = parseExpression(tokens, pos)
        return result
    }

    private fun tokenize(input: String, env: Map<String, String>): List<String> {
        val tokens = ArrayList<String>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == '$' -> {
                    // variable reference
                    var j = i + 1
                    if (j < input.length && input[j] == '{') {
                        val end = input.indexOf('}', j)
                        if (end != -1) {
                            val name = input.substring(j + 1, end)
                            tokens.add(env[name] ?: "0")
                            i = end + 1
                        } else { tokens.add("0"); i = j + 1 }
                    } else {
                        while (j < input.length && (input[j].isLetterOrDigit() || input[j] == '_')) j++
                        if (j == i + 1) { i++; continue } // lone $
                        val name = input.substring(i + 1, j)
                        tokens.add(env[name] ?: "0")
                        i = j
                    }
                }
                c.isLetter() || c == '_' -> {
                    // bare variable name in arithmetic context (e.g. `x + 1`)
                    var j = i
                    while (j < input.length && (input[j].isLetterOrDigit() || input[j] == '_')) j++
                    val name = input.substring(i, j)
                    tokens.add(env[name] ?: "0")
                    i = j
                }
                c in "+-*/%()" -> { tokens.add(c.toString()); i++ }
                c.isDigit() || (c == '-' && i + 1 < input.length && input[i].isDigit()) -> {
                    var j = i
                    if (input[j] == '-') j++
                    while (j < input.length && input[j].isDigit()) j++
                    tokens.add(input.substring(i, j))
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }

    private fun parseExpression(tokens: List<String>, pos: IntArray): Long {
        var left = parseTerm(tokens, pos)
        while (pos[0] < tokens.size && (tokens[pos[0]] == "+" || tokens[pos[0]] == "-")) {
            val op = tokens[pos[0]]
            pos[0]++
            val right = parseTerm(tokens, pos)
            left = if (op == "+") left + right else left - right
        }
        return left
    }

    private fun parseTerm(tokens: List<String>, pos: IntArray): Long {
        var left = parseFactor(tokens, pos)
        while (pos[0] < tokens.size && (tokens[pos[0]] == "*" || tokens[pos[0]] == "/" || tokens[pos[0]] == "%")) {
            val op = tokens[pos[0]]
            pos[0]++
            val right = parseFactor(tokens, pos)
            left = when (op) {
                "*" -> left * right
                "/" -> if (right != 0L) left / right else 0L
                else -> if (right != 0L) left % right else 0L
            }
        }
        return left
    }

    private fun parseFactor(tokens: List<String>, pos: IntArray): Long {
        if (pos[0] >= tokens.size) return 0L
        val tok = tokens[pos[0]]
        if (tok == "(") {
            pos[0]++
            val v = parseExpression(tokens, pos)
            if (pos[0] < tokens.size && tokens[pos[0]] == ")") pos[0]++
            return v
        }
        if (tok == "-") {
            pos[0]++
            return -parseFactor(tokens, pos)
        }
        if (tok == "+") {
            pos[0]++
            return parseFactor(tokens, pos)
        }
        pos[0]++
        return tok.toLongOrNull() ?: 0L
    }
}