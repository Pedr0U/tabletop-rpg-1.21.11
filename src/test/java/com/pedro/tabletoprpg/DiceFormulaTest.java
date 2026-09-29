package com.pedro.tabletoprpg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do motor de formulas {@link DiceFormula}.
 *
 * <p><b>Por que estes testes existem:</b> o parser e a ordem de resolucao dos
 * sufixos sao a parte com mais chance de erro silencioso, porque nada os amarra
 * em tempo de compilacao: trocar a ordem de {@code ++N} e {@code dl} continua
 * compilando e so muda o total devolvido ao jogador. Cada teste aqui fixa uma
 * regra da ordem de resolucao com rolagem deterministica, para o erro aparecer
 * na build e nao no chat do jogo.
 *
 * <p><b>Nenhum teste toca Minecraft:</b> a classe e Java puro de proposito, e o
 * {@code roller} injetado e um {@link IntUnaryOperator} controlado aqui, sem
 * acesso a RNG, semente ou estado global.
 */
class DiceFormulaTest {

    // ------------------------------------------------------------------
    // Roladores deterministas
    // ------------------------------------------------------------------

    /** Devolve sempre o mesmo valor; so use com valor dentro de 1..faces. */
    private static IntUnaryOperator always(int value) {
        return sides -> value;
    }

    /** Devolve os valores em ciclo, um por rolagem. */
    private static IntUnaryOperator cycling(int... values) {
        int[] pool = values;
        int[] index = {0};
        return sides -> pool[index[0]++ % pool.length];
    }

    /** Avalia a formula e exige que o total bata. Devolve o Outcome inteiro. */
    private static DiceFormula.Outcome roll(String raw, long expectedTotal, IntUnaryOperator roller)
            throws DiceFormula.SyntaxException {
        DiceFormula.Outcome outcome = DiceFormula.parse(raw).evaluate(roller);
        assertEquals(expectedTotal, outcome.total(), "total of '" + raw + "'");
        return outcome;
    }

    /** A formula precisa ser recusada, com mensagem util para o chat. */
    private static void assertRejected(String raw) {
        DiceFormula.SyntaxException thrown =
                assertThrows(DiceFormula.SyntaxException.class, () -> DiceFormula.parse(raw),
                        "'" + raw + "' should be rejected");
        assertNotNull(thrown.getMessage(), "'" + raw + "' needs a message");
        assertFalse(thrown.getMessage().isEmpty(), "'" + raw + "' needs a non-empty message");
    }

    // ------------------------------------------------------------------
    // Termo simples
    // ------------------------------------------------------------------

    @Test
    @DisplayName("d20 rola uma vez e devolve a face")
    void singleDie() throws Exception {
        roll("d20", 7L, always(7));
    }

    @Test
    @DisplayName("2d6 soma os dois dados")
    void twoDice() throws Exception {
        roll("2d6", 8L, always(4));
    }

    @Test
    @DisplayName("d20+10 soma um termo fixo positivo")
    void plusFlatTerm() throws Exception {
        DiceFormula.Outcome outcome = roll("d20+10", 17L, always(7));
        assertEquals(2, outcome.parts().size());
        assertEquals(1, outcome.parts().get(0).sign());
        assertEquals(1, outcome.parts().get(1).sign());
        assertEquals("10", outcome.parts().get(1).text());
    }

    @Test
    @DisplayName("o detalhe mostra o rotulo do dado e a lista de faces entre colchetes")
    void diceDetailShowsLabelAndFaces() throws Exception {
        DiceFormula.Outcome outcome = roll("d20+10", 17L, always(7));
        // O rotulo e obrigatorio: sem ele o chat mostraria so "[7]" e o
        // jogador nao saberia o que rolou. Formato antigo era "d20 [7]".
        assertEquals("d20 [7]", outcome.parts().get(0).text());
        assertEquals("d20 [7] + 10", outcome.plainText());
    }

    @Test
    @DisplayName("o rotulo inclui os sufixos, para o chat mostrar a formula rolada")
    void diceDetailLabelCarriesSuffixes() throws Exception {
        DiceFormula.Outcome outcome = roll("4d6kh3", 14L, cycling(1, 5, 3, 6));
        assertTrue(outcome.parts().get(0).text().startsWith("4d6kh3 ["),
                "o rotulo perdeu os sufixos: " + outcome.parts().get(0).text());
    }

