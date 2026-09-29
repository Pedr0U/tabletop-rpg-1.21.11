package com.pedro.tabletoprpg;

import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 *
 * <p><b>28/09/2026 (id da pericia):</b> a pericia deixou de ser identificada
 * pelo nome e passou a ter um {@code id} estavel, como o atributo. Os testes
 * de rename foram invertidos: antes documentavam a perda de valor como
 * "comportamento conhecido", agora exigem que o valor e o atributo sobrevivam.
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
                    new SheetModel.PericiaDef("pericia_1", "Acrobacia", "destreza"),
                    new SheetModel.PericiaDef("pericia_2", "Persuasao", "forca")),
            // 28/09/2026: limites NAO PADRAO de proposito. Com o padrao (-30/30/30)
            // o round-trip passaria mesmo se os tres VAR_INT fossem lidos na
            // posicao errada, porque o encoder e o decoder leriam o mesmo zero nas
            // duas pontas. Com -5/12/7, um deslocamento de uma posicao troca os
            // numeros e o assertEquals falha.
            -5, 12, 7);

    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    SheetModel.STREAM_CODEC.encode(buf, original);
    SheetModel decoded = SheetModel.STREAM_CODEC.decode(buf);

    assertEquals(original, decoded);
}

/**
 * Os tres limites de valor tem de atravessar a rede na posicao certa, e um
 * save gravado antes deles tem de abrir no padrao.
 *
 * <p>Existe separado do {@link #roundTrip()} porque o que ele prova nao e a
 * simetria do par encoder/decoder, e sim a <b>compatibilidade de um lado so</b>:
 * o NBT antigo nao tem os campos, entao o caminho exercitado e o
 * {@code optionalFieldOf} com o padrao. Sem o padrao, toda ficha salva antes de
 * 28/09/2026 perderia os limites e o Mestre veria a regra voltar a -30/30/30 sem
 * ele ter mudado nada.
 */
@Test
@DisplayName("Limites de valor sobrevivem a rede e o NBT antigo abre no padrao")
void valueLimitsTravelAndLegacyNbtFallsBackToDefaults() {
    SheetModel custom = SheetModel.defaults().withValueLimits(-5, 12, 7);

    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    SheetModel.STREAM_CODEC.encode(buf, custom);
    SheetModel streamed = SheetModel.STREAM_CODEC.decode(buf);
    assertEquals(-5, streamed.attributeValueMin(), "o piso do atributo nao sobreviveu a rede");
    assertEquals(12, streamed.attributeValueMax(), "o teto do atributo nao sobreviveu a rede");
    assertEquals(7, streamed.periciaValueMax(), "o teto da pericia nao sobreviveu a rede");

    // NBT sem os tres campos: e a ficha de um mundo que ja rodava antes da
    // mudanca. O round-trip completo do modelo tambem tem de pasar, porque e o
    // mesmo codec gravado que a tela do editor vai ler.
    CompoundTag empty = new CompoundTag();
    SheetModel parsed = SheetModel.CODEC.parse(NbtOps.INSTANCE, empty).getOrThrow();
    assertEquals(SheetModel.DEFAULT_ATTRIBUTE_VALUE_MIN, parsed.attributeValueMin());
    assertEquals(SheetModel.DEFAULT_ATTRIBUTE_VALUE_MAX, parsed.attributeValueMax());
    assertEquals(SheetModel.DEFAULT_PERICIA_VALUE_MAX, parsed.periciaValueMax());

    // E o inverso: um NBT com os campos tem de devolver exatamente eles.
    Tag saved = SheetModel.CODEC.encodeStart(NbtOps.INSTANCE, custom).getOrThrow();
    SheetModel reread = SheetModel.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
    assertEquals(custom, reread, "os limites nao sobreviveram ao NBT");
}

/**
 * O piso e o teto podem chegar invertidos de um NBT editado a mao ou de um
 * payload forjado. Invertido nao pode significar "intervalo vazio", senao o clamp
 * da ficha cortaria <b>todo</b> valor para o piso, inclusive os que estavam
 * certos -- inclusive o proprio piso, que o Mestre veria saltar de 0 para 10.
 */
