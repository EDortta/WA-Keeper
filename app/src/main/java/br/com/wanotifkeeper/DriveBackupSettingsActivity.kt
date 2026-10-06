package br.com.wanotifkeeper

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.launch

class DriveBackupSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDriveBackupSettingsBinding
    private var enableAfterSignIn = false

    private val signIn =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            runCatching { task.getResult(Exception::class.java) }
                .onSuccess { account ->
                    DriveBackupPolicy.setAccountEmail(this, account.email)
                    if (enableAfterSignIn) {
                        DriveBackupPolicy.setGlobalEnabled(this, true)
                        enableAfterSignIn = false
                        lifecycleScope.launch {
                            val count = DriveBackupStore.backupAllEnabled(this@DriveBackupSettingsActivity)
                            Toast.makeText(
                                this@DriveBackupSettingsActivity,
                                "Google Drive conectado. $count mensagens sincronizadas.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    refresh()
                }
                .onFailure {
                    enableAfterSignIn = false
                    Toast.makeText(this, "Não foi possível conectar ao Google Drive.", Toast.LENGTH_LONG).show()
                    refresh()
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriveBackupSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnChooseDriveFolder.setOnClickListener {
            openGoogleSignIn()
        }

        binding.swDriveGlobal.setOnCheckedChangeListener { _, checked ->
            onGlobalChanged(checked)
        }

        binding.btnDriveWhatsapp.setOnClickListener { cyclePackage(Prefs.PKG_WHATSAPP) }
        binding.btnDriveBusiness.setOnClickListener { cyclePackage(Prefs.PKG_BUSINESS) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openGoogleSignIn() {
        signIn.launch(DriveAuth.client(this).signInIntent)
    }

    private fun onGlobalChanged(checked: Boolean) {
        if (checked && !DriveBackupPolicy.isConfigured(this)) {
            binding.swDriveGlobal.setOnCheckedChangeListener(null)
            binding.swDriveGlobal.isChecked = false
            binding.swDriveGlobal.setOnCheckedChangeListener { _, value -> onGlobalChanged(value) }
            enableAfterSignIn = true
            openGoogleSignIn()
            return
        }

        DriveBackupPolicy.setGlobalEnabled(this, checked)
        if (checked) {
            lifecycleScope.launch {
                val count = DriveBackupStore.backupAllEnabled(this@DriveBackupSettingsActivity)
                Toast.makeText(
                    this@DriveBackupSettingsActivity,
                    "$count mensagens sincronizadas com o Google Drive.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun cyclePackage(pkg: String) {
        if (!DriveBackupPolicy.isConfigured(this)) {
            openGoogleSignIn()
            return
        }
        val next = DriveBackupPolicy.next(DriveBackupPolicy.packageMode(this, pkg))
        DriveBackupPolicy.setPackageMode(this, pkg, next)
        refresh()
        if (next == DriveBackupMode.ENABLED) {
            lifecycleScope.launch { DriveBackupStore.backupAllEnabled(this@DriveBackupSettingsActivity) }
        }
    }

    private fun refresh() {
        val account = DriveAuth.account(this)
        if (account != null) {
            DriveBackupPolicy.setAccountEmail(this, account.email)
        }

        binding.tvDriveFolder.text =
            if (account == null) {
                "Google Drive não conectado"
            } else {
                "${account.email ?: "Conta Google"} · Meu Drive / WA-Keeper"
            }

        binding.btnChooseDriveFolder.text =
            if (account == null) "Conectar Google Drive" else "Trocar conta do Google Drive"

        binding.swDriveGlobal.setOnCheckedChangeListener(null)
        binding.swDriveGlobal.isChecked =
            DriveBackupPolicy.globalEnabled(this) && DriveBackupPolicy.isConfigured(this)
        binding.swDriveGlobal.setOnCheckedChangeListener { _, checked -> onGlobalChanged(checked) }

        binding.btnDriveWhatsapp.text =
            "WhatsApp: " + modeLabel(DriveBackupPolicy.packageMode(this, Prefs.PKG_WHATSAPP))
        binding.btnDriveBusiness.text =
            "WhatsApp Business: " + modeLabel(DriveBackupPolicy.packageMode(this, Prefs.PKG_BUSINESS))
    }

    private fun modeLabel(mode: DriveBackupMode): String = when (mode) {
        DriveBackupMode.INHERIT -> "herdar"
        DriveBackupMode.ENABLED -> "salvar"
        DriveBackupMode.DISABLED -> "não salvar"
    }
}
