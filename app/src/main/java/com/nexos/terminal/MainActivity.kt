package com.nexos.terminal

import android.app.Activity
import android.os.Build
import android.system.Os
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Terminal Ligera 2.1 — terminal propia, con instalador experimental.
 *
 * 2.1: arregla el error 13 (Permission denied) que salió en el
 * teléfono al correr `bash --version`: el chmod de la 2.0 iba
 * por el shell y sin verificar, y los enlaces de SYMLINKS.txt
 * se creaban al revés y en la carpeta equivocada (0 enlaces).
 * Ahora los permisos se ponen con Os.chmod directo (0755) y se
 * verifica que bash sea ejecutable; si bash no arranca, los
 * comandos caen al linker del sistema o al sh de Android en
 * vez de quedarse atorados. El comando nuevo `reparar` vuelve
 * a poner permisos y enlaces sin bajar el bootstrap otra vez.
 *
 * 2.2: el `reparar` de la 2.1 puso los permisos (bash ya dice
 * ejecutable: sí) pero los enlaces siguieron en 0: el archivo
 * real vive en la MISMA carpeta del enlace (ej. el real
 * libreadline.so.8.3 está en lib/ para el enlace
 * lib/libreadline.so.8) y mi código lo buscaba en la raíz de
 * usr/. Sin ese enlace, bash no arranca ni por el linker
 * ("libreadline.so.8 not found"). Además quedó probado que
 * Android bloquea ejecutar bash directo aunque tenga 0755
 * (error 13), así que la vía real es el linker del sistema.
 * bash por el linker y reporta si corre.
 *
 * 2.3: firma estable. Cada build de Actions salía con un
 * certificado distinto (cada runner genera su debug keystore)
 * y Android no dejaba actualizar sin desinstalar, lo que
 * borraba el bootstrap. Ahora hay una sola llave en el repo
 * (app/terminal-ligera.keystore) y el workflow compila en
 * release con ella: el instalador nativo actualiza sin
 * desinstalar y el bootstrap se conserva.
 *
 * 1.0: solo /system/bin/sh de Android, sin instalar nada.
 * 2.0: comando `instalar` baja el bootstrap oficial de Termux
 * (aarch64, ~32 MB, SHA-256 verificado) y lo descomprime en la
 * carpeta privada de la app (filesDir/usr). Después de eso, los
 * comandos corren con el bash de ese bootstrap, con PREFIX, PATH
 * y LD_LIBRARY_PATH apuntando a él, así que `pkg`, `apt`, `curl`
 * y `bash` ya existen, y `pkg-instalar git` puede instalar git.
 * Después, `muse-instalar` intenta instalar Muse Code con su
 * instalador oficial (curl | bash).
 *
 * LÍMITES HONESTOS (léelos antes de ilusionarte):
 * - Esto es un intento, no una garantía. El bootstrap está hecho
 *   para Termux (paquete com.termux); aquí vive en
 *   com.nexos.terminal, así que algunos scripts con rutas de
 *   Termux escritas pueden fallar. El error exacto saldrá en
 *   pantalla y se puede iterar sobre él.
 * - Muse Code es interactivo: necesita una terminal con PTY y
 *   lee su login desde /dev/tty. Esta app corre cada comando
 *   de una sola vez, sin PTY, con límite de tiempo. Por eso
 *   `muse --version` sí puede funcionar, pero `muse login`
 *   y `muse` a secas muy probablemente fallen o se corten.
 *   Lo que sí puede funcionar es `muse exec` con META_API_KEY,
 *   si ya tienes una.
 * - Sin root. Sin tocar /system ni la carpeta de Termux.
 * - La instalación baja ~32 MB y ocupa ~150 MB al descomprimir.
 *   Necesitas internet y espacio libre.
 */
class MainActivity : Activity() {

    private lateinit var scrollTerminal: ScrollView
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText

    private val terminalText = StringBuilder()
    private val history = ArrayList<String>()
    private var historyIndex = -1
    private lateinit var currentDir: File

