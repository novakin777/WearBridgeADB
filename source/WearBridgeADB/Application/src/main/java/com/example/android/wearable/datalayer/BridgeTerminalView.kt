package dev.wearbrdgeadb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.max

/** WearBridge's small VT screen. It consumes bytes from the existing remote PTY directly. */
internal class BridgeTerminalView(
    context: Context,
    private val send: (ByteArray) -> Unit,
    private val resize: (Int, Int) -> Unit,
    fontSizeSp: Float = 15f
) : View(context) {
    private data class Cell(var char: Char = ' ', var fg: Int = Color.rgb(222, 232, 235), var bg: Int = Color.rgb(13, 22, 28))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = fontSizeSp * resources.displayMetrics.scaledDensity }
    private val palette = intArrayOf(0xff102028.toInt(), 0xffef7773.toInt(), 0xff89d68f.toInt(), 0xfff1d184.toInt(), 0xff86b5ee.toInt(), 0xffca9ee6.toInt(), 0xff82d3d3.toInt(), 0xffdce8eb.toInt())
    private val defaultFg = palette[7]
    private val defaultBg = 0xff0d161c.toInt()
    private var fg = defaultFg
    private var bg = defaultBg
    private var cols = 80
    private var rows = 24
    private var x = 0
    private var y = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var savedX = 0
    private var savedY = 0
    private var scrollOffset = 0
    private var alternate = false
    private var normalScreen: MutableList<MutableList<Cell>>? = null
    private var screen = newScreen()
    private val history = ArrayDeque<MutableList<Cell>>()
    private var state = 0
    private val escape = StringBuilder()
    private var utfNeeded = 0
    private var utfValue = 0
    private var touchY = 0f
    private var touchX = 0f
    private var selecting = false
    private var selectionStart: Pair<Int, Int>? = null
    private var selectionEnd: Pair<Int, Int>? = null
    private val beginSelection = Runnable { selecting = true; val point = touchCell(touchX, touchY); selectionStart = point; selectionEnd = point; invalidate() }
    private fun touchCell(px: Float, py: Float): Pair<Int, Int> {
        val hiddenRows = (rows - max(1, (height / ch).toInt())).coerceAtLeast(0)
        val line = ((if(alternate) 0 else history.size) + hiddenRows + (py / ch).toInt() - scrollOffset).coerceIn(0, history.size + rows - 1)
        return Pair(line, (px / cw).toInt().coerceIn(0, cols - 1))
    }
    private fun cellOrder(a: Pair<Int, Int>, b: Pair<Int, Int>): Int = if(a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)
    fun selectedText(): String? {
        val a = selectionStart ?: return null
        val b = selectionEnd ?: return null
        val first = if (cellOrder(a, b) <= 0) a else b
        val last = if (cellOrder(a, b) >= 0) a else b
        val lines = history.toList() + screen
        return (first.first..last.first).joinToString("\n") { index ->
            val line = lines.getOrNull(index) ?: return@joinToString ""
            val from = if (index == first.first) first.second else 0
            val to = if (index == last.first) last.second else cols - 1
            line.subList(from.coerceAtMost(line.size), (to + 1).coerceAtMost(line.size)).joinToString("") { it.char.toString() }.trimEnd()
        }
    }
    private var lastSize = Pair(0, 0)
    private fun newScreen() = MutableList(rows) { newRow() }
    private fun newRow() = MutableList(cols) { Cell(bg = bg) }
    init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(defaultBg) }
    private val cw get() = max(1f, paint.measureText("M"))
    private val ch get() = max(1f, paint.fontMetrics.descent - paint.fontMetrics.ascent + 2f * resources.displayMetrics.density)
    fun setFontSizeSp(size: Float) {
        paint.textSize = size * resources.displayMetrics.scaledDensity
        resizeGrid(width, height)
        invalidate()
    }
    fun clearScreen() {
        if (!alternate) history.clear()
        screen = newScreen()
        x = 0; y = 0; scrollOffset = 0
        selectionStart = null; selectionEnd = null
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeGrid(w, h)
    }
    private fun resizeGrid(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val newCols = (w / cw).toInt().coerceIn(20, 500)
        val newRows = (h / ch).toInt().coerceIn(2, 300)
        if (newCols != cols || newRows != rows) {
            val oldRows = rows
            cols = newCols; rows = newRows
            screen.forEach { row -> while (row.size < cols) row.add(Cell(bg = bg)); while (row.size > cols) row.removeAt(row.lastIndex) }
            if (rows < oldRows) {
                // Keep the active command line visible when an IME or rotation shrinks the viewport.
                val removeFromTop = (y - rows + 1).coerceIn(0, oldRows - rows)
                repeat(removeFromTop) { if (!alternate) pushHistory(screen.removeAt(0)) else screen.removeAt(0) }
                repeat(oldRows - rows - removeFromTop) { screen.removeAt(screen.lastIndex) }
                y -= removeFromTop
            } else if (rows > oldRows) {
                val restoreAbove = if (alternate) 0 else minOf(rows - oldRows, history.size)
                repeat(restoreAbove) { screen.add(0, history.removeLast()); y++ }
                while (screen.size < rows) screen.add(newRow())
            }
            scrollTop = 0; scrollBottom = rows - 1
            x = x.coerceIn(0, cols - 1); y = y.coerceIn(0, rows - 1); invalidate()
        }
        if (lastSize != Pair(rows, cols)) { lastSize = Pair(rows, cols); resize(rows, cols) }
    }
    private fun pushHistory(row: MutableList<Cell>) { history.addLast(row); while (history.size > 3000) history.removeFirst() }
    private fun scrollUp(){
        val removed = screen.removeAt(scrollTop)
        if (!alternate && scrollTop == 0 && scrollBottom == rows - 1) pushHistory(removed)
        screen.add(scrollBottom, newRow())
    }
    private fun scrollDown(){ screen.removeAt(scrollBottom); screen.add(scrollTop, newRow()) }
    private fun lineFeed() { if (y == scrollBottom) scrollUp() else y = (y + 1).coerceAtMost(rows - 1); scrollOffset = 0 }
    private fun put(c: Char) { if (x >= cols) { x = 0; lineFeed() }; screen[y][x] = Cell(c, fg, bg); x++ }
    fun feed(bytes: ByteArray, count: Int = bytes.size) {
        for (i in 0 until count) {
            val b = bytes[i].toInt() and 255
            if (utfNeeded > 0) {
                if (b and 0xc0 == 0x80) { utfValue = (utfValue shl 6) or (b and 63); utfNeeded--; if (utfNeeded == 0) Character.toChars(utfValue).forEach { put(it) }; continue }
                utfNeeded = 0; put('\uFFFD')
            }
            if (state == 1) { if (b == 91) { state = 2; escape.clear() } else if (b == 93) { state = 3 } else { state = 0; when (b) { 55 -> { savedX = x; savedY = y }; 56 -> { x = savedX; y = savedY }; 68 -> lineFeed(); 69 -> { x = 0; lineFeed() }; 77 -> { if(y == scrollTop) scrollDown() else y = (y - 1).coerceAtLeast(0) } } }; continue }
            if (state == 2) { if (b in 0x40..0x7e) { csi(b.toChar(), escape.toString()); state = 0 } else if (escape.length < 64) escape.append(b.toChar()) else state = 0; continue }
            if (state == 3) { if (b == 7) state = 0 else if (b == 27) state = 4; continue }
            if (state == 4) { state = if (b == 92) 0 else 3; continue }
            when (b) {
                27 -> state = 1
                13 -> x = 0
                10, 11, 12 -> lineFeed()
                8 -> x = (x - 1).coerceAtLeast(0)
                9 -> x = ((x / 8 + 1) * 8).coerceAtMost(cols - 1)
                in 32..126 -> put(b.toChar())
                in 0xc2..0xdf -> { utfNeeded = 1; utfValue = b and 31 }
                in 0xe0..0xef -> { utfNeeded = 2; utfValue = b and 15 }
                in 0xf0..0xf4 -> { utfNeeded = 3; utfValue = b and 7 }
            }
        }
        invalidate()
    }
    private fun csi(command: Char, sequence: String) {
        val privateMode = sequence.startsWith("?")
        val p = sequence.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(index: Int, fallback: Int = 1) = (p.getOrNull(index) ?: fallback).let { if (it == 0) fallback else it }
        when (command) {
            'A' -> y = (y - arg(0)).coerceAtLeast(0)
            'B' -> y = (y + arg(0)).coerceAtMost(rows - 1)
            'C' -> x = (x + arg(0)).coerceAtMost(cols - 1)
            'D' -> x = (x - arg(0)).coerceAtLeast(0)
            'G' -> x = (arg(0) - 1).coerceIn(0, cols - 1)
            'd' -> y = (arg(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { y = (arg(0) - 1).coerceIn(0, rows - 1); x = (arg(1) - 1).coerceIn(0, cols - 1) }
            'J' -> { val start = if (p[0] == 0) y else 0; val end = if (p[0] == 1) y else rows - 1; for (r in start..end) for (c in 0 until cols) if (p[0] == 2 || r != y || (p[0] == 0 && c >= x) || (p[0] == 1 && c <= x)) screen[r][c] = Cell(bg = bg) }
            'K' -> { for (c in 0 until cols) if (p[0] == 2 || (p[0] == 0 && c >= x) || (p[0] == 1 && c <= x)) screen[y][c] = Cell(bg = bg) }
            'r' -> { scrollTop = (arg(0) - 1).coerceIn(0, rows - 1); scrollBottom = (arg(1, rows) - 1).coerceIn(scrollTop, rows - 1); x = 0; y = 0 }
            'L' -> repeat(arg(0).coerceAtMost(rows)) { if(y in scrollTop..scrollBottom) { screen.removeAt(scrollBottom); screen.add(y, newRow()) } }
            'M' -> repeat(arg(0).coerceAtMost(rows)) { if(y in scrollTop..scrollBottom) { screen.removeAt(y); screen.add(scrollBottom, newRow()) } }
            'S' -> repeat(arg(0).coerceAtMost(rows)) { scrollUp() }
            'T' -> repeat(arg(0).coerceAtMost(rows)) { scrollDown() }
            '@' -> repeat(arg(0).coerceAtMost(cols)) { screen[y].add(x.coerceAtMost(cols - 1), Cell(bg = bg)); screen[y].removeAt(cols) }
            'P' -> repeat(arg(0).coerceAtMost(cols)) { screen[y].removeAt(x.coerceAtMost(cols - 1)); screen[y].add(Cell(bg = bg)) }
            'X' -> for(c in x until (x + arg(0)).coerceAtMost(cols)) screen[y][c] = Cell(bg = bg)
            'm' -> {
                var i = 0
                while(i < p.size){
                    val code = p[i]
                    when(code){
                        0 -> { fg = defaultFg; bg = defaultBg }; 39 -> fg = defaultFg; 49 -> bg = defaultBg
                        in 30..37 -> fg = palette[code - 30]; in 40..47 -> bg = palette[code - 40]
                        in 90..97 -> fg = palette[code - 90]; in 100..107 -> bg = palette[code - 100]
                        38, 48 -> {
                            val color = when(p.getOrNull(i + 1)){
                                5 -> { i += 2; indexedColor(p.getOrNull(i) ?: 7) }
                                2 -> { val red = (p.getOrNull(i+2) ?: 0).coerceIn(0,255); val green = (p.getOrNull(i+3) ?: 0).coerceIn(0,255); val blue = (p.getOrNull(i+4) ?: 0).coerceIn(0,255); i += 4; Color.rgb(red,green,blue) }
                                else -> null
                            }
                            if(color != null) { if(code == 38) fg = color else bg = color }
                        }
                    }
                    i++
                }
            }
            's' -> { savedX = x; savedY = y }
            'u' -> { x = savedX.coerceIn(0, cols - 1); y = savedY.coerceIn(0, rows - 1) }
            'h', 'l' -> if (privateMode && p.any { it == 1049 || it == 47 }) { val entering = command == 'h'; if (entering && !alternate) { normalScreen = screen; alternate = true; screen = newScreen(); x = 0; y = 0 } else if (!entering && alternate) { screen = normalScreen ?: newScreen(); normalScreen = null; alternate = false; screen.forEach { row -> while(row.size < cols) row.add(Cell(bg=bg)); while(row.size > cols) row.removeAt(row.lastIndex) }; while(screen.size < rows)screen.add(newRow()); while(screen.size > rows)pushHistory(screen.removeAt(0)); x = 0; y = 0 } }
        }
    }
    private fun indexedColor(index:Int):Int {
        val n=index.coerceIn(0,255)
        if(n < 8)return palette[n]
        if(n < 16)return palette[n - 8]
        if(n < 232){
            val v=n-16
            fun part(k:Int)=if(k==0)0 else 55+40*k
            return Color.rgb(part(v/36),part(v/6%6),part(v%6))
        }
        val gray=8+(n-232)*10;return Color.rgb(gray,gray,gray)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visibleHistory = if (alternate) emptyList() else history.toList()
        val all = visibleHistory + screen
        val start = (all.size - rows - scrollOffset).coerceAtLeast(0)
        val hiddenRows = if (scrollOffset == 0) (rows - max(1, (height / ch).toInt())).coerceAtLeast(0) else 0
        val baseline = -paint.fontMetrics.ascent
        for (r in 0 until rows) {
            val row = all.getOrNull(start + hiddenRows + r) ?: continue
            for (c in row.indices) {
                val cell = row[c]; val px = c * cw; val py = r * ch
                val here = Pair(start + r, c)
                val selected = selectionStart?.let { a -> selectionEnd?.let { b -> cellOrder(here, a) >= 0 && cellOrder(here, b) <= 0 || cellOrder(here, b) >= 0 && cellOrder(here, a) <= 0 } } == true
                if (selected || cell.bg != defaultBg) { paint.color = if (selected) 0xff35566c.toInt() else cell.bg; canvas.drawRect(px, py, px + cw, py + ch, paint) }
                if (cell.char != ' ') { paint.color = cell.fg; canvas.drawText(cell.char.toString(), px, py + baseline, paint) }
            }
        }
        if (scrollOffset == 0 && hasFocus()) { paint.color = 0xff86b5ee.toInt(); val px = x.coerceAtMost(cols - 1) * cw; val py = (y - hiddenRows) * ch; if (py >= 0f) canvas.drawRect(px, py + ch - 3f, px + cw, py + ch, paint) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchY = event.y; touchX = event.x; selecting = false; selectionStart = null; selectionEnd = null; requestFocus(); postDelayed(beginSelection, android.view.ViewConfiguration.getLongPressTimeout().toLong()); return true }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) { selectionEnd = touchCell(event.x, event.y); invalidate() }
                else { val delta = ((event.y - touchY) / ch).toInt(); if (delta != 0) { removeCallbacks(beginSelection); scrollOffset = (scrollOffset + delta).coerceIn(0, history.size); touchY = event.y; invalidate() } }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { removeCallbacks(beginSelection); if (!selecting) performClick(); selecting = false; return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { if (!text.isNullOrEmpty()) send(text.toString().replace("\n", "\r").toByteArray(Charsets.UTF_8)); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { repeat(beforeLength.coerceIn(0, 100)) { send(byteArrayOf(127)) }; return true }
            override fun sendKeyEvent(event: KeyEvent): Boolean = this@BridgeTerminalView.dispatchKeyEvent(event)
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> byteArrayOf(13)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(127)
            KeyEvent.KEYCODE_TAB -> byteArrayOf(9)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(27)
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A".toByteArray()
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B".toByteArray()
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C".toByteArray()
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D".toByteArray()
            else -> if (event.isCtrlPressed && event.unicodeChar in 64..95) byteArrayOf((event.unicodeChar and 31).toByte()) else null
        }
        if (bytes != null) { send(bytes); return true }
        if (event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) { send(byteArrayOf((keyCode - KeyEvent.KEYCODE_A + 1).toByte())); return true }
        if (event.unicodeChar > 0) { send(String(Character.toChars(event.unicodeChar)).toByteArray(Charsets.UTF_8)); return true }
        return super.onKeyDown(keyCode, event)
    }
}
