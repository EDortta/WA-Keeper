package br.com.wanotifkeeper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.com.wanotifkeeper.databinding.ActivityContactScheduleBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

data class ScheduleContact(
    val name: String,
    val phones: List<String>
)

class ContactScheduleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactScheduleBinding
    private lateinit var adapter: ContactAdapter
    private val packageNameTarget by lazy {
        intent.getStringExtra(EXTRA_PACKAGE)?.takeIf {
            it == Prefs.PKG_WHATSAPP || it == Prefs.PKG_BUSINESS
        } ?: Prefs.PKG_WHATSAPP
    }
    private var contacts = emptyList<ScheduleContact>()

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        binding.permissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) {
            ContactDirectory.refreshAsync()
            loadContacts()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = if (packageNameTarget == Prefs.PKG_BUSINESS) {
            "Agendar · WhatsApp Business"
        } else {
            "Agendar · WhatsApp"
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ContactAdapter { contact -> choosePhone(contact) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnAllowContacts.setOnClickListener {
            permission.launch(Manifest.permission.READ_CONTACTS)
        }

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        binding.permissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) {
            ContactDirectory.refreshAsync()
            loadContacts()
        } else permission.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun loadContacts() {
        val cached = ContactDirectory.entries()
        if (cached.isNotEmpty()) {
            contacts = cached.map { ScheduleContact(it.name, it.phones) }
            render(binding.search.text?.toString().orEmpty())
            return
        }

        lifecycleScope.launch {
            ContactDirectory.refreshNow(this@ContactScheduleActivity)
            contacts = ContactDirectory.entries()
                .map { ScheduleContact(it.name, it.phones) }
            render(binding.search.text?.toString().orEmpty())
        }
    }

    private fun render(query: String) {
        val q = query.trim().lowercase()
        val digits = q.filter(Char::isDigit)
        val filtered = if (q.isBlank()) {
            contacts
        } else {
            contacts.filter { c ->
                c.name.lowercase().contains(q) ||
                    (digits.isNotBlank() && c.phones.any { it.contains(digits) })
            }
        }
        adapter.submit(filtered)
        binding.empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun choosePhone(contact: ScheduleContact) {
        if (contact.phones.size == 1) {
            openComposer(contact.name, contact.phones.single())
            return
        }

        val labels = contact.phones.map(::formatPhone).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Telefone de ${contact.name}")
            .setItems(labels) { _, which ->
                openComposer(contact.name, contact.phones[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openComposer(name: String, phone: String) {
        startActivity(
            ScheduledMessagesActivity.intent(
                ctx = this,
                packageName = packageNameTarget,
                sender = name,
                recipientPhone = phone
            )
        )
    }

    private fun formatPhone(digits: String): String = when {
        digits.startsWith("55") && digits.length == 13 ->
            "+55 ${digits.substring(2, 4)} ${digits.substring(4, 9)}-${digits.substring(9)}"
        digits.startsWith("55") && digits.length == 12 ->
            "+55 ${digits.substring(2, 4)} ${digits.substring(4, 8)}-${digits.substring(8)}"
        else -> "+$digits"
    }

    companion object {
        const val EXTRA_PACKAGE = "target_package"
    }
}

private class ContactAdapter(
    private val onClick: (ScheduleContact) -> Unit
) : RecyclerView.Adapter<ContactAdapter.VH>() {

    private val items = mutableListOf<ScheduleContact>()

    fun submit(newItems: List<ScheduleContact>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        val avatar: TextView = card.findViewById(R.id.tvContactAvatar)
        val name: TextView = card.findViewById(R.id.tvContactName)
        val phones: TextView = card.findViewById(R.id.tvContactPhones)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_schedule_contact, parent, false) as MaterialCardView
        return VH(card)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.avatar.text = item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.name.text = item.name
        holder.phones.text = item.phones.joinToString(" · ") { phone ->
            when {
                phone.startsWith("55") && phone.length == 13 ->
                    "+55 ${phone.substring(2, 4)} ${phone.substring(4, 9)}-${phone.substring(9)}"
                phone.startsWith("55") && phone.length == 12 ->
                    "+55 ${phone.substring(2, 4)} ${phone.substring(4, 8)}-${phone.substring(8)}"
                else -> "+$phone"
            }
        }
        holder.card.setOnClickListener { onClick(item) }
    }
}
