package com.kelofred.keloray.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager

/**
 * Draws every window (activity, dialogs, bottom sheets, menus) into one
 * bitmap. Compose's captureToImage() waits for a hardware frame Robolectric
 * never produces, and the activity's own view doesn't include dialog windows.
 */
fun captureAllWindows(base: View): Bitmap {
    val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val views = runCatching {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmg.getMethod("getInstance").invoke(null)
        val field = wmg.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(instance) as List<View>).filter { it.isShown && it.width > 0 }
    }.getOrDefault(listOf(base))
    for (v in views) {
        val lp = v.layoutParams as? WindowManager.LayoutParams
        if (v !== base && lp != null && lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
            canvas.drawColor(Color.argb((lp.dimAmount * 255).toInt(), 0, 0, 0))
        }
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        canvas.save()
        canvas.translate(loc[0].toFloat(), loc[1].toFloat())
        v.draw(canvas)
        canvas.restore()
    }
    return bmp
}
