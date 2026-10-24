package com.pedro.tabletoprpg;

import net.minecraft.network.chat.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Troca o <b>nome</b> de um atributo dentro de uma formula de rolagem pelo numero que
 * ele vale para a ficha de quem vai rolar (01/10/2026; so atributo em 02/10/2026).
 *
 * <p><b>Para que serve.</b> A formula do preset e guardada como a jogadora digitou:
 * {@code 1d6+Strength}. Se fosse guardada ja somada ({@code 1d6+14}), cada ponto novo
 * em Forca deixaria o preset velho, que e justamente o preset que a jogadora quer
 * usar. Guardando o nome, a resolucao acontece <b>na hora da rolagem</b> e o preset
 * acompanha a ficha sozinho.
 *
 * <p><b>Valor cru, nao modificador</b> (decisao do usuario em 01/10/2026):
 * {@code Strength} com 14 vira {@code +14}. E a mesma conta que o
 * {@link SheetData#attributeValue} e o {@code MasterCommands.rollPericia} ja fazem,
 * entao o preset nao inventa uma regra diferente da do comando. Consequencia aceita:
 * os numeros ficam grandes. Quem quiser o modificador escreve o numero na formula.
 *
 * <p><b>So atributo, nao pericia</b> (decisao do usuario em 02/10/2026). Antes esta
 * classe aceitava o nome de uma pericia como token independente, e
 * {@code +Athletics+Strength} somava as duas coisas. A pericia saiu daqui: a
 * rolagem de pericia ja tem o proprio caminho ({@code /rpg roll <pericia>}, que
 * soma o valor dela mais o do atributo ligado), e aceitar o nome nos dois lugares
 * fazia a mesma soma ser escrita de duas maneiras diferentes. Quem quiser o valor
 * de uma pericia dentro de um preset escreve o numero, ou o nome do atributo que
 * ela usa.
 *
 * <p><b>Nomes aceitos.</b> Todos os nomes que o {@link SheetModel} expoe para um
 * atributo: {@code id}, {@code label} e {@code name}. {@code Strength},
 * {@code strength}, {@code STR} e {@code forca} caem no mesmo atributo. A
 * comparacao tira acento, ignora maiuscula e junta espacos, pelo mesmo motivo do
 * {@link RollPreset#key()}.
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

    /**
     * Erro de resolucao. {@link #getMessage()} segue em ingles (logs e testes);
     * {@link #toComponent()} devolve a mesma mensagem traduzida pela chave, para o
     * chat seguir o idioma de quem recebe.
     */
    public static class ResolveException extends Exception {
        private final String key;
        private final Object[] args;

        public ResolveException(String message) {
            super(message);
            this.key = null;
            this.args = new Object[0];
        }

        public ResolveException(String message, String key, Object... args) {
            super(message);
            this.key = key;
            this.args = args;
        }

        public Component toComponent() {
            if (key == null) {
                return Component.literal(getMessage());
            }
            return Component.translatable(key, args);
        }

        public String getKey() {
            return key;
        }

        public Object[] getArgs() {
            return args;
        }
    }

    /**
     * Troca os nomes pelos numeros da ficha e devolve uma formula so com numeros e
     * dados, pronta para o {@link DiceFormula}.
     *
     * @throws ResolveException se algum nome nao existe no modelo ou se a ficha nao
     *                          tem o valor desse atributo
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
            throw new ResolveException("cannot use an attribute name in a formula"
                    + " without a character sheet",
                    "message.tabletoprpg.formula.resolve.no_sheet");
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
                // O teto vale AQUI, e nao so na validacao do save: sem esta conta,
                // uma formula com mil nomes passava sem limite e cada nome virava
                // uma busca na ficha. So o `placeholderFormula` nao segurava este
                // caminho, que e o que roda na rolagem.
                throw new ResolveException("formula uses more than " + MAX_TOKENS
                        + " attribute names", "message.tabletoprpg.formula.resolve.too_many_names",
                        MAX_TOKENS);
            }
            String raw = formula.substring(span[0], span[1]);
            Token token = table.get(key(raw));
            if (token == null) {
                throw unknownAttribute(raw, table);
            }
            Integer value = attributeValue(sheet, token.id);
            if (value == null) {
                throw new ResolveException("this sheet has no '" + token.display
                        + "' to add to the roll", "message.tabletoprpg.formula.resolve.missing_value",
                        token.display);
            }
            out.append(formula, cursor, span[0]).append(value);
            cursor = span[1];
        }
        // A cauda depois da ultima palavra: sem isto, "1d6+Strength" viraria "14" e
        // perderia o dado.
        return out.append(formula, cursor, formula.length()).toString();
    }

    /**
     * Troca cada nome pelo numero {@code 0}, para o {@link DiceFormula} conferir a
     * <b>estrutura</b> da formula sem depender de nenhuma ficha.
     *
     * <p><b>Por que este metodo existe (02/10/2026):</b> o {@link RollPreset#create}
     * precisa validar a formula no Save, e o {@code DiceFormula} so entende numeros e
     * dados. Se a validacao fosse feita na formula original, {@code 1d6+Strength} seria
     * recusada como sintaxe invalida e o recurso inteiro seria inalcancavel pela tela
     * e pelo comando. Trocando o nome por {@code 0}, o que sobra para o parser e a
     * estrutura de dados e sinais -- {@code 1d6+0} -- que e a parte que ele sabe julgar.
     *
     * <p>O {@code 0} e um placeholder, <b>nao</b> o valor do atributo: aqui nao existe
     * ficha, e a resolucao de verdade acontece na rolagem. O que este metodo recusa e
     * nome desconhecido, que e o unico erro que o {@code DiceFormula} nao veria.
     *
     * @throws ResolveException se algum nome nao existe no modelo
     */
    public static String placeholderFormula(String formula, SheetModel model)
            throws ResolveException {

        if (formula == null || formula.isEmpty()) {
            return "";
        }
        Map<String, Token> table = table(model);
        List<int[]> spans = scanWords(formula, table);
        StringBuilder out = new StringBuilder(formula.length());
        int cursor = 0;
        int count = 0;
        for (int[] span : spans) {
            if (++count > MAX_TOKENS) {
                throw new ResolveException("formula uses more than " + MAX_TOKENS
                        + " attribute names", "message.tabletoprpg.formula.resolve.too_many_names",
                        MAX_TOKENS);
            }
            String raw = formula.substring(span[0], span[1]);
            if (table.get(key(raw)) == null) {
                throw unknownAttribute(raw, table);
            }
            out.append(formula, cursor, span[0]).append('0');
            cursor = span[1];
        }
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

    /** Um nome que a formula pode usar, ja apontando para onde buscar o valor. */
    private record Token(String id, String display) {
    }

    /**
     * Todos os nomes resolviveis, indexados pela chave normalizada.
     *
     * <p><b>Por que o mapa e montado a cada chamada e nao guardado:</b> o
     * {@link SheetModel} muda quando o Mestre edita a ficha, e um cache aqui
     * resolveria o preset contra um modelo velho depois de uma edicao. O mapa e
     * pequeno (dez atributos, tres nomes cada) e a rolagem nao e um caminho quente:
     * refazer o mapa e mais barato que acertar a invalidacao.
     */
    private static Map<String, Token> table(SheetModel model) {
        Map<String, Token> table = new LinkedHashMap<>();
        if (model == null) {
            return table;
        }
        for (SheetModel.AttributeDef def : model.attributes()) {
            add(table, def.id(), def.label(), def.name());
        }
        return table;
    }

    private static void add(Map<String, Token> table, String... names) {
        for (String name : names) {
            String k = key(name);
            if (k.isEmpty()) {
                continue;
            }
            // `putIfAbsent`: o primeiro nome que chegou e o dono da chave. E o que
            // evita que "STR" e "Strength" fiquem disputando a mesma chave com ids
            // diferentes, quando o modelo repete um nome em dois atributos.
            table.putIfAbsent(k, new Token(names[0], names[names.length - 1]));
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
            // `kh3`/`dl1`/`c4` sao OPERADOR de dado, pelo mesmo motivo do `d20` acima.
            int operator = operatorLength(formula, i);
            if (operator > 0) {
                i += operator;
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
                // ("unknown attribute 'CarismaMaximo'") e nao a primeira letra dela.
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

    /**
     * Quantas letras, contadas de {@code i}, formam um OPERADOR de dado do
     * {@link DiceFormula}; {@code 0} se aqui comeca um nome de atributo.
     *
     * <p><b>Por que este metodo existe (02/10/2026):</b> preset com {@code 6#2d6dl1}
     * era recusado com {@code unknown attribute 'dl'}. O {@link #isDiceAt} andava ate o
     * fim do {@code d6} e o scanner caia no {@code dl} do {@code dl1}: nao achava
     * atributo com esse nome e devolvia a palavra inteira, como se a jogadora tivesse
     * escrito o nome {@code dl}. {@code dl} e {@code kh} sao operadores do
     * {@code DiceFormula}, entao o scanner precisa saber disso tanto quanto sabe que
     * {@code d6} e dado. O {@code isDiceAt} so olhava para o que vem DEPOIS do
     * {@code d}, e depois do dado vem o operador.
     *
     * <p><b>Por que os cinco e nao dois (05/10/2026):</b> a lista encolheu em dois e
     * por isso {@code /rpg roll 4#2d6c4} era recusado com {@code unknown attribute
     * 'c'} -- a formula do critico, justamente a que a jogadora usa todo turno. Sao
     * cinco operadores comecados em letra, e a lista precisa fechar com o
     * {@code DiceFormula}: {@code kh}/{@code kl}/{@code dh}/{@code dl} e {@code c}.
     * Os demais ({@code !}, {@code >>}, {@code <<}, {@code ++}, {@code --}) nao
     * comecam em letra e por isso nunca chegam aqui.
     *
     * <p>O numero apos {@code kh}/{@code kl}/{@code dh}/{@code dl} e obrigatorio
     * porque esses operadores sem dado antes nao tem o que manter ou descartar: o
     * {@code DiceFormula} recusa assim mesmo. A guarda existe para o scanner nao
     * acusar a jogadora de escrever um atributo chamado {@code dl}, e nao para
     * aceitar a formula.
     *
     * <p><b>Por que {@code c} e diferente:</b> no {@code DiceFormula} o limiar do
     * critico e opcional ({@code 2d6c} usa a face maxima), entao o operador e a letra
     * sozinha. O criterio aqui e "a proxima coisa <b>nao e letra</b>", e nao "a
     * proxima coisa e digito": {@code 2d6c4} e {@code 2d6c} precisam passar, e
     * {@code Conhecimento} precisa continuar sendo nome. Esse criterio separa os dois
     * casos porque um nome de atributo so avanca por letra, enquanto depois de um
     * operador pode vir digito, espaco, {@code +}, {@code )} ou o fim da formula.
     */
    private static int operatorLength(String formula, int i) {
        char first = Character.toLowerCase(formula.charAt(i));
        if (first == 'c') {
            return (i + 1 >= formula.length() || !Character.isLetter(formula.charAt(i + 1)))
                    ? 1
                    : 0;
        }
        if (i + 2 >= formula.length()) {
            return 0;
        }
        char second = Character.toLowerCase(formula.charAt(i + 1));
        boolean keepDrop = (first == 'k' && (second == 'h' || second == 'l'))
                || (first == 'd' && (second == 'h' || second == 'l'));
        return keepDrop && Character.isDigit(formula.charAt(i + 2)) ? 2 : 0;
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
     * <p><b>Por que so os cinco primeiros:</b> o modelo pode ter muitos atributos
     * depois de varias edicoes do Mestre, e a lista inteira empurraria a frase para
     * fora da tela do jogo. Cinco ja dizem o formato ("Strength", "Dexterity...") e
     * o jogador acerta o resto sozinho.
     */
    private static ResolveException unknownAttribute(String raw, Map<String, Token> table) {
        List<String> names = new ArrayList<>();
        for (Token token : table.values()) {
            if (!names.contains(token.display)) {
                names.add(token.display);
            }
        }
        String head = "unknown attribute '" + raw + "' in formula";
        if (names.isEmpty()) {
            return new ResolveException(head + ". This sheet model has no attributes",
                    "message.tabletoprpg.formula.resolve.unknown_attribute_no_model", raw);
        }
        int shown = Math.min(5, names.size());
        String list = String.join(", ", names.subList(0, shown));
        if (names.size() > shown) {
            return new ResolveException(head + ". Valid names: " + list
                            + ", ... (" + names.size() + " total)",
                    "message.tabletoprpg.formula.resolve.unknown_attribute_more",
                    raw, list, names.size());
        }
        return new ResolveException(head + ". Valid names: " + list,
                "message.tabletoprpg.formula.resolve.unknown_attribute", raw, list);
    }
}
