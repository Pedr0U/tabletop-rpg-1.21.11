package com.pedro.tabletoprpg;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do {@link SheetModel} e do alinhamento de {@link SheetData}.
 *
 * <p><b>Por que estes testes existem:</b> o {@code STREAM_CODEC} de
 * {@code SheetModel} e um par de lambdas independentes (encoder e decoder), e
 * nada os amarra em tempo de compilacao. Uma versao anterior escrevia as listas
 * no inicio do encoder e as lia no fim do decoder; o mod compilava inteiro e a
 * falha so aparecia em runtime, quando um lado lia um comprimento de string no
 * lugar do valor. O round-trip abaixo e o que pega esse tipo de erro.
 */
class SheetModelCodecTest {

    /** Round-trip pelo codec de rede: encode e decode tem de devolver o mesmo modelo. */
    @Test
    @DisplayName("SheetModel sobrevive a um round-trip pelo STREAM_CODEC")
    void roundTrip() {
        SheetModel original = new SheetModel(
                "Nome", "Raca", true, "Classe", "Historia",
                "Vida", "Energia", false, "Nivel", "Experiencia",
                SheetModel.XpMode.TEXT,
                List.of(
                        new SheetModel.AttributeDef("forca", "FOR", "Forca"),
                        new SheetModel.AttributeDef("destreza", "DES", "Destreza")),
                List.of(
                        new SheetModel.PericiaDef("Acrobacia", "destreza"),
                        new SheetModel.PericiaDef("Persuasao", "forca")));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SheetModel.STREAM_CODEC.encode(buf, original);
        SheetModel decoded = SheetModel.STREAM_CODEC.decode(buf);

        assertEquals(original, decoded);
    }

    /**
     * O round-trip acima passa com o modelo do RECORD inteiro. Este teste pega o
     * caso em que so os valores mudam, que e o que o Mestre produz de verdade ao
     * renomear um campo ou trocar o modo do XP.
     */
    @Test
    @DisplayName("Round-trip preserva rotulos, modo de XP e listas")
    void roundTripPreservesEveryField() {
        SheetModel base = SheetModel.defaults();
        // Os ids do modelo padrao sao em ingles ("strength", "dexterity"...).
        // Pegar o id do proprio modelo, em vez de escrever umliteral, e o que
        // impede este teste de passar a falhar por um motivo que nao tem nada a
        // ver com o codec.
        String attributeId = base.attributes().get(0).id();

        SheetModel changed = base
                .withLabel("characterName", "Nome do Heroi")
                .withEnabled("race", false)
                .withEnabled("mana", true)
                .withXpMode(SheetModel.XpMode.HIDDEN)
                .withAttributeText(attributeId, "FOR", "Forca Bruta")
                .addPericia();

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SheetModel.STREAM_CODEC.encode(buf, changed);
        SheetModel decoded = SheetModel.STREAM_CODEC.decode(buf);

        assertEquals("Nome do Heroi", decoded.nameLabel());
        assertEquals("Forca Bruta", decoded.attributes().get(0).name());
        assertEquals(SheetModel.XpMode.HIDDEN, decoded.xp());
        assertEquals(base.periciaCount() + 1, decoded.periciaCount());
        assertEquals(changed, decoded);
    }

    /**
     * O construtor compacto e a unica validacao que roda antes do servidor usar o
     * modelo, porque o record e reconstruido na desserializacao. Se ele deixar de
     * cortar, um cliente forjado escreve no mundo.
     */
    @Test
    @DisplayName("O construtor compacto corta rotulos, duplicatas e listas grandes")
    void compactConstructorSanitises() {
        SheetModel tooMany = new SheetModel("Nome", "Raca", true, "Classe", "Bg",
                "HP", "Mana", true, "Nivel", "XP", SheetModel.XpMode.NUMBER,
                List.of(new SheetModel.AttributeDef("a", "A", "A"),
                        new SheetModel.AttributeDef("A", "B", "B")),
                List.of(new SheetModel.PericiaDef("X", "a"),
                        new SheetModel.PericiaDef("x", "a")));

        assertTrue(tooMany.attributeCount() <= SheetModel.MAX_ATTRIBUTES,
                "atributos acima do teto");
        assertTrue(tooMany.periciaCount() <= SheetModel.MAX_PERICIAS,
                "pericias acima do teto");
        // "A" e "a" colidem na deduplicacao por id em caixa baixa.
        assertEquals(1, tooMany.attributeCount());
        assertEquals(1, tooMany.periciaCount());
        assertTrue(tooMany.nameLabel().length() <= SheetModel.LABEL_MAX);
    }

