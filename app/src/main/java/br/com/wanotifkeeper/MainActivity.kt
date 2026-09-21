package br.com.wanotifkeeper

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import br.com.wanotifkeeper.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQ_MIC = 7301
        const val DIRECT_COMMAND_WINDOW_MS = 20_000L
        const val READ_MODE_ON = "#25D366"
        const val READ_MODE_OFF = "#48484A"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NotifAdapter
    private val db by lazy { NotifDatabase.get(this) }
    private val audio by lazy { AudioArbiter.get(applicationContext) }
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    private var currentTab = 0
    private var currentQuery = ""
    private var collectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(null)

        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        lifecycleScope.launch { Retention.purge(this@MainActivity, System.currentTimeMillis()) }

        adapter = NotifAdapter(
            fmt,
            onClick = { item ->
                startActivity(
                    ConversationActivity.intent(
                        context = this,
                        packageName = item.packageName,
                        sender = item.sender,
                        conversationKey = item.conversationKey
                    )
                )
            },
            onSettings = { item ->
                startActivity(
                    Intent(this, RetentionActivity::class.java)
                        .putExtra(RetentionActivity.EXTRA_SENDER, item.sender)
                )
            },
            onSpeak = { item -> audio.speakText(item.text) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnEntities.setOnClickListener {
            startActivity(Intent(this, EntitiesActivity::class.java))
        }

        binding.btnReadMode.setOnClickListener {
            val enabled = ManualReadMode.toggle()
            renderReadMode()
            Toast.makeText(
                this,
                if (enabled) "Leitura automática ligada mesmo parado" else "Leitura automática voltou a depender do movimento",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnMic.setOnClickListener { onMicTapped() }

        updatePermissionBanner()
        binding.bannerPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                reload()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString() ?: ""
                reload()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        reload()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        renderMicState()
        renderReadMode()
        Prefs.registerChangeListener(this, micPrefsListener)
    }

    override fun onPause() {
        Prefs.unregisterChangeListener(this, micPrefsListener)
        super.onPause()
    }

    private fun renderReadMode() {
        val enabled = ManualReadMode.isEnabled()
        binding.btnReadMode.alpha = 1f
        binding.btnReadMode.setColorFilter(Color.parseColor(if (enabled) READ_MODE_ON else READ_MODE_OFF))
        binding.btnReadMode.contentDescription = if (enabled) {
            "Desativar leitura automática quando parado"
        } else {
            "Ativar leitura automática mesmo parado"
        }
    }

    private fun onMicTapped() {
        if (isDirectListening()) {
            Prefs.setDirectCommandUntil(this, 0L)
            return
        }

        if (!Prefs.isVoiceCommandsEnabled(this)) {
            Toast.makeText(this, "Ative os comandos de voz nos Ajustes", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }

        if (!isListenerEnabled()) {
            Toast.makeText(this, "Ative o acesso a notificações para usar comandos de voz", Toast.LENGTH_LONG).show()
            return
        }

        Prefs.setDirectCommandUntil(this, System.currentTimeMillis() + DIRECT_COMMAND_WINDOW_MS)
    }

    private fun isDirectListening() = Prefs.directCommandUntil(this) > System.currentTimeMillis()

    private fun renderMicState() {
        val ouvindo = isDirectListening()
        binding.tvMicHint.visibility = if (ouvindo) View.VISIBLE else View.GONE
        binding.tvMicHint.text =
            if (ouvindo) "Ouvindo — fale, ou toque de novo para fechar" else ""
        binding.btnMic.setImageResource(
            if (ouvindo) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now
        )
    }

    private val micPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_DIRECT_COMMAND_UNTIL) runOnUiThread { renderMicState() }
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            onMicTapped()
        }
    }

    private fun updatePermissionBanner() {
        binding.bannerPermission.visibility = if (isListenerEnabled()) View.GONE else View.VISIBLE
    }

    private fun reload() {
        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            val pkg = when (currentTab) {
                1 -> "com.whatsapp"
                2 -> "com.whatsapp.w4b"
                else -> null
            }
            val flow = when {
                currentQuery.isNotBlank() -> db.dao().searchFlow(currentQuery)
                pkg != null -> db.dao().byPackageFlow(pkg)
                else -> db.dao().allFlow()
            }
            flow.collectLatest { list ->
                // A home representa conversas, não mensagens: históricos importados ficam
                // no contexto da entidade; conversas reais agrupam por título legado + chave
                // técnica quando ela existe, juntando rows antigas sem conversationKey.
                val conversations = ConversationIdentity.conversationBuckets(list)
                    .mapNotNull { items ->
                        val newest = items.maxByOrNull { it.timestamp } ?: return@mapNotNull null
                        newest.copy(
                            sender = ConversationIdentity.canonicalSender(
                                newest.sender,
                                newest.packageName
                            )
                        )
                    }
                    .sortedByDescending { it.timestamp }
                adapter.submitList(conversations)
                binding.recycler.visibility = if (conversations.isEmpty()) View.GONE else View.VISIBLE
                binding.emptyState.visibility = if (conversations.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(this, NotifListenerService::class.java).flattenToString())
    }
}

class NotifAdapter(
    private val fmt: SimpleDateFormat,
    private val onClick: (NotifEntity) -> Unit,
    private val onSettings: (NotifEntity) -> Unit,
    private val onSpeak: (NotifEntity) -> Unit
) : ListAdapter<NotifEntity, NotifAdapter.VH>(DIFF) {

    inner class VH(val card: CardView) : RecyclerView.ViewHolder(card) {
        val avatar: TextView = card.findViewById(R.id.tvAvatar)
        val sender: TextView = card.findViewById(R.id.tvSender)
        val text: TextView = card.findViewById(R.id.tvText)
        val time: TextView = card.findViewById(R.id.tvTime)
        val badge: TextView = card.findViewById(R.id.tvBadge)
        val playText: ImageView = card.findViewById(R.id.btnPlayText)
        val settings: ImageView = card.findViewById(R.id.btnSettings)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notif, parent, false) as CardView
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.sender.text = item.sender
        holder.text.text = when {
            item.imagePath == null -> item.text
            MediaHints.startsWithImageEmoji(item.text) -> item.text
            else -> "📷 ${item.text}"
        }
        holder.time.text = fmt.format(Date(item.timestamp))

        holder.card.setOnClickListener { onClick(item) }
        val isVoiceMessage = item.audioPath != null || MediaHints.looksLikeVoiceMessage(item.text)
        holder.playText.visibility = if (isVoiceMessage) View.GONE else View.VISIBLE
        holder.playText.setOnClickListener { if (!isVoiceMessage) onSpeak(item) }
        holder.settings.setOnClickListener { onSettings(item) }

        holder.avatar.text = item.sender.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        if (item.packageName == "com.whatsapp.w4b") {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = "BIZ"
        } else {
            holder.badge.visibility = View.GONE
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<NotifEntity>() {
            override fun areItemsTheSame(a: NotifEntity, b: NotifEntity) = a.id == b.id
            override fun areContentsTheSame(a: NotifEntity, b: NotifEntity) = a == b
        }
    }
}
