package br.com.wanotifkeeper

import android.content.Context

/**
 * Preferências simples do app (SharedPreferences).
 *
 * Hoje guarda apenas os switches de leitura em voz alta em movimento — um por
 * conta do WhatsApp monitorada. Chave por pacote para não confundir normal e
 * Business.
 */
object Prefs {

    private const val FILE = "wa_keeper_prefs"
    private const val KEY_TTS_PREFIX = "tts_enabled_"

    const val PKG_WHATSAPP = "com.whatsapp"
    const val PKG_BUSINESS = "com.whatsapp.w4b"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Leitura em voz alta habilitada para este pacote? Desligado por padrão. */
    fun isTtsEnabled(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(KEY_TTS_PREFIX + packageName, false)

    fun setTtsEnabled(context: Context, packageName: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TTS_PREFIX + packageName, enabled).apply()
    }

    /** Alguma conta com leitura ligada — usado para evitar ligar sensores à toa. */
    fun anyTtsEnabled(context: Context): Boolean =
        isTtsEnabled(context, PKG_WHATSAPP) || isTtsEnabled(context, PKG_BUSINESS)
}
