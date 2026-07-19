package com.amneziaguard.core.ui

import java.util.Locale

/** Human-readable byte count, e.g. 12.3 MB. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

fun formatRate(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"
