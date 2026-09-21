package br.com.wanotifkeeper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.com.wanotifkeeper.databinding.ActivityEntityAssociationsBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AssociationCandidate(
    val key: String,
    val title: String,
    val subtitle: String,
    val packageName: String,
    val sender: String,
    val role: String,
    val kind: Kind,
    val memberSenders: List<String> = listOf(sender)
) {
    enum class Kind { CONVERSATION, CONTACT }
}

class EntityAssociationsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEntityAssociationsBinding
    private val db by lazy { NotifDatabase.get(this) }
    private val memory by lazy { MemoryRepository(this) }
    private var entityId: Long = 0L

    private val allCandidates = mutableListOf<AssociationCandidate>()
    private val selectedKeys = linkedSetOf<String>()
    private var currentQuery = ""
    private var currentTab = 0

    private lateinit var adapter: AssociationAdapter

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        renderContactsPermission(granted)
        if (granted) lifecycleScope.launch { reloadCandidates() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEntityAssociationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        entityId = intent.getLongExtra(EXTRA_ENTITY_ID, 0L)
        if (entityId == 0L) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveAndFinish() }
        binding.btnAllowContacts.setOnClickListener {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }

        adapter = AssociationAdapter(
            isSelected = { selectedKeys.contains(it.key) },
            onToggle = { candidate, checked ->
                if (checked) selectedKeys.add(candidate.key) else selectedKeys.remove(candidate.key)
                updateSelectionCount()
            }
        )
        binding.recyclerAssociations.layoutManager = LinearLayoutManager(this)
        binding.recyclerAssociations.adapter = adapter

        binding.searchAssociations.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.filterTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        val hasContacts = hasContactsPermission()
        renderContactsPermission(hasContacts)
        if (!hasContacts) {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }

        lifecycleScope.launch { reloadCandidates() }
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun renderContactsPermission(granted: Boolean) {
        binding.contactsPermissionCard.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private suspend fun reloadCandidates() {
        val entity = db.memory().entityById(entityId)
        binding.tvEntityName.text = entity?.name ?: "Entidade"

        val links = db.memory().linksForEntity(entityId)
        selectedKeys.clear()

        val conversations = withContext(Dispatchers.IO) {
            db.dao().getAll()
                .groupBy { item ->
                    if (item.packageName == "wa.keeper.import") {
                        item.packageName to item.sender.trim().lowercase()
                    } else {
                        item.packageName to ConversationIdentity.displayGroupKey(item)
                    }
                }
                .map { (_, items) ->
                    val newest = items.maxByOrNull { it.timestamp } ?: items.first()
                    val canonical = if (newest.packageName == "wa.keeper.import") {
                        newest.sender.trim()
                    } else {
                        ConversationIdentity.canonicalSender(newest.sender, newest.packageName)
                    }
                    val members = (items.map { it.sender.trim() } + canonical)
                        .filter { it.isNotBlank() }
                        .distinct()

                    AssociationCandidate(
                        key = keyFor(newest.packageName, canonical, "CONVERSATION"),
                        title = canonical,
                        subtitle = when (newest.packageName) {
                            "com.whatsapp.w4b" -> "Conversa · WhatsApp Business"
                            "com.whatsapp" -> "Conversa · WhatsApp"
                            "wa.keeper.import" -> "Conversa · histórico importado"
                            else -> "Conversa"
                        },
                        packageName = newest.packageName,
                        sender = canonical,
                        role = "CONVERSATION",
                        kind = AssociationCandidate.Kind.CONVERSATION,
                        memberSenders = members
                    )
                }
        }

        val contacts = if (hasContactsPermission()) {
            withContext(Dispatchers.IO) { readDeviceContacts() }
        } else {
            emptyList()
        }

        allCandidates.clear()
        allCandidates.addAll(conversations)
        allCandidates.addAll(contacts)

        for (candidate in allCandidates) {
            val selected = links.any { link ->
                link.packageName == candidate.packageName &&
                    link.role == candidate.role &&
                    candidate.memberSenders.contains(link.sender)
            }
            if (selected) selectedKeys.add(candidate.key)
        }

        applyFilter()
        updateSelectionCount()
    }

    private fun readDeviceContacts(): List<AssociationCandidate> {
        data class ContactBucket(
            var name: String,
            val phones: LinkedHashMap<String, String> = linkedMapOf(),
            val legacySenders: MutableList<String> = mutableListOf()
        )

        val byContact = linkedMapOf<Long, ContactBucket>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val rawNumber = cursor.getString(numberIndex)?.trim().orEmpty()
                if (name.isBlank() && rawNumber.isBlank()) continue

                val bucket = byContact.getOrPut(contactId) { ContactBucket(name) }
                if (bucket.name.isBlank() && name.isNotBlank()) bucket.name = name

                val normalized = normalizePhoneKey(rawNumber)
                if (normalized.isNotBlank()) {
                    bucket.phones.putIfAbsent(normalized, formatPhone(normalized))
                }
                bucket.legacySenders += MemoryRepository.encodeContactAlias(name, rawNumber)
            }
        }

        // Merge duplicate Android contacts when they share a normalized phone number.
        val merged = mutableListOf<ContactBucket>()
        for (bucket in byContact.values) {
            val match = merged.firstOrNull { existing ->
                existing.phones.keys.any { it in bucket.phones.keys }
            }
            if (match != null) {
                if (match.name.isBlank()) match.name = bucket.name
                match.phones.putAll(bucket.phones)
                match.legacySenders.addAll(bucket.legacySenders)
            } else {
                merged += bucket
            }
        }

        return merged.map { bucket ->
            val phones = bucket.phones.values.toList()
            val encoded = MemoryRepository.encodeContactAlias(bucket.name, phones.joinToString(","))
            AssociationCandidate(
                key = keyFor(CONTACTS_PACKAGE, encoded, "CONTACT"),
                title = bucket.name.ifBlank { phones.firstOrNull() ?: "Contato" },
                subtitle = if (phones.isEmpty()) {
                    "Contato do telefone"
                } else {
                    "Contato\n" + phones.joinToString("\n")
                },
                packageName = CONTACTS_PACKAGE,
                sender = encoded,
                role = "CONTACT",
                kind = AssociationCandidate.Kind.CONTACT,
                memberSenders = (bucket.legacySenders + encoded).distinct()
            )
        }.sortedBy { it.title.lowercase() }
    }

    private fun normalizePhoneKey(value: String): String {
        var digits = value.filter { it.isDigit() }
        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.startsWith("55") && digits.length in 12..13) digits = digits.drop(2)
        if (digits.startsWith("0") && digits.length in 11..12) digits = digits.drop(1)
        return digits
    }

    private fun formatPhone(normalized: String): String = when (normalized.length) {
        11 -> "+55 ${normalized.substring(0, 2)} ${normalized.substring(2, 7)}-${normalized.substring(7)}"
        10 -> "+55 ${normalized.substring(0, 2)} ${normalized.substring(2, 6)}-${normalized.substring(6)}"
        else -> normalized
    }

    private fun applyFilter() {
        val q = currentQuery.lowercase()
        val filtered = allCandidates.filter { item ->
            val tabMatches = when (currentTab) {
                1 -> item.kind == AssociationCandidate.Kind.CONVERSATION
                2 -> item.kind == AssociationCandidate.Kind.CONTACT
                else -> true
            }
            val textMatches = q.isBlank() ||
                item.title.lowercase().contains(q) ||
                item.subtitle.lowercase().contains(q)
            tabMatches && textMatches
        }.sortedWith(
            compareBy<AssociationCandidate> { it.kind.ordinal }
                .thenBy { it.title.lowercase() }
        )

        adapter.submit(filtered)
        binding.emptyAssociations.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateSelectionCount() {
        val count = selectedKeys.size
        binding.btnSave.text = if (count == 0) "Salvar" else "Salvar ($count)"
    }

    private fun saveAndFinish() {
        lifecycleScope.launch {
            val existing = db.memory().linksForEntity(entityId)

            for (candidate in allCandidates) {
                val selected = selectedKeys.contains(candidate.key)
                val existingForCandidate = existing.filter { link ->
                    link.packageName == candidate.packageName &&
                        link.role == candidate.role &&
                        candidate.memberSenders.contains(link.sender)
                }

                if (selected) {
                    // Keep one clean alias for contacts; keep all historical variants for
                    // conversations so old retained messages remain part of the entity.
                    if (candidate.kind == AssociationCandidate.Kind.CONTACT) {
                        for (link in existingForCandidate) {
                            if (link.sender != candidate.sender) {
                                db.memory().unlink(entityId, link.packageName, link.sender)
                            }
                        }
                        memory.linkConversation(
                            entityId,
                            candidate.packageName,
                            candidate.sender,
                            candidate.role
                        )
                    } else {
                        for (sender in candidate.memberSenders) {
                            memory.linkConversation(
                                entityId,
                                candidate.packageName,
                                sender,
                                candidate.role
                            )
                        }
                    }
                } else {
                    for (link in existingForCandidate) {
                        db.memory().unlink(entityId, link.packageName, link.sender)
                    }
                }
            }

            finish()
        }
    }

    private fun keyFor(packageName: String, sender: String, role: String): String =
        "$role|$packageName|$sender"

    companion object {
        private const val EXTRA_ENTITY_ID = "entity_id"
        const val CONTACTS_PACKAGE = "android.contacts"

        fun intent(context: Context, entityId: Long): Intent =
            Intent(context, EntityAssociationsActivity::class.java)
                .putExtra(EXTRA_ENTITY_ID, entityId)
    }
}

private class AssociationAdapter(
    private val isSelected: (AssociationCandidate) -> Boolean,
    private val onToggle: (AssociationCandidate, Boolean) -> Unit
) : RecyclerView.Adapter<AssociationAdapter.VH>() {

    private val items = mutableListOf<AssociationCandidate>()

    fun submit(newItems: List<AssociationCandidate>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        val title: TextView = card.findViewById(R.id.tvAssociationTitle)
        val subtitle: TextView = card.findViewById(R.id.tvAssociationSubtitle)
        val check: CheckBox = card.findViewById(R.id.cbAssociation)
        val avatar: TextView = card.findViewById(R.id.tvAssociationAvatar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_association_candidate, parent, false) as MaterialCardView
        return VH(card)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.avatar.text = item.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = isSelected(item)
        holder.check.setOnCheckedChangeListener { _, checked ->
            onToggle(item, checked)
        }

        holder.card.setOnClickListener {
            holder.check.isChecked = !holder.check.isChecked
        }
    }
}
