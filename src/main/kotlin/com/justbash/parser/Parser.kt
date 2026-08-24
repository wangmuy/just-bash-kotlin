package com.justbash.parser

import com.justbash.ast.*

class Parser(private val parseBudget: ParseBudget = ParseBudget()) {
    private var tokens = mutableListOf<Token>()
    private var pos = 0
    private val pendingHeredocs = mutableListOf<PendingHeredocEntry>()
    private var input = ""
    private var processLineState: ProcessLineState? = null

    class PendingHeredocEntry(
        val redirect: RedirectionNode, val delimiter: String,
        val stripTabs: Boolean, val quoted: Boolean,
    )

    class ProcessLineState(var currentLogicalLine: Int, var currentPhysicalLine: Int? = null)

    fun getInput(): String = input

    fun checkIterationLimit() { val t = current(); parseBudget.chargeIteration(t.line, t.column) }

    fun enterDepth(): () -> Unit { val t = current(); return parseBudget.enter(t.line, t.column) }

    fun <T> withDepth(callback: () -> T): T { val exit = enterDepth(); try { return callback() } finally { exit() } }

    fun parse(input: String, options: ParseOptions? = null): ScriptNode {
        parseBudget.reset()
        if (input.length > MAX_INPUT_SIZE) throw ParseException("Input too large: ${input.length} bytes exceeds limit", 1, 1)
        this.input = input; processLineState = null
        val lexer = Lexer(input, options?.maxHeredocSize ?: 10_485_760)
        tokens = lexer.tokenize().toMutableList()
        if (tokens.size > MAX_TOKENS) throw ParseException("Too many tokens: ${tokens.size} exceeds limit", 1, 1)
        pos = 0; pendingHeredocs.clear(); parseBudget.chargeTokens(tokens.size)
        return parseScript()
    }

    fun current(): Token = tokens.getOrElse(pos) { tokens.last() }
    fun peek(offset: Int = 0): Token = tokens.getOrElse(pos + offset) { tokens.last() }
    fun advance(): Token { val t = current(); if (pos < tokens.size - 1) pos++; return t }
    fun getPos(): Int = pos

    fun getSourceLine(token: Token = current()): Int {
        val pls = processLineState ?: return token.line
        if (pls.currentPhysicalLine == null) pls.currentPhysicalLine = token.line
        else if (token.line > (pls.currentPhysicalLine ?: 0)) { pls.currentPhysicalLine = token.line; pls.currentLogicalLine += 1 }
        return pls.currentLogicalLine
    }

    fun check(vararg types: TokenType): Boolean = tokens.getOrNull(pos)?.type?.let { types.contains(it) } ?: false

    fun expect(type: TokenType, message: String? = null): Token {
        if (check(type)) return advance()
        val t = current(); throw ParseException(message ?: "Expected $type, got ${t.type}", t.line, t.column, t)
    }

    fun error(message: String): Nothing { val t = current(); throw ParseException(message, t.line, t.column, t) }

    fun skipNewlines() { while (check(TokenType.NEWLINE, TokenType.COMMENT)) { if (check(TokenType.NEWLINE)) { advance(); processHeredocs() } else advance() } }

    fun skipSeparators(includeCaseTerminators: Boolean = true) {
        while (true) {
            when { check(TokenType.NEWLINE) -> { advance(); processHeredocs(); continue }; check(TokenType.SEMICOLON, TokenType.COMMENT) -> { advance(); continue }; includeCaseTerminators && check(TokenType.DSEMI, TokenType.SEMI_AND, TokenType.SEMI_SEMI_AND) -> { advance(); continue }; else -> break }
        }
    }

    fun addPendingHeredoc(redirect: RedirectionNode, delimiter: String, stripTabs: Boolean, quoted: Boolean) {
        pendingHeredocs.add(PendingHeredocEntry(redirect, delimiter, stripTabs, quoted))
    }

