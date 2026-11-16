package app.switchboard.engine

import app.switchboard.data.PackageKind
import app.switchboard.data.RunnerMode
import app.switchboard.data.ToolPackage
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Install commands, checks and presets for the toolbox. Tools live in ~/switchboard-tools on Termux/computers and in
 * the workspace's tools/ folder on the phone.
 */
object Toolbox {
    const val INSTALL_TOOL = "install_tool"

    val installToolSpec = app.switchboard.net.ToolSpec(INSTALL_TOOL,
        "Install a tool, driver, SDK, library or repository permanently on the tool machine and add it to the user's Toolbox, so every future chat, " +
            "project, agent and scheduled task can use it without downloading again. Check the Toolbox list in the system prompt first.",
        kotlinx.serialization.json.buildJsonObject {
            put("type", "object")
            put("properties", kotlinx.serialization.json.buildJsonObject {
                fun p(n: String, d: String) = put(n, kotlinx.serialization.json.buildJsonObject { put("type", "string"); put("description", d) })
                p("name", "Short name, e.g. Android platform tools")
                p("source", "Package name (pkg/apt), pip:name, npm:name, git repo URL, download URL, or an install script")
                p("type", "Optional: package, pip, npm, git, url or script (default: detected from source)")
                p("description", "What it is, shown to future chats")
            })
            put("required", kotlinx.serialization.json.JsonArray(listOf("name", "source").map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })

    private val gitHost = Regex("^https?://(www\\.)?(github\\.com|gitlab\\.com|bitbucket\\.org|codeberg\\.org)/[^/\\s]+/[^/\\s]+?(\\.git)?/?$", RegexOption.IGNORE_CASE)

    fun kindOf(p: ToolPackage): PackageKind = if (p.kind != PackageKind.AUTO) p.kind else detect(p.source)

    fun detect(source: String): PackageKind {
        val s = source.trim()
        return when {
            s.startsWith("pip:") -> PackageKind.PIP
            s.startsWith("npm:") -> PackageKind.NPM
            s.endsWith(".git") || gitHost.matches(s) || s.startsWith("git@") -> PackageKind.GIT
            s.startsWith("http://") || s.startsWith("https://") -> PackageKind.URL
            '\n' in s || s.contains("&&") || s.contains(';') -> PackageKind.SCRIPT
            else -> PackageKind.PACKAGE
        }
    }

    fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "tool" }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun bare(src: String) = src.removePrefix("pip:").removePrefix("npm:").trim()

    /** Folder for git/URL tools on a runner machine. */
    fun runnerDir(p: ToolPackage) = "\$HOME/switchboard-tools/${slug(p.name)}"

    /** Shell command that installs [p] on [mode], or null when that machine can't (phone packages). */
    fun installScript(p: ToolPackage, mode: RunnerMode, tokenFile: String? = null): String? {
        p.scripts[mode.name]?.let { return it }
        // Presets with per-machine scripts only run where they have one.
        if (p.scripts.isNotEmpty() && kindOf(p) == PackageKind.SCRIPT) return null
        val src = bare(p.source)
        val termux = mode == RunnerMode.TERMUX
        val header = tokenFile?.let { "H=\"\$(cat $it)\"; rm -f $it; " } ?: "H=; "
        return when (kindOf(p)) {
            PackageKind.PACKAGE -> when (mode) {
                RunnerMode.TERMUX -> "pkg install -y $src"
                RunnerMode.REMOTE -> "if command -v apt-get >/dev/null; then sudo -n apt-get install -y $src; elif command -v dnf >/dev/null; then sudo -n dnf install -y $src; " +
                    "elif command -v pacman >/dev/null; then sudo -n pacman -S --noconfirm $src; elif command -v brew >/dev/null; then brew install $src; " +
                    "else echo 'No supported package manager (apt, dnf, pacman, brew) found'; exit 1; fi"
                RunnerMode.PHONE -> null
            }
            PackageKind.PIP -> when (mode) {
                RunnerMode.TERMUX -> "command -v pip >/dev/null || pkg install -y python; pip install $src"
                RunnerMode.REMOTE -> "python3 -m pip install --user $src || pip3 install --user $src"
                RunnerMode.PHONE -> null
            }
            PackageKind.NPM -> when (mode) {
                RunnerMode.TERMUX -> "command -v npm >/dev/null || pkg install -y nodejs; npm install -g $src"
                RunnerMode.REMOTE -> "npm install -g $src"
                RunnerMode.PHONE -> null
            }
            PackageKind.GIT -> if (mode == RunnerMode.PHONE) null else {
                val d = runnerDir(p)
                (if (termux) "command -v git >/dev/null || pkg install -y git; " else "") + header +
                    "d=\"$d\"; mkdir -p \"\$(dirname \"\$d\")\"; " +
                    "if [ -d \"\$d/.git\" ]; then git \${H:+-c http.extraHeader=\"\$H\"} -C \"\$d\" pull --ff-only; " +
                    "else git \${H:+-c http.extraHeader=\"\$H\"} clone --depth 1 ${q(src)} \"\$d\"; fi && echo \"LOCATION=\$d\""
            }
            PackageKind.URL -> if (mode == RunnerMode.PHONE) null else {
                val d = runnerDir(p)
                (if (termux) "command -v curl >/dev/null && command -v unzip >/dev/null || pkg install -y curl unzip; " else "") + header +
                    "d=\"$d\"; mkdir -p \"\$d\" && cd \"\$d\" && u=${q(src)}; f=\$(basename \"\${u%%\\?*}\"); [ -n \"\$f\" ] || f=download; " +
                    "curl -fL \${H:+-H \"\$H\"} -o \"\$f\" \"\$u\" && case \"\$f\" in *.zip) unzip -oq \"\$f\" && rm -f \"\$f\";; " +
                    "*.tar.gz|*.tgz) tar xzf \"\$f\" && rm -f \"\$f\";; *.tar.xz) tar xJf \"\$f\" && rm -f \"\$f\";; *.tar.bz2) tar xjf \"\$f\" && rm -f \"\$f\";; esac && echo \"LOCATION=\$d\""
            }
            PackageKind.SCRIPT -> if (mode == RunnerMode.PHONE) null else src
            PackageKind.AUTO -> null
        }
    }

    /** Command that exits 0 when [p] is already present (and may print LOCATION=...), or null when there's no way to tell. */
    fun checkScript(p: ToolPackage, mode: RunnerMode): String? {
        p.checks[mode.name]?.let { return it }
        if (p.checks.isNotEmpty() && kindOf(p) == PackageKind.SCRIPT) return null
        val src = bare(p.source)
        val first = src.split(Regex("\\s+")).first()
        return when (kindOf(p)) {
            PackageKind.PACKAGE -> "command -v $first >/dev/null && echo \"LOCATION=\$(command -v $first)\" || dpkg -s $first >/dev/null 2>&1"
            PackageKind.PIP -> "python3 -m pip show $first >/dev/null 2>&1 || pip show $first >/dev/null 2>&1"
            PackageKind.NPM -> "npm ls -g $first >/dev/null 2>&1"
            PackageKind.GIT, PackageKind.URL -> if (mode == RunnerMode.PHONE) null else "d=\"${runnerDir(p)}\"; [ -n \"\$(ls -A \"\$d\" 2>/dev/null)\" ] && echo \"LOCATION=\$d\""
            else -> null
        }
    }

    /** Header line for private repos/downloads (GitHub-style token as basic auth, which GitHub, GitLab and Bitbucket accept). */
    fun authHeader(token: String) = "Authorization: Basic " + Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())

