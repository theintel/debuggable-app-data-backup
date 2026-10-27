package me.timschneeberger.appdatabackup

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import me.timschneeberger.appdatabackup.databinding.ItemToolbuttonBinding
import me.timschneeberger.appdatabackup.shizuku.ShellResult
import me.timschneeberger.appdatabackup.shizuku.ShizukuWrapper
import me.timschneeberger.appdatabackup.utils.showAlert
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.appdatabackup.utils.createProgressDialog
import me.timschneeberger.appdatabackup.utils.runOnUiThread
import java.io.File

/**
 * System-only fragment that lists packages whose data can be exported/imported.
 *
 * Two transfer modes are supported:
 *  - [TransferMode.RUN_AS]: debuggable apps, copied directly through `run-as`.
 *  - [TransferMode.ADB_BACKUP]: non-debuggable apps that allow backup in their manifest
 *    (`android:allowBackup`), handled by the system `bu` tool, which is what `adb backup` uses.
 */
class AppDataListFragment : BaseAppListFragment() {

    private enum class TransferMode { RUN_AS, ADB_BACKUP }

    private var customPackageFilter: Set<String>? = null

    private fun transferModeOf(info: ApplicationInfo): TransferMode? = when {
        info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 -> TransferMode.RUN_AS
        info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0 -> TransferMode.ADB_BACKUP
        else -> null
    }

