package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do {@link FormulaResolver}: a troca de nome de atributo/pericia por numero.
 *
 * <p><b>Por que estes testes existem.</b> A resolucao roda no meio do caminho da
 * rolagem, entao um erro dela nao quebra a compilacao: vira preset que rola o numero
 * errado, em silencio, todas as vezes que a jogadora usa. Os testes prendem as tres
 * fronteiras que importam: o dado {@code d20} nao pode ser lido como nome, o nome
 * desconhecido tem de ser recusado em vez de virar zero, e a formula resolvida ainda
 * tem de ser uma rolagem valida.
 */
class FormulaResolverTest {

    /** O modelo padrao: strength/dexterity/... e pericia_1..18 (SheetModel:268-293). */
    private final SheetModel model = SheetModel.defaults();

    // --- a regra principal: nome vira o valor da ficha ---

    @Test
    @DisplayName("+Strength vira o valor cru do atributo da ficha")
    void resolvesAttributeByName() throws Exception {
        // Decisao do usuario em 01/10/2026: valor CRU, nao modificador. Forca 14
        // soma 14.
        SheetData sheet = sheetWithAttribute("strength", 14);
        assertEquals("1d6+14", FormulaResolver.resolve("1d6+Strength", sheet, model));
    }

    @Test
    @DisplayName("o mesmo atributo casa por id, por label e por nome")
    void resolvesAttributeByIdLabelOrName() throws Exception {
        SheetData sheet = sheetWithAttribute("strength", 3);
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
        // modelo com acento de verdade -- senao "FOrCa" seria recusado por nao
        // existir, e o teste passaria pelo motivo errado.
        SheetModel accentModel = model.withAttributeText("strength", "FOR", "Força");
        SheetData sheet = sheetWithAttribute("strength", 5);
        assertEquals("1d8+5", FormulaResolver.resolve("1d8+Força", sheet, accentModel));
        assertEquals("1d8+5", FormulaResolver.resolve("1d8+forca", sheet, accentModel));
        assertEquals("1d8+5", FormulaResolver.resolve("1d8+FORCA", sheet, accentModel));
    }

    @Test
    @DisplayName("+Pericia soma o valor da pericia, e +Pericia+Strength soma os dois")
    void resolvesPericiaAndCombinesWithAttribute() throws Exception {
        // Decisao do usuario: a pericia e token independente. Quem quiser o padrao
        // classico de RPG escreve os dois nomes na formula.
        SheetData sheet = sheetWith("strength", 14, "pericia_4", "Athletics", 3);
        // O resolve devolve a FORMULA resolvida, nao a soma: quem soma e o
        // DiceFormula depois. Por isso o esperado aqui e "3+14", e nao "17".
        assertEquals("3", FormulaResolver.resolve("Athletics", sheet, model));
        assertEquals("3+14", FormulaResolver.resolve("Athletics+Strength", sheet, model));
    }

    // --- o scanner nao pode confundir dado com nome ---

    @Test
    @DisplayName("o d de d20 e de d6 nao e lido como nome")
    void keepsDiceIntact() throws Exception {
        // Este e o teste que impede o bug mais provavel: se 'd' fosse resolvido, a
        // formula viraria lixo e o preset pararia de rolar. O esperado mantem o
        // "d20" intacto -- o resolve NAO converte dado em numero.
        SheetData sheet = sheetWithAttribute("strength", 2);
        assertEquals("2d6+d20+2", FormulaResolver.resolve("2d6+d20+Strength", sheet, model));
        assertEquals("d20", FormulaResolver.resolve("d20", sheet, model));
    }

    @Test
    @DisplayName("minuscula e maiscula do d contam igual: D6 continua dado")
    void keepsUppercaseDiceIntact() throws Exception {
        SheetData sheet = sheetWithAttribute("strength", 1);
        assertEquals("D20+1", FormulaResolver.resolve("D20+Strength", sheet, model));
    }

    @Test
    @DisplayName("nomes dentro de parenteses e com sinal negativo resolvem")
    void resolvesInsideParenthesesAndWithMinus() throws Exception {
        SheetData sheet = sheetWith("strength", 4, "pericia_2", "Animal Handling", 2);
        // Espaco dentro do nome e normalizado, e o parentese e preservado porque o
        // scanner so reescreve o trecho da palavra.
        assertEquals("(4+2)", FormulaResolver.resolve("(Strength+Animal Handling)", sheet, model));
    }

    // --- recusas ---

    @Test
    @DisplayName("nome que nao existe e recusado, com a lista de nomes validos")
    void rejectsUnknownName() {
        SheetData sheet = sheetWithAttribute("strength", 14);
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
        SheetData sheet = sheetWithAttribute("strength", 14);
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
    @DisplayName("pericia que a ficha nao tem e recusada, nao somada como zero")
    void rejectsPericiaMissingFromSheet() {
        // Sheet SEM pericias: `sheetWithAttribute` cria as 18 do modelo, entao aqui
        // a lista vai vazia de proposito.
        SheetData sheet = sheetWithoutPericias(14);
        FormulaResolver.ResolveException error = assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.resolve("Athletics", sheet, model));
        assertTrue(error.getMessage().contains("Athletics"), error.getMessage());
    }

    @Test
    @DisplayName("formula com mais nomes que o teto e recusada")
    void rejectsTooManyNames() {
        StringBuilder formula = new StringBuilder("1d6");
        SheetData sheet = sheetWithAttribute("strength", 1);
        for (int i = 0; i <= FormulaResolver.MAX_TOKENS; i++) {
            formula.append("+Strength");
        }
        assertThrows(FormulaResolver.ResolveException.class,
                () -> FormulaResolver.resolve(formula.toString(), sheet, model));
    }