    @Test
    @DisplayName("d8-1 subtrai um termo fixo")
    void minusFlatTerm() throws Exception {
        roll("d8-1", 4L, always(5));
    }

    @Test
    @DisplayName("termo negativo no meio guarda o sinal na peca")
    void negativeTermInTheMiddle() throws Exception {
        DiceFormula.Outcome outcome = roll("d20-5", 15L, always(20));
        assertEquals(2, outcome.parts().size());
        assertEquals(-1, outcome.parts().get(1).sign());
        assertEquals("5", outcome.parts().get(1).text());
    }

    @Test
    @DisplayName("numero negativo puro vale o proprio numero")
    void pureNegativeNumber() throws Exception {
        DiceFormula.Outcome outcome = roll("-2", -2L, always(1));
        assertEquals(1, outcome.parts().size());
        assertEquals(-1, outcome.parts().get(0).sign());
        assertEquals("2", outcome.parts().get(0).text());
        assertEquals("-2", outcome.plainText());
    }

    @Test
    @DisplayName("espacos sao ignorados: ' 4d6 ++ 2 ' e igual a 4d6++2")
    void spacesAreIgnored() throws Exception {
        DiceFormula.Outcome spaced = roll(" 4d6 ++ 2 ", 18L, cycling(1, 2, 3, 4));
        DiceFormula.Outcome tight = roll("4d6++2", 18L, cycling(1, 2, 3, 4));
        assertEquals(tight.plainText(), spaced.plainText());
        assertEquals("4d6++2", DiceFormula.parse(" 4d6 ++ 2 ").source());
    }

    // ------------------------------------------------------------------
    // ++N / --N
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4d6++2 soma o modificador uma vez por dado")
    void perDiePlus() throws Exception {
        roll("4d6++2", 18L, cycling(1, 2, 3, 4));
    }

    @Test
    @DisplayName("4d6--2 subtrai o modificador uma vez por dado")
    void perDieMinus() throws Exception {
        roll("4d6--2", 2L, cycling(1, 2, 3, 4));
    }

    @Test
    @DisplayName("++N aparece entre parenteses no detalhe")
    void perDieShownInDetail() throws Exception {
        DiceFormula.Outcome outcome = roll("2d6++2", 10L, always(3));
        assertTrue(outcome.plainText().contains("3(+2)"), outcome.plainText());
    }

    // ------------------------------------------------------------------
    // explosao
    // ------------------------------------------------------------------

    @Test
    @DisplayName("d6! explode quando a face e o maximo")
    void explodeOnMax() throws Exception {
        DiceFormula.Outcome outcome = roll("d6!", 8L, cycling(6, 2));
        assertTrue(outcome.plainText().contains("6!2"), outcome.plainText());
    }

    @Test
    @DisplayName("d6!5 explode com limiar explicito")
    void explodeWithExplicitThreshold() throws Exception {
        roll("d6!5", 8L, cycling(5, 3));
    }

    @Test
    @DisplayName("a cadeia inteira conta como um dado: 6!6!2 vale 14")
    void explodeChainCountsAsOneDie() throws Exception {
        DiceFormula.Outcome outcome = roll("d6!5", 14L, cycling(6, 6, 2));
        assertEquals(1, outcome.parts().size());
        assertTrue(outcome.plainText().contains("6!6!2"), outcome.plainText());
    }

    @Test
    @DisplayName("explosao nao estoura o orcamento quando a face seguinte nao explode")
    void explodeStopsWhenFaceIsBelowThreshold() throws Exception {
        roll("d6!5", 4L, cycling(4, 6, 6));
    }

