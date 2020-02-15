package net.mamoe.mirai

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.Terminal
import com.googlecode.lanterna.terminal.TerminalResizeListener
import com.googlecode.lanterna.terminal.swing.SwingTerminal
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame
import net.mamoe.mirai.MiraiConsoleUI.LoggerDrawer.drawLog
import net.mamoe.mirai.MiraiConsoleUI.LoggerDrawer.redrawLogs
import net.mamoe.mirai.utils.currentTimeSeconds
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.ceil


object MiraiConsoleUI {

    val log = mutableMapOf<Long, LimitLinkedQueue<String>>().also { it[0L] = LimitLinkedQueue(50) }


    private val screens = mutableListOf(0L)
    private var currentScreenId = 0

    fun addBotScreen(uin: Long) {
        screens.add(uin)
        log[uin] = LimitLinkedQueue()
    }


    lateinit var terminal: Terminal
    lateinit var textGraphics: TextGraphics

    var hasStart = false
    private lateinit var internalPrinter: PrintStream
    fun start() {
        if (hasStart) {
            return
        }

        internalPrinter = System.out


        hasStart = true
        val defaultTerminalFactory = DefaultTerminalFactory(internalPrinter, System.`in`, Charset.defaultCharset())
        try {
            terminal = defaultTerminalFactory.createTerminal()
            terminal.enterPrivateMode()
            terminal.clearScreen()
            terminal.setCursorVisible(false)
        } catch (e: Exception) {
            try {
                terminal = SwingTerminalFrame("Mirai Console")
                terminal.enterPrivateMode()
                terminal.clearScreen()
                terminal.setCursorVisible(false)
            } catch (e: Exception) {
                error("can not create terminal")
            }
        }
        textGraphics = terminal.newTextGraphics()

        terminal.addResizeListener(TerminalResizeListener { terminal1: Terminal, newSize: TerminalSize ->
            terminal.clearScreen()
            inited = false
            update()
            redrawCommand()
            redrawLogs(log[screens[currentScreenId]]!!)
        })

        if (terminal !is SwingTerminalFrame) {
            System.setOut(PrintStream(object : OutputStream() {
                var builder = java.lang.StringBuilder()
                override fun write(b: Int) {
                    with(b.toChar()) {
                        if (this == '\n') {
                            pushLog(0, builder.toString())
                            builder = java.lang.StringBuilder()
                        } else {
                            builder.append(this)
                        }
                    }
                }
            }))
        }

        System.setErr(System.out)

        update()

        val charList = listOf(',', '.', '/', '@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '=', '+', '!', ' ')
        thread {
            while (true) {
                var keyStroke: KeyStroke = terminal.readInput()

                when (keyStroke.keyType) {
                    KeyType.ArrowLeft -> {
                        currentScreenId = getLeftScreenId()
                        update()
                    }
                    KeyType.ArrowRight -> {
                        currentScreenId = getRightScreenId()
                        update()
                    }
                    KeyType.Enter -> {
                        emptyCommand()
                    }
                    else -> {
                        if (keyStroke.character != null) {
                            if (keyStroke.character.toInt() == 8) {
                                deleteCommandChar()
                            }
                            if (keyStroke.character.isLetterOrDigit() || charList.contains(keyStroke.character)) {
                                addCommandChar(keyStroke.character)
                            }
                        }
                    }
                }
            }
        }
    }

    fun getLeftScreenId(): Int {
        var newId = currentScreenId - 1
        if (newId < 0) {
            newId = screens.size - 1
        }
        return newId
    }

    fun getRightScreenId(): Int {
        var newId = 1 + currentScreenId
        if (newId >= screens.size) {
            newId = 0
        }
        return newId
    }

    fun getScreenName(id: Int): String {
        return when (screens[id]) {
            0L -> {
                "Console Screen"
            }
            else -> {
                "Bot: ${screens[id]}"
            }
        }
    }


    var inited = false
    fun clearRows(row: Int) {
        textGraphics.putString(0, row, " ".repeat(terminal.terminalSize.columns))
    }

    fun drawFrame(
        title: String
    ) {
        val width = terminal.terminalSize.columns
        val height = terminal.terminalSize.rows
        terminal.setBackgroundColor(TextColor.ANSI.DEFAULT)
        if (!inited) {
            val mainTitle = "Mirai Console v0.01 Core v0.15"
            textGraphics.foregroundColor = TextColor.ANSI.WHITE
            textGraphics.backgroundColor = TextColor.ANSI.GREEN
            textGraphics.putString((width - mainTitle.length) / 2, 1, mainTitle, SGR.BOLD)
            textGraphics.foregroundColor = TextColor.ANSI.DEFAULT
            textGraphics.backgroundColor = TextColor.ANSI.DEFAULT
            textGraphics.putString(2, 3, "-".repeat(width - 4))
            textGraphics.putString(2, 5, "-".repeat(width - 4))
            textGraphics.putString(2, height - 4, "-".repeat(width - 4))
            textGraphics.putString(2, height - 3, "|>>>")
            textGraphics.putString(width - 3, height - 3, "|")
            textGraphics.putString(2, height - 2, "-".repeat(width - 4))
            inited = true
        }
        textGraphics.foregroundColor = TextColor.ANSI.DEFAULT
        textGraphics.backgroundColor = TextColor.ANSI.DEFAULT
        val leftName = getScreenName(getLeftScreenId())
        clearRows(2)
        textGraphics.putString((width - title.length) / 2 - "$leftName << ".length, 2, "$leftName << ")
        textGraphics.foregroundColor = TextColor.ANSI.WHITE
        textGraphics.backgroundColor = TextColor.ANSI.YELLOW
        textGraphics.putString((width - title.length) / 2, 2, title, SGR.BOLD)
        textGraphics.foregroundColor = TextColor.ANSI.DEFAULT
        textGraphics.backgroundColor = TextColor.ANSI.DEFAULT
        val rightName = getScreenName(getRightScreenId())
        textGraphics.putString((width + title.length) / 2 + 1, 2, ">> $rightName")
    }

    fun drawMainFrame(
        onlineBotCount: Number
    ) {
        drawFrame("Console Screen")
        val width = terminal.terminalSize.columns
        textGraphics.foregroundColor = TextColor.ANSI.DEFAULT
        textGraphics.backgroundColor = TextColor.ANSI.DEFAULT
        clearRows(4)
        textGraphics.putString(2, 4, "|Online Bots: $onlineBotCount")
        textGraphics.putString(
            width - 2 - "Powered By Mamoe Technologies|".length,
            4,
            "Powered By Mamoe Technologies|"
        )
    }

    fun drawBotFrame(
        qq: Long,
        adminCount: Number
    ) {
        drawFrame("Bot: $qq")
        val width = terminal.terminalSize.columns
        textGraphics.foregroundColor = TextColor.ANSI.DEFAULT
        textGraphics.backgroundColor = TextColor.ANSI.DEFAULT
        clearRows(4)
        textGraphics.putString(2, 4, "|Admins: $adminCount")
        textGraphics.putString(width - 2 - "Add admins via commands|".length, 4, "Add admins via commands|")
    }


    object LoggerDrawer {
        var currentHeight = 6

        fun drawLog(string: String, flush: Boolean = true) {
            val maxHeight = terminal.terminalSize.rows - 6
            val heightNeed = (string.length / (terminal.terminalSize.columns - 6)) + 1
            if (currentHeight + heightNeed > maxHeight) {
                cleanPage()
            }
            textGraphics.foregroundColor = TextColor.ANSI.GREEN
            textGraphics.backgroundColor = TextColor.ANSI.DEFAULT
            val width = terminal.terminalSize.columns - 6
            var x = string
            while (true) {
                if (x == "") break
                val toWrite = if (x.length > width) {
                    x.substring(0, width).also {
                        x = x.substring(width)
                    }
                } else {
                    x.also {
                        x = ""
                    }
                }
                try {
                    textGraphics.putString(3, currentHeight, toWrite, SGR.ITALIC)
                } catch (ignored: Exception) {
                    //
                }
                ++currentHeight
            }
            if (flush && terminal is SwingTerminalFrame) {
                terminal.flush()
            }
        }


        fun cleanPage() {
            for (index in 6 until terminal.terminalSize.rows - 6) {
                clearRows(index)
            }
            currentHeight = 6
        }


        fun redrawLogs(toDraw: List<String>) {
            this.cleanPage()
            var logsToDraw = 0
            var vara = 0
            toDraw.reversed().forEach {
                val heightNeed = (it.length / (terminal.terminalSize.columns - 6)) + 1
                vara += heightNeed
                if (currentHeight + vara < terminal.terminalSize.rows - 6) {
                    logsToDraw++
                } else {
                    return
                }
            }
            for (index in (toDraw.size - logsToDraw) until toDraw.size - 1) {
                drawLog(toDraw[index], false)
            }
            if (terminal is SwingTerminalFrame) {
                terminal.flush()
            }
        }
    }

    fun pushLog(uin: Long, str: String) {
        log[uin]!!.push(str)
        if (uin == screens[currentScreenId]) {
            drawLog(str)
        }
    }


    var commandBuilder = StringBuilder()
    fun redrawCommand() {
        val height = terminal.terminalSize.rows
        val width = terminal.terminalSize.columns
        clearRows(height - 3)
        textGraphics.foregroundColor = TextColor.ANSI.DEFAULT
        textGraphics.putString(2, height - 3, "|>>>")
        textGraphics.putString(width - 3, height - 3, "|")
        textGraphics.foregroundColor = TextColor.ANSI.BLUE
        textGraphics.putString(7, height - 3, commandBuilder.toString())
        if (terminal is SwingTerminalFrame) {
            terminal.flush()
        }
    }

    fun addCommandChar(
        c: Char
    ) {
        if (commandBuilder.isEmpty() && c != '/') {
            addCommandChar('/')
        }
        textGraphics.foregroundColor = TextColor.ANSI.BLUE
        val height = terminal.terminalSize.rows
        commandBuilder.append(c)
        if (terminal is SwingTerminalFrame) {
            redrawCommand()
        } else {
            textGraphics.putString(6 + commandBuilder.length, height - 3, c.toString())
        }
    }

    fun deleteCommandChar() {
        if (!commandBuilder.isEmpty()) {
            commandBuilder = StringBuilder(commandBuilder.toString().substring(0, commandBuilder.length - 1))
        }
        val height = terminal.terminalSize.rows
        if (terminal is SwingTerminalFrame) {
            redrawCommand()
        } else {
            textGraphics.putString(7 + commandBuilder.length, height - 3, " ")
        }
    }


    fun emptyCommand() {
        commandBuilder = StringBuilder()
        redrawCommand()
        if (terminal is SwingTerminal) {
            terminal.flush()
        }
    }

    fun update() {
        when (screens[currentScreenId]) {
            0L -> {
                drawMainFrame(screens.size - 1)
            }
            else -> {
                drawBotFrame(screens[currentScreenId], 0)
            }
        }
        redrawLogs(log[screens[currentScreenId]]!!)
    }
}

class LimitLinkedQueue<T>(
    val limit: Int = 50
) : LinkedList<T>(), List<T> {
    override fun push(e: T) {
        if (size >= limit) {
            pollLast()
        }
        super.push(e)
    }
}