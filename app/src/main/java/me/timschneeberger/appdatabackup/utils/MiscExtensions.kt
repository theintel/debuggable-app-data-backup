@file:Suppress("unused")

package me.timschneeberger.appdatabackup.utils

import android.util.Log
import java.io.InputStream

fun executeShellCatching(cmdline: String): String = runCatching {
    Runtime.getRuntime().exec(cmdline).readAllToString()
}.getOrElse(Throwable::stackTraceToString)

fun executeShell(cmdline: String): String =
    Runtime.getRuntime().exec(cmdline).readAllToString()

fun Process?.readAllToString(): String = StringBuilder().let {
    it.append(this?.inputStream.readToString(isError = false))
    it.append(this?.errorStream.readToString(isError = true))
    it.toString()
}

fun InputStream?.readToString(isError: Boolean): String {
    return this?.bufferedReader()?.use { reader ->
        val output = StringBuilder()
        reader.forEachLine { line ->
            output.append(line).append('\n')
            if (isError) {
                Log.e("DroppedAPK", line)
            } else {
                Log.i("DroppedAPK", line)
            }
        }
        output.toString()
    } ?: ""
}