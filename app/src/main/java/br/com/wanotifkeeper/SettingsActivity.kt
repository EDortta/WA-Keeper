package br.com.wanotifkeeper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import br.com.wanotifkeeper.databinding.ActivitySettingsBinding

/** Ajustes do app — hoje, os switches de leitura em voz alta em movimento. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swWhatsapp.isChecked = Prefs.isTtsEnabled(this, Prefs.PKG_WHATSAPP)
        binding.swBusiness.isChecked = Prefs.isTtsEnabled(this, Prefs.PKG_BUSINESS)

        binding.swWhatsapp.setOnCheckedChangeListener { _, checked ->
            Prefs.setTtsEnabled(this, Prefs.PKG_WHATSAPP, checked)
        }
        binding.swBusiness.setOnCheckedChangeListener { _, checked ->
            Prefs.setTtsEnabled(this, Prefs.PKG_BUSINESS, checked)
        }
    }
}
