package app.switchboard.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Starts runner.py inside Termux through Termux's RUN_COMMAND API, so the user doesn't copy scripts or tokens.
 * Needs (once): Termux installed, `allow-external-apps = true` in ~/.termux/termux.properties,
 * and the "Run commands in Termux environment" permission granted to Switchboard.
 */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val PORT = 8765
    const val URL = "http://127.0.0.1:$PORT"
    const val ENABLE_EXTERNAL_APPS = "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"

    fun isInstalled(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Writes runner.py into Termux's home, installs python if missing, and starts it in the background. */
    fun startRunner(ctx: Context, token: String) {
        if (!isInstalled(ctx)) throw ApiException("Termux isn't installed. Install it from F-Droid, open it once, then try again.")
        if (!hasPermission(ctx)) throw ApiException("Switchboard doesn't have the Termux permission yet. Grant it in Settings > Tools.")
        val script = ctx.assets.open("runner.py").bufferedReader().use { it.readText() }
        // $1 = runner.py source, $2 = token. Re-running replaces any older runner on the port.
        val bootstrap = """
            mkdir -p ~/.switchboard
            printf '%s' "${'$'}1" > ~/.switchboard/runner.py
            command -v python >/dev/null 2>&1 || yes | pkg install -y python
            [ -f ~/.switchboard/pid ] && kill "${'$'}(cat ~/.switchboard/pid)" 2>/dev/null
            echo ${'$'}${'$'} > ~/.switchboard/pid
            exec python ~/.switchboard/runner.py --port $PORT --token "${'$'}2"
        """.trimIndent()
        val intent = Intent("com.termux.RUN_COMMAND")
            .setClassName(PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", bootstrap, "switchboard", script, token))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent) else ctx.startService(intent)
        } catch (e: Exception) {
            throw ApiException("Termux refused to start the runner (${e.message}). In Termux, run: $ENABLE_EXTERNAL_APPS")
        }
    }
}
