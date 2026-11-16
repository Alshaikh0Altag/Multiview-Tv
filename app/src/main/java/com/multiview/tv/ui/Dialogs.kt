package com.multiview.tv.ui

import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog

/**
 * Shows the dialog and makes its list wrap around with the remote:
 * ▲ on the first item jumps to the last one, ▼ on the last item back to the first.
 */
fun AlertDialog.Builder.showWrapped(): AlertDialog {
    val d = show()
    // with OK / Cancel buttons, ▼ on the last item must still reach the buttons
    val hasButtons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
        .any { d.getButton(it)?.let { b -> b.visibility == android.view.View.VISIBLE && b.text.isNotEmpty() } == true }
    d.listView?.let { list ->
        list.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val count = list.adapter?.count ?: 0
            if (count < 2) return@setOnKeyListener false
            val pos = list.selectedItemPosition
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && pos == 0 -> { list.setSelection(count - 1); true }
                // with OK / Cancel buttons below the list, ▼ on the last item goes down to them
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN && pos == count - 1 && !hasButtons(d) -> { list.setSelection(0); true }
                else -> false
            }
        }
    }
    return d
}

private fun hasButtons(d: AlertDialog) = listOf(
    android.content.DialogInterface.BUTTON_POSITIVE,
    android.content.DialogInterface.BUTTON_NEGATIVE,
    android.content.DialogInterface.BUTTON_NEUTRAL,
).any { d.getButton(it)?.let { b -> b.visibility == android.view.View.VISIBLE && b.text.isNotEmpty() } == true }