    companion object {
        private const val MAX_OUTPUT_CHARS = 80000
        private const val MAX_CMD_OUTPUT = 15000
        private const val MAX_LONG_OUTPUT = 30000
        private const val TIMEOUT_SECONDS = 30L
        private const val LONG_TIMEOUT_SECONDS = 180L

        // Bootstrap oficial de Termux, verificado en 2026-11-07:
        // https://github.com/termux/termux-packages/releases/download/
        //   bootstrap-2026.11.01-r1+apt.android-7/bootstrap-aarch64.zip
        // 32 MB, SHA-256 5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae
        // Trae bash, curl, apt, pkg y dpkg. Git NO viene: se instala
        // después con `pkg-instalar git`.
        private const val BOOTSTRAP_VERSION = "2026.11.01-r1+apt.android-7"
        private const val BOOTSTRAP_URL =
            "https://github.com/termux/termux-packages/releases/download/" +
                "bootstrap-2026.11.01-r1+apt.android-7/bootstrap-aarch64.zip"
        private const val BOOTSTRAP_SHA256 =
            "5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae"
        private const val MUSE_INSTALL_URL = "https://dev.meta.ai/install.sh"
    }

    private val prefixDir: File get() = File(filesDir, "usr")
    private val bootstrapOkFile: File get() = File(filesDir, ".bootstrap-ok")
    private val museBin: File get() = File(filesDir, ".local/bin/muse")

    private fun isBootstrapInstalled(): Boolean {
        return bootstrapOkFile.exists() &&
            File(prefixDir, "bin/bash").exists() &&
            File(prefixDir, "bin/curl").exists()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scrollTerminal = findViewById(R.id.scroll_terminal)
        tvOutput = findViewById(R.id.tv_output)
        etInput = findViewById(R.id.et_input)

        currentDir = filesDir

        if (savedInstanceState != null) {
            terminalText.append(savedInstanceState.getString("terminalText", ""))
            val dir = savedInstanceState.getString("currentDir", "")
            if (dir.isNotEmpty()) {
                val f = File(dir)
                if (f.isDirectory && f.canRead()) currentDir = f
            }
            history.addAll(savedInstanceState.getStringArrayList("history") ?: arrayListOf())
            historyIndex = history.size
            tvOutput.text = terminalText.toString()
        } else {
            appendOutput(WELCOME)
            if (isBootstrapInstalled()) {
                appendOutput("[bootstrap ya instalado: bash, curl, pkg listos. Escribe 'estado']\n")
            } else {
                appendOutput("[sin bootstrap todavía. Escribe 'instalar' para bajarlo (~32 MB)]\n")
            }
        }
        scrollToBottom()

        val btnRun: Button = findViewById(R.id.btn_run)
        btnRun.setOnClickListener { runTypedCommand() }

        etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            ) {
                runTypedCommand()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener { runCommand("clear") }
        findViewById<Button>(R.id.btn_up).setOnClickListener { historyStep(-1) }
        findViewById<Button>(R.id.btn_down).setOnClickListener { historyStep(1) }

        // Atajos: corren directo.
        findViewById<Button>(R.id.chip_ls).setOnClickListener { runCommand("ls -la") }
        findViewById<Button>(R.id.chip_pwd).setOnClickListener { runCommand("pwd") }
        findViewById<Button>(R.id.chip_date).setOnClickListener { runCommand("date") }
        findViewById<Button>(R.id.chip_help).setOnClickListener { runCommand("help") }

        // Teclas extra: insertan el caracter en el cursor.
        findViewById<Button>(R.id.key_slash).setOnClickListener { insertText("/") }
        findViewById<Button>(R.id.key_dash).setOnClickListener { insertText("-") }
        findViewById<Button>(R.id.key_pipe).setOnClickListener { insertText("|") }
        findViewById<Button>(R.id.key_tilde).setOnClickListener { insertText("~") }
        findViewById<Button>(R.id.key_dollar).setOnClickListener { insertText("$") }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("terminalText", terminalText.toString())
        outState.putString("currentDir", currentDir.absolutePath)
        outState.putStringArrayList("history", history)
    }

