package br.com.wanotifkeeper

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import br.com.wanotifkeeper.databinding.ActivityConversationBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationBinding
    private val audio by lazy { AudioArbiter.get(applicationContext) }
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sender = intent.getStringExtra(EXTRA_SENDER)?.takeIf { it.isNotBlank() }
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() }
        val conversationKey = intent.getStringExtra(EXTRA_CONVERSATION_KEY)?.takeIf { it.isNotBlank() }
        if (sender == null || pkg == null) {
            finish()
            return
        }

        binding.toolbar.title = sender
        binding.toolbar.subtitle = if (pkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSchedule.setOnClickListener {
            startActivity(ScheduledMessagesActivity.intent(this, pkg, sender))
        }
        binding.btnRetention.setOnClickListener {
            startActivity(
                Intent(this, RetentionActivity::class.java)
                    .putExtra(RetentionActivity.EXTRA_SENDER, sender)
            )
        }

        val adapter = ConversationMessageAdapter(
            fmt = fmt,
            onOpen = { item ->
                startActivity(
                    Intent(this, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_ID, item.id)
                )
            },
            onSpeak = { item -> audio.speakText(item.text) },
            onPlayAudio = { item -> item.audioPath?.let(audio::play) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null

        lifecycleScope.launch {
            NotifDatabase.get(this@ConversationActivity)
                .dao()
                .allFlow()
                .collectLatest { allMessages ->
                    val messages = allMessages
                        .asSequence()
                        .filter {
                            ConversationIdentity.sameConversation(
                                item = it,
                                packageName = pkg,
                                sender = sender,
                                conversationKey = conversationKey
                            )
                        }
                        .sortedBy { it.timestamp }
                        .toList()

                    adapter.submitList(messages) {
                        if (messages.isNotEmpty()) {
                            binding.recycler.scrollToPosition(messages.lastIndex)
                        }
                    }
                    binding.recycler.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
                    binding.emptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    companion object {
        private const val EXTRA_SENDER = "conversation_sender"
        private const val EXTRA_PACKAGE = "conversation_package"
        private const val EXTRA_CONVERSATION_KEY = "conversation_key"

        fun intent(
            context: Context,
            packageName: String,
            sender: String,
            conversationKey: String? = null
        ) =
            Intent(context, ConversationActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_CONVERSATION_KEY, conversationKey)
    }
}

private class ConversationMessageAdapter(
    private val fmt: SimpleDateFormat,
    private val onOpen: (NotifEntity) -> Unit,
    private val onSpeak: (NotifEntity) -> Unit,
    private val onPlayAudio: (NotifEntity) -> Unit
) : ListAdapter<NotifEntity, ConversationMessageAdapter.VH>(DIFF) {

    inner class VH(val card: CardView) : RecyclerView.ViewHolder(card) {
        val text: TextView = card.findViewById(R.id.tvText)
        val time: TextView = card.findViewById(R.id.tvTime)
        val image: ImageView = card.findViewById(R.id.imgAttachment)
        val play: ImageView = card.findViewById(R.id.btnPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation_message, parent, false) as CardView
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.text.text = item.text
        holder.time.text = fmt.format(Date(item.timestamp))

        val imageFile = item.imagePath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
        val bitmap = imageFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bitmap != null) {
            holder.image.setImageBitmap(bitmap)
            holder.image.visibility = View.VISIBLE
        } else {
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
        }

        val isAudio = item.audioPath?.let(::File)?.exists() == true
        holder.play.visibility = View.VISIBLE
        holder.play.contentDescription = if (isAudio) "Tocar áudio" else "Ouvir mensagem"
        holder.play.setOnClickListener {
            if (isAudio) onPlayAudio(item) else onSpeak(item)
        }
        holder.card.setOnClickListener { onOpen(item) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<NotifEntity>() {
            override fun areItemsTheSame(a: NotifEntity, b: NotifEntity) = a.id == b.id
            override fun areContentsTheSame(a: NotifEntity, b: NotifEntity) = a == b
        }
    }
}
