package br.com.wanotifkeeper

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import br.com.wanotifkeeper.databinding.ActivityEntitiesBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class EntitiesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEntitiesBinding
    private val memory by lazy { MemoryRepository(this) }
    private val db by lazy { NotifDatabase.get(this) }
    private lateinit var adapter: EntityAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEntitiesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddEntity.setOnClickListener { showCreateEntityDialog() }

        adapter = EntityAdapter { entity ->
            startActivity(EntityDetailActivity.intent(this, entity.id))
        }
        binding.recyclerEntities.layoutManager = LinearLayoutManager(this)
        binding.recyclerEntities.adapter = adapter

        lifecycleScope.launch {
            db.memory().entitiesFlow().collectLatest { entities ->
                adapter.submitList(entities)
                binding.emptyEntities.visibility =
                    if (entities.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun showCreateEntityDialog() {
        val input = EditText(this).apply {
            hint = "Ex.: Cliente Alpha, Projeto Beta"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(48, 12, 48, 0)
        }

        AlertDialog.Builder(this)
            .setTitle("Nova entidade")
            .setMessage("Uma entidade pode reunir vários números, grupos e conversas.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val id = memory.createEntity(name)
                        startActivity(EntityDetailActivity.intent(this@EntitiesActivity, id))
                    }
                }
            }
            .show()
    }
}

private class EntityAdapter(
    private val onClick: (MemoryEntity) -> Unit
) : ListAdapter<MemoryEntity, EntityAdapter.VH>(DIFF) {

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        val name: TextView = card.findViewById(R.id.tvEntityName)
        val kind: TextView = card.findViewById(R.id.tvEntityKind)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_entity, parent, false) as MaterialCardView
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entity = getItem(position)
        holder.name.text = entity.name
        holder.kind.text = when (entity.kind) {
            "PROJECT" -> "Projeto"
            "ORGANIZATION" -> "Organização"
            else -> "Pessoa / entidade"
        }
        holder.card.setOnClickListener { onClick(entity) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MemoryEntity>() {
            override fun areItemsTheSame(a: MemoryEntity, b: MemoryEntity) = a.id == b.id
            override fun areContentsTheSame(a: MemoryEntity, b: MemoryEntity) = a == b
        }
    }
}
