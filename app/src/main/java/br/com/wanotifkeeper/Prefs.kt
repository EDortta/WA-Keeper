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
    private const val KEY_AUDIO_CAPTURE_PREFIX = "audio_capture_enabled_"
    private const val KEY_AUDIO_PLAY_MOTION = "audio_play_in_motion"
    private const val KEY_AUDIO_BLOCKED = "audio_blocked_senders"

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

    /** Guardar os áudios de voz recebidos desta conta (copia o .opus antes que apaguem). Desligado por padrão. */
    fun isAudioCaptureEnabled(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_CAPTURE_PREFIX + packageName, false)

    fun setAudioCaptureEnabled(context: Context, packageName: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_CAPTURE_PREFIX + packageName, enabled).apply()
    }

    /** Alguma conta com guarda de áudio ligada — usado para o aviso de permissão. */
    fun anyAudioCaptureEnabled(context: Context): Boolean =
        isAudioCaptureEnabled(context, PKG_WHATSAPP) || isAudioCaptureEnabled(context, PKG_BUSINESS)

    /** Contatos que recusaram a guarda de áudio (por remetente, como aparece no app). */
    fun isAudioBlocked(context: Context, sender: String): Boolean =
        prefs(context).getStringSet(KEY_AUDIO_BLOCKED, emptySet())?.contains(sender) == true

    fun setAudioBlocked(context: Context, sender: String, blocked: Boolean) {
        val current = prefs(context).getStringSet(KEY_AUDIO_BLOCKED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (blocked) current.add(sender) else current.remove(sender)
        prefs(context).edit().putStringSet(KEY_AUDIO_BLOCKED, current).apply()
    }

    /** Tocar o áudio recebido em voz alta quando em movimento. Desligado por padrão. */
    fun isAudioPlayInMotion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_PLAY_MOTION, false)

    fun setAudioPlayInMotion(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_PLAY_MOTION, enabled).apply()
    }
}