    // ------------------------------------------------------------------
    // kh / kl / dh / dl
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4d6kh3 mantem os tres maiores e marca o descartado no detalhe")
    void keepHigh() throws Exception {
        DiceFormula.Outcome outcome = roll("4d6kh3", 14L, cycling(1, 5, 3, 6));
        List<DiceFormula.Face> faces = outcome.parts().get(0).faces();
        // O 1 foi o menor e virou o descartado; o chat risca este dado em vez
        // de escrever "dropped 1", que nao dizia qual face tinha caido.
        assertTrue(faces.get(0).discarded(), outcome.plainText());
        assertFalse(faces.get(1).discarded(), outcome.plainText());
        assertFalse(faces.get(2).discarded(), outcome.plainText());
        assertFalse(faces.get(3).discarded(), outcome.plainText());
        assertFalse(outcome.plainText().contains("dropped"),
                "a contagem de descartados voltou: " + outcome.plainText());
    }

    @Test
    @DisplayName("4d6kl2 mantem os dois menores")
    void keepLow() throws Exception {
        roll("4d6kl2", 4L, cycling(1, 5, 3, 6));
    }

    @Test
    @DisplayName("4d6dl1 joga fora o menor")
    void dropLow() throws Exception {
        roll("4d6dl1", 14L, cycling(1, 5, 3, 6));
    }

    @Test
    @DisplayName("4d6dh1 joga fora o maior")
    void dropHigh() throws Exception {
        roll("4d6dh1", 9L, cycling(1, 5, 3, 6));
    }

    @Test
    @DisplayName("kh e dl aplicados nesta ordem: 4d6kh3dl1 mantem 3 e descarta o menor dos 3")
    void keepThenDropInFixedOrder() throws Exception {
        roll("4d6kh3dl1", 11L, cycling(1, 5, 3, 6));
    }

    @Test
    @DisplayName("++N entra antes de dl: 4d6++2dl1 fica 4+5+6")
    void perDieBeforeDropLow() throws Exception {
        roll("4d6++2dl1", 15L, cycling(1, 2, 3, 4));
    }

    // ------------------------------------------------------------------
    // ordem dos sufixos, rotulo fiel e sufixo repetido
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a ordem digitada manda: 4d6dl1kh3 descarta o menor antes de manter 3")
    void suffixOrderIsTheTypedOrder() throws Exception {
        DiceFormula.Outcome outcome = roll("4d6dl1kh3", 14L, cycling(1, 5, 3, 6));
        assertTrue(outcome.plainText().contains("4d6dl1kh3"), outcome.plainText());
    }

    @Test
    @DisplayName("o rotulo sai na ordem digitada, com o sinal de ++N e --N")
    void labelKeepsTypedOrderAndSign() throws Exception {
        assertTrue(roll("4d6--2", 2L, cycling(1, 2, 3, 4)).plainText().contains("4d6--2"));
        assertTrue(roll("4d6dl1kh3++2", 20L, cycling(1, 5, 3, 6)).plainText()
                .contains("4d6dl1kh3++2"));
        // "d6!" sem numero continua "d6!" no rotulo, e nao vira "d6!6".
        assertTrue(roll("d6!", 3L, always(3)).plainText().contains("d6! [3]"));
    }

    @Test
    @DisplayName("sufixo repetido e recusado, em vez de sobrescrever em silencio")
    void repeatedSuffixRejected() {
        assertRejected("4d6kh3kh2");
        assertRejected("4d6dl1dl2");
        assertRejected("d6!!");
    }

    // ------------------------------------------------------------------
    // detalhe vazio e limiares
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quando tudo e descartado o detalhe mostra todas as faces marcadas e o total e zero")
    void everythingDroppedShowsNone() throws Exception {
        DiceFormula.Outcome outcome = roll("4d6dh4", 0L, cycling(1, 5, 3, 6));
        // Nao existe mais [none]: o jogador rolou quatro dados e precisa ver os
        // quatro, marcados, para entender por que o total e zero.
        assertFalse(outcome.plainText().contains("[none]"), outcome.plainText());
        assertFalse(outcome.plainText().contains("dropped"), outcome.plainText());
        assertEquals("4d6dh4 [1,5,3,6]", outcome.plainText());
        for (DiceFormula.Face face : outcome.parts().get(0).faces()) {
            assertTrue(face.discarded(), "um dado sobrevivente ficou sem marcacao: " + outcome.plainText());
        }
    }

