package com.pedro.tabletoprpg;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Troca os <b>nomes</b> de atributo e pericia dentro de uma formula de rolagem pelos
 * numeros que eles valem para a ficha de quem vai rolar (01/10/2026).
 *
 * <p><b>Para que serve.</b> A formula do preset e guardada como a jogadora digitou:
 * {@code 1d6+Strength}. Se fosse guardada ja somada ({@code 1d6+14}), cada ponto novo
 * em Forca deixaria o preset velho, que e justamente o preset que a jogadora quer
 * usar. Guardando o nome, a resolucao acontece <b>na hora da rolagem</b> e o preset
 * acompanha a ficha sozinho.
 *
 * <p><b>Valor cru, nao modificador</b> (decisao do usuario em 01/10/2026):
 * {@code Strength} com 14 vira {@code +14}. E a mesma conta que o
 * {@link SheetData#attributeValue} e o {@code MasterCommands.rollPericia} ja fazem
 * para a rolagem de pericia, entao o preset nao inventa uma regra diferente da do
 * comando. Consequencia aceita: os numeros ficam grandes. Quem quiser o modificador
 * escreve o numero na formula.
 *
 * <p><b>Pericia e token independente</b> (idem): {@code +Athletics} soma o valor da
 * pericia e {@code +Athletics+Strength} soma o valor dela mais o do atributo. Quem
 * quiser o padrao classico de RPG escreve os dois. Nao ha atalho que faca as duas
 * coisas, porque "somar o atributo da pericia junto" e uma escolha da formula, e a
 * formula e da jogadora.
 *
 * <p><b>Nomes aceitos.</b> Todos os nomes que o {@link SheetModel} expoe para um
 * atributo ({@code id}, {@code label} e {@code name}) e para uma pericia ({@code id}
 * e {@code name}): {@code Strength}, {@code strength}, {@code STR} e {@code forca}
 * caem no mesmo atributo. A comparacao tira acento, ignora maiuscula e junta espacos,
 * pelo mesmo motivo do {@link RollPreset#key()}.
 *
 * <p><b>Atributo ganha a colisao.</b> Se um atributo e uma pericia tem o mesmo nome no
 * modelo, o atributo vence. A ordem importa porque o Mestreao pode renomear os dois
 * para a mesma coisa; sem uma regra fixa, o mesmo preset resolveria valores diferentes
 * dependendo de qual lista foi percorrida primeiro.
 *
 * <p><b>Sem ficha, sem resolucao.</b> Com {@code sheet == null} todo nome e recusado,
 * em vez de virar zero silencioso. Um preset que rola 6 quando a jogadora tem Forca 14
 * e pior do que um preset que avisa que precisa de ficha.
 */
public final class FormulaResolver {

    /** Quantos nomes um preset pode usar antes de a busca virar trabalho de verdade. */
    public static final int MAX_TOKENS = 32;

    private FormulaResolver() {
    }

    /** Erro de resolucao com frase ja pronta para o chat (sem prefixo de cor). */
    public static class ResolveException extends Exception {
        public ResolveException(String message) {
            super(message);
        }
    }

    /**
     * Devolve os nomes que a formula usa, na ordem em que aparecem.
     *
     * <p><b>Por que a lista e o que a GUI mostra:</b> a tela de presets precisa
     * listar os tokens para a jogadora clicar em vez de decorar. A lista sai daqui
     * para o texto poder ser resolvido em dois lugares sem duplicar o scanner.
     *
     * <p>Nao deduplica: {@code +Strength+Strength} e valido e vale o dobro, e a
     * tela mostra as duas ocorrencias.
     *
     * @throws ResolveException se algum nome nao existe no modelo
     */
    public static List<String> tokens(String formula, SheetModel model) throws ResolveException {
        List<String> out = new ArrayList<>();
        if (formula == null) {
            return out;
        }
        Map<String, Token> table = table(model);
        for (int[] span : scanWords(formula, table)) {
            if (out.size() >= MAX_TOKENS) {
                throw new ResolveException("formula uses more than " + MAX_TOKENS
                        + " attribute or skill names (got " + (out.size() + 1) + ")");
            }
            String raw = formula.substring(span[0], span[1]);
            Token token = table.get(key(raw));
            if (token == null) {
                throw new ResolveException("unknown attribute or skill '" + raw + "' in formula."
                        + validHint(table));
            }
            out.add(token.display);
        }
        return out;
    }

    /**
     * Troca os nomes pelos numeros da ficha e devolve uma formula so com numeros e
     * dados, pronta para o {@link DiceFormula}.
     *
     * @throws ResolveException se algum nome nao existe no modelo ou se a ficha nao
     *                          tem o valor desse atributo/pericia
     */
    public static String resolve(String formula, SheetData sheet, SheetModel model)
            throws ResolveException {

        if (formula == null || formula.isEmpty()) {
            return "";
        }
        if (sheet == null) {
            // Sem ficha nao ha valor nenhum para trocar. Recusar aqui e melhor do que
            // devolver 0 em cada token: a jogadora veria o resultado errado em vez do
            // aviso.
            throw new ResolveException("cannot use attribute or skill names in a formula"
                    + " without a character sheet");
        }

        Map<String, Token> table = table(model);
        List<int[]> spans = scanWords(formula, table);
        StringBuilder out = new StringBuilder(formula.length());
        // Copia com o cursor andando na lista de trechos, em vez de reescrever a
        // formula inteira a cada nome: com tres nomes na mesma expressao, a versao
        // com StringBuilder faria tres copias do texto inteiro.
        int cursor = 0;
        int resolved = 0;
        for (int[] span : spans) {
            if (++resolved > MAX_TOKENS) {
                // O teto vale AQUI tambem, e nao so em `tokens`: sem esta conta, uma
                // formula com mil nomes passava sem limite e cada nome virava uma
                // busca na ficha. O teto em `tokens` sozinho nao segurava o caminho
                // do `resolve`, que e o que roda na rolagem.
                throw new ResolveException("formula uses more than " + MAX_TOKENS
                        + " attribute or skill names");
            }
            String raw = formula.substring(span[0], span[1]);
            Token token = table.get(key(raw));
            if (token == null) {
                throw new ResolveException("unknown attribute or skill '" + raw + "' in formula."
                        + validHint(table));
            }
            Integer value = token.kind == Kind.ATTRIBUTE
                    ? attributeValue(sheet, token.id)
                    : periciaValue(sheet, token.id);
            if (value == null) {
                throw new ResolveException("this sheet has no '" + token.display
                        + "' to add to the roll");
            }
            out.append(formula, cursor, span[0]).append(value);
            cursor = span[1];
        }
        // A cauda depois da ultima palavra: sem isto, "1d6+Strength" viraria "14" e
        // perderia o dado.
        return out.append(formula, cursor, formula.length()).toString();
    }

    /**
     * O valor do atributo pelo id, ou {@code null} se a ficha nao tem esse atributo.
     *
     * <p>{@code null} e nao {@code 0}: id que a ficha nao tem e presenca ausente, e
     * o preset que dependia dele precisa ser corrigido. Um zero silencioso daria a
     * jogadora um resultado errado sem nenhuma pista de por que.
     */
    private static Integer attributeValue(SheetData sheet, String attributeId) {
        if (attributeId == null) {
            return null;
        }
        for (SheetData.Attributes.AttributeValue value : sheet.attributes().values()) {
            if (attributeId.equals(value.id())) {
                return value.value();
            }
        }
        return null;
    }

    /**
     * O valor de uma pericia pelo id, ou {@code null} se a ficha nao tem essa pericia.
     *
     * <p><b>Por que {@code null} e nao {@code 0}:</b> a ficha so tem as pericias do
     * modelo atual, e o Mestre pode ter trocado o conjunto. Pericia que sumiu e
     * presenca ausente, nao bonus zero -- o preset que dependia dela precisa ser
     * corrigido, e o aviso e o que diz isso.
     */
    private static Integer periciaValue(SheetData sheet, String periciaId) {
        for (SheetData.Pericia pericia : sheet.pericias()) {
            if (pericia != null && periciaId.equals(pericia.id())) {
                return pericia.value();
            }
        }
        return null;
    }

    private enum Kind {
        /** Atributo da ficha. Vence colisao de nome; ver o javadoc da classe. */
        ATTRIBUTE,
        /** Pericia da ficha, pelo valor que o jogador digitou nela. */
        PERICIA
    }

    /** Um nome que a formula pode usar, ja apontando para onde buscar o valor. */
    private record Token(Kind kind, String id, String display) {
    }

    /**
     * Todos os nomes resolviveis, indexados pela chave normalizada.
     *
     * <p><b>Por que o mapa e montado a cada chamada e nao guardado:</b> o
     * {@link SheetModel} muda quando o Mestre edita a ficha, e um cache aqui
     * resolveria o preset contra um modelo velho depois de uma edicao. O mapa e
     * pequeno (dez atributos, trinta pericias, tres nomes cada) e a rolagem nao e
     * um caminho quente: refazer o mapa e mais barato que acertar a invalidacao.
     */
    private static Map<String, Token> table(SheetModel model) {
        Map<String, Token> table = new LinkedHashMap<>();
        if (model == null) {
            return table;
        }
        // Atributo PRIMEIRO: a colisao e resolvida em favor dele, e a ordem do
        // LinkedHashMap garante que o primeiro `put` nao seja sobrescrito pelo
        // segundo.
        for (SheetModel.AttributeDef def : model.attributes()) {
            add(table, Kind.ATTRIBUTE, def.id(), def.label(), def.name());
        }
        for (SheetModel.PericiaDef def : model.pericias()) {
            add(table, Kind.PERICIA, def.id(), def.name());
        }
        return table;
    }

    private static void add(Map<String, Token> table, Kind kind, String... names) {
        for (String name : names) {
            String k = key(name);
            if (k.isEmpty()) {
                continue;
            }
            // `putIfAbsent`: o primeiro nome que chegou e o dono da chave. E o que
            // mantem o atributo na colisao e tambem evita que "STR" e "Strength"
            // fiquem disputando a mesma chave com ids diferentes.
            table.putIfAbsent(k, new Token(kind, names[0], names[names.length - 1]));
        }
    }

    /**
     * Os trechos da formula que sao nomes resolviveis, na posicao em que aparecem.
     *
     * <p><b>Por que este scanner e nao um parser proprio:</b> a formula ja tem uma
     * gramatica ({@link DiceFormula}) e nao convem escrever uma segunda. O que
     * interessa aqui e achar <i>nomes</i>, e nao ler a formula: os sinais, os
     * parenteses e os dados ficam intactos porque a troca reescreve so o trecho.
     *
     * <p><b>Por que a tabela entra no scanner:</b> nomes tem espaco
     * ({@code Animal Handling}) e o scanner precisa saber onde um nome acaba e o
     * outro comeca. Sem a tabela, o espaco seria fim de palavra e
     * {@code +Animal Handling} seria recusado. Aqui a tentativa e <b>do maior para o
     * menor</b>: {@code Animal Handling} casa inteiro antes de {@code Animal} ser
     * testado sozinho.
     *
     * <p><b>Por que {@code d20} nao vira nome:</b> o {@code d} seguido de digito e
     * dado. O filtro esta em {@link #isDice}, que roda antes de qualquer tentativa de
     * nome -- e o que impede "2d6" de ser lido como o nome "d6".
     */
    private static List<int[]> scanWords(String formula, Map<String, Token> table) {
        List<int[]> spans = new ArrayList<>();
        int i = 0;
        int n = formula.length();
        while (i < n) {
            if (!Character.isLetter(formula.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            // `d20`/`D20` e dado: anda com o numero para nao testar nome nenhum.
            if (isDiceAt(formula, i)) {
                i++;
                while (i < n && Character.isDigit(formula.charAt(i))) {
                    i++;
                }
                continue;
            }
            // grown e o fim do trecho candidato mais longo. A chave ignora espaco,
            // entao o espaco entre duas palavras pode ser servido.
            int grown = i;
            int fullEnd = i;
            int matchedEnd = -1;
            while (grown < n) {
                if (Character.isLetter(formula.charAt(grown))) {
                    grown++;
                } else if (formula.charAt(grown) == ' '
                        && grown + 1 < n
                        && Character.isLetter(formula.charAt(grown + 1))) {
                    // Espaco so entra como candidato quando ha letra depois: um
                    // espaco no fim do trecho (`1d6 + Strength`) nao deve virar
                    // parte do nome.
                    grown += 2;
                } else {
                    break;
                }
                fullEnd = grown;
                if (table.containsKey(key(formula.substring(start, grown)))) {
                    matchedEnd = grown;
                }
            }
            if (matchedEnd < 0) {
                // Nenhum prefixo casou. Emite o TRECHO INTEIRO, e nao uma letra
                // solta: o erro precisa devolver a palavra que a jogadora escreveu
                // ("unknown name 'CarismaMaximo'"), e nao a primeira letra dela.
                spans.add(new int[]{start, fullEnd});
                i = fullEnd;
            } else {
                spans.add(new int[]{start, matchedEnd});
                i = matchedEnd;
            }
        }
        return spans;
    }

    /**
     * O caractere na posicao {@code i} abre um dado {@code d}/{@code D} com numero?
     *
     * <p>Olha so o que vem <b>depois</b>: o {@code d} de {@code d20} tem digito e por
     * isso e dado; o {@code d} de {@code +Dex} e letra e por isso nome.
     */
    private static boolean isDiceAt(String formula, int i) {
        char c = formula.charAt(i);
        if (c != 'd' && c != 'D') {
            return false;
        }
        return i + 1 < formula.length() && Character.isDigit(formula.charAt(i + 1));
    }

    /** Chave de comparacao dos nomes: sem acento, minuscula, sem espaco. */
    private static String key(String rawName) {
        if (rawName == null) {
            return "";
        }
        String stripped = Normalizer.normalize(rawName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "");
    }

    /**
     * A dica que acompanha o "nome desconhecido", montada dos nomes do modelo.
     *
     * <p><b>Por que so os cinco primeiros:</b> trinta pericias na mesma linha de erro
     * empurra a frase para fora da tela do jogo. Cinco ja dizem o formato
     * ("Perception", "Pericias...") e o jogador acerta o resto sozinho.
     */
    private static String validHint(Map<String, Token> table) {
        List<String> names = new ArrayList<>();
        for (Token token : table.values()) {
            if (!names.contains(token.display)) {
                names.add(token.display);
            }
        }
        if (names.isEmpty()) {
            return ". This sheet model has no attributes or skills";
        }
        int shown = Math.min(5, names.size());
        String more = names.size() > shown
                ? ", ... (" + names.size() + " total)"
                : "";
        return ". Valid names: " + String.join(", ", names.subList(0, shown)) + more;
    }
}