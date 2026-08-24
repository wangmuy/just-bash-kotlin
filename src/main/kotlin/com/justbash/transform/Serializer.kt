package com.justbash.transform

import com.justbash.ast.*

/**
 * AST serializer: turns a [ScriptNode] back into a bash script string.
 *
 * Port of just-bash `src/transform/serialize.ts`.
 */
object Serializer {

    /** Serialize a script AST to a bash script string. */
    fun serialize(node: ScriptNode): String = serializeScript(node)

    /** Serialize a single word node to its bash representation. */
    fun serializeWord(word: WordNode): String =
        word.parts.joinToString("") { serializeWordPart(it, false) }

    private fun serializeScript(node: ScriptNode): String =
        node.statements.joinToString("\n") { serializeStatement(it) }

    private fun serializeStatement(node: StatementNode): String {
        val parts = mutableListOf<String>()
        for (i in node.pipelines.indices) {
            parts.add(serializePipeline(node.pipelines[i]))
            if (i < node.operators.size) {
                parts.add(node.operators[i])
            }
        }
        var result = parts.joinToString(" ")
        if (node.background) {
            result += " &"
        }
        return result
    }

    private fun serializePipeline(node: PipelineNode): String {
        val prefix = mutableListOf<String>()
        if (node.timed) {
            prefix.add(if (node.timePosix) "time -p" else "time")
        }
        if (node.negated) {
            prefix.add("!")
        }

        val cmdParts = mutableListOf<String>()
        for (i in node.commands.indices) {
            cmdParts.add(serializeCommand(node.commands[i]))
            if (i < node.commands.size - 1) {
                val pipeStderr = node.pipeStderr?.getOrNull(i) ?: false
                cmdParts.add(if (pipeStderr) "|&" else "|")
            }
        }

        val prefixStr = if (prefix.isNotEmpty()) "${prefix.joinToString(" ")} " else ""
        return prefixStr + cmdParts.joinToString(" ")
    }

    private fun serializeCommand(node: CommandNode): String = when (node) {
        is SimpleCommandNode -> serializeSimpleCommand(node)
        is IfNode -> serializeIf(node)
        is ForNode -> serializeFor(node)
        is CStyleForNode -> serializeCStyleFor(node)
        is WhileNode -> serializeWhile(node)
        is UntilNode -> serializeUntil(node)
        is CaseNode -> serializeCase(node)
        is SubshellNode -> serializeSubshell(node)
        is GroupNode -> serializeGroup(node)
        is ArithmeticCommandNode -> serializeArithmeticCommand(node)
        is ConditionalCommandNode -> serializeConditionalCommand(node)
        is FunctionDefNode -> serializeFunctionDef(node)
        else -> throw IllegalArgumentException("Unsupported command type: ${node.type}")
    }

    private fun serializeSimpleCommand(node: SimpleCommandNode): String {
        val parts = mutableListOf<String>()
        node.assignments.forEach { parts.add(serializeAssignment(it)) }
        node.name?.let { parts.add(serializeWord(it)) }
        node.args.forEach { parts.add(serializeWord(it)) }
        node.redirections.forEach { parts.add(serializeRedirection(it)) }
        return parts.joinToString(" ")
    }

    private fun serializeAssignment(node: AssignmentNode): String {
        val op = if (node.append) "+=" else "="
        node.array?.let {
            val items = it.joinToString(" ") { w -> serializeWord(w) }
            return "${node.name}$op($items)"
        }
        val value = node.value
        return if (value != null) {
            "${node.name}$op${serializeWord(value)}"
        } else {
            "${node.name}$op"
        }
    }

    /**
     * Serialize a word in a "raw" context where shell metacharacters don't
     * need escaping (e.g., inside `${...}` parameter expansions).
     */
    private fun serializeWordRaw(node: WordNode): String =
        node.parts.joinToString("") { serializeWordPart(it, true) }

