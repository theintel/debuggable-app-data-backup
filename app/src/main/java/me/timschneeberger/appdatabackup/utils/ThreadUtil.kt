package me.timschneeberger.appdatabackup.utils

import android.app.Activity
import androidx.fragment.app.Fragment

fun Fragment.runOnUiThread(action: Activity.() -> Unit) {
    if (!isAdded) return

    activity?.let {
        it.runOnUiThread { action(it) }
    }
}