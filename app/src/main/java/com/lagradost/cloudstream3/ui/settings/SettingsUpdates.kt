package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.LogcatBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.services.BackupWorkManager
import com.lagradost.cloudstream3.ui.result.txt
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.getPref
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.BackupUtils.restorePrompt
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.InAppUpdater.Companion.runAutoUpdate
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.VideoDownloadManager
import okhttp3.internal.closeQuietly
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.*

class SettingsUpdates : PreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_updates)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_updates, rootKey)
        //val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        getPref(R.string.backup_key)?.setOnPreferenceClickListener {
            BackupUtils.backup(activity)
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.automatic_backup_key)?.setOnPreferenceClickListener {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

            val prefNames = resources.getStringArray(R.array.periodic_work_names)
            val prefValues = resources.getIntArray(R.array.periodic_work_values)
            val current = settingsManager.getInt(getString(R.string.automatic_backup_key), 0)

            activity?.showDialog(
                prefNames.toList(),
                prefValues.indexOf(current),
                getString(R.string.backup_frequency),
                true,
                {}) { index ->
                settingsManager.edit()
                    .putInt(getString(R.string.automatic_backup_key), prefValues[index]).apply()
                BackupWorkManager.enqueuePeriodicWork(
                    context ?: AcraApplication.context,
                    prefValues[index].toLong()
                )
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.redo_setup_key)?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.navigation_setup_language)
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.restore_key)?.setOnPreferenceClickListener {
            activity?.restorePrompt()
            return@setOnPreferenceClickListener true
        }
        getPref(R.string.show_logcat_key)?.setOnPreferenceClickListener { pref ->
            val builder =
                AlertDialog.Builder(pref.context, R.style.AlertDialogCustom)

            val binding = LogcatBinding.inflate(layoutInflater, null, false)
            builder.setView(binding.root)

            val dialog = builder.create()
            dialog.show()
            val log = StringBuilder()
            try {
                //https://developer.android.com/studio/command-line/logcat
                val process = Runtime.getRuntime().exec("logcat -d")
                val bufferedReader = BufferedReader(
                    InputStreamReader(process.inputStream)
                )

                var line: String?
                while (bufferedReader.readLine().also { line = it } != null) {
                    log.append("${line}\n")
                }
            } catch (e: Exception) {
                logError(e) // kinda ironic
            }

            val text = log.toString()
            binding.text1.text = text

            binding.copyBtt.setOnClickListener {
                clipboardHelper(txt("Logcat"), text)
                dialog.dismissSafe(activity)
            }

            binding.clearBtt.setOnClickListener {
                Runtime.getRuntime().exec("logcat -c")
                dialog.dismissSafe(activity)
            }

            binding.saveBtt.setOnClickListener {
                val date = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(Date(currentTimeMillis()))
                var fileStream: OutputStream? = null
                try {
                    fileStream = VideoDownloadManager.setupStream(
                            it.context,
                            "logcat_${date}",
                            null,
                            "txt",
                            false
                        ).openNew()
                    fileStream.writer().write(text)
                    dialog.dismissSafe(activity)
                } catch (t: Throwable) {
                    logError(t)
                    showToast(t.message)
                } finally {
                    fileStream?.closeQuietly()
                }
            }

            binding.closeBtt.setOnClickListener {
                dialog.dismissSafe(activity)
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.apk_installer_key)?.setOnPreferenceClickListener {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(it.context)

            val prefNames = resources.getStringArray(R.array.apk_installer_pref)
            val prefValues = resources.getIntArray(R.array.apk_installer_values)

            val currentInstaller =
                settingsManager.getInt(getString(R.string.apk_installer_key), 0)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(currentInstaller),
                getString(R.string.apk_installer_settings),
                true,
                {}) { num ->
                try {
                    settingsManager.edit()
                        .putInt(getString(R.string.apk_installer_key), prefValues[num])
                        .apply()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.manual_check_update_key)?.setOnPreferenceClickListener {
            ioSafe {
                if (activity?.runAutoUpdate(false) == false) {
                    activity?.runOnUiThread {
                        showToast(
                            R.string.no_update_found,
                            Toast.LENGTH_SHORT
                        )
                    }
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.auto_download_plugins_key)?.setOnPreferenceClickListener {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(it.context)

            val prefNames = resources.getStringArray(R.array.auto_download_plugin)
            val prefValues =
                enumValues<AutoDownloadMode>().sortedBy { x -> x.value }.map { x -> x.value }

            val current = settingsManager.getInt(getString(R.string.auto_download_plugins_key), 0)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(current),
                getString(R.string.automatic_plugin_download_mode_title),
                true,
                {}) { num ->
                settingsManager.edit()
                    .putInt(getString(R.string.auto_download_plugins_key), prefValues[num]).apply()
                (context ?: AcraApplication.context)?.let { ctx -> app.initClient(ctx) }
            }
            return@setOnPreferenceClickListener true
        }
    }
}
