@file:Suppress("unused")

package me.timschneeberger.appdatabackup.utils

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.appdatabackup.R

fun Context.createProgressDialog(message: String): AlertDialog {
    val ctx = this

    val dp16 = (16 * resources.displayMetrics.density).toInt()
    val dp24 = (24 * resources.displayMetrics.density).toInt()

    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp24, dp16, dp24, dp16)
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    val progressBar = ProgressBar(ctx).apply {
        isIndeterminate = true
        val dp = 40
        val size = (dp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size) .apply {
            rightMargin = dp16
        }
    }

    val tv = TextView(ctx).apply {
        text = message
        setTextAppearance(android.R.style.TextAppearance_Large)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    container.addView(progressBar)
    container.addView(tv)

    return MaterialAlertDialogBuilder(ctx)
        .setTitle(null)
        .setView(container)
        .setCancelable(false)
        .create()
}


fun Context.showAlert(@StringRes title: Int, @StringRes message: Int) {
    showAlert(getString(title), getString(message))
}

fun Context.showAlert(e: Throwable) {
    MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.error))
        .setMessage(e.stackTraceToString())
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

fun Context.showAlert(title: String, message: String) {
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

fun Context.showConfirmDialog(@StringRes title: Int, @StringRes message: Int, onConfirm: (() -> Unit)) {
    showConfirmDialog(getString(title), getString(message), onConfirm)
}

fun Context.showConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit
) {
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel) { _, _ -> }
        .show()
}

fun Context.toast(message: String, long: Boolean = true) = Toast.makeText(this, message,
    if(long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun Context.toast(@StringRes message: Int, long: Boolean = true) = toast(getString(message), long)

fun Context.toast(e: Throwable, long: Boolean = true) =
    toast(getString(R.string.error_template, e, e.message), long)