    fun location(output: String): String = output.lines().lastOrNull { it.startsWith("LOCATION=") }?.removePrefix("LOCATION=")?.trim().orEmpty()

    // ---------- phone (no shell tools): downloads and GitHub repos as ZIPs ----------

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()

    /** Installs a git (GitHub) or URL tool into [toolsDir]/<slug> on the phone. Returns the folder. */
    fun phoneInstall(p: ToolPackage, toolsDir: File, apiBase: String = "https://api.github.com"): File {
        val dest = File(toolsDir, slug(p.name))
        val src = p.source.trim()
        val url = when (kindOf(p)) {
            PackageKind.GIT -> {
                val m = Regex("github\\.com[/:]([^/\\s]+)/([^/\\s]+?)(\\.git)?/?$", RegexOption.IGNORE_CASE).find(src)
                    ?: throw IllegalArgumentException("On the phone, only GitHub repositories can be added (as a download). Use Termux or Computer mode for other git hosts.")
                "$apiBase/repos/${m.groupValues[1]}/${m.groupValues[2]}/zipball"
            }
            PackageKind.URL -> src
            else -> throw IllegalArgumentException("Installing packages needs Termux or Computer mode (Settings > Tools); the phone sandbox can't run installers.")
        }
        val req = Request.Builder().url(url).header("User-Agent", "Switchboard")
            .apply { if (p.token.isNotBlank()) header("Authorization", "Bearer ${p.token}") }.build()
        val tmp = File(toolsDir, ".${slug(p.name)}.download").apply { parentFile?.mkdirs() }
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download failed: HTTP ${r.code}${if (r.code == 404 && p.token.isBlank()) " (private repo? add an access token)" else ""}")
            tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        try {
            dest.deleteRecursively(); dest.mkdirs()
            val isZip = tmp.inputStream().use { val h = ByteArray(2); it.read(h) == 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() }
            if (!isZip) { tmp.copyTo(File(dest, url.substringAfterLast('/').substringBefore('?').ifBlank { "download" }), overwrite = true); return dest }
            val strip = kindOf(p) == PackageKind.GIT // GitHub zips wrap everything in one top folder
            ZipInputStream(tmp.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val rel = if (strip) e.name.substringAfter('/', "") else e.name
                    if (rel.isBlank()) continue
                    val out = File(dest, rel).canonicalFile
                    require(out.path.startsWith(dest.canonicalPath + File.separator)) { "Unsafe path in archive: ${e.name}" }
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                }
            }
        } finally { tmp.delete() }
        return dest
    }

    // ---------- presets ----------

    private fun preset(name: String, desc: String, termux: String?, remote: String?, termuxCheck: String?, remoteCheck: String?, phone: Pair<PackageKind, String>? = null) =
        ToolPackage(
            name = name, description = desc, kind = phone?.first ?: PackageKind.SCRIPT, source = phone?.second ?: (remote ?: termux ?: ""),
            scripts = listOfNotNull(termux?.let { RunnerMode.TERMUX.name to it }, remote?.let { RunnerMode.REMOTE.name to it }).toMap(),
            checks = listOfNotNull(termuxCheck?.let { RunnerMode.TERMUX.name to it }, remoteCheck?.let { RunnerMode.REMOTE.name to it }).toMap(),
        )

    private fun pkg(name: String, desc: String, termuxPkg: String, aptPkg: String, bin: String) = preset(name, desc,
        "pkg install -y $termuxPkg",
        "if command -v apt-get >/dev/null; then sudo -n apt-get install -y $aptPkg; elif command -v brew >/dev/null; then brew install ${aptPkg.split(' ').first()}; elif command -v dnf >/dev/null; then sudo -n dnf install -y $aptPkg; else echo 'No supported package manager found'; exit 1; fi",
        "command -v $bin >/dev/null && echo \"LOCATION=\$(command -v $bin)\"", "command -v $bin >/dev/null && echo \"LOCATION=\$(command -v $bin)\"")

    private const val SDK_URL = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

    /** Common tools; [mode] filters out the ones that machine can't install. */
    fun presets(mode: RunnerMode): List<ToolPackage> = listOf(
        pkg("Python 3", "Python with pip", "python", "python3 python3-pip", "python3"),
        pkg("Node.js", "JavaScript runtime with npm", "nodejs", "nodejs npm", "node"),
        pkg("Git", "Version control", "git", "git", "git"),
        pkg("Java (OpenJDK 17)", "Java compiler and runtime", "openjdk-17", "openjdk-17-jdk", "javac"),
        preset("Android build tools", "aapt2, d8, apksigner, zip and android.jar for building APKs",
            "pkg install -y openjdk-17 aapt aapt2 d8 apksigner zip && echo \"LOCATION=\$(find \$PREFIX -name android.jar 2>/dev/null | head -1)\"",
            null, "command -v aapt2 >/dev/null && command -v d8 >/dev/null && echo \"LOCATION=\$(find \$PREFIX -name android.jar 2>/dev/null | head -1)\"", null),
        preset("Android SDK (command-line)", "sdkmanager, platform-tools, platform 35 and build-tools 35 (needs Java)", null,
            "set -e; d=\$HOME/switchboard-tools/android-sdk; mkdir -p \$d/cmdline-tools; cd \$d; " +
                "if [ ! -x cmdline-tools/latest/bin/sdkmanager ]; then curl -fLo clt.zip $SDK_URL; unzip -qo clt.zip -d cmdline-tools; rm -rf cmdline-tools/latest; mv cmdline-tools/cmdline-tools cmdline-tools/latest; rm -f clt.zip; fi; " +
                "yes | cmdline-tools/latest/bin/sdkmanager --sdk_root=\$d 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' >/dev/null; echo \"LOCATION=\$d\"",
            null, "d=\$HOME/switchboard-tools/android-sdk; [ -d \$d/platforms/android-35 ] && echo \"LOCATION=\$d\""),
        pkg("C/C++ compiler", "clang/gcc, make", "clang make", "build-essential", "cc"),
        pkg("Go", "Go toolchain", "golang", "golang", "go"),
        pkg("Rust", "rustc and cargo", "rust", "rustc cargo", "cargo"),
        pkg("FFmpeg", "Audio/video conversion", "ffmpeg", "ffmpeg", "ffmpeg"),
        pkg("ImageMagick", "Image conversion and editing", "imagemagick", "imagemagick", "magick"),
        pkg("Pandoc", "Document conversion (Markdown, Word, PDF, ...)", "pandoc", "pandoc", "pandoc"),
        pkg("SQLite", "SQLite database shell", "sqlite", "sqlite3", "sqlite3"),
        preset("esptool", "Flash ESP32/ESP8266 boards", "command -v pip >/dev/null || pkg install -y python; pip install esptool",
            "python3 -m pip install --user esptool", "command -v esptool.py >/dev/null || command -v esptool >/dev/null", "python3 -m pip show esptool >/dev/null 2>&1"),
        preset("arduino-cli", "Compile and upload Arduino sketches",
            "pkg install -y curl; mkdir -p \$HOME/switchboard-tools/bin && curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=\$HOME/switchboard-tools/bin sh && echo \"LOCATION=\$HOME/switchboard-tools/bin/arduino-cli\"",
            "mkdir -p \$HOME/switchboard-tools/bin && curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=\$HOME/switchboard-tools/bin sh && echo \"LOCATION=\$HOME/switchboard-tools/bin/arduino-cli\"",
            "[ -x \$HOME/switchboard-tools/bin/arduino-cli ] && echo \"LOCATION=\$HOME/switchboard-tools/bin/arduino-cli\"",
            "[ -x \$HOME/switchboard-tools/bin/arduino-cli ] && echo \"LOCATION=\$HOME/switchboard-tools/bin/arduino-cli\""),
        pkg("Android platform tools", "adb (incl. adb pair for Android 11+ wireless debugging) and fastboot", "android-tools", "adb fastboot", "adb"),
        pkg("Network tools", "nmap, netcat, dig, traceroute for finding and testing devices on your network",
            "nmap netcat-openbsd dnsutils traceroute", "nmap netcat-openbsd dnsutils traceroute", "nmap"),
        preset("Termux:API USB access", "termux-usb and libusb so programs in Termux can open USB devices (also install the Termux:API app from F-Droid)",
            "pkg install -y termux-api libusb && echo \"LOCATION=\$(command -v termux-usb)\"", null,
            "command -v termux-usb >/dev/null && echo \"LOCATION=\$(command -v termux-usb)\"", null),
        pkg("USB utilities", "lsusb and usb-devices for inspecting USB hardware", "usbutils", "usbutils", "lsusb"),
        ToolPackage(name = "rea (GitHub)", description = "smdc19812011/rea repository", kind = PackageKind.GIT, source = "https://github.com/smdc19812011/rea"),
    ).filter { p -> mode == RunnerMode.PHONE && kindOf(p) in setOf(PackageKind.GIT, PackageKind.URL) || mode != RunnerMode.PHONE && installScript(p, mode) != null }

    /** What the model is told: installed tools with their paths, and listed-but-missing ones. */
    fun note(packages: List<ToolPackage>, mode: RunnerMode): String {
        if (packages.isEmpty()) return ""
        val (ok, missing) = packages.partition { it.installs[mode.name]?.ok == true }
        val sb = StringBuilder()
        if (ok.isNotEmpty()) {
            sb.append("Preinstalled on the tool machine (use these; don't download or install them again):\n")
            ok.forEach { p ->
                val loc = p.installs[mode.name]?.location.orEmpty()
                sb.append("- ${p.name}${if (p.description.isNotBlank()) " (${p.description})" else ""}${if (loc.isNotBlank()) " at $loc" else ""}\n")
            }
            sb.append("Git repositories can be refreshed with `git -C <path> pull` if the user wants the latest version.")
        }
        if (missing.isNotEmpty()) sb.append("\nIn the user's toolbox but not installed on this machine yet: ${missing.joinToString { it.name }}. Settings > Toolbox installs them.")
        return sb.toString().trim()
    }
}
