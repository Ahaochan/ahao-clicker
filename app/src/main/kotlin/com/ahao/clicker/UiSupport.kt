package com.ahao.clicker

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun View.applySafeInsets() {
    val padding = resources.getDimensionPixelSize(R.dimen.content_padding)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(safe.left + padding, safe.top + padding, safe.right + padding, safe.bottom + padding)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

@Suppress("DEPRECATION")
fun displayKey(context: android.content.Context): String {
    val point = android.graphics.Point()
    val display = context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
    display.getRealSize(point)
    return "${point.x}:${point.y}:${display.rotation}"
}
