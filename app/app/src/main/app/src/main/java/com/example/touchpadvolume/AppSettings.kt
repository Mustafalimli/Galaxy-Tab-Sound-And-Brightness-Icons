package com.example.touchpadvolume

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.WindowManager

class AppSettings(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("unified_touchpad_prefs", Context.MODE_PRIVATE)

    fun getScreenBounds(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private val initialBounds = getScreenBounds()
    private val sw = initialBounds.first
    private val sh = initialBounds.second

    var isServiceRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(v) = prefs.edit().putBoolean(KEY_SERVICE_RUNNING, v).apply()

    var activeMode: Int
        get() = prefs.getInt(KEY_ACTIVE_MODE, MODE_STRIPS)
        set(v) = prefs.edit().putInt(KEY_ACTIVE_MODE, v).apply()

    var isCalibrationMode: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATION_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_CALIBRATION_MODE, v).apply()

    var clickThroughBypass: Boolean
        get() = prefs.getBoolean(KEY_CLICK_THROUGH, true)
        set(v) = prefs.edit().putBoolean(KEY_CLICK_THROUGH, v).apply()

    var scrollCooldownMs: Long
        get() = prefs.getLong(KEY_COOLDOWN_MS, 65L)
        set(v) = prefs.edit().putLong(KEY_COOLDOWN_MS, v).apply()

    var invertScroll: Boolean
        get() = prefs.getBoolean(KEY_INVERT_SCROLL, false)
        set(v) = prefs.edit().putBoolean(KEY_INVERT_SCROLL, v).apply()

    var showSystemVolumeUI: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYS_UI, true)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_SYS_UI, v).apply()

    var volStripEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOL_STRIP_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_VOL_STRIP_ENABLED, v).apply()

    var briStripEnabled: Boolean
        get() = prefs.getBoolean(KEY_BRI_STRIP_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_BRI_STRIP_ENABLED, v).apply()

    var volStripWidth: Int
        get() = prefs.getInt(KEY_VOL_STRIP_W, 110)
        set(v) = prefs.edit().putInt(KEY_VOL_STRIP_W, v).apply()

    var volStripHeight: Int
        get() = prefs.getInt(KEY_VOL_STRIP_H, (sh * 0.45f).toInt().coerceAtLeast(200))
        set(v) = prefs.edit().putInt(KEY_VOL_STRIP_H, v).apply()

    var volStripX: Int
        get() = prefs.getInt(KEY_VOL_STRIP_X, (sw - 110).coerceAtLeast(0))
        set(v) = prefs.edit().putInt(KEY_VOL_STRIP_X, v).apply()

    var volStripY: Int
        get() = prefs.getInt(KEY_VOL_STRIP_Y, (sh * 0.05f).toInt())
        set(v) = prefs.edit().putInt(KEY_VOL_STRIP_Y, v).apply()

    var briStripWidth: Int
        get() = prefs.getInt(KEY_BRI_STRIP_W, 110)
        set(v) = prefs.edit().putInt(KEY_BRI_STRIP_W, v).apply()

    var briStripHeight: Int
        get() = prefs.getInt(KEY_BRI_STRIP_H, (sh * 0.45f).toInt().coerceAtLeast(200))
        set(v) = prefs.edit().putInt(KEY_BRI_STRIP_H, v).apply()

    var briStripX: Int
        get() = prefs.getInt(KEY_BRI_STRIP_X, 0)
        set(v) = prefs.edit().putInt(KEY_BRI_STRIP_X, v).apply()

    var briStripY: Int
        get() = prefs.getInt(KEY_BRI_STRIP_Y, (sh * 0.05f).toInt())
        set(v) = prefs.edit().putInt(KEY_BRI_STRIP_Y, v).apply()

    var volBubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOL_BUBBLE_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_VOL_BUBBLE_ENABLED, v).apply()

    var briBubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_BRI_BUBBLE_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_BRI_BUBBLE_ENABLED, v).apply()

    var volBubbleX: Int
        get() = prefs.getInt(KEY_VOL_BUBBLE_X, (sw - 130).coerceAtLeast(0))
        set(v) = prefs.edit().putInt(KEY_VOL_BUBBLE_X, v).apply()

    var volBubbleY: Int
        get() = prefs.getInt(KEY_VOL_BUBBLE_Y, (sh * 0.20f).toInt())
        set(v) = prefs.edit().putInt(KEY_VOL_BUBBLE_Y, v).apply()

    var briBubbleX: Int
        get() = prefs.getInt(KEY_BRI_BUBBLE_X, (sw - 130).coerceAtLeast(0))
        set(v) = prefs.edit().putInt(KEY_BRI_BUBBLE_X, v).apply()

    var briBubbleY: Int
        get() = prefs.getInt(KEY_BRI_BUBBLE_Y, (sh * 0.35f).toInt())
        set(v) = prefs.edit().putInt(KEY_BRI_BUBBLE_Y, v).apply()

    var bubbleSizePx: Int
        get() = prefs.getInt(KEY_BUBBLE_SIZE, 105)
        set(v) = prefs.edit().putInt(KEY_BUBBLE_SIZE, v).apply()

    var dropdownSliderHeightPx: Int
        get() = prefs.getInt(KEY_SLIDER_HEIGHT, 340)
        set(v) = prefs.edit().putInt(KEY_SLIDER_HEIGHT, v).apply()

    var bubbleIdleAlpha: Float
        get() = prefs.getFloat(KEY_BUBBLE_ALPHA, 0.45f)
        set(v) = prefs.edit().putFloat(KEY_BUBBLE_ALPHA, v).apply()

    var autoCollapseSec: Int
        get() = prefs.getInt(KEY_AUTO_COLLAPSE_SEC, 4)
        set(v) = prefs.edit().putInt(KEY_AUTO_COLLAPSE_SEC, v).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(l)
    }

    companion object {
        const val MODE_STRIPS = 0
        const val MODE_BUBBLES = 1

        const val KEY_SERVICE_RUNNING = "is_service_running"
        const val KEY_ACTIVE_MODE = "active_mode"
        const val KEY_CALIBRATION_MODE = "is_calibration_mode"
        const val KEY_CLICK_THROUGH = "click_through_bypass"
        const val KEY_COOLDOWN_MS = "scroll_cooldown_ms"
        const val KEY_INVERT_SCROLL = "invert_scroll"
        const val KEY_SHOW_SYS_UI = "show_sys_ui"

        const val KEY_VOL_STRIP_ENABLED = "vol_strip_enabled"
        const val KEY_BRI_STRIP_ENABLED = "bri_strip_enabled"
        const val KEY_VOL_STRIP_W = "vol_strip_w"
        const val KEY_VOL_STRIP_H = "vol_strip_h"
        const val KEY_VOL_STRIP_X = "vol_strip_x"
        const val KEY_VOL_STRIP_Y = "vol_strip_y"
        const val KEY_BRI_STRIP_W = "bri_strip_w"
        const val KEY_BRI_STRIP_H = "bri_strip_h"
        const val KEY_BRI_STRIP_X = "bri_strip_x"
        const val KEY_BRI_STRIP_Y = "bri_strip_y"

        const val KEY_VOL_BUBBLE_ENABLED = "vol_bubble_enabled"
        const val KEY_BRI_BUBBLE_ENABLED = "bri_bubble_enabled"
        const val KEY_VOL_BUBBLE_X = "vol_bubble_x"
        const val KEY_VOL_BUBBLE_Y = "vol_bubble_y"
        const val KEY_BRI_BUBBLE_X = "bri_bubble_x"
        const val KEY_BRI_BUBBLE_Y = "bri_bubble_y"
        const val KEY_BUBBLE_SIZE = "bubble_size_px"
        const val KEY_SLIDER_HEIGHT = "dropdown_slider_height_px"
        const val KEY_BUBBLE_ALPHA = "bubble_idle_alpha"
        const val KEY_AUTO_COLLAPSE_SEC = "auto_collapse_sec"
    }
}
