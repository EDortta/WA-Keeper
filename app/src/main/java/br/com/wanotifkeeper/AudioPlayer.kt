package br.com.wanotifkeeper

import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Toca os áudios recebidos em voz alta (canal de assistente, respeita o áudio do carro).
 * Fila serial: áudios em sequência tocam um após o outro, sem se cortarem.
 */
class AudioPlayer {

    private val queue = ArrayDeque<String>()
    private var player: MediaPlayer? = null
    private var playing = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Synchronized
    fun play(path: String) {
        queue.addLast(path)
        if (!playing) startNext()
    }

    @Synchronized
    private fun startNext() {
        val path = queue.removeFirstOrNull()
        if (path == null) { playing = false; return }
        playing = true
        player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setOnCompletionListener { onFinished() }
            setOnErrorListener { _, _, _ -> onFinished(); true }
            setOnPreparedListener { start() }
            runCatching {
                setDataSource(path)
                prepareAsync()
            }.onFailure { onFinished() }
        }
    }

    @Synchronized
    private fun onFinished() {
        runCatching { player?.release() }
        player = null
        startNext()
    }

    @Synchronized
    fun shutdown() {
        queue.clear()
        runCatching { player?.release() }
        player = null
        playing = false
    }
}
