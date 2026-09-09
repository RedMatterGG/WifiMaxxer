package com.wifimaxxer

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object AppLog {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX")

    fun file(context: Context): File = File(context.filesDir, "logs/wifi-maxxer.log").also {
        val directory = checkNotNull(it.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create the log directory." }
        if (!it.exists()) check(it.createNewFile()) { "Could not create the log file." }
    }

    @Synchronized
    fun read(context: Context): List<String> = file(context).useLines { it.toList() }

    @Synchronized
    fun append(context: Context, message: String): List<String> {
        val lines = formatLogLines(message, OffsetDateTime.now().format(timestampFormat))
        file(context).appendText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        return lines
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).writeText("", Charsets.UTF_8)
    }
}

internal fun formatLogLines(message: String, timestamp: String): List<String> =
    message.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { "[$timestamp] $it" }
