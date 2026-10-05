package com.setbd.vibeshare.core.log

/**
 * Tiny pluggable logger. Android layers inject a Logcat-backed sink;
 * JVM tests keep the default console sink or silence it.
 * Never logs tokens, keys or file contents.
 */
object VibeLog {

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, throwable: Throwable?)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: Sink = Sink { level, tag, message, throwable ->
        val t = throwable?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        println("[${level.name.first()}] $tag: $message$t")
    }

    @Volatile
    var minLevel: Level = Level.INFO

    fun d(tag: String, message: String) = dispatch(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = dispatch(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = dispatch(Level.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = dispatch(Level.ERROR, tag, message, throwable)

    private fun dispatch(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        runCatching { sink.log(level, tag, message, throwable) }
    }
}
