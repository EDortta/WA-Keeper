package br.com.wanotifkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guarda de regressão do lead 1 da revisão da PR #20: o indício de imagem era
 * `containsMatchIn` sobre `foto|imagem|image|photo|picture|fotografia`, então qualquer
 * mensagem de TEXTO que mencionasse a palavra disparava a varredura do diretório de mídia
 * e podia anexar uma imagem alheia que caísse na mesma janela de tempo — exatamente o que
 * a #17 proíbe: *"Não anexar imagem antiga a uma mensagem de texto por coincidência
 * temporal."*
 */
class MediaHintsTest {

    // --- o que TEM que continuar disparando o fallback de imagem ---

    @Test
    fun `rotulo de foto com emoji conta como imagem`() {
        assertTrue(MediaHints.looksLikeImageMessage("📷 Foto"))
        assertTrue(MediaHints.looksLikeImageMessage("📷 Photo"))
        assertTrue(MediaHints.looksLikeImageMessage("📷 Imagen"))
    }

    @Test
    fun `emoji com legenda continua contando como imagem`() {
        assertTrue(MediaHints.looksLikeImageMessage("📷 olha o bolo que ficou pronto"))
    }

    @Test
    fun `rotulo sozinho sem emoji conta como imagem`() {
        assertTrue(MediaHints.looksLikeImageMessage("Foto"))
        assertTrue(MediaHints.looksLikeImageMessage("foto"))
        assertTrue(MediaHints.looksLikeImageMessage("  Imagem  "))
        assertTrue(MediaHints.looksLikeImageMessage("Photo"))
    }

    @Test
    fun `rotulo agrupado conta como imagem`() {
        assertTrue(MediaHints.looksLikeImageMessage("2 fotos"))
        assertTrue(MediaHints.looksLikeImageMessage("13 photos"))
        assertTrue(MediaHints.looksLikeImageMessage("3 imágenes"))
    }

    // --- o bug: mensagem de TEXTO que só menciona a palavra ---

    @Test
    fun `mensagem de texto pedindo foto nao conta como imagem`() {
        assertFalse(MediaHints.looksLikeImageMessage("me manda a foto"))
        assertFalse(MediaHints.looksLikeImageMessage("você viu aquela foto?"))
        assertFalse(MediaHints.looksLikeImageMessage("send me the picture please"))
        assertFalse(MediaHints.looksLikeImageMessage("a imagem do contrato ficou boa"))
        assertFalse(MediaHints.looksLikeImageMessage("mándame la imagen"))
    }

    @Test
    fun `palavra dentro de outra palavra nao conta como imagem`() {
        assertFalse(MediaHints.looksLikeImageMessage("fotocópia"))
        assertFalse(MediaHints.looksLikeImageMessage("fotógrafo"))
        assertFalse(MediaHints.looksLikeImageMessage("imagens é o que não falta por aqui"))
    }

    @Test
    fun `texto comum nao conta como imagem`() {
        assertFalse(MediaHints.looksLikeImageMessage("bom dia"))
        assertFalse(MediaHints.looksLikeImageMessage(""))
    }

    // --- voz: o comportamento não pode ter mudado nesta branch ---

    @Test
    fun `mensagem de voz continua sendo reconhecida`() {
        assertTrue(MediaHints.looksLikeVoiceMessage("🎤 Mensagem de voz"))
        assertTrue(MediaHints.looksLikeVoiceMessage("Voice message"))
        assertTrue(MediaHints.looksLikeVoiceMessage("mensaje de voz (0:07)"))
    }

    @Test
    fun `mensagem de voz e imagem nao se confundem`() {
        assertFalse(MediaHints.looksLikeImageMessage("🎤 Mensagem de voz"))
        assertFalse(MediaHints.looksLikeVoiceMessage("📷 Foto"))
    }
}
