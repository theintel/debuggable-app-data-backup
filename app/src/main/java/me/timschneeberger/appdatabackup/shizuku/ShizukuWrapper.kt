package me.timschneeberger.appdatabackup.shizuku

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import me.timschneeberger.appdatabackup.BuildConfig
import me.timschneeberger.appdatabackup.IShizukuUserService
import me.timschneeberger.appdatabackup.R
import me.timschneeberger.appdatabackup.utils.showAlert
import me.timschneeberger.appdatabackup.utils.showConfirmDialog
import me.timschneeberger.appdatabackup.utils.toast
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderDeadListener
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import rikka.shizuku.Shizuku.UserServiceArgs


@SuppressLint("StaticFieldLeak")
object ShizukuWrapper {
    class InvalidShizukuStateException : Exception()

    private val binderReceivedListener = OnBinderReceivedListener(this::onBinderReceived)
    private val binderDeadListener = OnBinderDeadListener(this::onBinderDead)
    private val permissionResultListener = OnRequestPermissionResultListener(this::onRequestPermissionsResult)

    var userService: IShizukuUserService? = null
        private set

    private var hasShizukuBinder: Boolean = false

    private val userServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder?) {
            Log.d(TAG, "onServiceConnected")

            if (binder != null && binder.pingBinder()) {
                userService = IShizukuUserService.Stub.asInterface(binder)
            } else {
                Log.e(TAG, "onServiceConnected: binder is null or dead")
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Log.d(TAG, "onServiceDisconnected")
            userService = null
        }
    }

    private val userServiceArgs: UserServiceArgs = UserServiceArgs(
        ComponentName(
            BuildConfig.APPLICATION_ID,
            ShizukuUserService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("userService")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private lateinit var context: Context

    fun onCreate(context: Context) {
        Log.d(TAG, "onCreate")
        this.context = context
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun onDestroy() {
        Log.d(TAG, "onDestroy")
        if (userService != null) {
            unbindUserService()
        }

        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    private fun onBinderReceived() {
        Log.d(TAG, "onBinderReceived")
        hasShizukuBinder = true

        if (checkPermission()) {
            bindUserService()
        }
    }

    private fun onBinderDead() {
        Log.d(TAG, "onBinderDead")
        hasShizukuBinder = false
    }

    fun ensureRunAs(): Boolean = useServiceSafely { srv ->
        srv.canUseRunAs().also {
            if (!it) {
                context.showAlert(R.string.error, R.string.shizuku_wrong_environment)
                throw InvalidShizukuStateException()
            }
        }
    }

    fun <T> useServiceSafely(callback: (IShizukuUserService) -> T): T {
        if (shizukuLaunchIntent == null) {
            context.showAlert(R.string.error, R.string.shizuku_not_installed)
            throw InvalidShizukuStateException()
        }

        if (!hasShizukuBinder) {
            context.showConfirmDialog(R.string.error, R.string.shizuku_not_active) {
                context.startActivity(shizukuLaunchIntent)
            }
            throw InvalidShizukuStateException()
        }

        if (!checkPermission()) {
            context.toast(context.getString(R.string.shizuku_permission_not_granted))
            throw InvalidShizukuStateException()
        }

        val service = userService
        if (service == null) {
            context.showConfirmDialog(R.string.error, R.string.shizuku_not_active) {
                context.startActivity(shizukuLaunchIntent)
            }
            throw InvalidShizukuStateException()
        }

        try {
            return callback(service)
        } catch (tr: RemoteException) {
            Log.e(TAG, "useServiceSafely error", tr)

            context.showAlert(tr)
            throw InvalidShizukuStateException()
        }
    }

    val shizukuLaunchIntent: Intent?
        get() = context.packageManager
            .getLaunchIntentForPackage("moe.shizuku.privileged.api")
            .apply {
                this?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

    private fun onRequestPermissionsResult(requestCode: Int, grantResult: Int) {
        if (grantResult == PERMISSION_GRANTED && requestCode == REQUEST_ID) {
            bindUserService()
        }
    }

    fun checkPermission(): Boolean {
        if (Shizuku.isPreV11()) {
            return false
        }
        try {
            if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) {
                return true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                context.toast(context.getString(R.string.shizuku_needs_manual_permission))
                return false
            } else {
                Shizuku.requestPermission(REQUEST_ID)
                return false
            }
        } catch (e: Throwable) {
            context.showAlert(e)
            return false
        }
    }

    private fun bindUserService() {
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (tr: Throwable) {
            Log.e(TAG, "unbindUserService error", tr)
            context.showAlert(tr)
        }
    }

    private fun unbindUserService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            userService = null
        } catch (tr: Throwable) {
            Log.e(TAG, "unbindUserService error", tr)
        }
    }

    const val TAG = "ShizukuWrapper"
    const val REQUEST_ID = 1
}