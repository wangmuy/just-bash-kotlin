package com.justbash.interpreter.expansion

import com.justbash.ast.DoubleQuotedPart
import com.justbash.ast.EscapedPart
import com.justbash.ast.LiteralPart
import com.justbash.ast.SingleQuotedPart
import com.justbash.ast.WordPart

fun getLiteralValue(part: WordPart): String? = when (part) { is LiteralPart -> part.value; is SingleQuotedPart -> part.value; is EscapedPart -> part.value; else -> null }
fun isQuotedPart(part: WordPart): Boolean = when (part) { is SingleQuotedPart, is EscapedPart, is DoubleQuotedPart -> true; is LiteralPart -> part.value.isEmpty(); else -> false }
