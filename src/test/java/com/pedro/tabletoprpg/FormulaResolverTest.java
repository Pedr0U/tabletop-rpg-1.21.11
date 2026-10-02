package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do {@link FormulaResolver}: a troca do nome de um atributo pelo numero.
 *
 * <p><b>Por que estes testes existem.</b> A resolucao roda no meio do caminho da
 * rolagem, entao um erro dela nao quebra a compilacao: vira preset que rola o numero
 * errado, em silencio, todas as vezes que a jogadora usa. Os testes prendem as tres
 * fronteiras que importam: o dado {@code d20} nao pode ser lido como nome, o nome
 * desconhecido tem de ser recusado em vez de virar zero, e a formula resolvida ainda
 * tem de ser uma rolagem valida.
 */
class FormulaResolverTest {

    /** O modelo padrao: strength/dexterity/... (SheetModel:268-293). */
    private final SheetModel model = SheetModel.defaults();

    // --- a regra principal: nome vira o valor da ficha ---

    @Test
    @DisplayName("+Strength vira o valor cru do atributo da ficha")
    void resolvesAttributeByName() throws Exception {
        // Decisao do usuario em 01/10/2026: valor CRU, nao modificador. Forca 14
        // soma 14.
        SheetData sheet = sheetWith("strength", 14);
        assertEquals("1d6+14", FormulaResolver.resolve("1d6+Strength", sheet, model));
    }

    @Test
    @DisplayName("o mesmo atributo casa por id, por label e por nome")
    void resolvesAttributeByIdLabelOrName() throws Exception {
        SheetData sheet = sheetWith("strength", 3);
        // Os tres nomes do AttributeDef padrao: id "strength", label "STR", name
        // "Strength". A jogadora digita o que lembrar.
        assertEquals("7+3", FormulaResolver.resolve("7+strength", sheet, model));
        assertEquals("7+3", FormulaResolver.resolve("7+STR", sheet, model));
        assertEquals("7+3", FormulaResolver.resolve("7+Strength", sheet, model));
    }

    @Test
    @DisplayName("o nome casa sem acento e sem diferenciar maiuscula")
    void resolvesIgnoringAccentAndCase() throws Exception {
        // O modelo padrao esta em ingles, entao o teste do acento precisa de um
        // modelo com acento de verdade -- senao "forca" seria recusado por nao
        // existir, e o teste passaria pelo motivo errado.
        SheetModel accentModel = model.withAttributeText("strength", "FOR", "Força");
        SheetData sheet = sheetWith("strength", 5);
        assertEquals("1d8+5", FormulaResolver.resolve("1d8+Força", sheet, accentModel));
        assertEquals("1d8+5", FormulaResolver.resolve("1d8+forca", sheet, accentModel));
        assertEquals("1d8+5", FormulaResolver.resolve("1d8+FORCA", sheet, accentModel));
    }

    @Test
    @DisplayName("o mesmo atributo pode aparecer mais de uma vez")
    void sumsRepeatedAttribute() throws Exception {
        // Sem deduplicar de proposito: "+Strength+Strength" vale o dobro, e e o que a
        // jogadora escreveu.
        SheetData sheet = sheetWith("strength", 7);
        assertEquals("1d6+7+7", FormulaResolver.resolve("1d6+Strength+Strength", sheet, model));
    }

    // --- o scanner nao pode confundir dado com nome ---

    @Test
    @DisplayName("o d de d20 e de d6 nao e lido como nome")
    void keepsDiceIntact() throws Exception {
        // Este e o teste que impede o bug mais provavel: se 'd' fosse resolvido, a
        // formula viraria lixo e o preset pararia de rolar. O esperado mantem o
        // "d20" intacto -- o resolve NAO converte dado em numero.
        SheetData sheet = sheetWith("strength", 2);
        assertEquals("2d6+d20+2", FormulaResolver.resolve("2d6+d20+Strength", sheet, model));
        assertEquals("d20", FormulaResolver.resolve("d20", sheet, model));
    }

