package com.nuvio.app.desktop.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * Minimal JNA bindings for libmpv (client API + software render API).
 * Constants follow mpv's client.h and render.h.
 */
@Suppress("FunctionName", "LocalVariableName")
internal interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): String

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: RenderUpdateCallback?, callbackCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    fun interface RenderUpdateCallback : Callback {
        fun invoke(ctx: Pointer?)
    }

    companion object {
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5

        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        const val END_FILE_REASON_ERROR = 4

        const val RENDER_PARAM_INVALID = 0
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20
        const val RENDER_UPDATE_FRAME = 1L

        @Volatile
        private var loaded: LibMpv? = null
        @Volatile
        var loadError: String? = null
            private set

        /** Loads the libmpv-2.dll bundled with the app (or found on PATH). */
        fun load(): LibMpv? {
            loaded?.let { return it }
            synchronized(this) {
                loaded?.let { return it }
                val candidates = buildList {
                    System.getProperty("compose.application.resources.dir")?.let { add(File(it, "libmpv-2.dll")) }
                    System.getProperty("kk.libmpv.path")?.let { add(File(it)) }
                    add(File(System.getProperty("user.dir"), "libmpv-2.dll"))
                }
                val errors = mutableListOf<String>()
                for (file in candidates) {
                    if (!file.isFile) continue
                    runCatching { Native.load(file.absolutePath, LibMpv::class.java) }
                        .onSuccess { loaded = it; return it }
                        .onFailure { errors += "${file.path}: ${it.message}" }
                }
                for (name in listOf("libmpv-2", "mpv-2", "mpv")) {
                    runCatching { Native.load(name, LibMpv::class.java) }
                        .onSuccess { loaded = it; return it }
                        .onFailure { errors += "$name: ${it.message}" }
                }
                loadError = errors.joinToString("; ").ifBlank { "libmpv not found" }
                return null
            }
        }
    }
}