    @Test
    @DisplayName("'!' acima das faces e valido: nao explode, mas nao e erro")
    void explodeThresholdAboveSidesIsValid() throws Exception {
        roll("d6!7", 3L, always(3));
    }

    @Test
    @DisplayName("'!' fora de 1-1000 e recusado, como c e >>")
    void explodeThresholdOutOfRangeRejected() {
        assertRejected("d6!1001");
        assertRejected("d6!0");
    }

    @Test
    @DisplayName("numero que estoura o int e recusado, em vez de virar outro")
    void numberTooLargeRejected() {
        assertRejected("4294967297d6");
        assertRejected("d6!4294967295");
    }

    // ------------------------------------------------------------------
    // repeticao N#
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6#4d6dl1 soma o subtotal das seis voltas")
    void repeatSumsRoundSubtotals() throws Exception {
        IntUnaryOperator roller = cycling(1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6);
        DiceFormula.Outcome outcome = roll("6#4d6dl1", 74L, roller);
        assertEquals("6#4d6dl1 [9, 13, 15, 9, 13, 15]", outcome.plainText());
    }

    @Test
    @DisplayName("6#4d6dl1 mantem a formula no detalhe e repete o subtotal")
    void repeatDetailKeepsLabelAndSubtotals() throws Exception {
        DiceFormula.Outcome outcome = roll("6#4d6dl1", 72L, always(4));
        assertTrue(outcome.plainText().contains("6#4d6dl1"), outcome.plainText());
        assertEquals("6#4d6dl1 [12, 12, 12, 12, 12, 12]", outcome.plainText());
    }

    @Test
    @DisplayName("a repeticao respeita o sinal do termo")
    void repeatKeepsTermSign() throws Exception {
        roll("2#d6-3", 3L, always(3));
    }

    // ------------------------------------------------------------------
    // a regra dos slots: o descarte fica no lugar onde caiu
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4d20kh2 mantem a ordem em que os dados sairam, com os dois menores marcados")
    void keepHighStaysInRolledOrder() throws Exception {
        DiceFormula.Outcome outcome = roll("4d20kh2", 35L, cycling(18, 17, 14, 10));
        // Exemplo do jogador: 4d20kh2 [18,17,14,10], com 14 e 10 riscados.
        // O kh ordena de forma decrescente, mas so quem sobrevive muda de valor:
        // os dois menores caem no fim, e a ordem do lancamento fica visivel.
        // O total e 35 (18+17), e nao 34: o do exemplo do pedido estava errado.
        assertEquals("4d20kh2 [18,17,14,10]", outcome.plainText());
        List<DiceFormula.Face> faces = outcome.parts().get(0).faces();
        assertFalse(faces.get(0).discarded(), outcome.plainText());
        assertFalse(faces.get(1).discarded(), outcome.plainText());
        assertTrue(faces.get(2).discarded(), outcome.plainText());
        assertTrue(faces.get(3).discarded(), outcome.plainText());
    }

    @Test
    @DisplayName("4d6dl1 mantem a ordem em que os dados sairam, com o menor marcado na frente")
    void dropLowStaysInRolledOrder() throws Exception {
        DiceFormula.Outcome outcome = roll("4d6dl1", 9L, cycling(1, 2, 3, 4));
        // O outro lado da regra dos slots: o dl ordena de forma crescente, entao
        // o menor cai no comeco. Se os sobreviventes fossem para o comeco da
        // lista, isto viraria [2,3,4,1] e o jogador perderia a leitura de qual
        // face ele rolou.
        assertEquals("4d6dl1 [1,2,3,4]", outcome.plainText());
        List<DiceFormula.Face> faces = outcome.parts().get(0).faces();
        assertTrue(faces.get(0).discarded(), outcome.plainText());
        assertFalse(faces.get(1).discarded(), outcome.plainText());
        assertFalse(faces.get(2).discarded(), outcome.plainText());
        assertFalse(faces.get(3).discarded(), outcome.plainText());
    }