    private fun processHeredocs() {
        for (h in pendingHeredocs) {
            if (check(TokenType.HEREDOC_CONTENT)) {
                val c = advance()
                val cw = if (h.quoted) WordNode(mutableListOf(LiteralPart(c.value))) else parseWordFromString(c.value, false, false, false, true)
                val nt = HereDocNode(h.delimiter, cw, h.stripTabs, h.quoted, c.heredocTerminated ?: false)
                h.redirect.target = nt
            }
        }
        pendingHeredocs.clear()
    }

    fun isStatementEnd(): Boolean = check(TokenType.EOF, TokenType.NEWLINE, TokenType.SEMICOLON, TokenType.AMP, TokenType.AND_AND, TokenType.OR_OR, TokenType.RPAREN, TokenType.RBRACE, TokenType.DSEMI, TokenType.SEMI_AND, TokenType.SEMI_SEMI_AND)

    private fun isCommandStart(): Boolean {
        val t = current().type
        return isWord() || t == TokenType.ASSIGNMENT_WORD || t == TokenType.FD_VARIABLE || t == TokenType.LPAREN || t == TokenType.LBRACE || t == TokenType.DPAREN_START || t == TokenType.DBRACK_START || CommandParser.isRedirection(this)
    }

    private fun parseScript(): ScriptNode {
        val stmts = mutableListOf<StatementNode>(); var iters = 0; skipNewlines()
        while (!check(TokenType.EOF)) {
            iters++; if (iters > 10000) error("Parser stuck: too many iterations (>10000)")
            val de = checkUnexpectedToken()
            if (de != null) { stmts.add(de); skipSeparators(false); continue }
            val pb = pos; val stmt = parseStatement()
            if (stmt != null) stmts.add(stmt)
            if (check(TokenType.HEREDOC_CONTENT)) processHeredocs()
            skipSeparators(false)
            if (check(TokenType.DSEMI, TokenType.SEMI_AND, TokenType.SEMI_SEMI_AND)) error("syntax error near unexpected token `${current().value}'")
            if (pos == pb && !check(TokenType.EOF)) error("syntax error near unexpected token `${current().value}'")
        }
        return ScriptNode(stmts)
    }

    private fun checkUnexpectedToken(): StatementNode? {
        val t = current().type; val v = current().value
        if (currentTokenJoinsProcessSubstitution()) return null
        if (t == TokenType.DO || t == TokenType.DONE || t == TokenType.THEN || t == TokenType.ELSE || t == TokenType.ELIF || t == TokenType.FI || t == TokenType.ESAC) error("syntax error near unexpected token `$v'")
        if (t == TokenType.RBRACE || t == TokenType.RPAREN) { val em = "syntax error near unexpected token `$v'"; advance(); return StatementNode(mutableListOf(PipelineNode(mutableListOf(SimpleCommandNode()))), mutableListOf(), false, DeferredError(em, v)) }
        if (t == TokenType.DSEMI || t == TokenType.SEMI_AND || t == TokenType.SEMI_SEMI_AND || t == TokenType.SEMICOLON) error("syntax error near unexpected token `$v'")
        if (t == TokenType.AMP || t == TokenType.AND_AND || t == TokenType.OR_OR || t == TokenType.PIPE || t == TokenType.PIPE_AMP) error("syntax error near unexpected token `$v'")
        return null
    }

    fun parseStatement(): StatementNode? {
        skipNewlines(); if (!isCommandStart()) return null
        val so = current().start; val pipelines = mutableListOf<PipelineNode>(); val operators = mutableListOf<String>(); var bg = false
        pipelines.add(parsePipeline())
        while (check(TokenType.AND_AND, TokenType.OR_OR)) { val op = advance(); operators.add(if (op.type == TokenType.AND_AND) "&&" else "||"); skipNewlines(); pipelines.add(parsePipeline()) }
        if (check(TokenType.AMP)) { advance(); bg = true }
        val eo = if (pos > 0) tokens[pos - 1].end else so; val st = if (eo > so) input.substring(so, eo) else null
        return StatementNode(pipelines, operators, bg, null, st)
    }