    @Test
    @DisplayName("minuscula e maiscula do d contam igual: D6 continua dado")
    void keepsUppercaseDiceIntact() throws Exception {
        SheetData sheet = sheetWith("strength", 1);
        assertEquals("D20+1", FormulaResolver.resolve("D20+Strength", sheet, model));
    }

    @Test
    @DisplayName("atributo com espaco no nome casa inteiro, e dentro de parenteses")
    void resolvesMultiWordAttributeInsideParentheses() throws Exception {
        // O scanner tenta o trecho mais longo primeiro: "Animal Handling" precisa casar
        // inteiro, e nao parar no espaco e recusar. O parentese e preservado porque o
        // scanner so reescreve o trecho da palavra.
        SheetModel spaced = model.withAttributeText("dexterity", "DEX", "Animal Handling");
        SheetData sheet = sheetOf("dexterity", 4, "strength", 2);
        // "(Strength+Animal Handling)" -> "(2+4)": Forca vale 2 e Dexterity vale 4.
        assertEquals("(2+4)", FormulaResolver.resolve("(Strength+Animal Handling)", sheet, spaced));
        // E o espaco no MEIO da formula nao pode entrar no nome. O espaco continua
        // na string: quem remove e o `RollPreset.create`, nao o `resolve`.
        assertEquals("2d6 + 4", FormulaResolver.resolve("2d6 + Strength", sheetWith("strength", 4), model));
    }

    // --- a pericia saiu do resolvido (02/10/2026) ---