    @Test
    @DisplayName("6#4d6dl1 expoe uma volta por repeticao, e a soma dos subtotais e o total")
    void repeatExposesOneRoundPerRoll() throws Exception {
        IntUnaryOperator roller = cycling(1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6,
                1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 5, 6);
        DiceFormula.Outcome outcome = roll("6#4d6dl1", 74L, roller);
        DiceFormula.Part part = outcome.parts().get(0);
        assertEquals(6, part.repeat());
        assertEquals(6, part.rounds().size());
        assertTrue(part.faces().isEmpty(), "o termo com '#' nao pode ter lista corrida de faces");

        long subtotals = 0;
        for (DiceFormula.Round round : part.rounds()) {
            assertEquals("4d6dl1", round.label());
            assertEquals(4, round.faces().size(), "a volta perdeu dados: " + round.faces());
            long kept = 0;
            int dropped = 0;
            for (DiceFormula.Face face : round.faces()) {
                if (face.discarded()) {
                    dropped++;
                } else {
                    kept++;
                }
            }
            // Cada volta risca exatamente um dado, e o subtotal e a soma dos
            // tres que sobraram. O descartado pode estar em qualquer slot: com
            // dl o menor cai onde ele foi rolado, nao sempre na frente.
            assertEquals(1, dropped, "a volta nao marcou um descartado: " + round.faces());
            assertEquals(3, kept, "a volta perdeu um sobrevivente: " + round.faces());
            subtotals += round.subtotal();
        }
        assertEquals(outcome.total(), subtotals,
                "a soma das linhas nao fecha com o total: " + outcome.plainText());
    }

    @Test
    @DisplayName("repeticao acima de DISPLAY_ROUNDS mantem todas as voltas para o chat resumir")
    void repeatOverDisplayRoundLimit() throws Exception {
        // O teto e do chat, nao do motor: o motor entrega todas as voltas e quem
        // decide mostrar 20 linhas e o "...+N more" e o MasterCommands.
        DiceFormula.Outcome outcome = roll("25#d6", 150L, always(6));
        DiceFormula.Part part = outcome.parts().get(0);
        assertEquals(25, part.repeat());
        assertEquals(25, part.rounds().size());
        assertTrue(part.rounds().size() > DiceFormula.DISPLAY_ROUNDS,
                "o caso de resumo precisa de mais voltas que o teto de exibicao");
        // Acima do teto o texto puro segue resumindo, e nao vira 25 linhas.
        assertEquals("25#d6 [6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6]",
                outcome.plainText());
    }

    // ------------------------------------------------------------------
    // contagem >>N / <<N
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10d6>>4dl2 conta so os sobreviventes, nao os dois descartados")
    void countIgnoresDroppedDice() throws Exception {
        // dl2 joga fora o 1 e o 2; sobram 3,3,4,4,5,5,6,6 e quatro deles passam
        // do limiar 4. Contar os descartados daria 6 e o jogador veria na tela
        // um total que nao bate com a lista riscada.
        DiceFormula.Outcome outcome = roll("10d6>>4dl2", 6L, cycling(6, 5, 4, 3, 2, 1, 6, 5, 4, 3));
        List<DiceFormula.Face> faces = outcome.parts().get(0).faces();
        // Os dez dados continuam na lista, para o chat riscar os dois cortados.
        assertEquals(10, faces.size());
        long survivors = faces.stream().filter(face -> !face.discarded()).count();
        assertEquals(8L, survivors);
    }

    @Test
    @DisplayName("10d6>>4 devolve quantos dados passaram do limiar, nao a soma")
    void countGreaterOrEqual() throws Exception {
        roll("10d6>>4", 4L, cycling(1, 2, 3, 4, 5, 6, 1, 2, 3, 4));
    }

    @Test
    @DisplayName("10d6<<3 devolve quantos dados ficaram no limite")
    void countLessOrEqual() throws Exception {
        roll("10d6<<3", 6L, cycling(1, 2, 3, 4, 5, 6, 1, 2, 3, 4));
    }

    @Test
    @DisplayName("a contagem olha o valor ja modificado por ++N")
    void countUsesModifiedValue() throws Exception {
        roll("4d6++2>>5", 2L, cycling(1, 2, 3, 4));
    }

    // ------------------------------------------------------------------
    // critico cN
    // ------------------------------------------------------------------