    /** Renomear a sigla ou o nome de um atributo tem de preservar o valor. */
    @Test
    @DisplayName("Renomear um atributo preserva o valor na ficha")
    void renamingAttributeKeepsValue() {
        SheetModel model = SheetModel.defaults();
        String id = model.attributes().get(0).id();

        SheetData sheet = SheetData.defaultSheet("Heroi").withField(id, "7");
        assertEquals(7, sheet.attributeValue(id), "o valor nao foi gravado na ficha de origem");

        SheetModel renamed = model.withAttributeText(id, "XYZ", "Nome novo");
        assertEquals(7, renamed.align(sheet).attributeValue(id),
                "o valor do atributo se perdeu ao renomear");
    }

    /**
     * <b>Limitacao conhecida, escrita de proposito para nao ficar implicita.</b>
     * A pericia e identificada pelo NOME e nao por um id, entao renomear uma
     * pericia troca a identidade dela e o valor dela nao sobrevive. O teste
     * documenta o comportamento atual para que uma mudanca nesse ponto seja
     * consciente, e nao um "expected" que alguem le como comportamento desejado.
     */
    @Test
    @DisplayName("Renomear uma pericia troca a identidade dela (limitacao conhecida)")
    void renamingPericiaIsALossyIdentityChange() {
        SheetModel model = SheetModel.defaults();
        String name = model.pericias().get(0).name();
        String id = model.pericias().get(0).attributeId();

        SheetData sheet = SheetData.defaultSheet("Heroi").withPericiaValue(name, 5);
        assertEquals(5, sheet.periciaByName(name).value(), "o valor nao foi gravado na ficha de origem");

        SheetModel renamed = model.withPericiaText(name, "Nome novo", id);
        assertEquals(0, renamed.align(sheet).periciaByName("Nome novo").value(),
                "a identidade mudou com o nome, e o valor foi perdido junto");
    }

    /** O id do atributo tem de sobreviver a qualquer edicao de texto. */
    @Test
    @DisplayName("withAttributeText nao troca o id do atributo")
    void attributeIdIsStable() {
        SheetModel model = SheetModel.defaults();
        String idBefore = model.attributes().get(0).id();
        SheetModel after = model.withAttributeText(idBefore, "XYZ", "Nome novo");
        assertEquals(idBefore, after.attributes().get(0).id());
    }

    /**
     * Add/remove tem de respeitar o minimo, senao o construtor compacto reverte
     * sozinho e a tela ficaria mostrando um botao que "nao faz nada".
     */
    @Test
    @DisplayName("Nao da para remover o ultimo atributo nem a ultima pericia")
    void cannotRemoveBelowMinimum() {
        SheetModel model = SheetModel.defaults();
        // As DUAS listas precisam estar no minimo. Reduzir so os atributos nao
        // impede a remocao de uma pericia, porque a guarda olha o tamanho da
        // lista da propria pericia.
        SheetModel atMinimum = new SheetModel(model.nameLabel(), model.raceLabel(),
                model.raceEnabled(), model.classLabel(), model.backgroundLabel(),
                model.hpLabel(), model.manaLabel(), model.manaEnabled(), model.levelLabel(),
                model.xpLabel(), model.xp(),
                List.of(model.attributes().get(0)),
                List.of(model.pericias().get(0)));

        assertEquals(atMinimum,
                atMinimum.removeAttribute(atMinimum.attributes().get(0).id()),
                "removeAttribute desceu abaixo do minimo de atributos");
        assertEquals(atMinimum,
                atMinimum.removePericia(atMinimum.pericias().get(0).name()),
                "removePericia desceu abaixo do minimo de pericias");
    }

    /** Alinhar duas vezes nao pode mexer em nada: e o que torna o ciclo seguro. */
    @Test
    @DisplayName("align e idempotente")
    void alignIsIdempotent() {
        SheetModel model = SheetModel.defaults().addPericia();
        SheetData once = model.align(SheetData.defaultSheet("Heroi"));
        SheetData twice = model.align(once);
        assertEquals(once, twice);
        assertNotNull(twice);
    }
}
