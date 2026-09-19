package dev.usbformat

import dev.usbformat.fmt.Cancel
import dev.usbformat.fmt.Phase
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface FormatStatus {
    data object Idle : FormatStatus

    data class Running(
        val phase: Phase,
        val done: Long,
        val total: Long,
        val bytesPerSec: Long,
        val etaSec: Long,
    ) : FormatStatus

    data object Done : FormatStatus
    data object Cancelled : FormatStatus
    data class Failed(val message: String) : FormatStatus
}

/** Process-wide state shared by the service (writer) and the UI (reader). */
object FormatState {
    val status = MutableStateFlow<FormatStatus>(FormatStatus.Idle)

    @Volatile
    var cancel = Cancel()
}

fun Phase.labelRes(): Int = when (this) {
    Phase.ZEROING -> R.string.phase_zeroing
    Phase.WRITING_TEST -> R.string.phase_writing
    Phase.READING_TEST -> R.string.phase_reading
    Phase.PARTITIONING -> R.string.phase_partitioning
    Phase.FORMATTING -> R.string.phase_formatting
}