    private fun runTypedCommand() {
        val cmd = etInput.text.toString().trim()
        if (cmd.isEmpty()) return
        etInput.setText("")
        runCommand(cmd)
    }

    /** Punto de entrada de todo comando: propios primero, resto al shell. */
    private fun runCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return

        if (history.isEmpty() || history.last() != cmd) history.add(cmd)
        historyIndex = history.size

        val first = cmd.split(" ").first().lowercase()
        when (first) {
            "clear", "limpiar" -> {
                terminalText.clear()
                tvOutput.text = ""
                return
            }
            "help", "ayuda" -> {
                appendOutput("$ $cmd\n")
                appendOutput(HELP)
                scrollToBottom()
                return
            }
            "acerca", "about" -> {
                appendOutput("$ $cmd\n")
                appendOutput(ABOUT)
                scrollToBottom()
                return
            }
            "history", "historial" -> {
                appendOutput("$ $cmd\n")
                if (history.isEmpty()) {
                    appendOutput("(sin historial)\n")
                } else {
                    history.forEachIndexed { i, h -> appendOutput("  ${i + 1}  $h\n") }
                }
                scrollToBottom()
                return
            }
            "pwd" -> {
                appendOutput("$ $cmd\n${currentDir.absolutePath}\n")
                scrollToBottom()
                return
            }
            "cd" -> {
                appendOutput("$ $cmd\n")
                changeDir(cmd.removePrefix("cd").trim())
                scrollToBottom()
                return
            }
            "salir", "exit" -> {
                appendOutput("$ $cmd\nPara salir usa el botón atrás del teléfono.\n")
                scrollToBottom()
                return
            }
            "estado" -> {
                appendOutput("$ $cmd\n")
                appendOutput(estadoTexto())
                scrollToBottom()
                return
            }
            "reparar" -> {
                appendOutput("$ $cmd\n")
                appendOutput("Reparando permisos y enlaces del bootstrap...\n")
                scrollToBottom()
                Thread {
                    val reporte = repararPermisosYEnlaces()
                    runOnUiThread {
                        appendOutput(reporte)
                        appendOutput(estadoTexto())
                        scrollToBottom()
                    }
                }.start()
                return
            }
            "instalar", "bootstrap" -> {
                appendOutput("$ $cmd\n")
                appendOutput("Instalando bootstrap de Termux ($BOOTSTRAP_VERSION, ~32 MB)...\nNo cierres la app, tarda unos minutos.\n")
                scrollToBottom()
                Thread { instalarBootstrap() }.start()
                return
            }
            "pkg-instalar" -> {
                val args = cmd.removePrefix("pkg-instalar").trim()
                appendOutput("$ $cmd\n")
                if (args.isEmpty() || !args.matches(Regex("[a-z0-9._\\- ]+"))) {
                    appendOutput("Uso: pkg-instalar git   (solo nombres de paquetes, sin ; | $)\n")
                    scrollToBottom()
                    return
                }
                if (!isBootstrapInstalled()) {
                    appendOutput("Primero corre 'instalar' para bajar el bootstrap.\n")
                    scrollToBottom()
                    return
                }
                scrollToBottom()
                Thread {
                    val out = runShellLong("pkg install -y $args")
                    runOnUiThread {
                        if (out.isNotEmpty()) appendOutput(out)
                        appendOutput(estadoTexto())
                        scrollToBottom()
                    }
                }.start()
                return
            }
            "muse-instalar" -> {
                appendOutput("$ $cmd\n")
                if (!isBootstrapInstalled()) {
                    appendOutput("Primero corre 'instalar' para bajar el bootstrap.\n")
                    scrollToBottom()
                    return
                }
                appendOutput("Instalando Muse Code con su instalador oficial...\nEsto baja ~100 MB. Después prueba 'muse --version'.\nOjo: 'muse login' es interactivo y aquí puede fallar, ver 'help'.\n")
                scrollToBottom()
                Thread {
                    val out = runShellLong("curl -fsSL $MUSE_INSTALL_URL | bash")
                    runOnUiThread {
                        if (out.isNotEmpty()) appendOutput(out)
                        if (museBin.exists()) {
                            appendOutput("[muse instalado en .local/bin/muse, probando versión...]\n")
                            Thread {
                                val v = runShellLong("muse --version")
                                runOnUiThread {
                                    if (v.isNotEmpty()) appendOutput(v)
                                    scrollToBottom()
                                }
                            }.start()
                        } else {
                            appendOutput("[muse NO quedó instalado. Revisa el error de arriba.]\n")
                        }
                        scrollToBottom()
                    }
                }.start()
                return
            }
        }

