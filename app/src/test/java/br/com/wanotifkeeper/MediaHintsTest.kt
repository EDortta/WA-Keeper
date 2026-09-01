package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
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
    fun `palavra colada em outra nao conta como imagem`() {
        // A regra NÃO é fronteira de palavra: é o texto inteiro ser o rótulo. Estes casos
        // caem pelo mesmo motivo que "me manda a foto", e é isso que o teste documenta.
        assertFalse(MediaHints.looksLikeImageMessage("fotocópia"))
        assertFalse(MediaHints.looksLikeImageMessage("fotógrafo"))
    }

    // --- concílio, lente adversarial: emoji digitado por uma pessoa ---

    @Test
    fun `emoji de camera no meio da frase nao conta como imagem`() {
        assertFalse(MediaHints.looksLikeImageMessage("comprei uma câmera nova 📸"))
        assertFalse(MediaHints.looksLikeImageMessage("que saudade daquele lugar 🏞"))
        assertFalse(MediaHints.looksLikeImageMessage("olha só 📷"))
    }

    // --- concílio, lente adversarial: notificação de grupo traz "Remetente: " ---

    @Test
    fun `rotulo com prefixo de remetente conta como imagem em grupo`() {
        assertTrue(MediaHints.looksLikeImageMessage("Ana: Foto", isGroup = true))
        assertTrue(MediaHints.looksLikeImageMessage("Você: 2 fotos", isGroup = true))
        assertTrue(MediaHints.looksLikeImageMessage("Ana: 📷 Foto", isGroup = true))
    }

    @Test
    fun `frase com prefixo de remetente nao conta como imagem em grupo`() {
        assertFalse(MediaHints.looksLikeImageMessage("Ana: me manda a foto", isGroup = true))
        assertFalse(MediaHints.looksLikeImageMessage("Ana: comprei uma câmera nova 📸", isGroup = true))
    }

    // --- S1 do concílio (rodada 2): o strip de prefixo reabria o buraco do BLOCKER ---

    @Test
    fun `texto que parece prefixo nao vira imagem em conversa 1-a-1`() {
        // Fora de grupo o WhatsApp não prefixa o remetente: qualquer "algo: " no início é
        // texto digitado por uma pessoa. Removê-lo fazia "Ana: fotos" varrer o diretório e
        // anexar foto alheia a uma mensagem de TEXTO — o que a #17 proíbe.
        assertFalse(MediaHints.looksLikeImageMessage("Ana: fotos"))
        assertFalse(MediaHints.looksLikeImageMessage("Olha isso: foto"))
        assertFalse(MediaHints.looksLikeImageMessage("Assunto: fotos"))
    }

    @Test
    fun `rotulo puro continua contando fora de grupo`() {
        // O caminho 1-a-1 real, confirmado no aparelho: "📷 Foto" sem prefixo nenhum.
        assertTrue(MediaHints.looksLikeImageMessage("📷 Foto"))
        assertTrue(MediaHints.looksLikeImageMessage("Foto"))
        assertTrue(MediaHints.looksLikeImageMessage("2 fotos"))
    }

    @Test
    fun `o padrao e o lado seguro`() {
        // Sem informação de grupo, não relaxa: o default tem que ser o comportamento estrito.
        assertEquals(
            MediaHints.looksLikeImageMessage("Ana: fotos"),
            MediaHints.looksLikeImageMessage("Ana: fotos", isGroup = false)
        )
        assertFalse(MediaHints.looksLikeImageMessage("Ana: fotos", isGroup = false))
    }

    // --- concílio, lente adversarial: rótulo de OUTRAS mídias não é imagem ---

    @Test
    fun `rotulo de outra midia nao conta como imagem`() {
        assertFalse(MediaHints.looksLikeImageMessage("🎥 Vídeo"))
        assertFalse(MediaHints.looksLikeImageMessage("🎬 GIF"))
        assertFalse(MediaHints.looksLikeImageMessage("📄 Documento"))
        assertFalse(MediaHints.looksLikeImageMessage("Figurinha"))
        assertFalse(MediaHints.looksLikeImageMessage("Sticker"))
    }

    // --- concílio, lente adversarial: separador e caixa ---

    @Test
    fun `variantes de escrita do rotulo contam como imagem`() {
        assertTrue(MediaHints.looksLikeImageMessage("1 foto"))
        assertTrue(MediaHints.looksLikeImageMessage("2\u00A0fotos"))   // NBSP, e não espaço
        assertTrue(MediaHints.looksLikeImageMessage("IMÁGENES"))
        assertTrue(MediaHints.looksLikeImageMessage("\u200EFoto"))     // marca bidi invisível
    }

    @Test
    fun `texto comum nao conta como imagem`() {
        assertFalse(MediaHints.looksLikeImageMessage("bom dia"))
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
