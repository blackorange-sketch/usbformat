package dev.usbformat

import dev.usbformat.fmt.DriveInfo

sealed interface InfoState {
    data object None : InfoState
    data object Loading : InfoState
    data class Ready(val info: DriveInfo) : InfoState
    data class Failed(val message: String) : InfoState
}

fun formatSize(bytes: Long): String {
    if (bytes < 1L shl 30) return "%.0f MiB".format(bytes / 1048576.0)
    return "%.2f GiB (%.2f GB)".format(bytes / 1073741824.0, bytes / 1e9)
}
