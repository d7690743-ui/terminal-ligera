package com.nexos.terminal

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Terminal Ligera 1.0 — terminal propia, sin Termux y sin paquetes.
 *
 * Cada comando se corre con /system/bin/sh en un hilo aparte y la
 * salida se muestra en la pantalla. No hay apt/pkg, ni Python, ni
 * Git, ni comandos interactivos (nano, vi, ssh, python sin args).
 * El shell corre como usuario de la app, sin root.
 *
 * Comandos propios (no pasan por el shell):
 *   help / ayuda, clear / limpiar, cd, pwd, history, acerca, salir
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
        private const val MAX_OUTPUT_CHARS = 60000
        private const val MAX_CMD_OUTPUT = 15000
        private const val TIMEOUT_SECONDS = 10L
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

        // Historial: no repetir el mismo comando seguido.
        if (history.isEmpty() || history.last() != cmd) history.add(cmd)
        historyIndex = history.size

        when (val first = cmd.split(" ").first().lowercase()) {
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
            else -> {
                // Sigue al shell. El primer token no es un comando propio.
                @Suppress("UNUSED_VARIABLE")
                val ignored = first
            }
        }

        appendOutput("$ $cmd\n")
        scrollToBottom()

        Thread {
            val out = runShell(cmd)
            runOnUiThread {
                if (out.isNotEmpty()) appendOutput(out)
                scrollToBottom()
            }
        }.start()
    }

    /** Corre el comando con /system/bin/sh, con timeout y salida recortada. */
    private fun runShell(cmd: String): String {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.directory(currentDir)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "[tiempo agotado: $TIMEOUT_SECONDS s, comando detenido]\n"
            }
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            var total = 0
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: break
                if (total + l.length > MAX_CMD_OUTPUT) {
                    sb.append("\n[salida recortada, era más larga]\n")
                    break
                }
                sb.append(l).append('\n')
                total += l.length + 1
            }
            reader.close()
            if (sb.isEmpty()) {
                val code = process.exitValue()
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
        // No dejar crecer la pantalla sin límite.
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
Terminal Ligera 1.0 — por Nexos
Escribe 'help' para ver los comandos.
Ojo: es ligera, no es Termux. No tiene pkg, apt,
Python ni Git. Corre los comandos de Android.
Tu carpeta de inicio es la de la app.
Pruébalo: pwd  •  ls -la  •  date  •  echo hola

"""

private const val HELP = """
Comandos que sí corren (del shell de Android):
  pwd, ls, ls -la, echo, date, whoami, id
  uname -a, cat <archivo>, env, getprop
  mkdir, touch, cp, mv, rm (con cuidado)

Comandos propios de esta app:
  help / ayuda   — esta lista
  clear          — limpia la pantalla
  cd <carpeta>   — cambia de carpeta (cd sin nada = casa)
  pwd            — muestra la carpeta actual
  history        — comandos que ya escribiste
  acerca         — qué es esta terminal
  salir          — te dice cómo salir

Lo que NO corre aquí:
  pkg, apt, python, pip, git, nano, vi, ssh
  ni nada interactivo. Para eso sigue Termux.

Atajos de arriba: ls, pwd, date, help.
Flechas ↑ ↓: recorren tu historial.
"""

private const val ABOUT = """
Terminal Ligera 1.0
Paquete: com.nexos.terminal
Hecha con Kotlin, sin librerías externas.
Corre /system/bin/sh como usuario de la app,
sin root. Sin internet, sin cuentas.
"""