    private fun parseTimePrefix(): Boolean? {
        if (!check(TokenType.TIME) || currentTokenJoinsProcessSubstitution()) return null
        advance(); var tp = false
        if ((check(TokenType.WORD, TokenType.NAME)) && current().value == "-p") { advance(); tp = true }
        return tp
    }

    private fun parsePipeline(): PipelineNode {
        var timed = false; var tp = false; var nc = 0
        while (check(TokenType.BANG) && !currentTokenJoinsProcessSubstitution()) { advance(); nc++ }
        if (nc == 0) { val pp = parseTimePrefix(); if (pp != null) { timed = true; tp = pp; while (check(TokenType.BANG) && !currentTokenJoinsProcessSubstitution()) { advance(); nc++ } } }
        val negated = nc % 2 == 1
        val commands = mutableListOf<CommandNode>(); val pe = mutableListOf<Boolean>()
        commands.add(parseCommand())
        while (check(TokenType.PIPE, TokenType.PIPE_AMP)) { val pt = advance(); skipNewlines(); pe.add(pt.type == TokenType.PIPE_AMP); commands.add(parseCommand()) }
        return PipelineNode(commands, negated, timed, tp, if (pe.isNotEmpty()) pe else null)
    }

    private fun parseCommand(): CommandNode {
        if (currentTokenJoinsProcessSubstitution()) return CommandParser.parseSimpleCommand(this)
        val t = current()
        when (t.type) {
            TokenType.IF -> return CompoundParser.parseIf(this)
            TokenType.FOR -> return CompoundParser.parseFor(this)
            TokenType.WHILE -> return CompoundParser.parseWhile(this)
            TokenType.UNTIL -> return CompoundParser.parseUntil(this)
            TokenType.CASE -> return CompoundParser.parseCase(this)
            TokenType.LPAREN -> return CompoundParser.parseSubshell(this)
            TokenType.LBRACE -> return CompoundParser.parseGroup(this)
            TokenType.DPAREN_START -> { if (dparenClosesWithSpacedParens()) return parseNestedSubshellsFromDparen(); return parseArithmeticCommand() }
            TokenType.DBRACK_START -> return parseConditionalCommand()
            TokenType.FUNCTION -> return parseFunctionDef()
            TokenType.TIME -> { /* fall through */ }
            TokenType.BANG -> error("syntax error near unexpected token `${t.value}'")
            else -> { if (isReservedWordToken(t.type)) error("syntax error near unexpected token `${t.value}'") }
        }
        if ((check(TokenType.NAME, TokenType.WORD)) && peek(1).type == TokenType.LPAREN && peek(2).type == TokenType.RPAREN) return parseFunctionDef()
        return CommandParser.parseSimpleCommand(this)
    }

    private fun dparenClosesWithSpacedParens(): Boolean {
        var d = 1; var o = 1
        while (o < tokens.size - pos) {
            val tok = peek(o)
            if (tok.type == TokenType.EOF) return false
            when { tok.type == TokenType.DPAREN_START || tok.type == TokenType.LPAREN -> d++; tok.type == TokenType.DPAREN_END -> { d -= 2; if (d <= 0) return false }; tok.type == TokenType.RPAREN -> { d--; if (d == 0) { if (peek(o + 1).type == TokenType.RPAREN) return true } } }
            o++
        }
        return false
    }

    private fun parseNestedSubshellsFromDparen(): SubshellNode {
        advance(); val ib = parseCompoundList(); expect(TokenType.RPAREN); expect(TokenType.RPAREN)
        val rd = parseOptionalRedirections(); val isub = SubshellNode(ib, mutableListOf())
        return SubshellNode(mutableListOf(StatementNode(mutableListOf(PipelineNode(mutableListOf(isub))))), rd)
    }