    private fun serializeWordPart(part: WordPart, inDoubleQuotes: Boolean): String = when (part) {
        is LiteralPart ->
            if (inDoubleQuotes) escapeDoubleQuoted(part.value) else escapeLiteral(part.value)
        is SingleQuotedPart -> "'${part.value}'"
        is DoubleQuotedPart -> "\"" + part.parts.joinToString("") { serializeWordPart(it, true) } + "\""
        is EscapedPart -> "\\${part.value}"
        is ParameterExpansionPart -> serializeParameterExpansion(part)
        is CommandSubstitutionPart ->
            if (part.legacy) "`${serializeScript(part.body)}`" else "\$(${serializeScript(part.body)})"
        is ArithmeticExpansionPart -> "\$((" + serializeArithExpr(part.expression.expression) + "))"
        is ProcessSubstitutionPart ->
            if (part.direction == "input") "<(${serializeScript(part.body)})"
            else ">(${serializeScript(part.body)})"
        is BraceExpansionPart -> serializeBraceExpansion(part)
        is TildeExpansionPart -> if (part.user != null) "~${part.user}" else "~"
        is GlobPart -> part.pattern
        else -> throw IllegalArgumentException("Unsupported word part type: ${part.type}")
    }

    /**
     * Escape shell metacharacters in literal values that appear outside of quotes.
     * These characters would otherwise cause word splitting, globbing, or be
     * interpreted as operators.
     *
     * `$` is intentionally NOT escaped, matching the TypeScript original: the
     * parser only creates Literal parts with `$` when it's in a non-expansion
     * position, and escaping it would change the AST type from Literal to Escaped
     * without functional benefit.
     */
    private fun escapeLiteral(value: String): String =
        value.replace(EXTRA_LITERAL_ESCAPE, "\\\\$1")

    /**
     * Escape characters that have special meaning inside double quotes.
     * In bash double quotes, `$`, `` ` ``, `"`, and `\` are special and must be
     * backslash-escaped to appear literally.
     */
    private fun escapeDoubleQuoted(value: String): String =
        value.replace(DOUBLE_QUOTED_ESCAPE, "\\\\$1")

    private val EXTRA_LITERAL_ESCAPE = Regex("""([\s\\'"`!|&;()<>{}[\\]*?~#])""")
    private val DOUBLE_QUOTED_ESCAPE = Regex("""([$`"\\])""")

    /**
     * Serialize heredoc content without escaping, since heredoc bodies are
     * delimited by the heredoc delimiter, not by shell metacharacters.
     */
    private fun serializeHeredocContent(node: WordNode, quoted: Boolean): String =
        node.parts.joinToString("") { serializeHeredocPart(it, quoted) }

    private fun serializeHeredocPart(part: WordPart, quoted: Boolean): String = when (part) {
        is LiteralPart ->
            if (quoted) part.value else part.value.replace(DOUBLE_QUOTED_DOLLAR_BACKTICK, "\\\\$1")
        is EscapedPart -> "\\${part.value}"
        is ParameterExpansionPart -> serializeParameterExpansion(part)
        is CommandSubstitutionPart ->
            if (part.legacy) "`${serializeScript(part.body)}`" else "\$(${serializeScript(part.body)})"
        is ArithmeticExpansionPart -> "\$((" + serializeArithExpr(part.expression.expression) + "))"
        else -> serializeWordPart(part, false)
    }

    private val DOUBLE_QUOTED_DOLLAR_BACKTICK = Regex("""([$`])""")

    private fun serializeParameterExpansion(node: ParameterExpansionPart): String {
        val op = node.operation
        if (op == null) {
            return if (needsBraces(node.parameter)) "\${${node.parameter}}" else "\$${node.parameter}"
        }
        return "\${${serializeParameterOp(node.parameter, op)}}"
    }

    private fun needsBraces(parameter: String): Boolean {
        if (SPECIAL_PARAMETER.matches(parameter)) return false
        if (SIMPLE_IDENTIFIER.matches(parameter)) return false
        return true
    }

