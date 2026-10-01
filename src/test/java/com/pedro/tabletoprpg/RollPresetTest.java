package com.pedro.tabletoprpg;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do {@link RollPreset} e do {@link RollPresetStore}.
 *
 * <p><b>Por que estes testes existem:</b> a validacao do preset e o ponto onde a
 * entrada da jogadora vira dados que o servidor vai rolar e salvar. Um parser que
 * aceite "1d20++" ou um codec que perda a cor nao quebram a compilacao: aparecem como
 * preset criado e depois ausente, ou como item que nao bate com o preset. Os testes
 * prendem as tres fronteiras: o que a criacao recusa, o que a chave de busca considera
 * o mesmo nome, e o que sobrevive a ida e volta pelo NBT.
 */
class RollPresetTest {

    // --- criacao e validacao ---

    @Test
    @DisplayName("create aceita formula valida e guarda o que foi digitado")
    void createAcceptsValidFormula() throws Exception {
        RollPreset preset = RollPreset.create("Ataque", "1d20+5", "red");
        assertEquals("Ataque", preset.name());
        assertEquals("1d20+5", preset.formula());
        assertEquals("red", preset.colorId());
        assertEquals(RollPresetColor.RED, preset.color());
    }

    @Test
    @DisplayName("create recusa formula que o DiceFormula nao reconhece, com a mensagem dele")
    void createRejectsGarbageFormula() {
        // Este e o requisito: a formula so e acusada no momento de salvar, e o erro
        // tem de ser o do parser, nao um texto novo.
        RollPreset.PresetException error = assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("Ruim", "1d20++", "red"));
        // A mensagem precisa ser a do DiceFormula, com a formula e a posicao: e ela que a
        // jogadora ve no chat quando acerta em Save. Nao se casa com o texto exato
        // porque o parser pode mudar aredito ("expected a number after '++'...").
        assertTrue(error.getMessage().contains("1d20++"), error.getMessage());
        assertTrue(error.getMessage().contains("position"), error.getMessage());
    }

    @Test
    @DisplayName("create recusa texto solto que nao e rolagem")
    void createRejectsPlainText() {
        assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("Ruim", "banana", "red"));
    }

    @Test
    @DisplayName("create remove os espacos da formula, como a rolagem da tela mostra")
    void createStripsFormulaSpaces() throws Exception {
        assertEquals("d20+d10+9", RollPreset.create("Espacada", "d20 + d10 + 9", "red").formula());
    }

    @Test
    @DisplayName("create recusa nome vazio e nome grande demais")
    void createRejectsBadName() {
        assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("   ", "1d20", "red"));
        assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("x".repeat(RollPreset.MAX_NAME + 1), "1d20", "red"));
    }

    @Test
    @DisplayName("create aceita cor em qualquer caixa e com espaco no lugar do _")
    void createAcceptsLooseColor() throws Exception {
        assertEquals(RollPresetColor.RED, RollPreset.create("A", "1d20", "RED").color());
        assertEquals(RollPresetColor.LIGHT_GRAY, RollPreset.create("B", "1d20", "light gray").color());
        assertEquals(RollPresetColor.DEFAULT, RollPreset.create("C", "1d20", "").color());
    }

    @Test
    @DisplayName("create recusa cor fora das 17 e lista as validas no erro")
    void createRejectsUnknownColor() {
        RollPreset.PresetException error = assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("A", "1d20", "chartreuse"));
        assertTrue(error.getMessage().contains("light_gray"), error.getMessage());
        assertTrue(error.getMessage().contains("magenta"), error.getMessage());
    }

    @Test
    @DisplayName("as 17 cores existem, na ordem pedida, e nenhuma se repete")
    void colorPaletteIsComplete() {
        List<String> ids = RollPresetColor.ids();
        assertEquals(17, ids.size());
        assertEquals(ids.size(), ids.stream().distinct().count());
        assertEquals("default", ids.get(0));
        assertEquals(List.of("white", "light_gray", "gray", "black", "brown", "red", "orange",
                "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"),
                ids.subList(1, 17));
    }

    // --- chave de busca ---

    @Test
    @DisplayName("a chave ignora caixa, acento e espaco: e o mesmo preset")
    void keyIsNormalized() throws Exception {
        assertEquals("ataque", RollPreset.create("Ataque", "1d20", "red").key());
        assertEquals("ataque", RollPreset.create("ATAQUE", "1d20", "red").key());
        assertEquals("fisico", RollPreset.create("  Fisico ", "1d20", "red").key());
        assertEquals("fisico", RollPreset.create("F\u00EDsico", "1d20", "red").key());
        assertEquals("golpe_duplo", RollPreset.create("Golpe Duplo", "1d20", "red").key());
    }

    // --- codec ---

    @Test
    @DisplayName("o preset sobrevive a ida e volta pelo NBT")
    void codecRoundTrip() {
        RollPreset preset = new RollPreset("Ataque", "1d20+5", "magenta");

        Tag tag = RollPreset.CODEC.encodeStart(NbtOps.INSTANCE, preset).getOrThrow();

        RollPreset back = RollPreset.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertEquals(preset, back);
        assertEquals("magenta", back.colorId());
    }

    @Test
    @DisplayName("o mapa inteiro de presets sobrevive a ida e volta pelo NBT")
    void storeCodecRoundTrip() {
        Map<String, RollPreset> map = new HashMap<>();
        map.put("ataque", new RollPreset("Ataque", "1d20+5", "red"));
        map.put("defesa", new RollPreset("Defesa", "2d6", "blue"));

        Tag tag = RollPresetStore.CODEC.encodeStart(NbtOps.INSTANCE, map).getOrThrow();
        Map<String, RollPreset> back = RollPresetStore.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

        assertEquals(2, back.size());
        assertEquals("1d20+5", back.get("ataque").formula());
        assertEquals("blue", back.get("defesa").colorId());
    }

    @Test
    @DisplayName("NBT com nome grande ou cor inventada nao derruba a carga: ele e cortado")
    void codecSanitizesInsteadOfThrowing() {
        // Um NBT editado a mao nao pode fazer o jogador nem entrar no mundo.
        RollPreset sujo = new RollPreset("x".repeat(300), "1d20+5", "rosa-choque");

        assertEquals(RollPreset.MAX_NAME, sujo.name().length());
        assertEquals(RollPresetColor.DEFAULT, sujo.color());
        assertEquals("1d20+5", sujo.formula());
    }

    // --- store ---

    @Test
    @DisplayName("store grava, acha pela chave normalizada, substitui e remove")
    void storeLifecycle() throws Exception {
        UUID uuid = UUID.randomUUID();
        RollPresetStore.forget(uuid);
        try {
            assertTrue(RollPresetStore.list(uuid).isEmpty());
            assertFalse(RollPresetStore.exists(uuid, "Ataque"));

            RollPresetStore.put(uuid, RollPreset.create("Ataque", "1d20+5", "red"));

            // Mesma chave em caixa diferente acha o preset, e nao cria um segundo.
            assertEquals("1d20+5", RollPresetStore.find(uuid, "ATAQUE").orElseThrow().formula());
            assertEquals(1, RollPresetStore.count(uuid));

            // Regra do preset: o mesmo nome substitui.
            RollPresetStore.put(uuid, RollPreset.create("Ataque", "2d10", "blue"));
            assertEquals(1, RollPresetStore.count(uuid));
            assertEquals("blue", RollPresetStore.find(uuid, "ataque").orElseThrow().colorId());

            // Deletar o preset nao tem nada a ver com o item: o store so esquece.
            assertTrue(RollPresetStore.remove(uuid, "Ataque").isPresent());
            assertFalse(RollPresetStore.exists(uuid, "Ataque"));
            assertTrue(RollPresetStore.remove(uuid, "Ataque").isEmpty());
        } finally {
            RollPresetStore.forget(uuid);
        }
    }

    @Test
    @DisplayName("list sai em ordem de nome, sem depender da ordem do mapa")
    void listIsSortedByName() throws Exception {
        UUID uuid = UUID.randomUUID();
        RollPresetStore.forget(uuid);
        try {
            RollPresetStore.put(uuid, RollPreset.create("Zeta", "1d20", "red"));
            RollPresetStore.put(uuid, RollPreset.create("alfa", "1d20", "red"));
            RollPresetStore.put(uuid, RollPreset.create("Meio", "1d20", "red"));

            assertEquals(List.of("alfa", "Meio", "Zeta"),
                    RollPresetStore.list(uuid).stream().map(RollPreset::name).toList());
        } finally {
            RollPresetStore.forget(uuid);
        }
    }

    @Test
    @DisplayName("os presets de uma jogadora nao vazam para a outra")
    void storeIsPerPlayer() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RollPresetStore.forget(a);
        RollPresetStore.forget(b);
        try {
            RollPresetStore.put(a, RollPreset.create("Ataque", "1d20+5", "red"));
            assertTrue(RollPresetStore.list(b).isEmpty());
            assertFalse(RollPresetStore.exists(b, "Ataque"));
        } finally {
            RollPresetStore.forget(a);
            RollPresetStore.forget(b);
        }
    }
}