    fun isWord(): Boolean = isWordToken(WordParseContext.Default) || isProcessSubstitutionStart() || currentTokenJoinsProcessSubstitution()

    private fun isWordToken(context: WordParseContext): Boolean {
        val t = current().type
        return t == TokenType.WORD || t == TokenType.NAME || t == TokenType.NUMBER || isReservedWordToken(t) || t == TokenType.BANG || (t == TokenType.ASSIGNMENT_WORD && context.has(WordParseContext.Regex))
    }

    fun isProcessSubstitutionStart(offset: Int = 0): Boolean {
        val op = peek(offset); val lp = peek(offset + 1)
        return (op.type == TokenType.LESS || op.type == TokenType.GREAT) && lp.type == TokenType.LPAREN && op.end == lp.start
    }

    fun currentTokenJoinsProcessSubstitution(): Boolean {
        val type = current().type
        if (type == TokenType.COMMENT || !isAdjacentWordToken(WordParseContext.Default)) return false
        return current().end == peek(1).start && isProcessSubstitutionStart(1)
    }

    private fun isAdjacentWordToken(context: WordParseContext): Boolean {
        val type = current().type
        return isWordToken(context) || type == TokenType.ASSIGNMENT_WORD || type == TokenType.COMMENT || type == TokenType.LBRACE || type == TokenType.RBRACE || type == TokenType.DBRACK_START || type == TokenType.DBRACK_END || type == TokenType.FD_VARIABLE
    }

    fun parseWord(context: WordParseContext = WordParseContext.Default): WordNode {
        if (isProcessSubstitutionStart()) return parseAdjacentWordParts(mutableListOf(), current().start, context)
        val t = advance(); val w = parseWordToken(t, context)
        return parseAdjacentWordParts(w.parts, t.end, context)
    }

    fun parseAdjacentWordParts(parts: MutableList<WordPart>, pe: Int, context: WordParseContext = WordParseContext.Default): WordNode {
        var p = pe; var rp = parts
        while (p == current().start) {
            if (isProcessSubstitutionStart()) { rp.add(parseProcessSubstitution()); p = tokens[pos - 1].end; continue }
            if (!isAdjacentWordToken(context)) break
            val t = advance(); rp.addAll(parseWordToken(t, context, rp.isEmpty()).parts); p = t.end
        }
        return WordNode(rp)
    }

    fun parseWordNoBraceExpansion(): WordNode = parseWord(WordParseContext.NoBraceExpansion)
    fun parseWordForRegex(): WordNode = parseWord(WordParseContext.NoBraceExpansion + WordParseContext.Regex)

    private fun parseWordToken(token: Token, context: WordParseContext, atWordStart: Boolean = true): WordNode {
        if (token.type == TokenType.LBRACE || token.type == TokenType.RBRACE || token.type == TokenType.DBRACK_START || token.type == TokenType.DBRACK_END || token.type == TokenType.FD_VARIABLE)
            return WordNode(mutableListOf(LiteralPart(if (token.type == TokenType.FD_VARIABLE) "{${token.value}}" else token.value)))
        return parseWordFromString(token.value, token.quoted, token.singleQuoted, context.has(WordParseContext.Assignment), false, context.has(WordParseContext.NoBraceExpansion), context.has(WordParseContext.Regex), atWordStart)
    }

    fun parseWordFromString(value: String, quoted: Boolean = false, singleQuoted: Boolean = false, isAssignment: Boolean = false, hereDoc: Boolean = false, noBraceExpansion: Boolean = false, regexPattern: Boolean = false, atWordStart: Boolean = true): WordNode {
        val parts = ExpansionParser.parseWordParts(this, value, quoted, singleQuoted, isAssignment, hereDoc, false, noBraceExpansion, regexPattern, false, atWordStart)
        return WordNode(parts.toMutableList())
    }

