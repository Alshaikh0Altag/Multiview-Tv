package com.multiview.tv.ui

import androidx.appcompat.app.AlertDialog
import com.multiview.tv.R
import com.multiview.tv.data.ContinueItem
import com.multiview.tv.data.Library
import com.multiview.tv.data.Prefs

/** Long-press on a "continue watching" item: resume, start over, or remove it. */
object ContinueActions {

    fun show(a: BaseActivity, item: ContinueItem, onRemoved: () -> Unit) {
        val opts = arrayOf(
            a.getString(R.string.resume_play),
            a.getString(R.string.start_over),
            a.getString(R.string.remove_continue),
        )
        AlertDialog.Builder(a)
            .setTitle(item.title)
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> play(a, item, VodPlayerActivity.RESUME_YES)
                    1 -> play(a, item, VodPlayerActivity.RESUME_NO)
                    2 -> {
                        Library.removeContinue(a, item.key)
                        Prefs.clearPosition(a, item.key)
                        onRemoved()
                    }
                }
            }
            .showWrapped()
    }

    fun play(a: BaseActivity, item: ContinueItem, resume: Int) {
        a.startActivity(VodPlayerActivity.intent(a,
            listOf(VodPlayerActivity.Item(item.key, item.title, item.url, item.poster, item.seriesId)), 0, resume))
    }
}