        appendOutput("$ $cmd\n")
        scrollToBottom()

        Thread {
            val out = runShell(cmd, TIMEOUT_SECONDS)
            runOnUiThread {
                if (out.isNotEmpty()) appendOutput(out)
                scrollToBottom()
            }
        }.start()
    }

    private fun estadoTexto(): String {
        val sb = StringBuilder()
        sb.append("Estado Terminal Ligera 2.3\n")
        sb.append("  bootstrap: " + if (isBootstrapInstalled()) "instalado ($BOOTSTRAP_VERSION)" else "NO instalado (corre 'instalar')")
        sb.append("\n")
        sb.append("  carpeta: ${filesDir.absolutePath}\n")
        if (isBootstrapInstalled()) {
            val prefix = prefixDir.absolutePath
            val bashFile = File(prefix, "bin/bash")
            sb.append("  bin/bash ejecutable: " + if (bashFile.canExecute()) "sí" else "NO — corre 'reparar'")
            sb.append("\n")
            listOf("bin/bash", "bin/curl", "bin/apt", "bin/pkg", "bin/git", "bin/python3").forEach {
                sb.append("  $it: " + if (File(prefix, it).exists()) "sí" else "no")
                sb.append("\n")
            }
        }
        sb.append("  muse: " + if (museBin.exists()) "instalado en .local/bin/muse" else "no instalado (corre 'muse-instalar' tras el bootstrap)")
        sb.append("\n")
        return sb.toString()
    }

    /** Descarga (con SHA-256) y descomprime el bootstrap en filesDir/usr. */
    private fun instalarBootstrap() {
        fun post(s: String) {
            runOnUiThread {
                appendOutput(s)
                scrollToBottom()
            }
        }
        try {
            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                post("[error: este instalador solo trae bootstrap aarch64 (arm64). Este teléfono reporta: ${Build.SUPPORTED_ABIS.joinToString()}]\n")
                return
            }
            if (isBootstrapInstalled()) {
                post("[el bootstrap ya estaba instalado. Reparando permisos y enlaces...]\n")
                post(repararPermisosYEnlaces())
                post(estadoTexto())
                return
            }
            val zipFile = File(filesDir, "bootstrap-aarch64.zip")
            post("Bajando bootstrap (~32 MB) de github.com/termux/termux-packages...\n")
            val url = URL(BOOTSTRAP_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                post("[error al bajar: HTTP ${conn.responseCode}]\n")
                return
            }
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var bajado = 0L
            var ultimoAviso = 0L
            BufferedInputStream(conn.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(zipFile)).use { out ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        bajado += n
                        if (bajado - ultimoAviso >= 5 * 1024 * 1024) {
                            ultimoAviso = bajado
                            val mb = bajado / 1024 / 1024
                            val tot = if (total > 0) " / ${total / 1024 / 1024} MB" else ""
                            post("  bajando... $mb MB$tot\n")
                        }
                    }
                }
            }
            conn.disconnect()
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            post("SHA-256 bajado: $sha\n")
            if (sha != BOOTSTRAP_SHA256) {
                zipFile.delete()
                post("[error: el SHA-256 no coincide con el oficial. Borré el zip, inténtalo de nuevo.]\n")
                return
            }
            post("SHA-256 correcto. Descomprimiendo en usr/...\n")
            prefixDir.mkdirs()
            var archivos = 0
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val outFile = File(prefixDir, entry.name)
                    // Seguridad: nada fuera de usr/.
                    if (outFile.canonicalPath.startsWith(prefixDir.canonicalPath)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                                zin.copyTo(out)
                            }
                            if (entry.name.startsWith("bin/") || entry.name.startsWith("libexec/") ||
                                entry.name.endsWith(".sh")
                            ) {
                                outFile.setExecutable(true, false)
                            }
                            archivos++
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            post("  $archivos archivos. Creando enlaces (SYMLINKS.txt)...\n")
            val enlaces = crearEnlaces()
            post("  $enlaces enlaces creados.\n")
            post("Dando permisos de ejecución a bin/ y libexec/...\n")
            post(aplicarPermisosEjecucion())
            if (!File(prefixDir, "bin/bash").canExecute()) {
                post("[ojo: bin/bash sigue sin ser ejecutable tras el chmod. Corre 'reparar' y dime qué dice.]\n")
            }
            zipFile.delete()
            bootstrapOkFile.writeText("bootstrap $BOOTSTRAP_VERSION instalado\n")
            post("Listo. Bootstrap instalado.\n")
            post(estadoTexto())
            post("Prueba ahora, en orden:\n  bash --version\n  curl --version\n  pkg-instalar git\n  git --version\nDespués: muse-instalar\n")
        } catch (e: Exception) {
            runOnUiThread {
                appendOutput("[error en instalar: ${e.message}]\n")
                scrollToBottom()
            }
        }
    }

    /** Crea los enlaces de SYMLINKS.txt.
     * Formato real del archivo (verificado con el zip oficial,
     * 1213 líneas): `ARCHIVO_REAL ← ./ruta/del/enlace`.
     * La izquierda es el archivo que ya existe y la derecha
     * (con ./) es el enlace a crear, en esa carpeta, apuntando
     * al archivo de la izquierda. Lo clave que la 2.1 falló:
     * el archivo real vive en la MISMA carpeta del enlace
     * (ej. lib/libreadline.so.8.3 es el real del enlace
     * lib/libreadline.so.8), no en la raíz de usr/.
     * Se hacen varias pasadas porque hay cadenas: un enlace
     * apunta a otro enlace que se crea en la misma tanda. */
    private fun crearEnlaces(): Int {
        var creados = 0
        try {
            val lista = File(prefixDir, "SYMLINKS.txt")
            if (!lista.exists()) return 0
            for (pasada in 0 until 3) {
                var enEstaPasada = 0
                lista.forEachLine { linea ->
                    if (!linea.contains("←")) return@forEachLine
                    val partes = linea.split("←")
                    if (partes.size != 2) return@forEachLine
                    val real = partes[0].trim()
                    val enlaceRel = partes[1].trim().removePrefix("./")
                    if (real.isEmpty() || enlaceRel.isEmpty()) return@forEachLine
                    val enlace = File(prefixDir, enlaceRel)
                    if (enlace.exists()) return@forEachLine
                    val dirEnlace = enlace.parentFile ?: return@forEachLine
                    val realFile = when {
                        File(dirEnlace, real.substringAfterLast("/")).exists() ->
                            File(dirEnlace, real.substringAfterLast("/"))
                        File(prefixDir, real).exists() -> File(prefixDir, real)
                        else -> return@forEachLine
                    }
                    try {
                        dirEnlace.mkdirs()
                        val rel = dirEnlace.toPath().relativize(realFile.toPath()).toString()
                        Os.symlink(rel, enlace.absolutePath)
                        creados++
                        enEstaPasada++
                    } catch (e: Exception) {
                        // Se cuenta solo lo que sí se creó.
                    }
                }
                if (enEstaPasada == 0) break
            }
        } catch (e: Exception) {
            // Los enlaces que falten se reportan en el conteo.
        }
        return creados
    }

    /** Pone 0755 con Os.chmod directo (sin pasar por el shell) a
     * todo lo de bin/ y libexec/. Devuelve un reporte con cuántos
     * archivos se arreglaron y si bash quedó ejecutable. En la
     * 2.0 esto se hacía con un `chmod -R +x` por el shell, sin
     * verificar, y en el teléfono el bash quedó sin permiso de
     * ejecución (error 13 al correr `bash --version`). */
    private fun aplicarPermisosEjecucion(): String {
        var total = 0
        var errores = 0
        fun arreglar(f: File) {
            try {
                if (f.isDirectory) {
                    f.listFiles()?.forEach { arreglar(it) }
                } else {
                    Os.chmod(f.absolutePath, 493) // 0755
                    total++
                }
            } catch (e: Exception) {
                errores++
            }
        }
        listOf(File(prefixDir, "bin"), File(prefixDir, "libexec")).forEach {
            if (it.exists()) arreglar(it)
        }
        val bashOk = File(prefixDir, "bin/bash").canExecute()
        return "  permisos 0755 aplicados a $total archivos ($errores errores).\n" +
            "  bin/bash ejecutable: " + if (bashOk) "sí\n" else "NO\n"
    }

    /** Prueba si bash arranca por el linker del sistema. En este
     * teléfono Android bloquea ejecutar bash directo aunque tenga
     * 0755 (error 13), así que el linker es la vía real. */
    private fun probarBashPorLinker(): String {
        return try {
            val bashPath = File(prefixDir, "bin/bash").absolutePath
            val pb = ProcessBuilder("/system/bin/linker64", bashPath, "-c", "echo prueba-bash-ok")
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            val prefix = prefixDir.absolutePath
            env["HOME"] = filesDir.absolutePath
            env["PREFIX"] = prefix
            env["TMPDIR"] = "$prefix/tmp"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["PATH"] = "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin"
            env["LD_LIBRARY_PATH"] = "$prefix/lib"
            val proc = pb.start()
            val termino = proc.waitFor(15, TimeUnit.SECONDS)
            if (!termino) {
                proc.destroyForcibly()
                return "  prueba bash (linker): NO arrancó (tiempo agotado)\n"
            }
            val salida = proc.inputStream.bufferedReader().readText().trim()
            val codigo = proc.exitValue()
            "  prueba bash (linker): " +
                if (codigo == 0 && salida.contains("prueba-bash-ok")) "SÍ, bash corre\n"
                else "NO (código $codigo): $salida\n"
        } catch (e: Exception) {
            "  prueba bash (linker): NO (${e.message})\n"
        }
    }

    /** Repara permisos y enlaces sin bajar el bootstrap otra vez. */
    private fun repararPermisosYEnlaces(): String {
        if (!prefixDir.exists()) return "[no hay bootstrap instalado. Corre 'instalar' primero.]\n"
        val sb = StringBuilder()
        sb.append(aplicarPermisosEjecucion())
        val enlaces = crearEnlaces()
        sb.append("  enlaces creados: $enlaces\n")
        sb.append(
            "  enlace libreadline.so.8: " +
                if (File(prefixDir, "lib/libreadline.so.8").exists()) "sí\n" else "NO\n"
        )
        sb.append(probarBashPorLinker())
        val bashOk = File(prefixDir, "bin/bash").canExecute()
        sb.append(
            if (bashOk) "Listo. Prueba 'bash --version'.\n"
            else "[bash sigue sin ejecutarse. Prueba 'bash --version' y mándame el error exacto.]\n"
        )
        return sb.toString()
    }

    /** Corre un comando corto con /system/bin/sh. Devuelve true si salió con 0. */
    private fun runSystemSh(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .directory(prefixDir)
                .redirectErrorStream(true)
                .start()
            p.waitFor(15, TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Shell principal: bash del bootstrap si está, si no /system/bin/sh. */
    private fun runShell(cmd: String, timeoutSeconds: Long): String {
        return runShellWithTimeout(cmd, timeoutSeconds, MAX_CMD_OUTPUT)
    }

    private fun runShellLong(cmd: String): String {
        return runShellWithTimeout(cmd, LONG_TIMEOUT_SECONDS, MAX_LONG_OUTPUT)
    }

    private fun runShellWithTimeout(cmd: String, timeoutSeconds: Long, maxChars: Int): String {
        return try {
            val bootstrap = isBootstrapInstalled()
            val bashPath = File(prefixDir, "bin/bash").absolutePath
            // Candidatos de shell, en orden. En la 2.0 solo se intentaba
            // el bash del bootstrap y si fallaba con permiso denegado
            // (error 13) todo el comando moría. Ahora se cae al linker
            // del sistema (que lee el binario en vez de ejecutarlo, y
            // funciona aunque el archivo no tenga permiso de ejecución)
            // y luego al sh de Android, y se avisa cuál se usó.
            val intentos: List<List<String>> = if (bootstrap) {
                listOf(
                    listOf(bashPath, "-c", cmd),
                    listOf("/system/bin/linker64", bashPath, "-c", cmd),
                    listOf("/system/bin/sh", "-c", cmd)
                )
            } else {
                listOf(listOf("/system/bin/sh", "-c", cmd))
            }
            fun nuevoPb(args: List<String>): ProcessBuilder {
                val p = ProcessBuilder(args)
                p.directory(currentDir)
                p.redirectErrorStream(true)
                if (bootstrap) {
                    val env = p.environment()
                    val prefix = prefixDir.absolutePath
                    val home = filesDir.absolutePath
                    env["HOME"] = home
                    env["PREFIX"] = prefix
                    env["TMPDIR"] = "$prefix/tmp"
                    env["TERM"] = "xterm-256color"
                    env["LANG"] = "en_US.UTF-8"
                    env["PATH"] = "$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin"
                    env["LD_LIBRARY_PATH"] = "$prefix/lib"
                    // Sin LD_PRELOAD a propósito: en la 2.0 se prueba sin él.
                }
                return p
            }
            var process: Process? = null
            var shellUsado = ""
            var ultimoError: Exception? = null
            for (intento in intentos) {
                try {
                    process = nuevoPb(intento).start()
                    shellUsado = intento[0]
                    break
                } catch (e: Exception) {
                    ultimoError = e
                }
            }
            val proc = process
                ?: return "[error: no se pudo arrancar ningún shell: ${ultimoError?.message}]\n"
            val sb = StringBuilder()
            if (bootstrap && shellUsado != bashPath) {
                if (shellUsado.contains("linker64")) {
                    sb.append("[aviso: Android bloquea ejecutar bash directo, usando linker del sistema]\n")
                } else {
                    sb.append("[aviso: bash no arrancó, usando sh de Android. Corre 'reparar']\n")
                }
            }
            val lector = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var line: String?
                    var total = 0
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: break
                        if (total + l.length > maxChars) {
                            sb.append("\n[salida recortada, era más larga]\n")
                            break
                        }
                        sb.append(l).append('\n')
                        total += l.length + 1
                    }
                    reader.close()
                } catch (e: Exception) {
                    // La lectura se corta si el proceso muere por timeout.
                }
            }
            lector.start()
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                lector.join(2000)
                sb.append("[tiempo agotado: $timeoutSeconds s, comando detenido]\n")
                return sb.toString()
            }
            lector.join(5000)
            if (sb.isEmpty()) {
                val code = try {
                    proc.exitValue()
                } catch (e: Exception) {
                    -1
                }
                if (code != 0) "[salió con código $code]\n" else ""
            } else {
                sb.toString()
            }
        } catch (e: Exception) {
            "[error: ${e.message}]\n"
        }
    }

    /** cd con carpeta persistente entre comandos. Sin argumento vuelve a casa. */
    private fun changeDir(arg: String) {
        val target: File = when {
            arg.isEmpty() || arg == "~" -> filesDir
            arg == ".." -> currentDir.parentFile ?: currentDir
            arg == "." -> currentDir
            arg.startsWith("/") -> File(arg)
            arg.startsWith("~/") -> File(filesDir, arg.removePrefix("~/"))
            else -> File(currentDir, arg)
        }
        val resolved = try {
            target.canonicalFile
        } catch (e: Exception) {
            target
        }
        when {
            !resolved.exists() -> appendOutput("cd: no existe: $arg\n")
            !resolved.isDirectory -> appendOutput("cd: no es carpeta: $arg\n")
            !resolved.canRead() -> appendOutput("cd: sin permiso para leer: $arg\n")
            else -> currentDir = resolved
        }
    }

    private fun historyStep(step: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + step).coerceIn(0, history.size)
        if (historyIndex in history.indices) {
            etInput.setText(history[historyIndex])
            etInput.setSelection(etInput.text.length)
        } else {
            etInput.setText("")
        }
    }

    private fun insertText(s: String) {
        val start = etInput.selectionStart.coerceAtLeast(0)
        val end = etInput.selectionEnd.coerceAtLeast(start)
        etInput.text.replace(start, end, s)
    }

    private fun appendOutput(text: String) {
        terminalText.append(text)
        if (terminalText.length > MAX_OUTPUT_CHARS) {
            terminalText.delete(0, terminalText.length - MAX_OUTPUT_CHARS)
        }
        tvOutput.text = terminalText.toString()
    }

    private fun scrollToBottom() {
        scrollTerminal.post { scrollTerminal.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

private const val WELCOME = """
Terminal Ligera 2.3 — por Nexos
Escribe 'help' para ver los comandos.
Sin bootstrap, corre solo los comandos de Android.
Con 'instalar' baja el bootstrap de Termux
(bash, curl, pkg, apt) y ya se le puede
instalar git y probar Muse Code.
Pruébalo: estado  •  help  •  instalar

"""

private const val HELP = """
Comandos de Android (sin bootstrap):
  pwd, ls, ls -la, echo, date, whoami, id
  uname -a, cat <archivo>, env, getprop
  mkdir, touch, cp, mv, rm (con cuidado)

Comandos propios de esta app:
  help / ayuda   — esta lista
  clear          — limpia la pantalla
  cd <carpeta>   — cambia de carpeta (cd sin nada = casa)
  pwd            — muestra la carpeta actual
  history        — comandos que ya escribiste
  estado         — dice si el bootstrap y muse están instalados
  acerca         — qué es esta terminal
  salir          — te dice cómo salir

NUEVO en 2.3 — firma estable, se actualiza solo:
  El APK ya se instala con el instalador normal de
  Android y se actualiza sin desinstalar, porque
  todos los builds usan la misma llave. El bootstrap
  ya instalado se conserva al actualizar.

NUEVO en 2.2 — enlaces y prueba por linker:
  reparar        — ahora sí crea los enlaces (el archivo
                   real vive en la misma carpeta del enlace)
                   y prueba bash por el linker, reportando
                   si corre. Si bash --version te decía
                   'libreadline.so.8 not found', era por eso.

NUEVO en 2.1 — arregla el error 13:
  reparar        — vuelve a poner permisos (0755) y
                   enlaces del bootstrap sin bajarlo otra
                   vez. Úsalo si 'bash --version' te dice
                   Permission denied.

NUEVO en 2.0 — instalar lo que falta:
  instalar       — baja y descomprime el bootstrap oficial
                   de Termux (~32 MB, SHA-256 verificado).
                   Tarda minutos, no cierres la app.
  pkg-instalar git — instala un paquete con pkg (tras 'instalar').
                   Ejemplo: pkg-instalar git
  muse-instalar  — instala Muse Code con su instalador oficial
                   (curl | bash, ~100 MB). Después prueba
                   'muse --version'.

Después de 'instalar', estos ya corren:
  bash, curl, pkg, apt, dpkg, y lo que instales con pkg.

Lo que probablemente SIGUE sin correr:
  muse login y 'muse' a secas: son interactivos, necesitan
  una terminal con PTY y leen desde /dev/tty, y esta app
  corre cada comando de una sola vez, con límite de 30 s.
  Si tienes META_API_KEY, prueba 'muse exec' en vez de 'muse'.
  nano, vi y ssh interactivo tampoco, por lo mismo.

Atajos de arriba: ls, pwd, date, help.
Flechas ↑ ↓: recorren tu historial.
"""

private const val ABOUT = """
Terminal Ligera 2.3
Paquete: com.nexos.terminal
Hecha con Kotlin, sin librerías externas.
Sin bootstrap: corre /system/bin/sh como usuario
de la app, sin root. Con bootstrap: corre el bash
de Termux en filesDir/usr, también sin root.
Con internet solo para bajar el bootstrap y
los paquetes que tú pidas. Sin cuentas.
"""