    @Test
    @DisplayName("d20c18 marca critico sem mexer no total")
    void criticalMarksOnly() throws Exception {
        DiceFormula.Outcome hit = roll("d20c18", 18L, always(18));
        assertTrue(hit.critical());
        DiceFormula.Outcome miss = roll("d20c18", 15L, always(15));
        assertFalse(miss.critical());
    }

    @Test
    @DisplayName("o critico aparece como asterisco no detalhe")
    void criticalShownInDetail() throws Exception {
        DiceFormula.Outcome outcome = roll("d20c18", 18L, always(18));
        assertTrue(outcome.plainText().contains("*"), outcome.plainText());
    }

    @Test
    @DisplayName("sem cN nada e critico")
    void noCriticalWithoutSuffix() throws Exception {
        assertFalse(roll("d20", 20L, always(20)).critical());
    }

    @Test
    @DisplayName("CRIT so vem de dado que sobrou: o 6 descartado por dh1 nao marca")
    void criticalOnlyFromKeptDice() throws Exception {
        DiceFormula.Outcome dropped = roll("4d6c6dh1", 3L, cycling(6, 1, 1, 1));
        assertFalse(dropped.critical(), dropped.plainText());
        assertFalse(dropped.plainText().contains("*"), dropped.plainText());
        assertTrue(roll("4d6c3", 10L, cycling(4, 1)).critical());
    }

    // ------------------------------------------------------------------
    // limites e exibicao
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mais de DISPLAY_LIMIT dados vira reticencias no detalhe")
    void displayLimitTruncatesDetail() throws Exception {
        DiceFormula.Outcome outcome = roll("30d6", 30L, always(1));
        assertTrue(outcome.plainText().contains("...+"), outcome.plainText());
        assertTrue(outcome.plainText().contains("10 more"), outcome.plainText());
    }

    @Test
    @DisplayName("ate DISPLAY_LIMIT dados o detalhe mostra todas as faces")
    void detailUpToDisplayLimitIsComplete() throws Exception {
        DiceFormula.Outcome outcome = roll("20d6", 60L, always(3));
        assertFalse(outcome.plainText().contains("...+"), outcome.plainText());
        assertTrue(outcome.plainText().contains("3,3,3"), outcome.plainText());
    }

    @Test
    @DisplayName("o teto de faces e o de dados sao os declarados na classe")
    void declaredLimits() {
        assertEquals(100, DiceFormula.MAX_DICE);
        assertEquals(1000, DiceFormula.MAX_SIDES);
        assertEquals(100, DiceFormula.MAX_REPEAT);
        assertEquals(999, DiceFormula.MAX_PER_DIE);
        assertEquals(1000, DiceFormula.MAX_THRESHOLD);
        assertEquals(100_000, DiceFormula.MAX_TOTAL_ROLLS);
        assertEquals(20, DiceFormula.DISPLAY_LIMIT);
        // Teto de voltas do '#', separado do teto de faces: 30d6 vira
        // "[...+10 more]" por um motivo e 25#d6 vira "...+5 more" por outro.
        assertEquals(20, DiceFormula.DISPLAY_ROUNDS);
    }

    @Test
    @DisplayName("no limite exato a formula e aceita")
    void boundariesAreAccepted() throws Exception {
        roll("100d6", 100L, always(1));
        roll("d1000", 1L, always(1));
        roll("100#d6", 100L, always(1));
        roll("d6++999", 1000L, always(1));
    }

    @Test
    @DisplayName("o roller precisa devolver um valor dentro de 1..faces")
    void rollerOutOfRangeIsRejected() throws Exception {
        DiceFormula.Formula formula = DiceFormula.parse("d6");
        DiceFormula.SyntaxException thrown = assertThrows(DiceFormula.SyntaxException.class,
                () -> formula.evaluate(sides -> 7));
        assertNotNull(thrown.getMessage());
        assertThrows(DiceFormula.SyntaxException.class, () -> formula.evaluate(sides -> 0));
        assertThrows(DiceFormula.SyntaxException.class, () -> formula.evaluate(sides -> -3));
    }

