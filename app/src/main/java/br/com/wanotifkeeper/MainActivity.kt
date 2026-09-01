package br.com.wanotifkeeper

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.cardview.widget.CardView
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
import java.util.*

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQ_MIC = 7301

        /** Janela em que o pedido do botão mantém o microfone aberto. */
        const val DIRECT_COMMAND_WINDOW_MS = 20_000L

        /** Por quanto tempo a dica "Pode falar o comando" fica na tela. */
        const val MIC_HINT_MS = 6_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NotifAdapter
    private val db by lazy { NotifDatabase.get(this) }
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    private var currentTab = 0   // 0=Todos, 1=WhatsApp, 2=Business
    private var currentQuery = ""
    private var collectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar manual (NoActionBar theme)
        setSupportActionBar(null)

        // Impede o EditText de roubar foco ao abrir a activity ou mudar de aba
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        // Retenção: ruído legado e mensagens fora da janela saem já na abertura
        lifecycleScope.launch { Retention.purge(this@MainActivity, System.currentTimeMillis()) }

        adapter = NotifAdapter(
            fmt,
            onClick = { item ->
                startActivity(
                    Intent(this, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_ID, item.id)
                )
            },
            onSettings = { item ->
                startActivity(
                    Intent(this, RetentionActivity::class.java)
                        .putExtra(RetentionActivity.EXTRA_SENDER, item.sender)
                )
            }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null

        // Ajustes
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnMic.setOnClickListener { onMicTapped() }

        // Banner de permissão
        updatePermissionBanner()
        binding.bannerPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Tabs
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                reload()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Search — universal (sempre busca em todos os pacotes)
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
    }

    /**
     * Botão de microfone: caminho direto para falar um comando, sem depender de a palavra de
     * ativação ser ouvida corretamente pelo reconhecedor on-device.
     *
     * Não liga o interruptor mestre por conta própria: se os comandos de voz estão desligados,
     * isso foi uma escolha, e o botão leva aos Ajustes em vez de desfazê-la em silêncio.
     */
    private fun onMicTapped() {
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
            // O motor de voz vive dentro do NotificationListenerService: sem o acesso a
            // notificações concedido, o serviço não está de pé e não há quem ouça.
            Toast.makeText(this, "Ative o acesso a notificações para usar comandos de voz", Toast.LENGTH_LONG).show()
            return
        }

        // O serviço observa esta chave e reage na hora — ver NotifListenerService.voicePrefsListener.
        Prefs.setDirectCommandUntil(this, System.currentTimeMillis() + DIRECT_COMMAND_WINDOW_MS)
        showMicHint()
    }

    private fun showMicHint() {
        binding.tvMicHint.visibility = View.VISIBLE
        binding.tvMicHint.removeCallbacks(hideMicHint)
        binding.tvMicHint.postDelayed(hideMicHint, MIC_HINT_MS)
    }

    private val hideMicHint = Runnable { binding.tvMicHint.visibility = View.GONE }

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
                currentQuery.isNotBlank() -> db.dao().searchFlow(currentQuery)          // search ignora tab
                pkg != null -> db.dao().byPackageFlow(pkg)
                else -> db.dao().allFlow()
            }
            flow.collectLatest { list ->
                adapter.submitList(list)
                binding.recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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
    private val onSettings: (NotifEntity) -> Unit
) : ListAdapter<NotifEntity, NotifAdapter.VH>(DIFF) {

    inner class VH(val card: CardView) : RecyclerView.ViewHolder(card) {
        val avatar: TextView = card.findViewById(R.id.tvAvatar)
        val sender: TextView = card.findViewById(R.id.tvSender)
        val text: TextView = card.findViewById(R.id.tvText)
        val time: TextView = card.findViewById(R.id.tvTime)
        val badge: TextView = card.findViewById(R.id.tvBadge)
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
        holder.text.text = if (item.imagePath != null) "📷 ${item.text}" else item.text
        holder.time.text = fmt.format(Date(item.timestamp))

        holder.card.setOnClickListener { onClick(item) }
        holder.settings.setOnClickListener { onSettings(item) }

        // Avatar: inicial do remetente
        holder.avatar.text = item.sender.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        // Badge Business
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
