package com.justbash.interpreter.expansion

fun quoteValue(value: String): String {
    if (value == "") return "''"
    val needsDollarQuote = Regex("[\\n\\r\\t\\x00-\\x1f\\x7f']").containsMatchIn(value)
    if (needsDollarQuote) { val sb = StringBuilder("\$'"); for (char in value) { when (char) { '\'' -> sb.append("\\'"); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t"); else -> { val code = char.code; if (code < 32 || code == 127) sb.append("\\").append(code.toString(8).padStart(3, '0')); else sb.append(char) } } }; return sb.append("'").toString() }
    return "'$value'"
}