    // ------------------------------------------------------------------
    // recusas
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formula vazia e recusada")
    void emptyFormulaRejected() {
        assertRejected("");
        assertRejected(null);
        assertRejected("   ");
    }

    @Test
    @DisplayName("explosao garantida e recusada, nunca ignorada em silencio")
    void guaranteedExplosionRejected() {
        assertRejected("1d1!1");
        assertRejected("d1!");
    }

    @Test
    @DisplayName("contagem de dados fora de 1-100 e recusada")
    void diceCountOutOfRange() {
        assertRejected("101d6");
        assertRejected("0d6");
    }

    @Test
    @DisplayName("faces fora de 1-1000 sao recusadas")
    void sidesOutOfRange() {
        assertRejected("d0");
        assertRejected("d1001");
    }

    @Test
    @DisplayName("numero faltando depois de ++ e recusado")
    void missingPerDieNumber() {
        assertRejected("d20++");
    }

    @Test
    @DisplayName("repeticao fora de 1-100 e recusada")
    void repeatOutOfRange() {
        assertRejected("101#d6");
    }

    @Test
    @DisplayName("repeticao sem dado depois e recusada")
    void repeatWithoutDice() {
        assertRejected("6#");
    }

    @Test
    @DisplayName("caractere invalido e recusado")
    void invalidCharacter() {
        assertRejected("d20x");
    }

    @Test
    @DisplayName("os dois contadores juntos sao recusados")
    void bothCountersRejected() {
        assertRejected("10d6>>4<<3");
    }

    @Test
    @DisplayName("numero faltando depois de kh e recusado")
    void missingKeepNumber() {
        assertRejected("4d6kh");
    }

    @Test
    @DisplayName("numero faltando depois de dl e recusado")
    void missingDropNumber() {
        assertRejected("4d6dl");
    }

    @Test
    @DisplayName("o nome mais longo e o separador: 'Pericia 0-2' corta em 'Pericia 0'")
    void nameSplitPicksTheLongestName() {
        List<String> known = List.of("pericia 0", "pericia 0 b", "fight");
        int at = DiceFormula.findNameSplitPoint("Pericia 0-2", known);
        assertEquals(9, at, "o separador nao foi achado no lugar certo");
        assertEquals("Pericia 0", "Pericia 0-2".substring(0, at));
        // O sinal digitado precisa chegar a cauda, senao o '-2' virava '+2'.
        assertEquals('-', "Pericia 0-2".charAt(at));
    }

    @Test
    @DisplayName("sem nome de pericia no comeco, nao ha separador")
    void nameSplitReturnsMinusOneForPlainFormula() {
        List<String> known = List.of("pericia 0", "fight");
        assertEquals(-1, DiceFormula.findNameSplitPoint("d20", known));
        assertEquals(-1, DiceFormula.findNameSplitPoint("4d6++2", known));
        assertEquals(-1, DiceFormula.findNameSplitPoint("d20+10", known));
        assertEquals(-1, DiceFormula.findNameSplitPoint("", known));
        assertEquals(-1, DiceFormula.findNameSplitPoint("+5", known), "nome vazio nao casa");
        assertEquals(-1, DiceFormula.findNameSplitPoint("d20", List.of()));
    }

    @Test
    @DisplayName("o nome casado com o mais longo, e nao com um prefixo curto")
    void nameSplitPrefersLongerKnownName() {
        List<String> known = List.of("fight", "fight magic");
        int at = DiceFormula.findNameSplitPoint("Fight Magic+d20", known);
        assertEquals(11, at);
        assertEquals("Fight Magic", "Fight Magic+d20".substring(0, at));
    }

    @Test
    @DisplayName("a cauda com sinal no inicio distribui o sinal por termo")
    void signedTailKeepsPerTermSigns() throws Exception {
        // Este e o bug que a revisao achou: o comando guardava o sinal separado
        // e multiplicava o total, o que fazia "-d6+2" virar "-(d6+2)".
        // Com o sinal na frente da cauda, o DiceFormula resolve termo a termo.
        DiceFormula.Outcome outcome = DiceFormula.parse("-d6+2").evaluate(always(3));
        assertEquals(-1L, outcome.total());
        assertEquals(-1, outcome.parts().get(0).sign());
        assertEquals(1, outcome.parts().get(1).sign());
        assertEquals("-d6 [3] + 2", outcome.plainText());
    }

