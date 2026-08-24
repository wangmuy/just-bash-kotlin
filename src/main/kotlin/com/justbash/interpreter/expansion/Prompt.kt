package com.justbash.interpreter.expansion

import com.justbash.encoding.Encoding
import java.util.Date
import java.util.Calendar

private class BoundedAppender(private val maxBytes: Int) { private val chunks = ArrayList<String>(); private var bytes = 0; fun append(value: String) { val vb = Encoding.utf8ByteLength(value); if (vb > maxBytes - bytes) throw ExecutionLimitError("prompt expansion exceeds string length limit ($maxBytes bytes)", "string_length"); if (value.isNotEmpty()) chunks.add(value); bytes += vb }; fun build(): String = chunks.joinToString("") }

fun expandPrompt(ctx: ExpansionContext, value: String): String {
    val result = BoundedAppender(ctx.limits.maxStringLength); var i = 0
    val user = ctx.state.env["USER"] ?: ctx.state.env["LOGNAME"] ?: "user"
    val hostname = ctx.state.env["HOSTNAME"] ?: "localhost"
    val shortHost = hostname.split(".")[0]
    val pwd = ctx.state.env["PWD"] ?: "/"
    val home = ctx.state.env["HOME"] ?: "/"
    val tildeExpanded = if (pwd.startsWith(home)) "~${pwd.substring(home.length)}" else pwd
    val pwdBasename = pwd.split("/").lastOrNull() ?: pwd
    val cmdNum = ctx.state.env["__COMMAND_NUMBER"] ?: "1"
    while (i < value.length) { val char = value[i]; if (char == '\\') { if (i + 1 >= value.length) { result.append("\\"); i++; continue }; val next = value[i + 1]; if (next in '0'..'7') { var os = ""; var j = i + 1; while (j < value.length && j < i + 4 && value[j] in '0'..'7') { os += value[j]; j++ }; val code = Integer.parseInt(os, 8) % 256; result.append(code.toChar().toString()); i = j; continue }; var advanced = false; when (next) { '\\' -> result.append("\\"); 'a' -> result.append("\u0007"); 'e' -> result.append("\u001b"); 'n' -> result.append("\n"); 'r' -> result.append("\r"); '$' -> result.append("\$"); '[', ']' -> {}; 'u' -> result.append(user); 'h' -> result.append(shortHost); 'H' -> result.append(hostname); 'w' -> result.append(tildeExpanded); 'W' -> result.append(pwdBasename); 's' -> result.append("bash"); 'v' -> result.append("5.0"); 'V' -> result.append("5.0.0"); 'j' -> result.append("0"); 'l' -> result.append("tty"); '#' -> result.append(cmdNum); '!' -> result.append(cmdNum); 'D' -> { if (i + 2 < value.length && value[i + 2] == '{') { val ci = value.indexOf('}', i + 3); if (ci != -1) { i = ci + 1; advanced = true } } }; else -> {} }; if (!advanced) i += 2 } else { result.append(char.toString()); i++ } }
    return result.build()
}
