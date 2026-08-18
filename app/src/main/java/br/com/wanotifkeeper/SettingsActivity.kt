package br.com.wanotifkeeper

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import br.com.wanotifkeeper.databinding.ActivitySettingsBinding

/** Ajustes do app: leitura em voz alta em movimento e guarda de áudios recebidos. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // --- Leitura em voz alta (TTS) por conta ---
        binding.swWhatsapp.isChecked = Prefs.isTtsEnabled(this, Prefs.PKG_WHATSAPP)
        binding.swBusiness.isChecked = Prefs.isTtsEnabled(this, Prefs.PKG_BUSINESS)
        binding.swWhatsapp.setOnCheckedChangeListener { _, checked ->
            Prefs.setTtsEnabled(this, Prefs.PKG_WHATSAPP, checked)
        }
        binding.swBusiness.setOnCheckedChangeListener { _, checked ->
            Prefs.setTtsEnabled(this, Prefs.PKG_BUSINESS, checked)
        }

        // --- Guarda de áudios recebidos, por conta ---
        binding.swAudioWhatsapp.isChecked = Prefs.isAudioCaptureEnabled(this, Prefs.PKG_WHATSAPP)
        binding.swAudioBusiness.isChecked = Prefs.isAudioCaptureEnabled(this, Prefs.PKG_BUSINESS)
        binding.swAudioMotion.isChecked = Prefs.isAudioPlayInMotion(this)

        val onCaptureToggle = { pkg: String, checked: Boolean ->
            Prefs.setAudioCaptureEnabled(this, pkg, checked)
            if (checked && !MediaVault.hasAllFilesAccess()) requestAllFilesAccess()
            refreshAudioPermissionBanner()
        }
        binding.swAudioWhatsapp.setOnCheckedChangeListener { _, checked -> onCaptureToggle(Prefs.PKG_WHATSAPP, checked) }
        binding.swAudioBusiness.setOnCheckedChangeListener { _, checked -> onCaptureToggle(Prefs.PKG_BUSINESS, checked) }
        binding.swAudioMotion.setOnCheckedChangeListener { _, checked ->
            Prefs.setAudioPlayInMotion(this, checked)
        }

        binding.tvAudioPermission.setOnClickListener { requestAllFilesAccess() }
    }

    override fun onResume() {
        super.onResume()
        refreshAudioPermissionBanner()
    }

    /** O aviso aparece só quando a guarda está ligada mas a permissão ainda falta. */
    private fun refreshAudioPermissionBanner() {
        val needed = Prefs.anyAudioCaptureEnabled(this) && !MediaVault.hasAllFilesAccess()
        binding.tvAudioPermission.visibility = if (needed) View.VISIBLE else View.GONE
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = runCatching {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
        }.getOrElse { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
    }
}
