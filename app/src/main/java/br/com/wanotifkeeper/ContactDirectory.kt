package br.com.wanotifkeeper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Snapshot em memória da agenda do aparelho.
 *
 * É carregado no início do processo e atualizado por ContentObserver. O listener de
 * notificações e a tela de agendamento consultam este snapshot, evitando reler toda
 * a agenda a cada mensagem recebida.
 */
object ContactDirectory {
    data class Entry(val name: String, val phones: List<String>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val byName = AtomicReference<Map<String, List<String>>>(emptyMap())
    private val entriesRef = AtomicReference<List<Entry>>(emptyList())

    @Volatile private var started = false
    @Volatile private var appContext: Context? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshAsync()
        }
    }

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val app = context.applicationContext
            appContext = app
            started = true
            runCatching {
                app.contentResolver.registerContentObserver(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    true,
                    observer
                )
            }
            refreshAsync()
        }
    }

    fun candidates(displayName: String): List<String> =
        byName.get()[key(displayName)].orEmpty()

    fun entries(): List<Entry> = entriesRef.get()

    fun refreshAsync() {
        val app = appContext ?: return
        scope.launch { refreshNow(app) }
    }

    suspend fun refreshNow(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            byName.set(emptyMap())
            entriesRef.set(emptyList())
            return
        }

        val grouped = linkedMapOf<String, Pair<String, LinkedHashSet<String>>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        )?.use { cursor ->
            val nameIx = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val phoneIx = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIx)?.trim().orEmpty()
                if (name.isBlank()) continue
                val phone = ContactPhone.normalizeForWhatsApp(
                    cursor.getString(phoneIx).orEmpty()
                )
                if (phone.length < 10) continue

                val k = key(name)
                val current = grouped[k]
                val phones = current?.second ?: linkedSetOf()
                phones += phone
                grouped[k] = (current?.first ?: name) to phones
            }
        }

        val map = grouped.mapValues { it.value.second.toList() }
        val entries = grouped.values
            .map { (name, phones) -> Entry(name, phones.toList()) }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }

        byName.set(map)
        entriesRef.set(entries)

        // Mantém os vínculos de conversas já conhecidas coerentes com a agenda atual.
        runCatching {
            val dao = NotifDatabase.get(context).conversationBindings()
            dao.all().forEach { binding ->
                if (!binding.isGroup) {
                    dao.upsert(
                        binding.copy(
                            candidatePhones = candidates(binding.sender).joinToString(","),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    private fun key(value: String): String =
        value.trim().lowercase(Locale.ROOT)
}
