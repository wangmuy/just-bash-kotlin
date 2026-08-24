package com.justbash.parser

// Parser limits
const val MAX_INPUT_SIZE = 1_000_000
const val MAX_TOKENS = 100_000
const val MAX_PARSE_ITERATIONS = 1_000_000
const val MAX_PARSER_DEPTH = 200

enum class TokenType {
    EOF, NEWLINE, SEMICOLON, AMP,
    PIPE, PIPE_AMP, AND_AND, OR_OR, BANG,
    LESS, GREAT, DLESS, DGREAT, LESSAND, GREATAND, LESSGREAT, DLESSDASH, CLOBBER, TLESS, AND_GREAT, AND_DGREAT,
    LPAREN, RPAREN, LBRACE, RBRACE,
    DSEMI, SEMI_AND, SEMI_SEMI_AND,
    DBRACK_START, DBRACK_END, DPAREN_START, DPAREN_END,
    IF, THEN, ELSE, ELIF, FI, FOR, WHILE, UNTIL, DO, DONE, CASE, ESAC, IN, FUNCTION, SELECT, TIME, COPROC,
    WORD, NAME, NUMBER, ASSIGNMENT_WORD, FD_VARIABLE,
    COMMENT, HEREDOC_CONTENT,
}

val RESERVED_WORDS: Map<String, TokenType> = linkedMapOf(
    "if" to TokenType.IF, "then" to TokenType.THEN, "else" to TokenType.ELSE, "elif" to TokenType.ELIF,
    "fi" to TokenType.FI, "for" to TokenType.FOR, "while" to TokenType.WHILE, "until" to TokenType.UNTIL,
    "do" to TokenType.DO, "done" to TokenType.DONE, "case" to TokenType.CASE, "esac" to TokenType.ESAC,
    "in" to TokenType.IN, "function" to TokenType.FUNCTION, "select" to TokenType.SELECT,
    "time" to TokenType.TIME, "coproc" to TokenType.COPROC,
)

val RESERVED_WORD_TOKEN_TYPES: Set<TokenType> = RESERVED_WORDS.values.toSet()
fun isReservedWordToken(type: TokenType): Boolean = type in RESERVED_WORD_TOKEN_TYPES

data class Token(
    val type: TokenType, val value: String, val start: Int, val end: Int,
    val line: Int, val column: Int,
    val quoted: Boolean = false, val singleQuoted: Boolean = false,
    val heredocDelimiter: String? = null, val heredocTerminated: Boolean? = null,
)

class LexerError(message: String, val line: Int, val column: Int) : Exception("line $line: $message")

class ParseException(message: String, val line: Int, val column: Int, val token: Token? = null)
    : Exception("Parse error at $line:$column: $message")

@JvmInline
value class WordParseContext(val mask: Int) {
    companion object {
        val Default = WordParseContext(0)
        val Assignment = WordParseContext(1 shl 0)
        val NoBraceExpansion = WordParseContext(1 shl 1)
        val Regex = WordParseContext(1 shl 2)
    }
    infix fun has(other: WordParseContext): Boolean = (mask and other.mask) != 0
    operator fun plus(other: WordParseContext): WordParseContext = WordParseContext(mask or other.mask)
}

val REDIRECTION_TOKENS: Set<TokenType> = setOf(
    TokenType.LESS, TokenType.GREAT, TokenType.DLESS, TokenType.DGREAT,
    TokenType.LESSAND, TokenType.GREATAND, TokenType.LESSGREAT, TokenType.DLESSDASH,
    TokenType.CLOBBER, TokenType.TLESS, TokenType.AND_GREAT, TokenType.AND_DGREAT,
)

val REDIRECTION_AFTER_NUMBER: Set<TokenType> = setOf(
    TokenType.LESS, TokenType.GREAT, TokenType.DLESS, TokenType.DGREAT,
    TokenType.LESSAND, TokenType.GREATAND, TokenType.LESSGREAT, TokenType.DLESSDASH,
    TokenType.CLOBBER, TokenType.TLESS,
)

val REDIRECTION_AFTER_FD_VARIABLE: Set<TokenType> = setOf(
    TokenType.LESS, TokenType.GREAT, TokenType.DLESS, TokenType.DGREAT,
    TokenType.LESSAND, TokenType.GREATAND, TokenType.LESSGREAT, TokenType.DLESSDASH,
    TokenType.CLOBBER, TokenType.TLESS, TokenType.AND_GREAT, TokenType.AND_DGREAT,
)

class ParseBudget {
    private var iterations = 0
    private var tokens = 0L
    private var depth = 0

    fun reset() { iterations = 0; tokens = 0; depth = 0 }

    fun chargeIteration(line: Int, column: Int) {
        iterations++
        if (iterations > MAX_PARSE_ITERATIONS)
            throw ParseException("Maximum parse iterations exceeded", line, column)
    }

    fun chargeTokens(count: Int, line: Int = 1, column: Int = 1) {
        tokens += count
        if (tokens > MAX_TOKENS)
            throw ParseException("Too many tokens: exceeds limit of $MAX_TOKENS", line, column)
    }

    fun enter(line: Int, column: Int): () -> Unit {
        depth++
        if (depth > MAX_PARSER_DEPTH) { depth--; throw ParseException("Maximum parser nesting depth exceeded", line, column) }
        var active = true
        return { if (active) { active = false; depth-- } }
    }
}

data class ParseOptions(val maxHeredocSize: Long? = null)