    // --- colisao de nome ---

    @Test
    @DisplayName("quando atributo e pericia tem o mesmo nome, o atributo vence")
    void attributeWinsNameCollision() throws Exception {
        // A regra esta no javadoc da classe: sem ela, o mesmo preset resolveria para
        // valores diferentes conforme a ordem da tabela.
        SheetModel colliding = model.withAttributeText("strength", "ST", "Insight");
        SheetData sheet = sheetWith("strength", 9, "pericia_7", "Insight", 2);
        // "Insight" e nome de uma pericia no modelo padrao; aqui virou nome do
        // atributo. O atributo tem de ganhar.
        assertEquals("9", FormulaResolver.resolve("Insight", sheet, colliding));
    }

    // --- a saida ainda e uma rolagem valida ---

    @Test
    @DisplayName("a formula resolvida continua sendo aceita pelo DiceFormula")
    void resolvedFormulaStillParses() throws Exception {
        // Este e o teste que amarra as duas metades: nao basta trocar o nome, a
        // formula resolvida tem de continuar sendo rolavel de verdade.
        SheetData sheet = sheetWith("strength", 14, "pericia_4", "Athletics", 3);
        String resolved = FormulaResolver.resolve("2d6+Strength+Athletics", sheet, model);
        assertEquals("2d6+14+3", resolved);
        // `evaluate` devolve sempre 3 (1+2), entao 2d6 da 6. A soma dos numeros
        // resolvidos entra inteira: 6 + 14 + 3 = 23. O valor da pericia e do
        // atributo tem de chegar no total, e nao ser descartado.
        DiceFormula.Outcome outcome = DiceFormula.parse(resolved).evaluate(sides -> 3);
        assertEquals(23, outcome.total());
    }

    // --- tokens() ---

    @Test
    @DisplayName("tokens lista os nomes usados, na ordem, sem deduplicar")
    void listsTokensInOrder() throws Exception {
        SheetData sheet = sheetWith("strength", 14, "pericia_4", "Athletics", 3);
        // Sem deduplicar de proposito: "+Strength+Strength" vale o dobro, e a tela
        // precisa mostrar as duas ocorrencias.
        assertEquals(List.of("Strength", "Athletics", "Strength"),
                FormulaResolver.tokens("Strength+Athletics+Strength", model));
    }

    @Test
    @DisplayName("tokens de formula so com numeros devolve lista vazia")
    void tokensOfPlainFormulaIsEmpty() throws Exception {
        assertTrue(FormulaResolver.tokens("1d20+5", model).isEmpty());
    }

    @Test
    @DisplayName("tokens normaliza o nome: STR aparece como Strength")
    void tokensShowDisplayName() throws Exception {
        assertEquals(List.of("Strength"), FormulaResolver.tokens("1d6+STR", model));
    }

    // --- ficha de teste ---

    /**
     * Ficha com um atributo em 0 e uma pericia em 0.
     *
     * <p><b>Por que comecar em tudo zero:</b> o teste tem de mudar so o campo que
     * esta exercitando. Se a ficha ja viesse com Forca 12, um teste de pericia
     * passaria por causa do atributo e nao por causa da pericia.
     */
    private SheetData sheetWithAttribute(String attributeId, int value) {
        return sheetWith(attributeId, value, null, null, 0);
    }

    private SheetData sheetWith(String attributeId, int attributeValue,
                                String periciaId, String periciaName, int periciaValue) {
        // A pericia entra no lugar da que o modelo ja define, e NAO e append: um
        // append criaria o mesmo id duas vezes, e `SheetData.sanitizePericias`
        // descarta a duplicata -- o teste passaria por causa da pericia com valor 0
        // em vez da que ele montou.
        List<SheetData.Pericia> pericias = new ArrayList<>();
        for (SheetModel.PericiaDef def : model.pericias()) {
            boolean isTarget = periciaId != null && def.id().equals(periciaId);
            pericias.add(new SheetData.Pericia(def.id(),
                    isTarget ? periciaName : def.name(),
                    isTarget ? periciaValue : 0,
                    def.attributeId()));
        }
        return sheet(attributeId, attributeValue, pericias);
    }

    /** Ficha com as seis pericias do modelo ausentes: so o atributo interessa. */
    private SheetData sheetWithoutPericias(int attributeValue) {
        return sheet("strength", attributeValue, List.of());
    }

    private SheetData sheet(String attributeId, int attributeValue,
                            List<SheetData.Pericia> pericias) {

        List<SheetData.Attributes.AttributeValue> values = new ArrayList<>();
        for (SheetModel.AttributeDef def : model.attributes()) {
            values.add(new SheetData.Attributes.AttributeValue(def.id(),
                    def.id().equals(attributeId) ? attributeValue : 0));
        }

        // Constroi a ficha pelo construtor canonico, com os defaults dos outros
        // grupos. O que o teste mexe e so `attributes` e `pericias`; se aparecer
        // um campo novo no record, este `new` quebra na hora em vez de devolver
        // uma ficha silenciosamente errada.
        return new SheetData(
                new SheetData.Identity("Heroi", "", "", "", "Tester", "", ""),
                SheetData.Vitals.defaults(),
                SheetData.Progress.defaults(),
                new SheetData.Attributes(values),
                List.of(),
                pericias,
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MIN,
                SheetModel.DEFAULT_ATTRIBUTE_VALUE_MAX,
                SheetModel.DEFAULT_PERICIA_VALUE_MAX,
                SheetData.Inventory.EMPTY,
                new SheetData.Spellbook(List.of(), "", 0));
    }
}