    private fun transferModeOf(pkg: String): TransferMode? = try {
        transferModeOf(requireContext().packageManager.getApplicationInfo(pkg, 0))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Since Android 12, `adb backup` no longer includes app data for non-debuggable apps
     * that target API 31 or higher, even if they allow backup.
     */
    private fun isAdbBackupRestricted(info: ApplicationInfo) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.targetSdkVersion >= Build.VERSION_CODES.S

    private fun checkIfFolderExists(path: String): Boolean {
        return ShizukuWrapper.useServiceSafely { srv ->
            return@useServiceSafely srv.run("ls -d $path").code == 0
        }
    }

    private fun runShell(command: String, throwError: Boolean = true): String {
        return ShizukuWrapper.useServiceSafely { srv ->
            val result = srv.run(command)
            if (result.code != 0 && throwError) {
                context?.showAlert(
                    title = getString(R.string.error),
                    message = getString(
                        R.string.shizuku_error_while_executing_command,
                        command,
                        result.error
                    )
                )
                throw ShizukuWrapper.InvalidShizukuStateException()
            }
            // Return error message if set
            return@useServiceSafely result.error?.plus("\n") ?: ""
        }
    }

    /** Runs a command and returns the full result without any error handling or dialogs. */
    private fun runShellForResult(command: String): ShellResult {
        return ShizukuWrapper.useServiceSafely { srv -> srv.run(command) }
    }

    @SuppressLint("SdCardPath")
    private val sdCard = "/sdcard"
    private val backupDir = "$sdCard/AppData"
    private fun writableTempDir(packageName: String, deviceEncryptedStorage: Boolean): String =
        sdCard + "/Android/data/$packageName/private_data".let {
            if (deviceEncryptedStorage) it + "_de" else it
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getStringArray(ARG_PACKAGE_NAMES)?.let {
            customPackageFilter = it.toSet()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ShizukuWrapper.onCreate(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ShizukuWrapper.onDestroy()
    }

    override fun onAppClicked(target: View, pkg: String) {}

    override fun bindToolButtons(holder: VH, pkg: String): List<View> = listOf(
            newIconButton(
                contentDescription = getString(R.string.app_data_import),
                iconRes = R.drawable.ic_download
            ) {
                val isCleanRestore = prefs?.getBoolean("pref_clean_restore", false) ?: false
                executeTransfer(pkg, isExport = false, isCleanRestore)
            },
            newIconButton(
                contentDescription = getString(R.string.app_data_export),
                iconRes = R.drawable.ic_upload
            ) {
                executeTransfer(pkg, isExport = true)
            }
        )

    /**
     * Backs up or restores an app through the system backup tool (`bu`), the same mechanism as
     * `adb backup` / `adb restore`. Android shows a confirmation screen on the device that the
     * user must accept before the transfer starts.
     */
    private fun executeAdbTransfer(pkg: String, isExport: Boolean) {
        val pkgDir = "$backupDir/$pkg"
        val backupFile = "$pkgDir/$ADB_BACKUP_FILE_NAME"

        if (isExport) {
            runShell("mkdir -p '$pkgDir'")

            val result = runShellForResult("bu backup $pkg > '$backupFile'")
            if (result.code != 0) {
                runShell("rm -f '$backupFile'", false)
                runOnUiThread {
                    showAlert(
                        getString(R.string.error),
                        getString(R.string.adb_backup_failed, result.error ?: "")
                    )
                }
                return
            }

            // A backup without any app data is only a few hundred bytes of headers
            val size = runShellForResult("stat -c %s '$backupFile'").result.trim().toLongOrNull() ?: 0L
            val warnings = buildString {
                if (!result.error.isNullOrBlank()) appendLine(result.error!!.trim())
                if (size < ADB_BACKUP_MIN_SIZE) {
                    appendLine(getString(R.string.adb_backup_empty))
                }
            }.trim()
            if (warnings.isNotEmpty()) {
                runOnUiThread {
                    showAlert(
                        title = getString(R.string.warnings),
                        message = getString(R.string.shizuku_shell_warnings, warnings)
                    )
                }
            }
        } else {
            if (!checkIfFolderExists(backupFile)) {
                runOnUiThread {
                    showAlert(
                        getString(R.string.error),
                        getString(R.string.no_adb_backup_found, pkg, backupFile)
                    )
                }
                return
            }

            val result = runShellForResult("bu restore < '$backupFile'")
            if (result.code != 0 || !result.error.isNullOrBlank()) {
                runOnUiThread {
                    showAlert(
                        title = getString(R.string.warnings),
                        message = getString(
                            R.string.shizuku_shell_warnings,
                            result.error.orEmpty().trim()
                        )
                    )
                }
            }
        }
    }

    private fun executeTransfer(pkg: String, isExport: Boolean, cleanRestore: Boolean = false) {
        val mode = transferModeOf(pkg)
        val progressDialog: AlertDialog = requireContext().createProgressDialog(
            when {
                mode == TransferMode.ADB_BACKUP -> getString(
                    if (isExport) R.string.exporting_adb else R.string.importing_adb
                )
                isExport -> getString(R.string.exporting)
                else -> getString(R.string.importing)
            }
        )

        // Show dialog on main thread and then run the work on IO dispatcher
        lifecycleScope.launch {
            if (isAdded) progressDialog.show()

            withContext(Dispatchers.IO) {
                try {
                    if (mode == TransferMode.ADB_BACKUP) {
                        executeAdbTransfer(pkg, isExport)
                        return@withContext
                    }

                    val temp = writableTempDir(pkg, false)
                    val tempDe = writableTempDir(pkg, true)

                    ShizukuWrapper.ensureRunAs()

                    var errors = ""
                    if (isExport) {
                        runShell("mkdir -p $temp; mkdir -p $tempDe")

                        // export: copy app data directory recursively to the sdcard location
                        errors += runShell("run-as $pkg sh -c 'chmod -f -R 777 /data/data/$pkg/*'", false)
                        errors += runShell("run-as $pkg sh -c 'chmod -f -R 777 /data/user_de/0/$pkg/*'", false)

                        // writable DEX files are not allowed, will cause app crashes (and crash loops if in code_cache/)
                        errors += runShell("run-as $pkg sh -c 'find /data/data/$pkg/* -name \"*.dex\" -exec chmod 444 {} \\;'", false)
                        errors += runShell("run-as $pkg sh -c 'find /data/user_de/0/$pkg/* -name \"*.dex\" -exec chmod 444 {} \\;'", false)


                        errors += runShell("run-as $pkg sh -c 'cp -d -r /data/data/$pkg/. $temp'", false)
                        errors += runShell("run-as $pkg sh -c 'cp -d -r /data/user_de/0/$pkg/. $tempDe'", false)

                        // re-run chmod once again, some files may still not have correct permissions. ignore errors.
                        runShell("run-as $pkg sh -c 'chmod -f -R 777 $temp/*'", false)
                        runShell("run-as $pkg sh -c 'chmod -f -R 777 $temp/*'", false)

                        // move to sdcard (ensure target exists)
                        errors += runShell("rm -rf '$backupDir/$pkg/'; mkdir -p '$backupDir/$pkg/'; " +
                            "mv -f $temp $backupDir/$pkg; mv -f $tempDe $backupDir/$pkg", false)

                        // check if private_data directories exist after move
                        if (checkIfFolderExists(temp)) {
                            Log.w("DataExport", "Move incomplete. Attempting to copy remaining files instead")
                            runShell("cp -d -r $temp $backupDir/$pkg/private_data/ && rm -rf $temp", false)
                        }
                        if (checkIfFolderExists(tempDe)) {
                            Log.w("DataExport", "Move for DE incomplete. Attempting to copy remaining files instead")
                            runShell("cp -d -r $tempDe $backupDir/$pkg/private_data_de/ && rm -rf $tempDe", false)
                        }

                    } else {
                        if (!File("$backupDir/$pkg").exists()) {
                            runOnUiThread {
                                showAlert(
                                    getString(R.string.error),
                                    getString(R.string.no_data_backup_found, pkg, backupDir, pkg)
                                )
                            }
                            return@withContext
                        }

                        // copy from sdcard to public temp location first
                        runShell("run-as $pkg mkdir -p $temp")
                        runShell("run-as $pkg mkdir -p $tempDe")
                        runShell(
                            "cp -d -r $backupDir/$pkg/private_data/. $temp; " +
                                    "cp -d -r $backupDir/$pkg/private_data_de/. $tempDe"
                        )

                        if (cleanRestore) {
                            // clean restore: delete existing data first
                            errors += runShell("run-as $pkg sh -c 'rm -rf /data/data/$pkg/*'", false)
                            errors += runShell("run-as $pkg sh -c 'rm -rf /data/user_de/0/$pkg/*'", false)
                        }

                        // import: copy recursively from sdcard location into the app data dir
                        // command executed as the app user via run-as so it can write into its data dir
                        errors += runShell("run-as $pkg sh -c 'cp -d -r $temp/. /data/data/$pkg'", false)
                        errors += runShell("run-as $pkg sh -c 'cp -d -r $tempDe/. /data/user_de/0/$pkg'", false)

                        // ensure correct permissions for dex files
                        errors += runShell("run-as $pkg sh -c 'find /data/data/$pkg/* -name \"*.dex\" -exec chmod 444 {} \\;'", false)
                        errors += runShell("run-as $pkg sh -c 'find /data/user_de/0/$pkg/* -name \"*.dex\" -exec chmod 444 {} \\;'", false)

                    }

                    if (!errors.isBlank()) {
                        runOnUiThread {
                            showAlert(
                                title = getString(R.string.warnings),
                                message = getString(R.string.shizuku_shell_warnings, errors.trim())
                            )
                        }
                    }
                }
                catch (_: ShizukuWrapper.InvalidShizukuStateException) {
                    // ShizukuWrapper already handled user notification
                }
                catch (e: Exception) {
                    runOnUiThread {
                        showAlert(getString(R.string.error), e.stackTraceToString())
                    }
                }
            }

            // Dismiss dialog safely on the main thread
            if (progressDialog.isShowing) {
                try { progressDialog.dismiss() } catch (_: Exception) {}
            }
        }
    }

    private fun newIconButton(
        contentDescription: String,
        @DrawableRes iconRes: Int,
        onClick: () -> Unit
    ): MaterialButton = ItemToolbuttonBinding.inflate(layoutInflater).root.apply {
        this.contentDescription = contentDescription
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.tooltipText = contentDescription
        }
        setIconResource(iconRes)
        setOnClickListener { onClick() }
    }

    override fun shouldShowPackage(info: PackageInfo) = customPackageFilter?.contains(info.packageName)
        ?: (info.applicationInfo?.let {
            val isPlainSystemApp = it.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            // Plain system apps are hidden unless debuggable: nearly all of them allow backup
            when (transferModeOf(it)) {
                TransferMode.RUN_AS -> true
                TransferMode.ADB_BACKUP -> !isPlainSystemApp
                null -> false
            }
        } == true)


    override fun queryPackageStatus(pkgName: String): String = try {
        val info = requireContext().packageManager.getApplicationInfo(pkgName, 0)
        when (transferModeOf(info)) {
            TransferMode.RUN_AS -> getString(R.string.mode_run_as)
            TransferMode.ADB_BACKUP -> getString(
                if (isAdbBackupRestricted(info)) R.string.mode_adb_backup_restricted
                else R.string.mode_adb_backup
            )
            null -> ""
        }
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    companion object {
        const val ARG_PACKAGE_NAMES = "package_names"

        private const val ADB_BACKUP_FILE_NAME = "backup.ab"
        private const val ADB_BACKUP_MIN_SIZE = 1024L

        fun newInstance(packageNames: Array<String>?) = AppDataListFragment().apply {
            arguments = Bundle().apply {
                if (packageNames != null) {
                    putStringArray(ARG_PACKAGE_NAMES, packageNames)
                }
            }
        }
    }
}
