package com.ahao.clicker

import android.os.SystemClock
import android.util.Log
import android.view.View

/** Opt in with log.tag.AhaoOverlayDiag=DEBUG; no polling or app-owned log files. */
internal object OverlayDiagnostics {
    const val TAG = "AhaoOverlayDiag"

    inline fun record(event: String, details: () -> String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) runCatching {
            Log.d(TAG, "event=$event elapsed=${SystemClock.elapsedRealtime()} ${details().take(3_000)}")
        }
    }

    fun caller(): String = Throwable().stackTrace.asSequence()
        .filter { it.className.startsWith("com.ahao.clicker.") && !it.className.endsWith("OverlayDiagnostics") }
        .take(8).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    fun watch(view: View, name: String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = record("view_attached") { state(v, name) }
            override fun onViewDetachedFromWindow(v: View) = record("view_detached") { state(v, name) }
        })
    }

    fun state(view: View, name: String): String = "view=$name id=${System.identityHashCode(view)} " +
        "attached=${view.isAttachedToWindow} visibility=${view.visibility} windowVisibility=${view.windowVisibility} " +
        "shown=${view.isShown} size=${view.width}x${view.height}"
}