    @Test
    @DisplayName("nome de pericia e recusado: a formula aceita so atributo")
    void rejectsPericiaName() {
        // Decisao do usuario em 02/10/2026: a pericia saiu do FormulaResolver. "Athletics"
        // e nome de pericia no modelo padrao, entao agora e um nome desconhecido e a
        // mensagem precisa dizer isso, em vez de somar a pericia em silencio.
        SheetData sheet = sheetWith("strength", 14);
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.resolve("1d6+Athletics", sheet, model));
        assertTrue(error.getMessage().contains("Athletics"), error.getMessage());
        assertTrue(error.getMessage().contains("unknown attribute"), error.getMessage());
    }

    @Test
    @DisplayName("o placeholder tambem recusa nome de pericia")
    void placeholderRejectsPericiaName() {
        // O `placeholderFormula` e o que o `RollPreset.create` usa para validar no
        // Save. Se ele ainda aceitasse pericia, o preset passaria pelo save e a
        // rolagem recusaria depois -- o preset pareceria valido na tela.
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.placeholderFormula("1d6+Athletics", model));
        assertTrue(error.getMessage().contains("Athletics"), error.getMessage());
    }

    // --- recusas ---

    @Test
    @DisplayName("nome que nao existe e recusado, com a lista de nomes validos")
    void rejectsUnknownName() {
        SheetData sheet = sheetWith("strength", 14);
        // Nome inventado e SO com letra: o scanner recusa por trecho de letra, entao
        // "Carisma999" seria recusado como "Carisma" e a mensagem diria outra coisa.
        // O que se quer testar aqui e o nome inteiro desconhecido.
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.resolve("1d6+CarismaMaximo", sheet, model));
        // A mensagem precisa dizer QUAL nome falhou e mostrar um exemplo valido: e o
        // que a jogadora ve no chat ao errar o nome.
        assertTrue(error.getMessage().contains("CarismaMaximo"), error.getMessage());
        assertTrue(error.getMessage().contains("Valid names"), error.getMessage());
    }

    @Test
    @DisplayName("letra solta que nao forma nome nenhum e recusada")
    void rejectsSingleLetter() {
        // "XYZ" nao casa com nenhum nome, e o scanner emite a letra solta para o
        // erro citar algo que a jogadora possa corrigir.
        SheetData sheet = sheetWith("strength", 14);
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.resolve("1d6+XYZ", sheet, model));
        assertTrue(error.getMessage().contains("XYZ"), error.getMessage());
    }

    @Test
    @DisplayName("sem ficha o nome e recusado em vez de virar zero")
    void rejectsWhenNoSheet() {
        // Zero silencioso daria a jogadora o resultado errado sem pista nenhuma.
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.resolve("1d6+Strength", null, model));
        assertTrue(error.getMessage().contains("sheet"), error.getMessage());
    }

    @Test
    @DisplayName("atributo que a ficha nao tem e recusado, nao somado como zero")
    void rejectsAttributeMissingFromSheet() {
        // Sheet sem o atributo STRENGTH. Zero silencioso daria um preset que rola
        // errado sempre que a ficha nao tiver o atributo -- e nao haveria pista de
        // que o preset e que esta quebrado.
        SheetData sheet = sheetMissing("strength");
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.resolve("1d6+Strength", sheet, model));
        assertTrue(error.getMessage().contains("Strength"), error.getMessage());
    }

    @Test
    @DisplayName("formula com mais nomes que o teto e recusada")
    void rejectsTooManyNames() {
        StringBuilder formula = new StringBuilder("1d6");
        SheetData sheet = sheetWith("strength", 1);
        for (int i = 0; i <= FormulaResolver.MAX_TOKENS; i++) {
            formula.append("+Strength");
        }
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.resolve(formula.toString(), sheet, model));
        assertTrue(error.getMessage().contains(String.valueOf(FormulaResolver.MAX_TOKENS)),
                error.getMessage());
    }

    @Test
    @DisplayName("o placeholder tambem respeita o teto de nomes")
    void placeholderRespectsMaxTokens() {
        StringBuilder formula = new StringBuilder("1d6");
        for (int i = 0; i <= FormulaResolver.MAX_TOKENS; i++) {
            formula.append("+Strength");
        }
        // Sem este teto, o save aceitaria uma formula que a rolagem recusaria: o
        // preset pareceria valido na tela e falharia toda vez que fosse usado.
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.placeholderFormula(formula.toString(), model));
        assertTrue(error.getMessage().contains(String.valueOf(FormulaResolver.MAX_TOKENS)),
                error.getMessage());
    }

    // --- o placeholder: validacao de estrutura no save ---

    @Test
    @DisplayName("o placeholder troca o nome por 0 e mantem dados e sinais")
    void placeholderKeepsStructure() throws Exception {
        // E o que o `DiceFormula` consegue julgar: `1d6+0` e uma rolagem valida,
        // enquanto `1d6+Strength` nao e.
        assertEquals("1d6+0", FormulaResolver.placeholderFormula("1d6+Strength", model));
        assertEquals("2d6+d20+0-0", FormulaResolver.placeholderFormula("2d6+d20+Strength-Dex", model));
        assertEquals("(0+0)", FormulaResolver.placeholderFormula("(Strength+Dex)", model));
        assertEquals("1d20+5", FormulaResolver.placeholderFormula("1d20+5", model));
    }

    @Test
    @DisplayName("o placeholder sobrevive ao DiceFormula, e e isso que o save usa")
    void placeholderIsParseableByDiceFormula() throws Exception {
        // Este e o teste que amarra o save com a rolagem: a string que o
        // `RollPreset.create` entrega ao parser tem de passar, senao `1d6+Strength`
        // seria recusado como sintaxe invalida e o recurso ficaria inalcancavel.
        String placeholder = FormulaResolver.placeholderFormula("1d6+Strength", model);
        DiceFormula.Outcome outcome = DiceFormula.parse(placeholder).evaluate(sides -> 3);
        // O 0 do placeholder entra como numero: 3 + 0 = 3. O que importa aqui e que
        // o TOTAL tem de bater com a formula, e nao virar lixo.
        assertEquals(3, outcome.total());
    }

    // --- a saida ainda e uma rolagem valida ---

    @Test
    @DisplayName("a formula resolvida continua sendo aceita pelo DiceFormula")
    void resolvedFormulaStillParses() throws Exception {
        // Este e o teste que amarra as duas metades: nao basta trocar o nome, a
        // formula resolvida tem de continuar sendo rolavel de verdade.
        SheetData sheet = sheetWith("strength", 14);
        String resolved = FormulaResolver.resolve("2d6+Strength+Dex", sheet, model);
        assertEquals("2d6+14+0", resolved);
        // `evaluate` devolve sempre 3 (1+2), entao 2d6 da 6. A soma dos numeros
        // resolvidos entra inteira: 6 + 14 = 20. O valor do atributo tem de chegar
        // no total, e nao ser descartado.
        DiceFormula.Outcome outcome = DiceFormula.parse(resolved).evaluate(sides -> 3);
        assertEquals(20, outcome.total());
    }

    // --- dois atributos com o mesmo nome ---

    @Test
    @DisplayName("quando dois atributos têm o mesmo nome, o primeiro do modelo vence")
    void firstAttributeWinsDuplicateName() throws Exception {
        // O Mestre pode renomear dois atributos para a mesma coisa. Sem uma regra
        // fixa, o mesmo preset resolveria para valores diferentes conforme a ordem
        // da tabela; `putIfAbsent` faz o primeiro do modelo ser o dono da chave.
        SheetModel colliding = model.withAttributeText("dexterity", "DE", "Insight");
        SheetData sheet = sheetOf("dexterity", 2, "strength", 9);
        assertEquals("2", FormulaResolver.resolve("Insight", sheet, colliding));
    }

    // --- ficha de teste ---

    /**
     * Ficha com um atributo no valor pedido e todos os outros em zero.
     *
     * <p><b>Por que comecar em tudo zero:</b> o teste tem de mudar so o campo que
     * esta exercitando. Se a ficha ja viesse com Forca 12, um teste passaria por
     * causa do atributo que nao esta em foco.
     */
    private SheetData sheetWith(String attributeId, int value) {
        return sheetOf(new String[]{attributeId}, new int[]{value});
    }

    /**
     * Ficha sem o atributo pedido: o id simplesmente nao aparece na lista.
     *
     * <p>Diferente de {@link #sheetWith} com valor zero: aqui o id nao existe na
     * ficha. Os dois casos precisam dar recusa diferente -- "nao tem o que somar" e
     * "tem, mas vale zero" -- e um helper so nao separa os dois.
     */
    private SheetData sheetMissing(String attributeId) {
        List<SheetData.Attributes.AttributeValue> out = new ArrayList<>();
        for (SheetModel.AttributeDef def : model.attributes()) {
            if (def.id().equals(attributeId)) {
                continue;
            }
            out.add(new SheetData.Attributes.AttributeValue(def.id(), 0));
        }
        return sheet(out);
    }

    /**
     * Ficha com dois atributos nos valores pedidos, e o resto em zero.
     *
     * <p>Os ids sao percorridos na ordem do modelo, e nao na ordem dos argumentos:
     * assim a ficha fica igual a que o jogo monta, e o teste nao depende de uma
     * ordem de insercao que so ele produz.
     */
    private SheetData sheetOf(String firstId, int firstValue, String secondId, int secondValue) {
        return sheetOf(new String[]{firstId, secondId}, new int[]{firstValue, secondValue});
    }

    private SheetData sheetOf(String[] attributeIds, int[] values) {
        List<SheetData.Attributes.AttributeValue> out = new ArrayList<>();
        for (SheetModel.AttributeDef def : model.attributes()) {
            int value = 0;
            for (int i = 0; i < attributeIds.length; i++) {
                if (def.id().equals(attributeIds[i])) {
                    value = values[i];
                }
            }
            out.add(new SheetData.Attributes.AttributeValue(def.id(), value));
        }
        return sheet(out);
    }

    private SheetData sheet(List<SheetData.Attributes.AttributeValue> values) {
        // Constroi a ficha pelo construtor canonico, com os defaults dos outros
        // grupos. O que o teste mexe e so `attributes`; se aparecer um campo novo no
        // record, este `new` quebra na hora em vez de devolver uma ficha
        // silenciosamente errada.
        return new SheetData(
                new SheetData.Identity("Heroi", "", "", "", "Tester", "", ""),
                SheetData.Vitals.defaults(),
                SheetData.Progress.defaults(),
                new SheetData.Attributes(values),
                List.of(),
                List.of(),
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MIN,
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MAX,
                SheetModel.DEFAULT_PERICIA_VALUE_MAX,
                SheetData.Inventory.EMPTY,
                new SheetData.Spellbook(List.of(), "", 0));
    }
}
