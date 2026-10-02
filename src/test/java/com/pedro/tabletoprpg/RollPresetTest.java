package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
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

    // --- formula com nome de atributo (02/10/2026) ---

    @Test
    @DisplayName("create aceita 1d6+Strength e guarda a formula com o nome")
    void createAcceptsAttributeName() throws Exception {
        // Este e o teste do bug que a jogadora reportou. A ordem das checagens em
        // `create` importava: o `DiceFormula.parse` rodava PRIMEIRO, sobre a formula
        // crua, e recusava `1d6+Strength` como sintaxe invalida -- o `FormulaResolver`
        // nunca chegava a ser chamado e o recurso ficava inalcancavel pela tela.
        RollPreset preset = RollPreset.create("Dano", "1d6+Strength", "red");
        assertEquals("Dano", preset.name());
        assertEquals("1d6+Strength", preset.formula());
    }

    @Test
    @DisplayName("o preset guarda o nome, nunca o valor nem o placeholder")
    void createKeepsTheNameNotTheValue() throws Exception {
        // Se guardasse o valor, o preset envelheceria junto com a ficha; se guardasse
        // o placeholder, a rolagem somaria zero sempre.
        RollPreset preset = RollPreset.create("Dano", "1d6+Strength", "white");
        assertFalse(preset.formula().contains("0"), preset.formula());
        assertFalse(preset.formula().contains("14"), preset.formula());
    }

    @Test
    @DisplayName("create aceita o nome por id, por label e sem acento")
    void createAcceptsEverySpellingOfTheAttribute() throws Exception {
        assertEquals("1d6+strength", RollPreset.create("A", "1d6+strength", "white").formula());
        assertEquals("1d6+STR", RollPreset.create("B", "1d6+STR", "white").formula());
        // O modelo padrao esta em ingles, entao o acento so e testavel com um modelo
        // que o tenha -- "forca" seria recusado por nao existir, e o teste passaria
        // pelo motivo errado. `SheetModelHolder` e um campo estatico: o `set` volta
        // para o padrao no fim, senao os testes seguintes herdariam o modelo trocado.
        SheetModelHolder.set(SheetModel.defaults().withAttributeText("strength", "FOR", "Força"));
        try {
            assertEquals("1d6+Força", RollPreset.create("C", "1d6+Força", "white").formula());
            assertEquals("1d6+FORCA", RollPreset.create("D", "1d6+FORCA", "white").formula());
        } finally {
            SheetModelHolder.set(SheetModel.defaults());
        }
    }

    @Test
    @DisplayName("create recusa nome de atributo que nao existe, com a lista de validos")
    void createRejectsUnknownAttributeName() {
        RollPreset.PresetException error = assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("Ruim", "1d6+CarismaMaximo", "red"));
        assertTrue(error.getMessage().contains("CarismaMaximo"), error.getMessage());
        assertTrue(error.getMessage().contains("Valid names"), error.getMessage());
    }

    @Test
    @DisplayName("create recusa nome de pericia: a formula aceita so atributo")
    void createRejectsPericiaName() {
        // Decisao do usuario em 02/10/2026. `Athletics` e pericia no modelo padrao,
        // entao agora e recusado no save -- e nao aceito aqui para so dar erro na
        // rolagem, depois que a jogadora achou que o preset estava valido.
        RollPreset.PresetException error = assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("Ruim", "1d6+Athletics", "red"));
        assertTrue(error.getMessage().contains("Athletics"), error.getMessage());
    }

    @Test
    @DisplayName("o erro de estrutura cita a formula que a jogadora digitou, nao o placeholder")
    void structureErrorQuotesTheTypedFormula() {
        // O parser so viu `1d6+0+`. Se a mensagem repetisse isso, a jogadora iria
        // procurar um zero que nunca escreveu. E o problema aqui e de leitura: o
        // ponto final do sinal e um erro comum de digitar.
        RollPreset.PresetException error = assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("Ruim", "1d6+Strength+", "red"));
        assertTrue(error.getMessage().contains("1d6+Strength+"), error.getMessage());
        assertFalse(error.getMessage().contains("1d6+0+"), error.getMessage());
    }

    @Test
    @DisplayName("create nao aceita formula com mais nomes que o teto do resolver")
    void createRejectsTooManyAttributeNames() {
        StringBuilder formula = new StringBuilder("1d6");
        for (int i = 0; i <= FormulaResolver.MAX_TOKENS; i++) {
            formula.append("+Strength");
        }
        // Sem o teto aqui, o save aceitaria e a rolagem recusaria a toda hora: o
        // preset pareceria valido na tela e falharia toda vez que fosse usado.
        assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("Longa", formula.toString(), "red"));
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
        // Sem cor: cai em WHITE. A cor "default" saiu em 01/10/2026.
        assertEquals(RollPresetColor.WHITE, RollPreset.create("C", "1d20", "").color());
        // Preset salvo antes da remocao, ainda com o id "default", tem de carregar.
        assertEquals(RollPresetColor.WHITE, RollPresetColor.idOrDefault("default"));
    }

    @Test
    @DisplayName("create recusa cor fora das 16 e lista as validas no erro")
    void createRejectsUnknownColor() {
        RollPreset.PresetException error = assertThrows(RollPreset.PresetException.class,
                () -> RollPreset.create("A", "1d20", "chartreuse"));
        assertTrue(error.getMessage().contains("light_gray"), error.getMessage());
        assertTrue(error.getMessage().contains("magenta"), error.getMessage());
    }

    @Test
    @DisplayName("as 16 cores existem, na ordem pedida, e nenhuma se repete")
    void colorPaletteIsComplete() {
        List<String> ids = RollPresetColor.ids();
        assertEquals(16, ids.size());
        assertEquals(ids.size(), ids.stream().distinct().count());
        // "default" saiu em 01/10/2026: marrom sem tingir parecia a cor "brown".
        assertFalse(ids.contains("default"), ids.toString());
        assertEquals(List.of("white", "light_gray", "gray", "black", "brown", "red", "orange",
                "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"),
                ids);
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
    @DisplayName("a lista inteira de presets sobrevive a ida e volta, NA ordem")
    void storeCodecRoundTrip() {
        // A ordem e o que o store guarda agora (01/10/2026): e ela que a lista de
        // preset da tela mostra e que as setas mexem. Um round-trip que devolvesse
        // os mesmos elementos em outra ordem ja teria perdido o dado gravado.
        List<RollPreset> list = List.of(
                new RollPreset("Zeta", "1d20+5", "red"),
                new RollPreset("Alfa", "2d6", "blue"),
                new RollPreset("Meio", "d8", "lime"));

        Tag tag = RollPresetStore.CODEC.encodeStart(NbtOps.INSTANCE, list).getOrThrow();
        List<RollPreset> back = RollPresetStore.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

        assertEquals(3, back.size());
        assertEquals(List.of("Zeta", "Alfa", "Meio"),
                back.stream().map(RollPreset::name).toList(),
                "a ordem da jogadora nao sobreviveu ao NBT");
        assertEquals("1d20+5", back.get(0).formula());
        assertEquals("blue", back.get(1).colorId());
    }

    @Test
    @DisplayName("NBT gravado no formato ANTIGO (mapa) ainda e lido, ordenado por nome")
    void storeCodecReadsLegacyMap() throws Exception {
        // Este e o teste da migracao: quem ja tinha preset salvo antes de 01/10/2026
        // tem um compound no NBT, e nao pode perder nada ao entrar no mundo.
        Map<String, RollPreset> legacy = new HashMap<>();
        legacy.put("ataque", new RollPreset("Ataque", "1d20+5", "red"));
        legacy.put("defesa", new RollPreset("Defesa", "2d6", "blue"));
        // O codec antigo, para escrever exatamente o formato que estava em disco.
        Tag tag = Codec.unboundedMap(Codec.STRING, RollPreset.CODEC)
                .encodeStart(NbtOps.INSTANCE, legacy).getOrThrow();

        List<RollPreset> back = RollPresetStore.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();

        assertEquals(2, back.size(), "o preset do formato antigo sumiu na migracao");
        // A migracao ordena por nome, que e a ordem que a versao anterior exibia.
        assertEquals(List.of("Ataque", "Defesa"),
                back.stream().map(RollPreset::name).toList());
        assertEquals("1d20+5", back.get(0).formula());
        assertEquals("blue", back.get(1).colorId());
    }

    @Test
    @DisplayName("o round-trip novo NAO volta pelo caminho do formato antigo")
    void storeCodecRoundTripIsNotAmbiguous() {
        // A lista e o mapa sao tags diferentes (lista contra compound). Se o novo
        // codec voltasse pelo alternativo, um preset gravado hoje poderia reaparecer
        // em outra ordem -- e a ordem gravada e o dado.
        List<RollPreset> list = List.of(
                new RollPreset("Zeta", "1d20", "red"),
                new RollPreset("Alfa", "1d20", "red"));

        Tag tag = RollPresetStore.CODEC.encodeStart(NbtOps.INSTANCE, list).getOrThrow();

        // O que foi gravado e uma LISTA, nao um compound: e isso que garante que a
        // leitura volte pelo codec principal e nao pela migracao.
        assertTrue(tag.getClass().getSimpleName().contains("List")
                        || tag.toString().startsWith("["),
                "o preset novo foi gravado fora do formato de lista: " + tag);
    }

    @Test
    @DisplayName("NBT com nome grande ou cor inventada nao derruba a carga: ele e cortado")
    void codecSanitizesInsteadOfThrowing() {
        // Um NBT editado a mao nao pode fazer o jogador nem entrar no mundo.
        RollPreset sujo = new RollPreset("x".repeat(300), "1d20+5", "rosa-choque");

        assertEquals(RollPreset.MAX_NAME, sujo.name().length());
        assertEquals(RollPresetColor.WHITE, sujo.color());
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
    @DisplayName("list sai na ordem em que a jogadora gravou, NAO em ordem de nome")
    void listKeepsInsertionOrder() throws Exception {
        // Mudanca de 01/10/2026: a ordem deixou de ser o alfabeto e passou a ser a
        // ordem da lista. Este e o teste que prende a mudanca -- e o que as setas da
        // tela gravam.
        UUID uuid = UUID.randomUUID();
        RollPresetStore.forget(uuid);
        try {
            RollPresetStore.put(uuid, RollPreset.create("Zeta", "1d20", "red"));
            RollPresetStore.put(uuid, RollPreset.create("alfa", "1d20", "red"));
            RollPresetStore.put(uuid, RollPreset.create("Meio", "1d20", "red"));

            // Zeta foi criado primeiro e continua primeiro, mesmo sendo o ultimo
            // no alfabeto.
            assertEquals(List.of("Zeta", "alfa", "Meio"),
                    RollPresetStore.list(uuid).stream().map(RollPreset::name).toList());

            // E as setas reordenam de verdade.
            assertTrue(RollPresetStore.move(uuid, 0, 2));
            assertEquals(List.of("alfa", "Meio", "Zeta"),
                    RollPresetStore.list(uuid).stream().map(RollPreset::name).toList());

            // indice fora da faixa nao troca nada: a GUI pode estar com lista velha.
            assertFalse(RollPresetStore.move(uuid, 0, 9));
            assertFalse(RollPresetStore.move(uuid, -1, 1));
            assertEquals(List.of("alfa", "Meio", "Zeta"),
                    RollPresetStore.list(uuid).stream().map(RollPreset::name).toList());
        } finally {
            RollPresetStore.forget(uuid);
        }
    }

    @Test
    @DisplayName("editar um preset nao muda a posicao dele na lista")
    void editingKeepsPosition() throws Exception {
        // Se editar devolvesse o preset para o fim, arrumar um erro de digitacao
        // custaria a posicao na tela. O nome mudar e o unico caso que reordena.
        UUID uuid = UUID.randomUUID();
        RollPresetStore.forget(uuid);
        try {
            RollPresetStore.put(uuid, RollPreset.create("Ataque", "1d20", "red"));
            RollPresetStore.put(uuid, RollPreset.create("Defesa", "2d6", "blue"));
            RollPresetStore.put(uuid, RollPreset.create("Fuga", "d8", "lime"));

            RollPresetStore.put(uuid, RollPreset.create("Ataque", "1d20+5", "red"));
            assertEquals(List.of("Ataque", "Defesa", "Fuga"),
                    RollPresetStore.list(uuid).stream().map(RollPreset::name).toList());
            assertEquals("1d20+5", RollPresetStore.find(uuid, "ataque").orElseThrow().formula());
        } finally {
            RollPresetStore.forget(uuid);
        }
    }

    @Test
    @DisplayName("indexOf devolve a posicao na ordem da lista, e -1 quando nao existe")
    void indexOfFollowsListOrder() throws Exception {
        UUID uuid = UUID.randomUUID();
        RollPresetStore.forget(uuid);
        try {
            RollPresetStore.put(uuid, RollPreset.create("Zeta", "1d20", "red"));
            RollPresetStore.put(uuid, RollPreset.create("Alfa", "1d20", "red"));

            assertEquals(0, RollPresetStore.indexOf(uuid, "zeta"));
            assertEquals(1, RollPresetStore.indexOf(uuid, "ALFA"));
            assertEquals(-1, RollPresetStore.indexOf(uuid, "Inexistente"));
            assertEquals(-1, RollPresetStore.indexOf(null, "Zeta"));
        } finally {
            RollPresetStore.forget(uuid);
        }
    }

    @Test
    @DisplayName("a lista devolvida e uma copia: mexer nela nao muda o cache")
    void listIsDefensiveCopy() throws Exception {
        // A tela reordena a lista local antes de pedir ao servidor. Se a devolvida
        // fosse a propria lista do cache, a ordem mudaria sem ninguem salvar e o
        // preset voltaria diferente do que foi persistido.
        UUID uuid = UUID.randomUUID();
        RollPresetStore.forget(uuid);
        try {
            RollPresetStore.put(uuid, RollPreset.create("Ataque", "1d20", "red"));

            RollPresetStore.list(uuid).clear();

            assertEquals(1, RollPresetStore.count(uuid));
            assertTrue(RollPresetStore.exists(uuid, "Ataque"));
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