@Test
@DisplayName("Teto abaixo do piso vira o proprio piso, e nao um intervalo vazio")
void invertedLimitsCollapseToTheFloor() {
    SheetModel inverted = SheetModel.defaults().withValueLimits(10, 5, 30);
    assertEquals(10, inverted.attributeValueMin());
    assertEquals(10, inverted.attributeValueMax(),
            "max < min nao foi corrigido: o intervalo ficou vazio");

    // E o efeito observavel: o intervalo continua existindo, entao o clamp
    // leva ao piso em vez de estourar ou apagar o atributo. Com [10,10] o 7
    // esta fora, e 10 e o unico lugar legitimo para ele parar.
    String id = SheetModel.defaults().attributes().get(0).id();
    SheetData sheet = SheetData.defaultSheet("Heroi").withField(id, "7");
    assertEquals(10, inverted.align(sheet).attributeValue(id),
            "o valor abaixo do piso nao foi cortado para o piso");

    // E o piso em si sobrevive, que e o que o Mestre nota: se o intervalo
    // invertido virasse "vazio", o atributo dele pularia de 10 para outro numero.
    assertEquals(10, inverted.align(sheet.withField(id, "10")).attributeValue(id),
            "o proprio piso nao sobreviveu ao intervalo invertido");

    // O teto da pericia tem piso 0 fixo, entao um teto negativo vira 0 e nao
    // "todos os valores negativos": o intervalo tem de continuar existindo.
    SheetModel negativePericia = SheetModel.defaults().withValueLimits(-30, 30, -5);
    assertEquals(0, negativePericia.periciaValueMax(),
            "teto de pericia negativo nao foi cortado no piso 0");
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
                List.of(new SheetModel.PericiaDef("pericia_1", "X", "a"),
                        new SheetModel.PericiaDef("PERICIA_1", "x", "a")),
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MIN,
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MAX,
                SheetModel.DEFAULT_PERICIA_VALUE_MAX);

        assertTrue(tooMany.attributeCount() <= SheetModel.MAX_ATTRIBUTES,
                "atributos acima do teto");
        assertTrue(tooMany.periciaCount() <= SheetModel.MAX_PERICIAS,
                "pericias acima do teto");
        // "A" e "a" colidem na deduplicacao por id em caixa baixa; as duas
        // pericias tambem colidem, porque e o id que agora e a identidade.
        assertEquals(1, tooMany.attributeCount());
        assertEquals(1, tooMany.periciaCount());
        assertTrue(tooMany.nameLabel().length() <= SheetModel.LABEL_MAX);
    }

    /**
     * <b>REGRA 1 no modelo.</b> Um modelo (ou ficha) gravado antes dos ids chega
     * com as pericias sem id. Deduplicar antes de preencher faria todas elas
     * disputarem o mesmo id {@code ""}, uma venceria e a lista <b>colapsaria
     * para uma so</b>: sem erro, sem log, e a lista inteira do Mestre
     * desapareceria.
     */
    @Test
    @DisplayName("Pericias sem id nao colapsam: o id e preenchido antes da deduplicacao")
    void legacyPericiasWithoutIdDoNotCollapse() {
        SheetModel model = new SheetModel("Nome", "Raca", true, "Classe", "Bg",
                "HP", "Mana", true, "Nivel", "XP", SheetModel.XpMode.NUMBER,
                List.of(new SheetModel.AttributeDef("a", "A", "A")),
                List.of(new SheetModel.PericiaDef("", "X", "a"),
                        new SheetModel.PericiaDef("", "Y", "a"),
                        new SheetModel.PericiaDef("", "Z", "a")),
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MIN,
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MAX,
                SheetModel.DEFAULT_PERICIA_VALUE_MAX);

        assertEquals(3, model.periciaCount(), "as pericias sem id colapsaram em uma so");
        assertEquals("pericia_1", model.pericias().get(0).id());
        assertEquals("pericia_2", model.pericias().get(1).id());
        assertEquals("pericia_3", model.pericias().get(2).id());
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
     * <b>28/09/2026: o inverso do teste antigo.</b> Antes a pericia era
     * identificada pelo nome, entao renomear trocava a identidade dela e o valor
     * do jogador sumia em silencio. Este teste existe para falhar se essa
     * limitacao voltar: o id tem de atravessar o rename intacto, e o align tem
     * de casar por ele, levando junto o valor E o atributo que o jogador escolheu.
     */
    @Test
    @DisplayName("Renomear uma pericia preserva o valor e o atributo dela")
    void renamingPericiaKeepsValueAndAttribute() {
        SheetModel model = SheetModel.defaults();
        SheetModel.PericiaDef def = model.pericias().get(0);
        String id = def.id();
        String chosenAttribute = model.attributes().get(1).id();

        // O jogador investe na pericia e troca o atributo que ela soma.
        SheetData sheet = SheetData.defaultSheet("Heroi")
                .withPericiaValue(id, 5)
                .withPericiaAttribute(id, chosenAttribute);
        assertEquals(5, sheet.periciaById(id).value(), "o valor nao foi gravado na ficha de origem");
        assertEquals(chosenAttribute, sheet.periciaById(id).attributeId());

        // O Mestre renomeia a pericia no editor, mantendo o atributo do modelo.
        SheetModel renamed = model.withPericiaText(id, "Nome novo", def.attributeId());
        SheetData.Pericia found = renamed.align(sheet).periciaById(id);

        assertNotNull(found, "o align nao achou a pericia pelo id depois do rename");
        assertEquals("Nome novo", found.name());
        assertEquals(5, found.value(), "o valor da pericia se perdeu ao renomear");
        assertEquals(chosenAttribute, found.attributeId(),
                "o atributo escolhido pelo jogador se perdeu ao renomear");
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
     * O id da pericia e a identidade dela: nenhum caminho de edicao pode troca-lo.
     * O {@code removeAttribute} e' o caso perigoso, porque ele reconstroi a
     * lista de pericias - um id perdido ali zera o valor da pericia em todas as
     * fichas do mundo no proximo align.
     */
    @Test
    @DisplayName("O id da pericia sobrevive ao rename, a troca de atributo e ao removeAttribute")
    void periciaIdIsStable() {
        SheetModel model = SheetModel.defaults();
        SheetModel.PericiaDef def = model.pericias().get(0);
        String id = def.id();

        SheetModel renamed = model.withPericiaText(id, "Nome novo", model.attributes().get(1).id());
        assertEquals(id, renamed.periciaByName("Nome novo").id(), "o rename trocou o id");
        assertEquals(model.attributes().get(1).id(), renamed.periciaById(id).attributeId());

        SheetModel withoutAttribute = model.removeAttribute(model.attributes().get(1).id());
        assertEquals(model.periciaCount(), withoutAttribute.periciaCount());
        for (SheetModel.PericiaDef current : withoutAttribute.pericias()) {
            assertNotNull(withoutAttribute.periciaById(current.id()),
                    "removeAttribute perdeu o id de uma pericia: " + current.name());
        }

        // addPericia gera um id novo, e nao um id que ja esta em uso.
        SheetModel added = model.addPericia();
        assertEquals(model.periciaCount() + 1, added.periciaCount());
        String generated = added.pericias().get(added.periciaCount() - 1).id();
        assertTrue(generated.startsWith("pericia_"), "id gerado fora do formato: " + generated);
        for (SheetModel.PericiaDef current : model.pericias()) {
            assertFalse(current.id().equals(generated), "addPericia reusou o id " + generated);
        }
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
                List.of(model.pericias().get(0)),
                model.attributeValueMin(), model.attributeValueMax(), model.periciaValueMax());

        assertEquals(atMinimum,
                atMinimum.removeAttribute(atMinimum.attributes().get(0).id()),
                "removeAttribute desceu abaixo do minimo de atributos");
        assertEquals(atMinimum,
                atMinimum.removePericia(atMinimum.pericias().get(0).id()),
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

    /**
     * O id da pericia viaja em um dos seis campos de {@link SheetData#STREAM_CODEC}
     * (a lista conta como um campo so), entao o round-trip da ficha inteira e o
     * que garante que cliente e servidor leem a mesma coisa.
     *
     * <p><b>28/09/2026: os tres limites entram com valores NAO PADRAO</b> por
     * causa do mesmo motivo do {@link #roundTrip()}: a ficha que o
     * {@code defaultSheet} devolve tem -30/30/30, e com o padrao um
     * {@code assertEquals(original, decoded)} passaria mesmo se os tres
     * {@code VAR_INT} fossem lidos na posicao errada.
     */
    @Test
    @DisplayName("SheetData sobrevive a um round-trip pelo STREAM_CODEC")
    void sheetSurvivesStreamRoundTrip() {
        String periciaId = SheetModel.defaults().pericias().get(0).id();
        SheetData base = SheetData.defaultSheet("Heroi")
                .withField("level", "7")
                .withPericiaValue(periciaId, 5);
        // Os limites entram pelo construtor, e nao por um metodo de copia, porque
        // o unico caminho que os GRAVA em producao e o SheetModel.align (o
        // Mestre mudou a regra, oalign propaga para todas as fichas). Forjar
        // direto no construtor e o que testa o codec, e nao um caminho que a
        // tela usa.
        SheetData original = new SheetData(base.identity(), base.vitals(), base.progress(),
                base.attributes(), base.skills(), base.pericias(), -5, 12, 7);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SheetData.STREAM_CODEC.encode(buf, original);
        SheetData decoded = SheetData.STREAM_CODEC.decode(buf);

        assertEquals(periciaId, decoded.pericias().get(0).id(),
                "o id da pericia nao sobreviveu ao round-trip");
        assertEquals(5, decoded.periciaById(periciaId).value());
        assertEquals(7, decoded.getNumeric("level"));
        assertEquals(-5, decoded.attributeValueMin(), "o piso do atributo nao sobreviveu a rede");
        assertEquals(12, decoded.attributeValueMax(), "o teto do atributo nao sobreviveu a rede");
        assertEquals(7, decoded.periciaValueMax(), "o teto da pericia nao sobreviveu a rede");
        assertEquals(original, decoded);
    }

    /**
     * <b>28/09/2026: o clamp autoritativo, que e o ponto inteiro da mudanca.</b>
     * Baixar o limite no editor tem de <b>cortar o valor ja salvo</b>, e nao so
     * travar a seta da tela: quem decide e o construtor compacto da
     * {@link SheetData}, entao o corte acontece no {@code align} e tambem na
     * leitura do NBT, sem ninguem precisar lembrar de limitar.
     *
     * <p><b>Por que testar pelo align e nao por um {@code withField}:</b> o
     * {@code withField} ja entregaria o numero grande intacto (ele so converte o
     * texto), e o corte no align e justamente o caminho que roda quando o Mestre
     * aperta Salvar. O {@code withField} com valor grande logo depois de
     * exists para provar que o valor ate chega na ficha, e so o align corta.
     */
    @Test
    @DisplayName("Baixar o limite no editor corta o valor ja salvo, no piso e no teto")
    void alignClampsSavedValuesToTheNewLimits() {
        SheetModel model = SheetModel.defaults();
        String attributeId = model.attributes().get(0).id();
        String periciaId = model.pericias().get(0).id();

        SheetData generous = SheetData.defaultSheet("Heroi")
                .withField(attributeId, "28")
                .withPericiaValue(periciaId, 25);

        // A regra nova e mais apertada dos dois lados.
        SheetModel tight = model.withValueLimits(-5, 12, 7);
        SheetData clamped = tight.align(generous);

        assertEquals(12, clamped.attributeValue(attributeId),
                "o atributo acima do teto novo nao foi cortado");
        assertEquals(7, clamped.periciaById(periciaId).value(),
                "a pericia acima do teto novo nao foi cortada");

        // E o piso, que e a outra metade do intervalo: um atributo abaixo do novo
        // piso sobe, e um valor dentro do intervalo nao se mexe.
        SheetData negative = SheetData.defaultSheet("Heroi")
                .withField(attributeId, "-28")
                .withField(model.attributes().get(1).id(), "3");
        SheetData floored = tight.align(negative);
        assertEquals(-5, floored.attributeValue(attributeId),
                "o atributo abaixo do piso novo nao foi cortado");
        assertEquals(3, floored.attributeValue(model.attributes().get(1).id()),
                "um valor dentro do intervalo foi cortado");

        // A pericia tem piso 0 fixo, entao o teto novo nunca a torna negativa.
        assertTrue(tight.periciaValueMax() >= 0, "o teto da pericia ficou negativo");

        // Voltar a um limite largo NAO ressuscita o valor cortado: o corte ja
        // foi aplicado na ficha. E o esperado -- o Mestre leu o numero, e um
        // teto que sobe depois nao pode adivinhar o que o jogador pretendia.
        SheetData reopened = model.withValueLimits(-30, 30, 30).align(clamped);
        assertEquals(12, reopened.attributeValue(attributeId),
                "o valor cortado voltou sozinho quando o limite subiu");
    }

    /**
     * <b>REGRA 1 na ficha (o caminho do NBT antigo).</b> O {@code id} da pericia
     * e opcional no codec justamente para isto: uma lista gravada antes de
     * 28/09/2026 nao tem o campo, e se ele fosse obrigatorio o
     * {@code Codec.list} derrubaria a {@code SheetData.CODEC} inteira - o jogador
     * perderia vida, mana, atributos e skills, e nao so as pericias.
     *
     * <p><b>Por que o teste usa as 18 pericias e nao 3 soltas:</b> o id de uma
     * pericia antiga sai da <b>posicao</b> dela, entao so ha o que comparar se a
     * ordem for a mesma. E ela e: o {@code align} antigo percorria a lista do
     * modelo e gravava a ficha nessa ordem, e o modelo so cresce no fim (o
     * Mestre remove, mas nao reordena, e nao ha botao de mover). Com o modelo
     * intacto, posicao N da ficha antiga e pericia N do modelo.
     *
     * <p><b>Limitacao residual - os dois casos, e so um deles e perigoso:</b>
     * <ul>
     *   <li><b>Remocao pura nao desloca nada.</b> Os sobreviventes mantem o id
     *       e a ordem relativa, entao a posicao N da ficha antiga continua
     *       sendo {@code pericia_N} do modelo. A ficha alinhada fica com uma
     *       linha a menos, que e o efeito desejado da remocao.</li>
     *   <li><b>Remocao seguida de criacao com o jogador offline e o caso
     *       perigoso:</b> {@code addPericia} pega o menor N livre, que pode ser
     *       o de uma pericia removida, e a pericia nova nasce <b>herdando o
     *       valor da removida</b> em todas as fichas, porque o {@code align} casa
     *       por id. O Javadoc de {@code SheetModel.addPericia} diz isso com as
     *       mesmas palavras.</li>
     * </ul>
     * Nao ha como evitar a segunda situacao sem um contador de atribuicao que o
     * save antigo nao tem - e o preco de um valor trocado de pericia e menor que
     * o de uma ficha inteira derrubada. Nao ha teste "assertando" o defeito, pelo
     * mesmo motivo do {@link #generatedAttributeIdIsNeverOneAlreadyInUse}.
     */
    @Test
    @DisplayName("NBT antigo sem id de pericia: os valores sobrevivem e o id e preenchido")
    void legacySheetWithoutIdKeepsValues() {
        SheetModel model = SheetModel.defaults();

        CompoundTag tag = new CompoundTag();
        ListTag pericias = new ListTag();
        for (int i = 0; i < model.periciaCount(); i++) {
            SheetModel.PericiaDef def = model.pericias().get(i);
            // O valor e a posicao: qualquer um serve, o que importa e que o align
            // nao mexa nele.
            addLegacyPericia(pericias, def.name(), i + 1, def.attributeId());
        }
        tag.put("pericias", pericias);

        DataResult<SheetData> parsed = SheetData.CODEC.parse(NbtOps.INSTANCE, tag);
        SheetData decoded = parsed.getOrThrow();

        assertEquals(model.periciaCount(), decoded.pericias().size(),
                "as pericias antigas colapsaram: o id vazio entrou na deduplicacao");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < decoded.pericias().size(); i++) {
            SheetData.Pericia pericia = decoded.pericias().get(i);
            assertEquals("pericia_" + (i + 1), pericia.id(),
                    "o id nao foi preenchido na posicao " + i + " (" + pericia.name() + ")");
            assertTrue(ids.add(pericia.id()), "o preenchimento gerou o id repetido " + pericia.id());
        }

        // Nenhum valor pode mudar no align: a posicao N da ficha antiga e a
        // pericia N do modelo.
        SheetData aligned = model.align(decoded);
        for (int i = 0; i < model.periciaCount(); i++) {
            SheetModel.PericiaDef def = model.pericias().get(i);
            assertEquals(i + 1, aligned.periciaById(def.id()).value(),
                    "o align do modelo alterou o valor de " + def.name());
        }
    }

    /**
     * <b>GARANTIA REAL do id gerado do atributo</b>, e nada mais que isso: ele
     * nunca sai igual a um id que o modelo ainda tem, e dois atributos seguidos
     * recebem ids diferentes.
     *
     * <p><b>Limitacao residual, NAO coberta por teste porque nao e garantida:</b>
     * um id cujo atributo o Mestre ja removeu volta a estar livre, e o proximo
     * {@code addAttribute} pode reemiti-lo - herdando o valor do atributo
     * removido no {@code align}. O Javadoc de {@code SheetModel.addAttribute}
     * diz isso com as mesmas palavras. Nao ha teste "assertando" o defeito: um
     * teste que espera o reuso Transformaria o bug em comportamento esperado, que
     * e exatamente o que aconteceu com o rename da pericia antes do id existir.
     */
    @Test
    @DisplayName("addAttribute gera um id novo, nunca um id que o modelo ja tem")
    void generatedAttributeIdIsNeverOneAlreadyInUse() {
        SheetModel model = SheetModel.defaults();
        SheetModel one = model.addAttribute();
        SheetModel two = one.addAttribute();

        assertEquals(model.attributeCount() + 2, two.attributeCount());
        String first = one.attributes().get(one.attributeCount() - 1).id();
        String second = two.attributes().get(two.attributeCount() - 1).id();
        assertTrue(first.startsWith("attr_"), "id gerado fora do formato: " + first);
        assertTrue(second.startsWith("attr_"), "id gerado fora do formato: " + second);
        assertFalse(first.equals(second), "os dois atributos novos receberam o mesmo id");
        assertNull(model.attribute(first), "o id gerado ja existia no modelo de origem");
        assertNotNull(one.attribute(first));
        assertNotNull(two.attribute(second));
    }

    /**
     * <b>Lista mista: o preenchimento tem de continuar a numeracao.</b> Um save
     * editado a mao (ou um payload antigo) pode trazer as primeiras pericias com
     * id e as seguintes sem. O {@code freshPericiaId} pega o menor N livre da
     * lista <b>ja preenchida</b>, entao as tres sem id tem de virar
     * {@code pericia_3}, {@code pericia_4} e {@code pericia_5} - e nao
     * {@code pericia_1} de novo, que colidiria com um id ja gravado e faria a
     * deduplicacao descartar a entrada.
     *
     * <p>E o unico estado em que o numero importa: com a lista toda sem id o N
     * cresce sozinho, e com a lista toda preenchida nao ha nada a preencher.
     */
    @Test
    @DisplayName("Lista mista: as pericias sem id recebem ids novos, sem colidir com os ja gravados")
    void mixedPericiaListKeepsNumberingAfterExistingIds() {
        SheetModel model = new SheetModel("Nome", "Raca", true, "Classe", "Bg",
                "HP", "Mana", true, "Nivel", "XP", SheetModel.XpMode.NUMBER,
                List.of(new SheetModel.AttributeDef("a", "A", "A")),
                List.of(new SheetModel.PericiaDef("pericia_1", "X", "a"),
                        new SheetModel.PericiaDef("pericia_2", "Y", "a"),
                        new SheetModel.PericiaDef("", "Z", "a"),
                        new SheetModel.PericiaDef("", "W", "a"),
                        new SheetModel.PericiaDef("", "V", "a")),
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MIN,
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MAX,
                SheetModel.DEFAULT_PERICIA_VALUE_MAX);

        assertEquals(5, model.periciaCount(), "alguma pericia sem id foi descartada");
        assertEquals(List.of("pericia_1", "pericia_2", "pericia_3", "pericia_4", "pericia_5"),
                List.of(model.pericias().get(0).id(), model.pericias().get(1).id(),
                        model.pericias().get(2).id(), model.pericias().get(3).id(),
                        model.pericias().get(4).id()),
                "o preenchimento nao continuou a numeracao depois dos ids ja gravados");
        Set<String> modelIds = new HashSet<>();
        for (SheetModel.PericiaDef current : model.pericias()) {
            assertTrue(modelIds.add(current.id()),
                    "o preenchimento gerou o id repetido " + current.id());
        }

        // A mesma regra na ficha, que e onde a lista mista entra pelo NBT.
        SheetData base = SheetData.defaultSheet("Heroi");
        SheetData sheet = new SheetData(base.identity(), base.vitals(), base.progress(),
                base.attributes(), base.skills(),
                List.of(new SheetData.Pericia("pericia_1", "X", 1, "a"),
                        new SheetData.Pericia("pericia_2", "Y", 2, "a"),
                        new SheetData.Pericia("", "Z", 3, "a"),
                        new SheetData.Pericia("", "W", 4, "a"),
                        new SheetData.Pericia("", "V", 5, "a")),
                base.attributeValueMin(), base.attributeValueMax(), base.periciaValueMax());

        assertEquals(5, sheet.pericias().size(), "a ficha perdeu uma pericia sem id");
        assertEquals("pericia_3", sheet.pericias().get(2).id());
        assertEquals("pericia_4", sheet.pericias().get(3).id());
        assertEquals("pericia_5", sheet.pericias().get(4).id());
        assertEquals(3, sheet.pericias().get(2).value(), "o valor andou com o id preenchido");
        assertEquals(5, sheet.pericias().get(4).value(), "o valor andou com o id preenchido");
    }

    /**
     * <b>Fecha o ACHADO 1: o align nao pode casar por nome quando a ficha tem
     * id.</b> Aqui a ficha tem id, mas esse id <b>nao existe no modelo</b> - e o
     * nome dela e exatamente o nome de uma pericia do modelo. Se alguem
     * reintroduzir o fallback por nome fora da condicao de "ficha sem id", o
     * valor 5 seria transplantado para a linha errada da ficha, e este teste
     * quebra.
     */
    @Test
    @DisplayName("Ficha com id desconhecido: o valor e 0 e o align nao casa pelo nome")
    void unknownPericiaIdDoesNotFallBackToName() {
        SheetModel model = SheetModel.defaults();
        String idInModel = model.pericias().get(0).id();
        String nameInModel = model.pericias().get(0).name();

        SheetData base = SheetData.defaultSheet("Heroi");
        SheetData sheet = new SheetData(base.identity(), base.vitals(), base.progress(),
                base.attributes(), base.skills(),
                List.of(new SheetData.Pericia("pericia_99", nameInModel, 5,
                        model.attributes().get(0).id())),
                base.attributeValueMin(), base.attributeValueMax(), base.periciaValueMax());

        assertTrue(sheet.hasPericiaIds(), "a ficha tem id: e por isso que o nome nao pode casar");
        // A prova de que o nome casaria: por isso o fallback por nome seria um bug
        // aqui, e nao uma protecao.
        assertNotNull(sheet.periciaByNameOrLegacy(nameInModel),
                "o nome casaria, entao este teste nao esta mais provando nada");

        SheetData aligned = model.align(sheet);
        assertEquals(0, aligned.periciaById(idInModel).value(),
                "o align transplantou o valor de um id desconhecido pelo nome");
        for (SheetModel.PericiaDef def : model.pericias()) {
            assertEquals(0, aligned.periciaById(def.id()).value(),
                    "a pericia " + def.name() + " recebeu valor de um id que nao existe no modelo");
        }
    }

    /**
     * <b>Renomear duas vezes seguidas.</b> O primeiro rename ja e coberto acima;
     * este pega o segundo, que e onde a troca de nome atrapalha: renomear "Nome
     * novo" para outro nome precisa preservar o id <b>no segundo passo
     * tambem</b>.
     *
     * <p><b>28/09/2026: o nome repetido entra (decisao do Mestre).</b> Antes este
     * teste travava a recusa - o modelo devolvia a ficha intacta, e a tela
     * devolvia o texto antigo na caixa, de modo que o Mestre nem conseguia
     * digitar o nome. Agora o rename para o nome de outra pericia e aceito, as
     * duas linhas ficam com o mesmo texto e ids diferentes (a identidade e o id),
     * e quem impede de gravar duas linhas iguais e a tela, com o Salvar desligado
     * enquanto o nome repetido estiver na tela.
     */
    @Test
    @DisplayName("Renomear duas vezes: o id atravessa as duas e o nome repetido entra")
    void renamingTwiceKeepsIdAndAcceptsRepeatedName() {
        SheetModel model = SheetModel.defaults();
        SheetModel.PericiaDef def = model.pericias().get(0);
        String id = def.id();
        String takenName = model.pericias().get(1).name();
        SheetData sheet = SheetData.defaultSheet("Heroi").withPericiaValue(id, 5);

        SheetModel first = model.withPericiaText(id, "Nome um", def.attributeId());
        SheetModel second = first.withPericiaText(id, "Nome dois", def.attributeId());

        assertNotNull(second.periciaById(id), "o segundo rename perdeu a pericia");
        assertEquals("Nome dois", second.periciaById(id).name());
        assertEquals(5, second.align(sheet).periciaById(id).value(),
                "o valor se perdeu no segundo rename");

        // Nome que ja pertence a outra pericia: entra, e as duas linhas ficam com
        // o mesmo texto em ids diferentes.
        String takenId = model.pericias().get(1).id();
        SheetModel third = second.withPericiaText(id, takenName, def.attributeId());
        assertEquals(takenName, third.periciaById(id).name(),
                "o rename nao aceitou o nome que ja estava em uso");
        assertEquals(takenName, third.periciaById(takenId).name(),
                "a pericia que ja tinha esse nome perdeu o texto");
        assertNotEquals(takenId, third.periciaById(id).id(),
                "o nome repetido casou as duas pericias em uma so");
        assertEquals(5, third.align(sheet).periciaById(id).value(),
                "o valor se perdeu quando o nome repetido entrou");
    }

    /**
     * <b>Trava o comportamento POSICIONAL.</b> O teste de NBT antigo acima usa os
     * nomes do padrao, o que deixa a porta aberta para o casamento por nome: com
     * os nomes certos, os dois mecanismos dariam o mesmo resultado. Aqui os nomes
     * sao <b>lixo</b> ({@code "X1"}..{@code "X18"}), que nao casa com nada -
     * nem por nome exato nem por apelido - entao se o valor chega na linha certa
     * e porque o id foi preenchido <b>por posicao</b> e o align casou por id.
     *
     * <p><b>Por que nomes de lixo e nao nomes vazios:</b> o
     * {@code sanitizePericias} <b>descarta</b> a entrada de nome vazio, entao um
     * fixture de nomes vazios testaria a limpeza, nao a migracao.
     */
    @Test
    @DisplayName("NBT antigo com nomes de lixo: os valores sobrevivem por posicao, nao por nome")
    void legacySheetWithGarbageNamesKeepsValuesByPosition() {
        SheetModel model = SheetModel.defaults();

        CompoundTag tag = new CompoundTag();
        ListTag pericias = new ListTag();
        for (int i = 0; i < model.periciaCount(); i++) {
            SheetModel.PericiaDef def = model.pericias().get(i);
            // Nome que nao existe no modelo, nem como apelido antigo.
            addLegacyPericia(pericias, "X" + (i + 1), i + 1, def.attributeId());
        }
        tag.put("pericias", pericias);

        SheetData decoded = SheetData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertEquals(model.periciaCount(), decoded.pericias().size(),
                "as pericias de lixo foram descartadas pelo sanitizePericias");

        SheetData aligned = model.align(decoded);
        for (int i = 0; i < model.periciaCount(); i++) {
            SheetModel.PericiaDef def = model.pericias().get(i);
            assertEquals(i + 1, aligned.periciaById(def.id()).value(),
                    "o valor de " + def.name() + " nao sobreviveu pela posicao");
            // A prova de que o nome nao fez nada: a linha traz o nome do MODELO,
            // e o save trazia "X" + (i + 1).
            assertEquals(def.name(), aligned.periciaById(def.id()).name());
        }
    }

    /** Grava uma pericia no formato antigo: sem o campo {@code id}. */
    private static void addLegacyPericia(ListTag list, String name, int value, String attribute) {
        CompoundTag pericia = new CompoundTag();
        pericia.putString("name", name);
        pericia.putInt("value", value);
        pericia.putString("attribute", attribute);
        list.add(list.size(), pericia);
    }
}