    fun parseCommandSubstitution(value: String, start: Int): Pair<CommandSubstitutionPart, Int> =
        parseCommandSubstitutionFromString(value, start, ParserFactory { Parser(parseBudget) }, ErrorFn { error(it) })

    fun parseProcessSubstitutionFromString(value: String, start: Int): Pair<ProcessSubstitutionPart, Int> =
        parseProcessSubstitutionFromString(value, start, ParserFactory { Parser(parseBudget) }, ErrorFn { error(it) })

    private fun parseProcessSubstitution(): WordPart {
        val op = advance(); expect(TokenType.LPAREN); val prev = processLineState
        val ll = getSourceLine(op); processLineState = ProcessLineState(ll)
        val body: ScriptNode
        try { body = ScriptNode(parseCompoundList()) } finally { processLineState = prev }
        if (check(TokenType.EOF)) error("unexpected EOF while looking for matching `)'")
        expect(TokenType.RPAREN)
        return ProcessSubstitutionPart(body, if (op.type == TokenType.LESS) "input" else "output")
    }

    fun parseBacktickSubstitution(value: String, start: Int, inDQ: Boolean = false): Pair<CommandSubstitutionPart, Int> =
        parseBacktickSubstitutionFromString(value, start, inDQ, ParserFactory { Parser(parseBudget) }, ErrorFn { error(it) })

    fun isDollarDparenSubshell(value: String, start: Int): Boolean =
        com.justbash.parser.isDollarDparenSubshell(value, start)

    fun parseArithmeticExpansion(value: String, start: Int): Pair<ArithmeticExpansionPart, Int> {
        val es = start + 3; var ad = 1; var pd = 0; var i = es
        while (i < value.length - 1 && ad > 0) {
            when {
                value[i] == '$' && value[i + 1] == '(' -> { if (value[i + 2] == '(') { ad++; i += 3 } else { pd++; i += 2 } }
                value[i] == '(' && value[i + 1] == '(' -> { ad++; i += 2 }
                value[i] == ')' && value[i + 1] == ')' -> { if (pd > 0) { pd--; i++ } else { ad--; if (ad > 0) i += 2 } }
                value[i] == '(' -> { pd++; i++ }
                value[i] == ')' -> { if (pd > 0) pd--; i++ }
                else -> i++
            }
        }
        val expr = ArithmeticParser.parseArithmeticExpression(this, value.substring(es, i))
        return ArithmeticExpansionPart(expr) to (i + 2)
    }

    private fun parseArithmeticCommand(): ArithmeticCommandNode {
        val st = expect(TokenType.DPAREN_START)
        var es = StringBuilder(); var dd = 1; var pd = 0; var pr = false; var fc = false
        while (dd > 0 && !check(TokenType.EOF)) {
            if (pr) { pr = false; if (pd > 0) { pd--; es.append(")"); continue }; if (check(TokenType.RPAREN)) { dd--; fc = true; advance(); continue }; if (check(TokenType.DPAREN_END)) { dd--; fc = true; continue }; es.append(")"); continue }
            when {
                check(TokenType.DPAREN_START) -> { dd++; es.append("(("); advance() }
                check(TokenType.DPAREN_END) -> { if (pd >= 2) { pd -= 2; es.append("))"); advance() } else if (pd == 1) { pd--; es.append(")"); pr = true; advance() } else { dd--; fc = true; if (dd > 0) es.append("))"); advance() } }
                check(TokenType.LPAREN) -> { pd++; es.append("("); advance() }
                check(TokenType.RPAREN) -> { if (pd > 0) pd--; es.append(")"); advance() }
                else -> { val v = current().value; val lc = if (es.isNotEmpty()) es[es.lastIndex] else '\u0000'; val ns = es.isNotEmpty() && !es.endsWith(" ") && !(v == "=" && "|&^+\\-*/%<>".contains(lc)) && !(v == "<" && lc == '<') && !(v == ">" && lc == '>'); if (ns) es.append(" "); es.append(v); advance() }
            }
        }
        if (!fc) expect(TokenType.DPAREN_END)
        val expr = ArithmeticParser.parseArithmeticExpression(this, es.toString().trim())
        val rd = parseOptionalRedirections(); val node = ArithmeticCommandNode(expr, rd); node.line = getSourceLine(st); return node
    }

