package br.com.wanotifkeeper

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.com.wanotifkeeper.databinding.ActivityDriveBackupSettingsBinding
import kotlinx.coroutines.launch

class DriveBackupSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDriveBackupSettingsBinding

    private val chooseFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult

            if (!isGoogleDriveUri(uri)) {
                Toast.makeText(
                    this,
                    "Escolha uma pasta do Google Drive, não do armazenamento local.",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }

            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                contentResolver.takePersistableUriPermission(uri, flags)
                DriveBackupPolicy.setRootUri(this, uri)
                refresh()
            }.onFailure {
                Toast.makeText(this, "Não foi possível manter acesso a essa pasta.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriveBackupSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnChooseDriveFolder.setOnClickListener {
            openDriveFolderPicker()
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

    private fun onGlobalChanged(checked: Boolean) {
        if (checked && DriveBackupPolicy.rootUri(this) == null) {
            binding.swDriveGlobal.setOnCheckedChangeListener(null)
            binding.swDriveGlobal.isChecked = false
            binding.swDriveGlobal.setOnCheckedChangeListener { _, value -> onGlobalChanged(value) }
            openDriveFolderPicker()
            return
        }
        DriveBackupPolicy.setGlobalEnabled(this, checked)
        if (checked) lifecycleScope.launch {
            DriveBackupStore.backupAllEnabled(this@DriveBackupSettingsActivity)
        }
    }

    private fun cyclePackage(pkg: String) {
        if (DriveBackupPolicy.rootUri(this) == null) {
            openDriveFolderPicker()
            return
        }
        val next = DriveBackupPolicy.next(DriveBackupPolicy.packageMode(this, pkg))
        DriveBackupPolicy.setPackageMode(this, pkg, next)
        refresh()
        if (next == DriveBackupMode.ENABLED) {
            lifecycleScope.launch { DriveBackupStore.backupAllEnabled(this@DriveBackupSettingsActivity) }
        }
    }

    private fun openDriveFolderPicker() {
        val current = DriveBackupPolicy.rootUri(this)
        val initial = current ?: googleDriveRootUri()

        if (initial == null) {
            Toast.makeText(
                this,
                "Google Drive não está disponível neste aparelho. Instale ou ative o app Google Drive.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        chooseFolder.launch(initial)
    }

    private fun googleDriveRootUri(): Uri? {
        for (authority in googleDriveAuthorities()) {
            val root = runCatching {
                val rootsUri = DocumentsContract.buildRootsUri(authority)
                contentResolver.query(
                    rootsUri,
                    arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val rootId = cursor.getString(0)
                    DocumentsContract.buildRootUri(authority, rootId)
                }
            }.getOrNull()

            if (root != null) return root
        }
        return null
    }

    private fun isGoogleDriveUri(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        return authority in googleDriveAuthorities()
    }

    private fun googleDriveAuthorities(): Set<String> {
        val result = linkedSetOf<String>()

        runCatching {
            val providers = packageManager.queryIntentContentProviders(
                Intent(DocumentsContract.PROVIDER_INTERFACE),
                PackageManager.MATCH_ALL
            )
            providers.forEach { resolved ->
                val info = resolved.providerInfo
                if (info.packageName == GOOGLE_DRIVE_PACKAGE) {
                    info.authority?.let(result::add)
                }
            }
        }

        if (packageManager.resolveContentProvider(GOOGLE_DRIVE_AUTHORITY, 0) != null) {
            result += GOOGLE_DRIVE_AUTHORITY
        }

        return result
    }

    private fun refresh() {
        val uri = DriveBackupPolicy.rootUri(this)
        binding.tvDriveFolder.text = uri?.let { displayUri(it) } ?: "Nenhuma pasta escolhida"

        binding.swDriveGlobal.setOnCheckedChangeListener(null)
        binding.swDriveGlobal.isChecked = DriveBackupPolicy.globalEnabled(this)
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

    private fun displayUri(uri: Uri): String =
        uri.lastPathSegment?.replace("primary:", "")?.ifBlank { uri.toString() } ?: uri.toString()

    companion object {
        private const val GOOGLE_DRIVE_PACKAGE = "com.google.android.apps.docs"
        private const val GOOGLE_DRIVE_AUTHORITY = "com.google.android.apps.docs.storage"
    }
}
