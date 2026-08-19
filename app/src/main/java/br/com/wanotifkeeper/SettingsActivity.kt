package br.com.wanotifkeeper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import br.com.wanotifkeeper.databinding.ActivitySettingsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ajustes do app: leitura em voz alta em movimento, guarda de áudios recebidos e comandos de voz. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val requestVoicePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshVoicePermissionBanner()
        }

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

        setupVoiceCommands()
    }

    override fun onResume() {
        super.onResume()
        refreshAudioPermissionBanner()
        refreshVoiceSection()
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

    // --- Comandos de voz ---

    private fun setupVoiceCommands() {
        binding.swVoiceCommands.setOnCheckedChangeListener { _, checked ->
            Prefs.setVoiceCommandsEnabled(this, checked)
            if (checked && !hasVoicePermissions()) requestVoicePermissions.launch(voicePermissions())
            refreshVoicePermissionBanner()
        }

        binding.rgVoiceAccount.setOnCheckedChangeListener { _, checkedId ->
            val pkg = if (checkedId == binding.rbVoiceBusiness.id) Prefs.PKG_BUSINESS else Prefs.PKG_WHATSAPP
            Prefs.setVoiceDefaultAccountPkg(this, pkg)
        }

        voiceTimerButtons().forEach { (button, minutes) ->
            button.setOnClickListener {
                Prefs.setManualListenUntil(this, System.currentTimeMillis() + minutes * 60_000L)
                Prefs.setManualDurationMinutes(this, minutes)
                refreshVoiceTimerStatus()
            }
        }
        binding.btnVoiceTimerStop.setOnClickListener {
            Prefs.setManualListenUntil(this, 0L)
            Prefs.setManualDurationMinutes(this, 0)
            refreshVoiceTimerStatus()
        }

        binding.tvVoicePermission.setOnClickListener {
            requestVoicePermissions.launch(voicePermissions())
        }

        binding.tvVoiceSpeechPackMissing.setOnClickListener { openVoiceInputSettings() }
    }

    /** Abaixo da API 31 não existe reconhecimento on-device garantido — a seção some, sem meio-termo. */
    private fun refreshVoiceSection() {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        binding.groupVoiceCommands.visibility = if (supported) View.VISIBLE else View.GONE
        binding.tvVoiceUnsupported.visibility = if (supported) View.GONE else View.VISIBLE
        if (!supported) return

        binding.swVoiceCommands.isChecked = Prefs.isVoiceCommandsEnabled(this)
        val targetId = if (Prefs.voiceDefaultAccountPkg(this) == Prefs.PKG_BUSINESS)
            binding.rbVoiceBusiness.id else binding.rbVoiceWhatsapp.id
        if (binding.rgVoiceAccount.checkedRadioButtonId != targetId) {
            binding.rgVoiceAccount.check(targetId)
        }
        refreshVoiceTimerStatus()
        refreshVoicePermissionBanner()
        binding.tvVoiceSpeechPackMissing.visibility =
            if (Prefs.isSpeechPackMissing(this)) View.VISIBLE else View.GONE
    }

    private fun openVoiceInputSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
    }

    private fun voiceTimerButtons() = listOf(
        binding.btnTimer15m to 15,
        binding.btnTimer30m to 30,
        binding.btnTimer1h to 60,
        binding.btnTimer2h to 120,
        binding.btnTimer4h to 240,
        binding.btnTimer8h to 480
    )

    private fun refreshVoiceTimerStatus() {
        val until = Prefs.manualListenUntil(this)
        val active = until > System.currentTimeMillis()
        binding.tvVoiceTimerStatus.text =
            if (active) "Ouvindo até ${timeFmt.format(Date(until))}" else "Timer manual desligado"
        binding.btnVoiceTimerStop.visibility = if (active) View.VISIBLE else View.GONE

        val selectedMinutes = if (active) Prefs.manualDurationMinutes(this) else 0
        voiceTimerButtons().forEach { (button, minutes) ->
            val selected = minutes == selectedMinutes
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (selected) 0xFF25D366.toInt() else android.graphics.Color.TRANSPARENT
            )
            button.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF25D366.toInt())
        }
    }

    /** O aviso aparece só quando os comandos estão ligados mas falta microfone/notificação. */
    private fun refreshVoicePermissionBanner() {
        val needed = Prefs.isVoiceCommandsEnabled(this) && !hasVoicePermissions()
        binding.tvVoicePermission.visibility = if (needed) View.VISIBLE else View.GONE
    }

    private fun hasVoicePermissions(): Boolean = voicePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun voicePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else
            arrayOf(Manifest.permission.RECORD_AUDIO)
}