    private fun parseConditionalCommand(): ConditionalCommandNode {
        val st = expect(TokenType.DBRACK_START); val expr = ConditionalParser.parseConditionalExpression(this)
        expect(TokenType.DBRACK_END); val rd = parseOptionalRedirections()
        val node = ConditionalCommandNode(expr, rd); node.line = getSourceLine(st); return node
    }

    private fun parseFunctionDef(): FunctionDefNode {
        val name: String
        if (check(TokenType.FUNCTION)) {
            advance()
            if (check(TokenType.NAME, TokenType.WORD)) name = advance().value
            else { val t = current(); throw ParseException("Expected function name", t.line, t.column, t) }
            if (check(TokenType.LPAREN)) { advance(); expect(TokenType.RPAREN) }
        } else {
            name = advance().value; if (name.contains('$')) error("`$name': not a valid identifier")
            expect(TokenType.LPAREN); expect(TokenType.RPAREN)
        }
        skipNewlines()
        val body = parseCompoundCommandBody(true); val rd = parseOptionalRedirections()
        return FunctionDefNode(name, body, rd)
    }

    private fun parseCompoundCommandBody(forFunctionBody: Boolean = false): CompoundCommandNode {
        val sr = forFunctionBody
        return when {
            check(TokenType.LBRACE) -> CompoundParser.parseGroup(this, CompoundParser.SkipRedirections(sr))
            check(TokenType.LPAREN) -> CompoundParser.parseSubshell(this, CompoundParser.SkipRedirections(sr))
            check(TokenType.IF) -> CompoundParser.parseIf(this, CompoundParser.SkipRedirections(sr))
            check(TokenType.FOR) -> CompoundParser.parseFor(this, CompoundParser.SkipRedirections(sr))
            check(TokenType.WHILE) -> CompoundParser.parseWhile(this, CompoundParser.SkipRedirections(sr))
            check(TokenType.UNTIL) -> CompoundParser.parseUntil(this, CompoundParser.SkipRedirections(sr))
            check(TokenType.CASE) -> CompoundParser.parseCase(this, CompoundParser.SkipRedirections(sr))
            else -> error("Expected compound command for function body")
        }
    }

    fun parseCompoundList(): MutableList<StatementNode> {
        val exit = enterDepth()
        try {
            val stmts = mutableListOf<StatementNode>(); skipNewlines()
            while ((currentTokenJoinsProcessSubstitution() || !check(TokenType.EOF, TokenType.FI, TokenType.ELSE, TokenType.ELIF, TokenType.THEN, TokenType.DO, TokenType.DONE, TokenType.ESAC, TokenType.RPAREN, TokenType.RBRACE, TokenType.DSEMI, TokenType.SEMI_AND, TokenType.SEMI_SEMI_AND)) && isCommandStart()) {
                checkIterationLimit(); val pb = pos; val stmt = parseStatement()
                if (stmt != null) stmts.add(stmt); skipSeparators()
                if (pos == pb && stmt == null) break
            }
            return stmts
        } finally { exit() }
    }

    fun parseOptionalRedirections(): MutableList<RedirectionNode> {
        val rd = mutableListOf<RedirectionNode>()
        while (CommandParser.isRedirection(this)) { checkIterationLimit(); val pb = pos; rd.add(CommandParser.parseRedirection(this)); if (pos == pb) break }
        return rd
    }
}

fun parse(text: String, options: ParseOptions = ParseOptions()): ScriptNode {
    val parser = Parser(); return parser.parse(text, options)
}