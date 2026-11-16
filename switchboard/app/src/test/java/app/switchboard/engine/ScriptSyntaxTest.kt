package app.switchboard.engine

import app.switchboard.data.RunnerMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every built-in install/check script must at least parse in bash on both Termux and computers. */
class ScriptSyntaxTest {
    @Test fun allCatalogScriptsParse() {
        for (p in Toolbox.catalog) for (mode in listOf(RunnerMode.TERMUX, RunnerMode.REMOTE)) {
            listOfNotNull(Toolbox.installScript(p, mode), Toolbox.checkScript(p, mode)).forEach { script ->
                val proc = ProcessBuilder("bash", "-n", "-c", script).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                assertEquals("${p.name} on $mode: $out\n$script", 0, proc.waitFor())
            }
        }
    }
}