    private val SPECIAL_PARAMETER = Regex("""^[?#@*$!\-0-9]$""")
    private val SIMPLE_IDENTIFIER = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*$""")

    private fun serializeParameterOp(param: String, op: ParameterOperation): String = when (op) {
        is LengthOp -> "#$param"
        is LengthSliceErrorOp -> "#$param:"
        is BadSubstitutionOp -> op.text
        is DefaultValueOp -> "${param}${if (op.checkEmpty) ":" else ""}-${serializeWordRaw(op.word)}"
        is AssignDefaultOp -> "${param}${if (op.checkEmpty) ":" else ""}=${serializeWordRaw(op.word)}"
        is ErrorIfUnsetOp -> "${param}${if (op.checkEmpty) ":" else ""}?${op.word?.let { serializeWordRaw(it) } ?: ""}"
        is UseAlternativeOp -> "${param}${if (op.checkEmpty) ":" else ""}+${serializeWordRaw(op.word)}"
        is SubstringOp -> {
            val offset = serializeArithExpr(op.offset.expression)
            if (op.length != null) "$param:$offset:${serializeArithExpr(op.length.expression)}"
            else "$param:$offset"
        }
        is PatternRemovalOp -> {
            val opChar = if (op.side == "prefix") "#" else "%"
            val opStr = if (op.greedy) "$opChar$opChar" else opChar
            "$param$opStr${serializeWordRaw(op.pattern)}"
        }
        is PatternReplacementOp -> {
            val prefix = when {
                op.all -> "//"
                op.anchor == "start" -> "/#"
                op.anchor == "end" -> "/%"
                else -> "/"
            }
            val repl = op.replacement?.let { "/${serializeWordRaw(it)}" } ?: ""
            "$param$prefix${serializeWordRaw(op.pattern)}$repl"
        }
        is CaseModificationOp -> {
            val opChar = if (op.direction == "upper") "^" else ","
            val opStr = if (op.all) "$opChar$opChar" else opChar
            val pat = op.pattern?.let { serializeWordRaw(it) } ?: ""
            "$param$opStr$pat"
        }
        is TransformOp -> "$param@${op.operator}"
        is IndirectionOp ->
            if (op.innerOp != null) "!${serializeParameterOp(param, op.innerOp)}" else "!$param"
        is ArrayKeysOp -> "!${op.array}[${if (op.star) "*" else "@"}]"
        is VarNamePrefixOp -> "!${op.prefix}${if (op.star) "*" else "@"}"
        else -> throw IllegalArgumentException("Unsupported parameter operation type: ${op.type}")
    }

    private fun serializeBraceExpansion(node: BraceExpansionPart): String {
        val items = node.items.joinToString(",") { serializeBraceItem(it) }
        return "{$items}"
    }

    private fun serializeBraceItem(item: BraceItem): String = when (item) {
        is BraceWordItem -> serializeWord(item.word)
        is BraceRangeItem -> {
            val startStr = item.startStr ?: item.start.toString()
            val endStr = item.endStr ?: item.end.toString()
            if (item.step != null) "$startStr..$endStr..${item.step}"
            else "$startStr..$endStr"
        }
    }

    private fun serializeRedirection(node: RedirectionNode): String {
        val fdStr = when {
            node.fdVariable != null -> "{${node.fdVariable}}"
            node.fd != null -> node.fd.toString()
            else -> ""
        }

        if (node.operator == "<<" || node.operator == "<<-") {
            val heredoc = node.target as HereDocNode
            if (heredoc.terminated == false) {
                throw IllegalStateException("Cannot serialize an unterminated here-document")
            }
            val delimStr = if (heredoc.quoted) "'${heredoc.delimiter}'" else heredoc.delimiter
            val content = serializeHeredocContent(heredoc.content, heredoc.quoted)
            val fdPrefix = if (node.fd != null && node.fd != 0) "${node.fd}" else ""
            return "$fdPrefix${node.operator}$delimStr\n${content}${heredoc.delimiter}"
        }

        if (node.operator == "<<<") {
            return "$fdStr<<< ${serializeWord(node.target as WordNode)}"
        }

        if (node.operator == "&>" || node.operator == "&>>") {
            return "${node.operator} ${serializeWord(node.target as WordNode)}"
        }

        return "$fdStr${node.operator} ${serializeWord(node.target as WordNode)}"
    }

    private fun serializeRedirections(redirections: List<RedirectionNode>): String {
        if (redirections.isEmpty()) return ""
        return " ${redirections.joinToString(" ") { serializeRedirection(it) }}"
    }

    private fun serializeBody(statements: List<StatementNode>): String =
        statements.joinToString("\n") { serializeStatement(it) }

    private fun serializeIf(node: IfNode): String {
        val parts = mutableListOf<String>()
        node.clauses.forEachIndexed { i, clause ->
            val keyword = if (i == 0) "if" else "elif"
            parts.add("$keyword ${serializeBody(clause.condition)}; then\n${serializeBody(clause.body)}")
        }
        node.elseBody?.let { parts.add("else\n${serializeBody(it)}") }
        return "${parts.joinToString("\n")}\nfi${serializeRedirections(node.redirections)}"
    }

    private fun serializeFor(node: ForNode): String {
        val header = if (node.words == null) {
            "for ${node.variable}"
        } else {
            val words = node.words.joinToString(" ") { serializeWord(it) }
            "for ${node.variable} in $words"
        }
        return "$header; do\n${serializeBody(node.body)}\ndone${serializeRedirections(node.redirections)}"
    }

    private fun serializeCStyleFor(node: CStyleForNode): String {
        val init = node.init?.let { serializeArithExpr(it.expression) } ?: ""
        val cond = node.condition?.let { serializeArithExpr(it.expression) } ?: ""
        val update = node.update?.let { serializeArithExpr(it.expression) } ?: ""
        return "for (($init; $cond; $update)); do\n${serializeBody(node.body)}\ndone${serializeRedirections(node.redirections)}"
    }

    private fun serializeWhile(node: WhileNode): String =
        "while ${serializeBody(node.condition)}; do\n${serializeBody(node.body)}\ndone${serializeRedirections(node.redirections)}"

    private fun serializeUntil(node: UntilNode): String =
        "until ${serializeBody(node.condition)}; do\n${serializeBody(node.body)}\ndone${serializeRedirections(node.redirections)}"

    private fun serializeCase(node: CaseNode): String {
        val items = node.items.joinToString("\n") { serializeCaseItem(it) }
        return "case ${serializeWord(node.word)} in\n$items\nesac${serializeRedirections(node.redirections)}"
    }

    private fun serializeCaseItem(node: CaseItemNode): String {
        val patterns = node.patterns.joinToString(" | ") { serializeWord(it) }
        val body = serializeBody(node.body)
        return if (body.isNotEmpty()) "$patterns)\n$body\n${node.terminator}"
        else "$patterns)\n${node.terminator}"
    }

    private fun serializeSubshell(node: SubshellNode): String =
        "(${serializeBody(node.body)})${serializeRedirections(node.redirections)}"

    private fun serializeGroup(node: GroupNode): String =
        "{ ${serializeBody(node.body)}; }${serializeRedirections(node.redirections)}"

    private fun serializeArithmeticCommand(node: ArithmeticCommandNode): String =
        "((" + serializeArithExpr(node.expression.expression) + "))" + serializeRedirections(node.redirections)

    private fun serializeConditionalCommand(node: ConditionalCommandNode): String =
        "[[ ${serializeCondExpr(node.expression)} ]]${serializeRedirections(node.redirections)}"

    private fun serializeFunctionDef(node: FunctionDefNode): String {
        val body = serializeCommand(node.body)
        return "${node.name}() $body${serializeRedirections(node.redirections)}"
    }

    private fun serializeArithExpr(expr: ArithExpr): String = when (expr) {
        is ArithNumberNode -> expr.value.toString()
        is ArithVariableNode -> if (expr.hasDollarPrefix) "\$${expr.name}" else expr.name
        is ArithSpecialVarNode -> "\$${expr.name}"
        is ArithBinaryNode -> "${serializeArithExpr(expr.left)} ${expr.operator} ${serializeArithExpr(expr.right)}"
        is ArithUnaryNode ->
            if (expr.prefix) "${expr.operator}${serializeArithExpr(expr.operand)}"
            else "${serializeArithExpr(expr.operand)}${expr.operator}"
        is ArithTernaryNode -> "${serializeArithExpr(expr.condition)} ? ${serializeArithExpr(expr.consequent)} : ${serializeArithExpr(expr.alternate)}"
        is ArithAssignmentNode -> {
            val target = when {
                expr.subscript != null -> "${expr.variable}[${serializeArithExpr(expr.subscript)}]"
                expr.stringKey != null -> "${expr.variable}[${expr.stringKey}]"
                else -> expr.variable
            }
            "$target ${expr.operator} ${serializeArithExpr(expr.value)}"
        }
        is ArithDynamicAssignmentNode -> {
            val dynTarget = expr.subscript?.let { "${serializeArithExpr(expr.target)}[${serializeArithExpr(it)}]" }
                ?: serializeArithExpr(expr.target)
            "$dynTarget ${expr.operator} ${serializeArithExpr(expr.value)}"
        }
        is ArithDynamicElementNode -> "${serializeArithExpr(expr.nameExpr)}[${serializeArithExpr(expr.subscript)}]"
        is ArithGroupNode -> "(${serializeArithExpr(expr.expression)})"
        is ArithNestedNode -> "\$((" + serializeArithExpr(expr.expression) + "))"
        is ArithCommandSubstNode -> "\$(${expr.command})"
        is ArithBracedExpansionNode -> "\${${expr.content}}"
        is ArithArrayElementNode -> when {
            expr.stringKey != null -> "${expr.array}[${expr.stringKey}]"
            expr.index != null -> "${expr.array}[${serializeArithExpr(expr.index)}]"
            else -> expr.array
        }
        is ArithDynamicBaseNode -> "\${${expr.baseExpr}}#${expr.value}"
        is ArithDynamicNumberNode -> "\${${expr.prefix}}${expr.suffix}"
        is ArithConcatNode -> expr.parts.joinToString("") { serializeArithExpr(it) }
        is ArithDoubleSubscriptNode -> "${expr.array}[${serializeArithExpr(expr.index)}]"
        is ArithNumberSubscriptNode -> "${expr.number}[${expr.errorToken}]"
        is ArithSyntaxErrorNode -> expr.errorToken
        is ArithSingleQuoteNode -> "'${expr.content}'"
        else -> throw IllegalArgumentException("Unsupported arithmetic expression type: ${expr.type}")
    }

    private fun serializeCondExpr(expr: ConditionalExpressionNode): String = when (expr) {
        is CondBinaryNode -> "${serializeWord(expr.left)} ${expr.operator} ${serializeWord(expr.right)}"
        is CondUnaryNode -> "${expr.operator} ${serializeWord(expr.operand)}"
        is CondNotNode -> "! ${serializeCondExpr(expr.operand)}"
        is CondAndNode -> "${serializeCondExpr(expr.left)} && ${serializeCondExpr(expr.right)}"
        is CondOrNode -> "${serializeCondExpr(expr.left)} || ${serializeCondExpr(expr.right)}"
        is CondGroupNode -> "( ${serializeCondExpr(expr.expression)} )"
        is CondWordNode -> serializeWord(expr.word)
        else -> throw IllegalArgumentException("Unsupported conditional expression type: ${expr.type}")
    }
}
