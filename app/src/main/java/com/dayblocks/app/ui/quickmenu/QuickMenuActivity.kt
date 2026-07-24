package com.dayblocks.app.ui.quickmenu

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent host activity for [QuickMenuSheet].
 * Launched by the floating bubble so the sheet appears as a system overlay
 * without bringing the full app to the foreground visually.
 * Finishes itself when the sheet is dismissed.
 */
class QuickMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No content view — the transparent window is just a host for the dialog fragment.
        if (savedInstanceState == null) {
            QuickMenuSheet().show(supportFragmentManager, "quick_menu")
        }
    }
}
