package dev.usbformat.log

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log that is shown on screen and can be copied, so problems can be reported without adb. */
object AppLog {
    private const val MAX_LINES = 3000
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Bumped on every change; the UI re-reads the log when it changes. */
    val version = MutableStateFlow(0)

    @Synchronized
    fun log(message: String) {
        lines.addLast("${clock.format(Date())} $message")
        if (lines.size > MAX_LINES) lines.removeFirst()
        version.value = version.value + 1
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun tail(count: Int): String = lines.toList().takeLast(count).joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        version.value = version.value + 1
    }
}
