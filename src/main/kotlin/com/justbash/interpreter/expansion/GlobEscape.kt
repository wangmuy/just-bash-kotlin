package com.justbash.interpreter.expansion

fun hasGlobPattern(value: String, extglob: Boolean): Boolean { if (Regex("[*?\\[]").containsMatchIn(value)) return true; if (extglob && Regex("[@*+?!]\\(").containsMatchIn(value)) return true; return false }
fun unescapeGlobPattern(pattern: String): String { val sb = StringBuilder(); var i = 0; while (i < pattern.length) { if (pattern[i] == '\\' && i + 1 < pattern.length) { sb.append(pattern[i + 1]); i += 2 } else { sb.append(pattern[i]); i++ } }; return sb.toString() }
private val GLOB_META = Regex("([*?\\[\\]\\\\()|])")
fun escapeGlobChars(str: String): String = GLOB_META.replace(str) { "\\${it.value}" }
private val REGEX_META = Regex("([\\\\^$.*+?()\\[\\]{}|])")
fun escapeRegexChars(str: String): String = REGEX_META.replace(str) { "\\${it.value}" }
