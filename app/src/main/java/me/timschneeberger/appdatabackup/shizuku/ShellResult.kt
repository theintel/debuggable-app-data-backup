package me.timschneeberger.appdatabackup.shizuku

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ShellResult(
    val code: Int?,
    val result: String,
    val error: String?
) : Parcelable