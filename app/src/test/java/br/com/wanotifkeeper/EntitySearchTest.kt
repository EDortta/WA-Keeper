package br.com.wanotifkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitySearchTest {
    @Test fun unaccentedQueryFindsAccentedName() {
        assertTrue(EntitySearch.matches("joao", "João da Silva"))
        assertTrue(EntitySearch.matches("Jose", "José"))
        assertTrue(EntitySearch.matches("marcia", "Márcia"))
    }

    @Test fun accentedQueryFindsUnaccentedName() {
        assertTrue(EntitySearch.matches("João", "JOAO"))
        assertTrue(EntitySearch.matches("ação", "ACAO"))
    }

    @Test fun searchesSubtitleAndPreservesNonMatches() {
        assertTrue(EntitySearch.matches("negocio", "Cliente", "Negócio"))
        assertFalse(EntitySearch.matches("pedro", "João", "WhatsApp"))
    }
}
