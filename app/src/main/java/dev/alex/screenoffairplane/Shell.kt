package dev.alex.screenoffairplane

import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs commands as the shell user (uid 2000) through Shizuku.
 *
 * Shizuku.newProcess is @hide in the API artifact, so it is reached by
 * reflection. This is the same entry point `rish` uses.
 */
object Shell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun run(vararg cmd: String): Result {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val process = method.invoke(null, arrayOf(*cmd), null, null) as Process

            val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val code = process.waitFor()
            Result(code, out.trim(), err.trim())
        } catch (t: Throwable) {
            Log.e(TAG, "shell failed: ${cmd.joinToString(" ")}", t)
            Result(-1, "", t.message ?: t.toString())
        }
    }

    private const val TAG = "SoA.Shell"
}
