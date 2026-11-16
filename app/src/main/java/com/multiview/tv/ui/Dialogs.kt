package com.multiview.tv.ui

import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog

/**
 * Shows the dialog and makes its list wrap around with the remote:
 * ▲ on the first item jumps to the last one, ▼ on the last item back to the first.
 */
fun AlertDialog.Builder.showWrapped(): AlertDialog {
    val d = show()
    d.listView?.let { list ->
        list.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val count = list.adapter?.count ?: 0
            if (count < 2) return@setOnKeyListener false
            val pos = list.selectedItemPosition
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && pos == 0 -> { list.setSelection(count - 1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN && pos == count - 1 -> { list.setSelection(0); true }
                else -> false
            }
        }
    }
    return d
}
