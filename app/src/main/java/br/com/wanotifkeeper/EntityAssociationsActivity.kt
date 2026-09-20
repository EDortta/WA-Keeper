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
    val kind: Kind
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
        for (link in links) {
            selectedKeys.add(keyFor(link.packageName, link.sender, link.role))
        }

        val conversations = withContext(Dispatchers.IO) {
            db.dao().getAll()
                .distinctBy { it.packageName to it.sender }
                .map { item ->
                    AssociationCandidate(
                        key = keyFor(item.packageName, item.sender, "CONVERSATION"),
                        title = item.sender,
                        subtitle = when (item.packageName) {
                            "com.whatsapp.w4b" -> "Conversa · WhatsApp Business"
                            "com.whatsapp" -> "Conversa · WhatsApp"
                            "wa.keeper.import" -> "Conversa · histórico importado"
                            else -> "Conversa"
                        },
                        packageName = item.packageName,
                        sender = item.sender,
                        role = "CONVERSATION",
                        kind = AssociationCandidate.Kind.CONVERSATION
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

        applyFilter()
        updateSelectionCount()
    }

    private fun readDeviceContacts(): List<AssociationCandidate> {
        val out = linkedMapOf<String, AssociationCandidate>()
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
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                if (name.isBlank() && number.isBlank()) continue

                val encoded = MemoryRepository.encodeContactAlias(name, number)
                val key = keyFor(CONTACTS_PACKAGE, encoded, "CONTACT")
                if (!out.containsKey(key)) {
                    out[key] = AssociationCandidate(
                        key = key,
                        title = if (name.isBlank()) number else name,
                        subtitle = if (number.isBlank()) "Contato do telefone" else "Contato · $number",
                        packageName = CONTACTS_PACKAGE,
                        sender = encoded,
                        role = "CONTACT",
                        kind = AssociationCandidate.Kind.CONTACT
                    )
                }
            }
        }
        return out.values.toList()
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
            val candidatesByKey = allCandidates.associateBy { it.key }

            for (candidate in allCandidates) {
                val selected = selectedKeys.contains(candidate.key)
                val alreadyHere = existing.any {
                    keyFor(it.packageName, it.sender, it.role) == candidate.key
                }
                if (selected && !alreadyHere) {
                    memory.linkConversation(
                        entityId = entityId,
                        packageName = candidate.packageName,
                        sender = candidate.sender,
                        role = candidate.role
                    )
                } else if (!selected && alreadyHere) {
                    db.memory().unlink(
                        entityId,
                        candidate.packageName,
                        candidate.sender
                    )
                }
            }

            // Preserve links that are not represented by the current device/query source.
            for (link in existing) {
                val key = keyFor(link.packageName, link.sender, link.role)
                if (!candidatesByKey.containsKey(key) && !selectedKeys.contains(key)) {
                    selectedKeys.add(key)
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
