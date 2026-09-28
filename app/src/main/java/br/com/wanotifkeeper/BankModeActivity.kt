package br.com.wanotifkeeper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.com.wanotifkeeper.databinding.ActivityBankModeBinding
import kotlinx.coroutines.launch

class BankModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBankModeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBankModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnActivateBankMode.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Ativar Modo Banco?")
                .setMessage(
                    "O WA Keeper pausará processamento, voz e mensagens agendadas. " +
                        "Depois, o Android ainda exigirá que você desligue manualmente " +
                        "Acessibilidade e Acesso a notificações."
                )
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Pausar") { _, _ -> activateBankMode() }
                .show()
        }

        binding.btnResumeWaKeeper.setOnClickListener { resumeWaKeeper() }

        binding.btnAccessibilitySettings.setOnClickListener {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }

        binding.btnNotificationSettings.setOnClickListener {
            openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

        binding.btnAppPermissions.setOnClickListener {
            openAppDetails()
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun activateBankMode() {
        Prefs.setBankModeEnabled(this, true)
        Prefs.setDirectCommandUntil(this, 0L)
        Prefs.setManualListenUntil(this, 0L)
        Prefs.setManualDurationMinutes(this, 0)
        ManualReadMode.disable()
        AudioArbiter.get(applicationContext).pauseAll()

        lifecycleScope.launch {
            ScheduledMessageAlarmScheduler.pause(applicationContext)
        }

        renderState()
    }

    private fun resumeWaKeeper() {
        Prefs.setBankModeEnabled(this, false)
        lifecycleScope.launch {
            ScheduledMessageAlarmScheduler.reschedule(applicationContext)
        }
        renderState()
    }

    private fun renderState() {
        val accessibility = BankMode.accessibilityEnabled(this)
        val notification = BankMode.notificationAccessEnabled(this)
        val bankMode = BankMode.isEnabled(this)
        val status = BankMode.status(bankMode, accessibility, notification)

        binding.tvBankModeState.text = when (status) {
            BankModeStatus.NORMAL ->
                "WA Keeper ativo normalmente"
            BankModeStatus.WAITING_ACCESSIBILITY ->
                "Pausado · desative a Acessibilidade do WA Keeper"
            BankModeStatus.WAITING_NOTIFICATION_ACCESS ->
                "Pausado · desative o Acesso a notificações do WA Keeper"
            BankModeStatus.READY ->
                "Seguro para tentar abrir o app bancário"
            BankModeStatus.RESUME_NEEDS_ACCESSIBILITY ->
                "Retomado parcialmente · reative a Acessibilidade para envio de mídia"
            BankModeStatus.RESUME_NEEDS_NOTIFICATION_ACCESS ->
                "Retomado parcialmente · reative o Acesso a notificações"
        }

        binding.tvAccessibilityState.text =
            if (accessibility) "Acessibilidade: ATIVA" else "Acessibilidade: desativada"

        binding.tvNotificationState.text =
            if (notification) "Acesso a notificações: ATIVO" else "Acesso a notificações: desativado"

        binding.btnActivateBankMode.isEnabled = !bankMode
        binding.btnResumeWaKeeper.isEnabled = bankMode

        binding.tvBankModeExplanation.text = if (bankMode) {
            "O processamento interno do WA Keeper está pausado. Para reduzir a superfície " +
                "detectável pelo banco, desligue também manualmente os dois acessos especiais abaixo."
        } else {
            "Use este modo antes de abrir um app bancário. O WA Keeper não tenta esconder suas " +
                "capacidades: ele pausa e leva você às telas oficiais do Android para desligá-las."
        }
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { openAppDetails() }
    }

    private fun openAppDetails() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }
}
