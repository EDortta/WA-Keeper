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
        // If a Drive tree was already granted, reopen there. Otherwise use the
        // Google Drive DocumentsProvider as the initial location. Do not block
        // on PackageManager discovery: Android 11+ can filter provider queries.
        val initial = DriveBackupPolicy.rootUri(this) ?: googleDriveRootUri()
        chooseFolder.launch(initial)
    }

    private fun googleDriveRootUri(): Uri {
        // Prefer the real root id reported by the provider.
        queryGoogleDriveRootUri()?.let { return it }

        // Samsung/Android builds may hide DocumentsProvider discovery even while
        // the provider is fully usable by DocumentsUI. The canonical authority
        // and root id still give OpenDocumentTree a valid initial hint.
        return DocumentsContract.buildRootUri(GOOGLE_DRIVE_AUTHORITY, GOOGLE_DRIVE_ROOT_ID)
    }

    private fun queryGoogleDriveRootUri(): Uri? {
        return runCatching {
            val rootsUri = DocumentsContract.buildRootsUri(GOOGLE_DRIVE_AUTHORITY)
            contentResolver.query(
                rootsUri,
                arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                DocumentsContract.buildRootUri(
                    GOOGLE_DRIVE_AUTHORITY,
                    cursor.getString(0)
                )
            }
        }.getOrNull()
    }

    private fun isGoogleDriveUri(uri: Uri): Boolean =
        uri.authority == GOOGLE_DRIVE_AUTHORITY ||
            uri.authority in googleDriveAuthorities()

    private fun googleDriveAuthorities(): Set<String> {
        val result = linkedSetOf(GOOGLE_DRIVE_AUTHORITY)

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
        private const val GOOGLE_DRIVE_ROOT_ID = "root"
    }
}
