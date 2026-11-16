package com.multiview.tv.ui

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.multiview.tv.R
import com.multiview.tv.data.Channel
import com.multiview.tv.data.Prefs
import com.multiview.tv.data.Repository
import com.multiview.tv.data.Settings

/** Long-press menu of a channel: multiview, favorites, custom lists, lock, hide. */
object ChannelActions {

    fun show(a: BaseActivity, c: Channel, onChanged: () -> Unit = {}) {
        val fav = Prefs.favorites(a).contains(c.id)
        val locked = c.id in Settings.lockedChannels(a)
        val opts = listOf(
            a.getString(R.string.add_multiview) to { addToMultiView(a, c) },
            a.getString(if (fav) R.string.remove_favorite else R.string.add_favorite) to {
                Prefs.toggleFavorite(a, c.id); onChanged()
            },
            a.getString(R.string.add_to_list) to { pickList(a, c, onChanged) },
            a.getString(if (locked) R.string.unlock_channel else R.string.lock_channel) to {
                a.askPin {
                    val set = Settings.lockedChannels(a).toMutableSet()
                    if (locked) set -= c.id else set += c.id
                    Settings.setLockedChannels(a, set)
                    Toast.makeText(a, if (locked) R.string.channel_unlocked else R.string.channel_locked, Toast.LENGTH_SHORT).show()
                    onChanged()
                }
            },
            a.getString(R.string.hide_channel) to {
                Settings.setHiddenChannels(a, Settings.hiddenChannels(a) + c.id)
                Repository.hiddenChannels = Settings.hiddenChannels(a)
                Toast.makeText(a, R.string.channel_hidden, Toast.LENGTH_LONG).show()
                onChanged()
            },
        )
        AlertDialog.Builder(a)
            .setTitle(c.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .show()
    }

    fun addToMultiView(a: BaseActivity, c: Channel) {
        val slot = Repository.addToMultiView(c)
        Toast.makeText(a, if (slot < 0) a.getString(R.string.multiview_full)
            else a.getString(R.string.added_to_slot, slot + 1), Toast.LENGTH_SHORT).show()
    }

    private fun pickList(a: BaseActivity, c: Channel, onChanged: () -> Unit) {
        val lists = Settings.customLists(a)
        val names = lists.keys.toList()
        val labels = names.map { n -> (if (c.id in lists[n].orEmpty()) "✓ " else "") + n } + a.getString(R.string.new_list)
        AlertDialog.Builder(a)
            .setTitle(R.string.add_to_list)
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == names.size) newList(a, c, onChanged) else toggleIn(a, names[i], c, onChanged)
            }
            .show()
    }

    private fun newList(a: BaseActivity, c: Channel, onChanged: () -> Unit) {
        val input = EditText(a).apply { hint = a.getString(R.string.list_name_hint) }
        AlertDialog.Builder(a)
            .setTitle(R.string.new_list)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) toggleIn(a, name, c, onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleIn(a: BaseActivity, name: String, c: Channel, onChanged: () -> Unit) {
        val lists = Settings.customLists(a)
        val ids = lists[name].orEmpty().toMutableList()
        val added = if (c.id in ids) { ids -= c.id; false } else { ids += c.id; true }
        lists[name] = ids
        Settings.setCustomLists(a, lists)
        Repository.customLists = lists
        Toast.makeText(a, a.getString(if (added) R.string.added_to_list else R.string.removed_from_list, name), Toast.LENGTH_SHORT).show()
        onChanged()
    }
}