    @Test
    @DisplayName("cauda so com menos continua negativa")
    void signedTailSingleMinusTerm() throws Exception {
        DiceFormula.Outcome outcome = DiceFormula.parse("-2").evaluate(sides -> 1);
        assertEquals(-2L, outcome.total());
        assertEquals(-1, outcome.parts().get(0).sign());
    }

    @Test
    @DisplayName("tudo descartado ainda lista as faces, marcadas, em vez de [none]")
    void allDroppedShowsNone() throws Exception {
        DiceFormula.Outcome outcome = roll("4d6dh4", 0L, always(1));
        assertEquals("4d6dh4 [1,1,1,1]", outcome.plainText());
        for (DiceFormula.Face face : outcome.parts().get(0).faces()) {
            assertTrue(face.discarded(), "um dado sobrevivente ficou sem marcacao: " + outcome.plainText());
        }
    }

    @Test
    @DisplayName("o CRIT so vem de dado que sobrou depois do descarte")
    void critIgnoresDroppedDice() throws Exception {
        // A face 6 foi jogada fora por dh1, entao nao pode marcar critico:
        // antes disso o chat mostrava CRIT sem nenhum asterisco visivel.
        DiceFormula.Outcome dropped = roll("4d6c6dh1", 3L, cycling(6, 1, 1, 1));
        assertFalse(dropped.critical(), "um dado descartado marcou critico");
        assertFalse(dropped.plainText().contains("*"),
                "o asterisco do descartado vazou: " + dropped.plainText());

        DiceFormula.Outcome kept = roll("4d6c3", 7L, cycling(4, 1, 1, 1));
        assertTrue(kept.critical(), "um dado mantido nao marcou critico");
    }

    @Test
    @DisplayName("sufixo repetido e recusado, em vez de sobrescrever em silencio")
    void duplicatedSuffixIsRejected() {
        assertThrows(DiceFormula.SyntaxException.class, () -> DiceFormula.parse("4d6kh3kh2"));
        assertThrows(DiceFormula.SyntaxException.class, () -> DiceFormula.parse("4d6dl1dl2"));
        assertThrows(DiceFormula.SyntaxException.class, () -> DiceFormula.parse("d6!!"));
        assertThrows(DiceFormula.SyntaxException.class, () -> DiceFormula.parse("4d6++2++3"));
    }

    @Test
    @DisplayName("os sufixos sao aplicados na ordem em que o jogador digitou")
    void suffixesFollowTypedOrder() throws Exception {
        // 4d6dl1kh3: primeiro joga fora o menor (1), depois mantem os 3 maiores
        // que sobraram (5,3,6) = 14. Na ordem fixa kh->dl daria 11, e o chat
        // mostraria um rotulo diferente do digitado.
        DiceFormula.Outcome typed = roll("4d6dl1kh3", 14L, cycling(1, 5, 3, 6));
        assertTrue(typed.plainText().startsWith("4d6dl1kh3 "),
                "o rotulo nao preservou a ordem digitada: " + typed.plainText());
        assertEquals(14L, typed.total());
    }

    @Test
    @DisplayName("numero enorme e recusado, em vez de estourar o int")
    void hugeNumberIsRejected() {
        assertThrows(DiceFormula.SyntaxException.class, () -> DiceFormula.parse("4294967297d6"));
        assertThrows(DiceFormula.SyntaxException.class, () -> DiceFormula.parse("d6!4294967295"));
    }

    @Test
    @DisplayName("limiar de explosao acima das faces e aceito, mas nao explode")
    void explodeAboveMaxIsLegalButNeverFires() throws Exception {
        // d6!7 nao vai explodir nunca, mas nao e erro: o jogador pode estar
        // montando um termo compartilhado entre dados de tamanhos diferentes.
        DiceFormula.Outcome outcome = roll("d6!7", 6L, always(6));
        assertEquals(6L, outcome.total());
        assertThrows(DiceFormula.SyntaxException.class, () -> DiceFormula.parse("d6!1001"));
    }
}
