package com.justbash.interpreter.expansion

private val REGEX_SPECIAL = Regex("[.*+?^\${\\}()|\\[\\]\\\\]")
fun escapeRegex(str: String): String = REGEX_SPECIAL.replace(str) { "\\${it.value}" }
