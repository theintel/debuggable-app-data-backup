package me.timschneeberger.appdatabackup

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import me.timschneeberger.appdatabackup.databinding.ItemToolbuttonBinding
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
 * System-only fragment that lists debuggable packages and exporting/importing their data.
 */
class AppDataListFragment : BaseAppListFragment() {

    private var customPackageFilter: Set<String>? = null

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

    private fun executeTransfer(pkg: String, isExport: Boolean, cleanRestore: Boolean = false) {
        val progressDialog: AlertDialog = requireContext().createProgressDialog(
            if (isExport) getString(R.string.exporting) else getString(R.string.importing)
        )

        // Show dialog on main thread and then run the work on IO dispatcher
        lifecycleScope.launch {
            if (isAdded) progressDialog.show()

            withContext(Dispatchers.IO) {
                try {
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
            it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        } == true)


    override fun queryPackageStatus(pkgName: String): String = ""

    companion object {
        const val ARG_PACKAGE_NAMES = "package_names"

        fun newInstance(packageNames: Array<String>?) = AppDataListFragment().apply {
            arguments = Bundle().apply {
                if (packageNames != null) {
                    putStringArray(ARG_PACKAGE_NAMES, packageNames)
                }
            }
        }
    }
}
