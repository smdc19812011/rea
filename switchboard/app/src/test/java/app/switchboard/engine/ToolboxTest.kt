package app.switchboard.engine

import app.switchboard.data.InstallState
import app.switchboard.data.PackageKind
import app.switchboard.data.RunnerMode
import app.switchboard.data.ToolPackage
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class ToolboxTest {
    companion object {
        private lateinit var proc: Process
        private var port = 0
        private val base get() = "http://127.0.0.1:$port"
        @BeforeClass @JvmStatic fun start() {
            port = ServerSocket(0).use { it.localPort }
            proc = ProcessBuilder("python3", File("src/test/resources/mock_services.py").absolutePath, "$port").redirectErrorStream(true).start()
            assertEquals("ready", proc.inputStream.bufferedReader().readLine())
        }
        @AfterClass @JvmStatic fun stop() { proc.destroy() }
    }

    /** Runs a generated script in bash with a throwaway HOME, like the runner does. */
    private fun bash(script: String, home: File): Pair<Int, String> {
        val p = ProcessBuilder("bash", "-lc", script).redirectErrorStream(true).apply { environment()["HOME"] = home.path }.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun detectsSourceTypes() {
        assertEquals(PackageKind.GIT, Toolbox.detect("https://github.com/smdc19812011/rea"))
        assertEquals(PackageKind.GIT, Toolbox.detect("https://example.com/team/thing.git"))
        assertEquals(PackageKind.URL, Toolbox.detect("https://example.com/sdk.zip"))
        assertEquals(PackageKind.PIP, Toolbox.detect("pip:requests"))
        assertEquals(PackageKind.NPM, Toolbox.detect("npm:typescript"))
        assertEquals(PackageKind.PACKAGE, Toolbox.detect("ffmpeg"))
        assertEquals(PackageKind.SCRIPT, Toolbox.detect("cd /tmp && make install"))
    }

    @Test fun gitInstallClonesThenUpdates() {
        val home = Files.createTempDirectory("home").toFile()
        val repo = File(home, "origin").apply { mkdirs() }
        bash("cd ${repo.path} && git init -q && git config user.email t@t && git config user.name t && echo v1 > f.txt && git add . && git commit -qm one", home)
        val p = ToolPackage(name = "My Repo", source = "file://${repo.path}", kind = PackageKind.GIT)
        val (code, out) = bash(Toolbox.installScript(p, RunnerMode.REMOTE)!!, home)
        assertEquals(out, 0, code)
        assertEquals(File(home, "switchboard-tools/my-repo").path, Toolbox.location(out))
        assertEquals("v1", File(home, "switchboard-tools/my-repo/f.txt").readText().trim())
        bash("cd ${repo.path} && echo v2 > f.txt && git commit -qam two", home)
        assertEquals(0, bash(Toolbox.installScript(p, RunnerMode.REMOTE)!!, home).first) // second run pulls
        assertEquals("v2", File(home, "switchboard-tools/my-repo/f.txt").readText().trim())
        assertEquals(0, bash(Toolbox.checkScript(p, RunnerMode.REMOTE)!!, home).first)
    }

    @Test fun urlInstallUnpacksArchivesAndSendsToken() {
        val home = Files.createTempDirectory("home").toFile()
        val p = ToolPackage(name = "Tool", source = "$base/files/tool.zip")
        val (code, out) = bash(Toolbox.installScript(p, RunnerMode.REMOTE)!!, home)
        assertEquals(out, 0, code)
        assertEquals("echo hello", File(home, "switchboard-tools/tool/bin/hello.sh").readText())
        assertTrue(!File(home, "switchboard-tools/tool/tool.zip").exists()) // archive removed after unpacking
        // Private download: the token is read from a file (so it never appears in the command) and the file is deleted.
        val priv = ToolPackage(name = "Private", source = "$base/files/private.zip", token = "secret")
        File(home, ".tok").writeText(Toolbox.authHeader("secret"))
        val (c2, o2) = bash(Toolbox.installScript(priv, RunnerMode.REMOTE, tokenFile = "\$HOME/.tok")!!, home)
        assertEquals(o2, 0, c2)
        assertTrue(File(home, "switchboard-tools/private/bin/hello.sh").isFile && !File(home, ".tok").exists())
    }

    @Test fun phoneInstallDownloadsGithubRepoAsZip() {
        val tools = Files.createTempDirectory("tools").toFile()
        val p = ToolPackage(name = "Repo", source = "https://github.com/owner/repo", token = "secret")
        val dir = Toolbox.phoneInstall(p, tools, apiBase = base)
        assertEquals("# repo", File(dir, "README.md").readText()) // top folder stripped
        assertEquals("print(1)", File(dir, "src/main.py").readText())
        val noToken = runCatching { Toolbox.phoneInstall(p.copy(token = ""), tools, apiBase = base) }.exceptionOrNull()
        assertTrue("$noToken", noToken?.message.orEmpty().contains("private repo? add an access token"))
        assertTrue(runCatching { Toolbox.phoneInstall(ToolPackage(name = "x", source = "ffmpeg"), tools) }.exceptionOrNull()!!.message!!.contains("Termux or Computer"))
    }

    @Test fun presetsMatchTheMachineAndNoteListsInstalledTools() {
        assertTrue(Toolbox.presets(RunnerMode.PHONE).map { it.name } == listOf("rea (GitHub)"))
        val termux = Toolbox.presets(RunnerMode.TERMUX).map { it.name }
        val remote = Toolbox.presets(RunnerMode.REMOTE).map { it.name }
        // The developer set is available on both Termux and computers.
        Toolbox.developerSet.forEach { assertTrue(it, it in termux && it in remote) }
        assertTrue(termux.toString(), "Android build tools only" in termux && "Termux:API USB access" in termux && "rea (GitHub)" in termux)
        assertTrue("Android build tools only" !in remote && "Termux:API USB access" !in remote)
        assertEquals(Toolbox.catalog.size, Toolbox.catalog.map { it.name }.toSet().size) // names are unique
        // Android SDK on Termux uses ARM build tools from packages and fetches android.jar with sdkmanager.
        val sdk = Toolbox.catalog.first { it.name == "Android SDK" }
        val termuxScript = Toolbox.installScript(sdk, RunnerMode.TERMUX)!!
        assertTrue(termuxScript, "pkg install -y openjdk-17 aapt aapt2 d8 apksigner" in termuxScript && "platforms;android-35" in termuxScript && "ANDROID_HOME" in termuxScript)
        val pkgs = listOf(
            ToolPackage(name = "rea (GitHub)", source = "https://github.com/smdc19812011/rea", installs = mapOf("TERMUX" to InstallState(1, true, "/data/home/switchboard-tools/rea-github"))),
            ToolPackage(name = "FFmpeg", source = "ffmpeg"))
        val note = Toolbox.note(pkgs, RunnerMode.TERMUX)
        assertTrue(note, note.contains("- rea (GitHub) at /data/home/switchboard-tools/rea-github") && note.contains("not installed on this machine yet: FFmpeg"))
        assertEquals("", Toolbox.note(emptyList(), RunnerMode.TERMUX))
    }

    @Test fun statusLineShowsTheRealErrorNotBrokenPipe() {
        val log = "Exit code 1.\nLoading package information...\nError: Failed to find package 'platforms;android-35'\n[=======   ] 70%\nyes: standard output: Broken pipe\n"
        assertEquals("Error: Failed to find package 'platforms;android-35'", Toolbox.summary(log))
        assertEquals("Done", Toolbox.summary("Exit code 0.\nDone\nLOCATION=/x\n"))
    }

    @Test fun androidSdkScriptSurfacesSdkmanagerFailuresAndJavaVersion() {
        val home = Files.createTempDirectory("home").toFile()
        val sdk = Toolbox.catalog.first { it.name == "Android SDK" }
        // Fake sdkmanager that accepts licenses but fails to install, plus a fake old java.
        val bin = File(home, "bin").apply { mkdirs() }
        val d = File(home, "switchboard-tools/android-sdk/cmdline-tools/latest/bin").apply { mkdirs() }
        File(d, "sdkmanager").apply { writeText("#!/bin/sh\ncase \"$*\" in *--licenses*) head -c 20 >/dev/null; exit 0;; esac\necho \"Error: Failed to find package 'platforms;android-35'\"; exit 1\n"); setExecutable(true) }
        File(bin, "java").apply { writeText("#!/bin/sh\necho 'openjdk version \"21.0.1\" 2023-10-17' >&2\n"); setExecutable(true) }
        val script = Toolbox.installScript(sdk, RunnerMode.REMOTE)!!
        val p = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).apply { environment()["HOME"] = home.path; environment()["PATH"] = "${bin.path}:" + System.getenv("PATH") }.start()
        val out = p.inputStream.bufferedReader().readText(); val code = p.waitFor()
        assertTrue(out, code != 0 && !out.contains("Broken pipe"))
        assertEquals("Error: sdkmanager couldn't install the SDK packages (full log: ${home.path}/switchboard-tools/android-sdk/sdkmanager.log)", Toolbox.summary(out))
        assertTrue(out, out.contains("Failed to find package 'platforms;android-35'")) // the log tail is shown
        // Java 11 is reported clearly.
        File(bin, "java").writeText("#!/bin/sh\necho 'openjdk version \"11.0.2\" 2019-01-15' >&2\n")
        val p2 = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).apply { environment()["HOME"] = home.path; environment()["PATH"] = "${bin.path}:" + System.getenv("PATH") }.start()
        val out2 = p2.inputStream.bufferedReader().readText(); p2.waitFor()
        assertTrue(out2, Toolbox.summary(out2).startsWith("Error: the Android SDK tools need Java 17 or newer"))
    }
}
