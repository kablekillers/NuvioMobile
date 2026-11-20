package com.nuvio.app.core.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows replacement for iOS's NSUserDefaults: a small key/value store saved
 * as JSON in %APPDATA%\KableKillers\settings.json. The method names match the
 * iOS API so the shared iOS storage code could be reused almost unchanged.
 * Writes are batched on a background thread and replaced atomically.
 */
object DesktopDefaults {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val values: MutableMap<String, JsonElement> = load()
    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kk-settings-writer").apply { isDaemon = true }
    }
    private val writePending = AtomicBoolean(false)

    init {
        Runtime.getRuntime().addShutdownHook(Thread { flushNow() })
    }

    // ---- reads ----

    fun objectForKey(key: String): Any? = synchronized(lock) { values[key] }?.let(::toPlain)

    fun stringForKey(key: String): String? = when (val value = synchronized(lock) { values[key] }) {
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

    fun boolForKey(key: String): Boolean = when (val value = synchronized(lock) { values[key] }) {
        is JsonPrimitive -> value.booleanOrNull ?: value.longOrNull?.let { it != 0L } ?: (value.contentOrNull == "true")
        else -> false
    }

    fun integerForKey(key: String): Long = when (val value = synchronized(lock) { values[key] }) {
        is JsonPrimitive -> value.longOrNull ?: value.doubleOrNull?.toLong() ?: 0L
        else -> 0L
    }

    fun floatForKey(key: String): Float = when (val value = synchronized(lock) { values[key] }) {
        is JsonPrimitive -> value.doubleOrNull?.toFloat() ?: 0f
        else -> 0f
    }

    fun doubleForKey(key: String): Double = when (val value = synchronized(lock) { values[key] }) {
        is JsonPrimitive -> value.doubleOrNull ?: 0.0
        else -> 0.0
    }

    fun arrayForKey(key: String): List<Any?>? = when (val value = synchronized(lock) { values[key] }) {
        is JsonArray -> value.map(::toPlain)
        else -> null
    }

    fun keys(): Set<String> = synchronized(lock) { values.keys.toSet() }

    // ---- writes ----

    fun setObject(value: Any?, forKey: String) {
        if (value == null) {
            removeObjectForKey(forKey)
        } else {
            put(forKey, toJson(value))
        }
    }

    fun setBool(value: Boolean, forKey: String) = put(forKey, JsonPrimitive(value))
    fun setInteger(value: Long, forKey: String) = put(forKey, JsonPrimitive(value))
    fun setFloat(value: Float, forKey: String) = put(forKey, JsonPrimitive(value))
    fun setDouble(value: Double, forKey: String) = put(forKey, JsonPrimitive(value))

    fun removeObjectForKey(key: String) {
        val changed = synchronized(lock) { values.remove(key) != null }
        if (changed) scheduleWrite()
    }

    /** Removes every key for which [predicate] returns true. */
    fun removeKeys(predicate: (String) -> Boolean) {
        val changed = synchronized(lock) {
            val doomed = values.keys.filter(predicate)
            doomed.forEach(values::remove)
            doomed.isNotEmpty()
        }
        if (changed) scheduleWrite()
    }

    fun synchronize(): Boolean {
        flushNow()
        return true
    }

    // ---- internals ----

    private fun put(key: String, value: JsonElement) {
        synchronized(lock) { values[key] = value }
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (writePending.compareAndSet(false, true)) {
            writer.schedule({ flushNow() }, 300, TimeUnit.MILLISECONDS)
        }
    }

    private fun flushNow() {
        writePending.set(false)
        val snapshot = synchronized(lock) { JsonObject(values.toMap()) }
        runCatching {
            val file = settingsFile()
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(JsonObject.serializer(), snapshot))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun load(): MutableMap<String, JsonElement> = runCatching {
        val file = settingsFile()
        if (!file.exists()) return@runCatching mutableMapOf()
        json.decodeFromString(JsonObject.serializer(), file.readText()).toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun settingsFile(): File = File(DesktopPaths.appDataDir, "settings.json")

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value.toLong())
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Iterable<*> -> JsonArray(value.map(::toJson))
        is Array<*> -> JsonArray(value.map(::toJson))
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        else -> JsonPrimitive(value.toString())
    }

    private fun toPlain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> if (element.isString) element.content
            else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
        is JsonArray -> element.map(::toPlain)
        is JsonObject -> element.mapValues { toPlain(it.value) }
    }
}

/** Where Kable Killers keeps its files on Windows (and a sensible fallback elsewhere). */
object DesktopPaths {
    val appDataDir: File by lazy {
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".config")
        File(base, "KableKillers").apply { mkdirs() }
    }

    val cacheDir: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".cache")
        File(base, "KableKillers").apply { mkdirs() }
    }

    fun dir(name: String): File = File(appDataDir, name).apply { mkdirs() }
}
