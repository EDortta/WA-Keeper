package br.com.wanotifkeeper

import android.content.Context
import android.content.SharedPreferences

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
    // Público: quem precisa reagir na hora (ver [registerChangeListener]) filtra por essa chave
    // em vez de fazer polling — foi exatamente a falta disso que deixava o microfone ligado por
    // até VOICE_GATE_CHECK_MS depois do switch desligar (ver NotifListenerService).
    const val KEY_VOICE_COMMANDS_ENABLED = "voice_commands_enabled"
    private const val KEY_VOICE_DEFAULT_ACCOUNT = "voice_default_account_pkg"
    private const val KEY_MANUAL_LISTEN_UNTIL = "voice_manual_listen_until"

    /** Pedido de comando por botão de microfone. Público: o serviço observa a mudança. */
    const val KEY_DIRECT_COMMAND_UNTIL = "voice_direct_command_until"
    private const val KEY_MANUAL_DURATION_MINUTES = "voice_manual_duration_minutes"
    private const val KEY_MANUAL_TIMER_MOTION_SEEN = "voice_manual_timer_motion_seen"
    private const val KEY_SPEECH_PACK_MISSING = "voice_speech_pack_missing"

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

    /** Switch mestre dos comandos de voz. Desligado por padrão. */
    fun isVoiceCommandsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_COMMANDS_ENABLED, false)

    fun setVoiceCommandsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_COMMANDS_ENABLED, enabled).apply()
    }

    /** Conta padrão dos comandos de voz — a frase "pelo whatsapp secundário" troca só naquele comando. */
    fun voiceDefaultAccountPkg(context: Context): String =
        prefs(context).getString(KEY_VOICE_DEFAULT_ACCOUNT, PKG_WHATSAPP) ?: PKG_WHATSAPP

    fun setVoiceDefaultAccountPkg(context: Context, pkg: String) {
        prefs(context).edit().putString(KEY_VOICE_DEFAULT_ACCOUNT, pkg).apply()
    }

    /** Epoch millis até quando a escuta manual (timer) fica liberada, mesmo parado. 0 = nenhum timer ativo. */
    fun directCommandUntil(context: Context): Long =
        prefs(context).getLong(KEY_DIRECT_COMMAND_UNTIL, 0L)

    fun setDirectCommandUntil(context: Context, untilMillis: Long) {
        prefs(context).edit().putLong(KEY_DIRECT_COMMAND_UNTIL, untilMillis).apply()
    }

    fun manualListenUntil(context: Context): Long =
        prefs(context).getLong(KEY_MANUAL_LISTEN_UNTIL, 0L)

    fun setManualListenUntil(context: Context, untilMillis: Long) {
        prefs(context).edit()
            .putLong(KEY_MANUAL_LISTEN_UNTIL, untilMillis)
            .putBoolean(KEY_MANUAL_TIMER_MOTION_SEEN, false)
            .apply()
    }

    /** Qual duração (minutos) está armada agora — só pra destacar o botão certo em Ajustes. */
    fun manualDurationMinutes(context: Context): Int =
        prefs(context).getInt(KEY_MANUAL_DURATION_MINUTES, 0)

    fun setManualDurationMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_MANUAL_DURATION_MINUTES, minutes).apply()
    }

    /** Corte de segurança do timer manual: já viu movimento parar desde que o timer foi armado? */
    fun manualTimerMotionSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MANUAL_TIMER_MOTION_SEEN, false)

    fun setManualTimerMotionSeen(context: Context, seen: Boolean) {
        prefs(context).edit().putBoolean(KEY_MANUAL_TIMER_MOTION_SEEN, seen).apply()
    }

    /** O pacote de voz pt-BR offline não está disponível no aparelho — visto na última tentativa. */
    fun isSpeechPackMissing(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEECH_PACK_MISSING, false)

    fun setSpeechPackMissing(context: Context, missing: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEECH_PACK_MISSING, missing).apply()
    }

    /**
     * Deixa [listener] avisado, na hora, sempre que alguma chave mudar (ex.: o switch mestre
     * de comandos de voz sendo desligado em Ajustes enquanto o serviço já está de olho num
     * timer/polling mais lento). O framework guarda só uma referência fraca ao listener — quem
     * chama precisa manter uma referência forte viva (ex.: campo da classe) e desregistrar em
     * [unregisterChangeListener], ou o listener some sem aviso.
     */
    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
