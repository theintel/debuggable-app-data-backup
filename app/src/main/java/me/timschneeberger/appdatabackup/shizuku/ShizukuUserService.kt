package me.timschneeberger.appdatabackup.shizuku

import android.util.Log
import me.timschneeberger.appdatabackup.IShizukuUserService
import java.io.DataOutputStream
import kotlin.system.exitProcess

class ShizukuUserService : IShizukuUserService.Stub() {
    /**
     * Reserved destroy method
     */
    override fun destroy() {
        Log.i(TAG, "destroy")
        exitProcess(0)
    }

    override fun canUseRunAs(): Boolean {
        // Check for SELinux denial. 126 is returned when run-as is denied by SELinux
        return run("run-as").code == 1
    }

    override fun run(command: String): ShellResult {
        val process = Runtime.getRuntime().exec("sh")
        val outputStream = DataOutputStream(process.outputStream)
        val commandResult = try {
            command.split('\n').filter { it.isNotBlank() }.forEach {
                outputStream.write(it.toByteArray())
                outputStream.writeBytes('\n'.toString())
                outputStream.flush()
            }
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            ShellResult(
                code = process.waitFor(),
                result = process.inputStream.bufferedReader().readText(),
                error = process.errorStream.bufferedReader().readText(),
            )
        } catch (e: Exception) {
            e.printStackTrace()
            val message = e.message
            val aimErrStr = "error="
            val index = message?.indexOf(aimErrStr)
            val code = if (index != null) {
                message.substring(index + aimErrStr.length)
                    .takeWhile { c -> c.isDigit() }
                    .toIntOrNull()
            } else {
                null
            } ?: 1
            ShellResult(
                code = code,
                result = "",
                error = e.message,
            )
        } finally {
            outputStream.close()
            process.inputStream.close()
            process.outputStream.close()
            process.destroy()
        }

        Log.i(TAG, "Command executed: $command\nCode: ${commandResult.code}\nResult: ${commandResult.result}\nError: ${commandResult.error}")
        return commandResult
    }

    companion object {
        const val TAG = "ShizukuUserService"
    }
}