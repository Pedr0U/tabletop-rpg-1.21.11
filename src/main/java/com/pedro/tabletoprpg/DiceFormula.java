package com.pedro.tabletoprpg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Motor de formulas de dados: le o texto do jogador e devolve o total ja
 * rolado, com o detalhamento de cada dado.
 *
 * <p><b>Por que uma classe separada e sem Minecraft:</b> a classe e Java puro
 * de proposito. O parser e o limitador de explosao sao a parte com mais chance
 * de erro silencioso do comando {@code /rpg roll}, e assim eles rodam em
 * {@code src/test/java} com rolagem deterministica (um {@link IntUnaryOperator}
 * controlado pelo teste) em vez de so serem verificados abrindo o jogo.
 *
 * <p><b>Separacao com o chat:</b> {@link Outcome} devolve os termos ja
 * separados ({@link Part} com sinal), e nao uma frase pronta. O
 * {@code MasterCommands} monta a mensagem com codigo de cor, porque cor e
 * coisa de Minecraft e esta classe nao depende dele.
 *
 * <p><b>Ordem de resolucao dentro de um dado (decisao do usuario, 29/09/2026):</b>
 * <ol>
 *   <li>rola {@code count} dados de {@code sides} lados cada, um por dado;</li>
 *   <li>cada dado que atinge o limiar de explosao continua rolando, e a cadeia
 *       inteira conta como <b>um</b> dado (soma das faces da cadeia);</li>
 *   <li>soma {@code ++N} / {@code --N} uma vez por dado inicial, depois da
 *       cadeia -- e o que faz {@code 4d6++2dl1} ser "some 8 e joga fora o
 *       pior", que e a leitura pretendida do comando;</li>
 *   <li>aplica {@code kh}/{@code kl}/{@code dh}/{@code dl} sobre os valores ja
 *       modificados;</li>
 *   <li>{@code >>N} / {@code &lt;&lt;N} trocam a soma pela <b>contagem</b> de
 *       dados que passaram do limiar.</li>
 * </ol>
 *
 * <p><b>Explosao garantida e recusada, nao ignorada:</b> o pedido do usuario foi
 * "dados explosivo que explodem com 100%% de chance vao ser ignorados". Descartar
 * o termo em silencio daria um total errado sem o jogador perceber; por isso
 * {@link #parse} recusa a formula inteira com mensagem explicativa.
 */
public final class DiceFormula {

    /** Limite de dados declarados num {@code NdM}. O mesmo teto do codigo antigo. */
    public static final int MAX_DICE = 100;
    /** Teto de faces de um dado. */
    public static final int MAX_SIDES = 1000;
    /** Teto de repeticoes do operador {@code N#}. */
    public static final int MAX_REPEAT = 100;
    /** Teto do modificador por dado ({@code ++N} / {@code --N}). */
    public static final int MAX_PER_DIE = 999;
    /** Teto dos limiares (explosao, contagem, critico, keep/drop). */
    public static final int MAX_THRESHOLD = 1000;
    /**
     * Teto de rolagens individuais de uma formula inteira. Existe porque
     * explosao nao tem teto closed-form: {@code 100d1000!} pode, no pior caso,
     * gerar uma sequencia enorme de rolagens. O orcamento e encerrado no meio
     * da execucao e vira erro, em vez de travar o servidor.
     */
    public static final int MAX_TOTAL_ROLLS = 100_000;
    /** Quantos dados aparecem no detalhamento antes de virar "...". */
    public static final int DISPLAY_LIMIT = 20;
    /**
     * Quantas voltas de {@code N#} sao mostradas linha a linha antes de virar
     * "...+N more".
     *
     * <p><b>Teto separado do {@link #DISPLAY_LIMIT} de proposito:</b> aquele
     * limita as <b>faces</b> de um dado grande ({@code 30d6} vira
     * {@code [...+10 more]}); este limita as <b>rodadas</b> de uma repeticao.
     * Sao dois problemas de tamanho diferentes, com numeros hoje iguais so por
     * coincidencia, e um teto so esconderia um dos dois ao mudar.
     */
    public static final int DISPLAY_ROUNDS = 20;

    private DiceFormula() {
    }

    /** Formula invalida, com a raza em texto pronto para o chat. */
    public static final class SyntaxException extends Exception {
        public SyntaxException(String message) {
            super(message);
        }
    }

    /**
     * Uma peca do resultado, com <b>estrutura</b> e nao so texto pronto.
     *
     * <p><b>Por que estrutura e nao frase:</b> o chat precisa riscar e pintar
     * <b>um dado especifico</b> ({@code 4d6dl1 [2,3,<s>1</s>]}), e uma String
     * ja montada nao diz onde cada face esta. Quem decide a cor e o
     * {@code MasterCommands}, porque cor e coisa de Minecraft.
     *
     * <p>Exatamente um dos tres formatos vale por peca:
     * <ul>
     *   <li>numero fixo: {@link #faces()} e {@link #rounds()} vazios, valor em
     *       {@link #flat()};</li>
     *   <li>rolagem simples: {@link #faces()} com os dados, {@link #rounds()}
     *       vazio, {@link #repeat()} igual a 1;</li>
     *   <li>repeticao {@code N#}: {@link #rounds()} com {@code repeat()}
     *       voltas, {@link #faces()} vazio.</li>
     * </ul>
     */
    public static final class Part {
        private final int sign;
        private final String label;
        private final List<Face> faces;
        private final List<Round> rounds;
        private final long flat;
        private final int repeat;

        private Part(int sign, String label, List<Face> faces, List<Round> rounds, long flat, int repeat) {
            this.sign = sign;
            this.label = label;
            this.faces = List.copyOf(faces);
            this.rounds = List.copyOf(rounds);
            this.flat = flat;
            this.repeat = repeat;
        }

        static Part fixed(int sign, long value) {
            return new Part(sign, "", List.of(), List.of(), value, 1);
        }

        static Part rolled(int sign, String label, List<Face> faces) {
            return new Part(sign, label, faces, List.of(), 0L, 1);
        }

        static Part repeated(int sign, String label, List<Round> rounds) {
            return new Part(sign, label, List.of(), rounds, 0L, rounds.size());
        }

        public int sign() {
            return sign;
        }

        /** {@code "d20"}, {@code "4d6dl1"}; vazio em numero fixo. */
        public String label() {
            return label;
        }

        /** Dados na ordem em que sairam, com o descarte no lugar; vazio senao. */
        public List<Face> faces() {
            return faces;
        }

        /** Uma volta por repeticao de {@code N#}; vazio quando nao ha {@code #}. */
        public List<Round> rounds() {
            return rounds;
        }

        /** Valor absoluto do numero fixo. So vale quando {@link #faces()} e {@link #rounds()} sao vazios. */
        public long flat() {
            return flat;
        }

        /** {@code 1}, ou o {@code N} de {@code N#}. */
        public int repeat() {
            return repeat;
        }

        /**
         * O mesmo resultado em texto puro, sem cor. Continua existindo porque o
         * log e o teste precisam de uma linha comparavel, mas nao e mais a fonte
         * do chat: quem monta a mensagem usa {@link #faces()} e {@link #rounds()}.
         */
        public String text() {
            if (faces.isEmpty() && rounds.isEmpty()) {
                return Long.toString(flat);
            }
            if (!rounds.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(repeat).append('#').append(label).append(" [");
                for (int i = 0; i < rounds.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(rounds.get(i).subtotal());
                }
                return sb.append(']').toString();
            }
            return label + ' ' + facesText(faces);
        }

        /** Detalhamento cru com o mesmo teto de faces do chat. */
        private static String facesText(List<Face> diceFaces) {
            StringBuilder sb = new StringBuilder("[");
            int shown = Math.min(diceFaces.size(), DISPLAY_LIMIT);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(diceFaces.get(i).text());
            }
            if (diceFaces.size() > shown) {
                sb.append(",...+").append(diceFaces.size() - shown).append(" more");
            }
            return sb.append(']').toString();
        }
    }

    /**
     * Um dado como saiu no chat, se ele foi cortado por {@code kh}/{@code kl}/
     * {@code dh}/{@code dl} e se ele foi critico.
     *
     * <p><b>O descartado continua na lista, no slot em que caiu:</b> o jogador
     * precisa ver <b>o proprio dado que rolou</b> riscado, e nao uma contagem
     * ("dropped 1"), que nao diz <b>qual</b> foi.
     *
     * <p><b>O {@code critical} e o do dado, e o chat combina com o
     * {@link #discarded}:</b> quem decide a cor e o {@code MasterCommands}, e ele
     * so pinta de amarelo o critico que <b>sobrou</b>. O asterisco do
     * {@link #text()} ja segue a mesma regra, para texto e cor nunca discordarem.
     */
    public static final class Face {
        private final String text;
        private final boolean discarded;
        private final boolean critical;

        private Face(String text, boolean discarded, boolean critical) {
            this.text = text;
            this.discarded = discarded;
            this.critical = critical;
        }

        /** A face como saiu: {@code "6"}, {@code "6!6!2"}, {@code "3(+2)"}, {@code "18*"}. */
        public String text() {
            return text;
        }

        /** {@code true} quando algum {@code kh}/{@code kl}/{@code dh}/{@code dl} cortou este dado. */
        public boolean discarded() {
            return discarded;
        }

        /** {@code true} quando o dado passou do limiar de {@code cN} ou saiu no maximo. */
        public boolean critical() {
            return critical;
        }
    }

    /** Uma volta do operador {@code N#}: os dados dela e o subtotal da linha. */
    public static final class Round {
        private final String label;
        private final List<Face> faces;
        private final List<Part> parts;
        private final long subtotal;
        private final boolean critical;

        private Round(String label, List<Face> faces, List<Part> parts, long subtotal, boolean critical) {
            this.label = label;
            this.faces = List.copyOf(faces);
            this.parts = List.copyOf(parts);
            this.subtotal = subtotal;
            this.critical = critical;
        }

        /** O rotulo do grupo repetido, como o jogador escreveu. */
        public String label() {
            return label;
        }

        /** Dados da volta, na ordem, com o descarte marcado no lugar. */
        public List<Face> faces() {
            return faces;
        }

        /**
         * Os termos internos da volta, um por peca, na ordem em que foram
         * rolados; lista vazia quando a volta repetida e um dado so.
         *
         * <p><b>Por que existe ao lado de {@link #faces()}:</b> a lista de
         * faces achata todos os tipos num colchete so, e a linha da volta perde
         * a informacao de qual face e de qual dado. Quem monta a mensagem
         * precisa dos termos separados para dar um colchete proprio a cada um.
         * {@link #faces()} continua igual, porque e ela que alimenta o critico
         * da volta.
         */
        public List<Part> parts() {
            return parts;
        }

        /** O que entra no {@code " = 9"} do fim da linha. */
        public long subtotal() {
            return subtotal;
        }

        /** {@code true} quando algum dado <b>mantido</b> da volta foi critico. */
        public boolean critical() {
            return critical;
        }
    }

    /** Resultado de uma avaliacao. */
    public static final class Outcome {
        private final long total;
        private final List<Part> parts;
        private final boolean critical;

        Outcome(long total, List<Part> parts, boolean critical) {
            this.total = total;
            this.parts = Collections.unmodifiableList(parts);
            this.critical = critical;
        }

        public long total() {
            return total;
        }

        public List<Part> parts() {
            return parts;
        }

        /** Verdadeiro quando algum dado <b>mantido</b> foi critico (limiar {@code cN} ou face maxima). */
        public boolean critical() {
            return critical;
        }

        /** Detalhamento em texto puro, sem cor. Util para log e teste. */
        public String plainText() {
            StringBuilder sb = new StringBuilder();
            for (Part part : parts) {
                if (sb.isEmpty()) {
                    if (part.sign() < 0) {
                        sb.append('-');
                    }
                } else {
                    sb.append(part.sign() < 0 ? " - " : " + ");
                }
                sb.append(part.text());
            }
            return sb.toString();
        }
    }

    /** Um termo da formula: sinal e corpo. */
    public static final class Term {
        private final int sign;
        private final Group group;

        Term(int sign, Group group) {
            this.sign = sign;
            this.group = group;
        }

        public int sign() {
            return sign;
        }

        public Group group() {
            return group;
        }
    }

    /**
     * Corpo de um termo: um dado (repetido N vezes), um numero fixo ou uma
     * formula entre parenteses. Exatamente um dos tres e preenchido.
     *
     * <p><b>Por que uma formula inteira pode ser corpo de um termo:</b> o
     * {@code N#} passou a valer para o que vem depois dele, e nao so para o
     * primeiro dado. Sem este campo, {@code 2#d20+5} so poderia repetir o
     * {@code d20} -- a constante de fora contaria uma vez, que e a leitura que o
     * jogador nao fez ao digitar o "#" no comeco.
     */
    public static final class Group {
        private final int repeat;
        private final Dice dice;
        private final long flat;
        private final String formulaLabel;
        private final List<Term> terms;

        Group(int repeat, Dice dice, long flat) {
            this(repeat, dice, flat, "", List.of());
        }

        Group(int repeat, Dice dice, long flat, String formulaLabel, List<Term> terms) {
            this.repeat = repeat;
            this.dice = dice;
            this.flat = flat;
            this.formulaLabel = formulaLabel;
            this.terms = List.copyOf(terms);
        }

        public int repeat() {
            return repeat;
        }

        /** O dado, ou {@code null} se o termo for um numero fixo ou uma formula. */
        public Dice dice() {
            return dice;
        }

        public long flat() {
            return flat;
        }

        /** Formula entre parenteses, ou lista vazia quando o termo nao tem uma. */
        boolean formula() {
            return !terms.isEmpty();
        }

        List<Term> terms() {
            return terms;
        }

        /**
         * O texto que o jogador escreveu para a formula do grupo, que e o que a
         * linha da repeticao mostra. Guardar o trecho digitado, em vez de
         * remontar a formula a partir das pecas, mantem o rotulo igual ao que
         * foi digitado.
         */
        String formulaLabel() {
            return formulaLabel;
        }
    }

    /**
     * Um sufixo do dado, do jeito que o jogador escreveu.
     *
     * <p>{@code value} e {@code null} quando o jogador NAO escreveu numero, como
     * em {@code d6!}: o acesso {@link Dice#explodeAt()} resolve esse caso para
     * as faces, e {@link Dice#label()} imprime so o nome, preservando o texto
     * digitado.
     *
     * <p>Em {@code ++N} / {@code --N} o valor ja vem <b>com sinal</b>
     * ({@code --2} guarda {@code -2}), porque a conta e a soma direta; o
     * rotulo tira o sinal de volta porque precisa sair como foi digitado.
     */
    public static final class Suffix {
        private final String name;
        private final Integer value;

        Suffix(String name, Integer value) {
            this.name = name;
            this.value = value;
        }

        /** {@code "!"}, {@code "kh"}, {@code "c"}, {@code ">>"}, {@code "++"}, ... */
        public String name() {
            return name;
        }

        /** Numero do sufixo, ou {@code null} quando nao foi escrito. */
        public Integer value() {
            return value;
        }

        /** {@code true} nos sufixos cujo nome ja carrega o sinal. */
        boolean signed() {
            return "++".equals(name) || "--".equals(name);
        }
    }

    /**
     * Especificacao de um dado com todos os sufixos aceitos, <b>na ordem em que
     * o jogador escreveu</b>.
     *
     * <p><b>Por que uma lista e nao campos soltos:</b> cada sufixo de
     * keep/drop ve o conjunto que o anterior deixou, entao {@code 4d6dl1kh3} e
     * {@code 4d6kh3dl1} dao resultados diferentes. Com a ordem guardada, o
     * {@link #label()} impresso no chat e a rolagem sao exatamente o que o
     * jogador digitou, em vez de uma ordem fixa escondida que ele nunca ve.
     *
     * <p>Os acessores publicos continuam igual: devolvem o valor do sufixo, o
     * padrao ({@code sides} para {@code !} e {@code c} sem numero) quando ele
     * nao tem numero, e {@code -1} quando o sufixo nao existe.
     */
    public static final class Dice {
        private final int count;
        private final int sides;
        private final List<Suffix> suffixes = new ArrayList<>();

        Dice(int count, int sides) {
            this.count = count;
            this.sides = sides;
        }

        public int count() {
            return count;
        }

        public int sides() {
            return sides;
        }

        /** Modificador por dado, com sinal; {@code 0} quando nao existe. */
        public int perDie() {
            for (Suffix suffix : suffixes) {
                if (suffix.signed()) {
                    return suffix.value();
                }
            }
            return 0;
        }

        /** Limiar de explosao; {@code -1} quando nao existe. */
        public int explodeAt() {
            return valueOf("!", -1);
        }

        /** Quantos maiores manter; {@code -1} quando nao existe. */
        public int keepHigh() {
            return valueOf("kh", -1);
        }

        /** Quantos menores manter; {@code -1} quando nao existe. */
        public int keepLow() {
            return valueOf("kl", -1);
        }

        /** Quantos maiores descartar; {@code -1} quando nao existe. */
        public int dropHigh() {
            return valueOf("dh", -1);
        }

        /** Quantos menores descartar; {@code -1} quando nao existe. */
        public int dropLow() {
            return valueOf("dl", -1);
        }

        /** Limiar de critico; {@code -1} quando nao existe. */
        public int critAt() {
            return valueOf("c", -1);
        }

        /** Limiar de {@code >>}; {@code -1} quando nao existe. */
        public int countGe() {
            return valueOf(">>", -1);
        }

        /** Limiar de {@code <<}; {@code -1} quando nao existe. */
        public int countLe() {
            return valueOf("<<", -1);
        }

        /**
         * Valor do sufixo pedido, ja com o padrao resolvido: {@code d6!} e
         * {@code d6!6} estouram em 6, porque sem numero o limiar e a face maxima.
         */
        private int valueOf(String name, int absent) {
            for (Suffix suffix : suffixes) {
                if (suffix.name().equals(name)) {
                    return suffix.value() == null ? sides : suffix.value();
                }
            }
            return absent;
        }

        /** Rotulo do dado como o jogador escreveu, para o detalhamento. */
        public String label() {
            StringBuilder sb = new StringBuilder();
            if (count != 1) {
                sb.append(count);
            }
            sb.append('d').append(sides);
            for (Suffix suffix : suffixes) {
                sb.append(suffix.name());
                if (suffix.value() == null) {
                    continue;
                }
                sb.append(suffix.signed() ? Math.abs(suffix.value()) : suffix.value());
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    /**
     * Le a formula. Espacos e tabulacoes sao ignorados, porque brigadier entrega
     * o argumento como o jogador digitou: {@code 4d6 kh3} e {@code 4d6kh3}
     * precisam dar a mesma coisa.
     *
     * <p><b>A gramatica do {@code N#} cobre a formula inteira:</b> depois do
     * {@code #} vem um grupo, e o grupo sem parenteses vai ate o fim da formula
     * no nivel corrente. E por isso que {@code 2#d20+5} vale {@code 2 x (d20+5)}:
     * o que o jogador digitou depois do {@code #} e o que se repete.
     *
     * @throws SyntaxException se a formula nao bater com a gramatica, se
     *                         ultrapassar um teto, ou se pedir explosao
     *                         garantida
     */
    public static Formula parse(String raw) throws SyntaxException {
        if (raw == null) {
            throw new SyntaxException("empty formula");
        }
        String source = stripSpaces(raw);
        if (source.isEmpty()) {
            throw new SyntaxException("empty formula");
        }

        Cursor cursor = new Cursor(source);
        List<Term> terms = parseTerms(cursor, false);
        if (!cursor.atEnd()) {
            throw new SyntaxException("unexpected '" + cursor.peek() + "' at position " + (cursor.i + 1)
                    + " in '" + source + "'");
        }

        Formula formula = new Formula(source, terms);
        formula.checkStaticBudget();
        return formula;
    }

    private static String stripSpaces(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Le uma sequencia de termos ate onde o <b>nivel corrente</b> acaba: no fim
     * do texto, no {@code )} que fecha o parenteses ou no proximo {@code N#}.
     *
     * <p><b>Por que o proximo {@code N#} fecha o grupo:</b> e o que faz
     * {@code 2#d20+3#d4} valer {@code 2 x d20 + 3 x d4}. Sem esta parada o
     * primeiro {@code #} engoliria o segundo, e o jogador veria uma unica
     * repeticao de um dado que ele nunca pediu.
     *
     * <p>O sinal do proximo termo NAO e consumido antes de olhar o {@code #}: o
     * topo do laco e quem le {@code +} ou {@code -}. Sem o rebobinamento aqui,
     * consumir o sinal e desistir do termo deixaria o sinal orfao na frente do
     * {@code #}, e {@code 2#d20+3#d4} viraria {@code 2 x d20 - 3 x d4}.
     *
     * <p><b>O primeiro termo do grupo nunca e o {@code #} que fecha o grupo:</b>
     * quem chamou esta funcao ja leu o proprio {@code N#}, e o {@code #} do
     * primeiro termo abre um grupo dentro do grupo -- e por isso que
     * {@code 2#(1#d6+2)} repete um {@code #} dentro de outro.
     */
    private static List<Term> parseTerms(Cursor cursor, boolean insideGroup) throws SyntaxException {
        List<Term> terms = new ArrayList<>();
        while (true) {
            int sign = 1;
            int signMark = cursor.i;
            if (cursor.peek() == '+') {
                cursor.next();
            } else if (cursor.peek() == '-') {
                sign = -1;
                cursor.next();
            }
            if (insideGroup && !terms.isEmpty() && startsRepeat(cursor)) {
                cursor.i = signMark;
                break;
            }
            terms.add(new Term(sign, parseGroup(cursor)));

            if (cursor.atEnd()) {
                break;
            }
            char next = cursor.peek();
            if (next == ')') {
                break;
            }
            if (next != '+' && next != '-') {
                throw new SyntaxException("unexpected '" + next + "' at position " + (cursor.i + 1)
                        + " in '" + cursor.text() + "'");
            }
            // O sinal do proximo termo NAO e consumido aqui: o topo do laco e
            // quem le '+' ou '-' e monta o sinal do Term. Consumir aqui
            // destruiria o sinal e "d8-1" viraria "d8+1". O unico detalhe e
            // garantir que sobra um caractere so: se fosse "++", o sufixo por
            // dado ja teria comido os dois.
        }
        return terms;
    }

    /** O que vem a seguir e um {@code N#}, que abre um grupo repetido. */
    private static boolean startsRepeat(Cursor cursor) {
        int mark = cursor.i;
        boolean digits = false;
        while (!cursor.atEnd() && Character.isDigit(cursor.peek())) {
            digits = true;
            cursor.next();
        }
        boolean repeat = digits && cursor.peek() == '#';
        cursor.i = mark;
        return repeat;
    }

    /**
     * Le {@code ( Formula )}, com aninhamento: o mesmo {@link Cursor} continua
     * andando, entao o grupo interno tem os mesmos sinais, numeros e sufixos do
     * texto de fora.
     *
     * @param openMark posicao do {@code (}, usada para a mensagem de erro
     */
    private static List<Term> parseParenthesized(Cursor cursor, int openMark) throws SyntaxException {
        cursor.next();
        List<Term> terms = parseTerms(cursor, true);
        if (cursor.peek() != ')') {
            throw new SyntaxException("missing ')' for the '(' at position " + (openMark + 1)
                    + " in '" + cursor.text() + "'");
        }
        cursor.next();
        if (terms.isEmpty()) {
            throw new SyntaxException("nothing to roll between the parentheses at position "
                    + (openMark + 1) + " in '" + cursor.text() + "'");
        }
        return terms;
    }

    private static Group parseGroup(Cursor cursor) throws SyntaxException {
        // Prefixo de repeticao: "N#".
        int repeatMark = cursor.i;
        Integer repeatDigits = cursor.readIntOrNull();
        int repeat = 1;
        if (repeatDigits != null && cursor.peek() == '#') {
            repeat = repeatDigits;
            cursor.next();
            if (repeat < 1 || repeat > MAX_REPEAT) {
                throw new SyntaxException("repetition must be 1-" + MAX_REPEAT + " (got " + repeat + ")");
            }
        } else {
            cursor.i = repeatMark;
        }

        // Parentheses: "(...)", com ou sem o prefixo "N#".
        int openMark = cursor.i;
        if (cursor.peek() == '(') {
            List<Term> terms = parseParenthesized(cursor, openMark);
            return new Group(repeat, null, 0L, cursor.slice(openMark + 1, cursor.i - 1), terms);
        }

        if (repeat != 1) {
            // Sem parenteses o grupo e tudo o que vem ate o proximo "N#" ou o
            // fim do nivel corrente, para que "2#d20+5" repita a constante tambem.
            int groupMark = cursor.i;
            List<Term> terms = parseTerms(cursor, true);
            if (terms.isEmpty()) {
                throw new SyntaxException("'" + repeat
                        + "#' must be followed by dice or a formula, like 6#4d6dl1");
            }
            return new Group(repeat, null, 0L, cursor.slice(groupMark, cursor.i), terms);
        }

        // Corpo: NdM com sufixos, ou numero fixo.
        int countMark = cursor.i;
        Integer count = cursor.readIntOrNull();
        char next = cursor.peek();
        if (next == 'd' || next == 'D') {
            cursor.next();
            int diceCount = count == null ? 1 : count;
            int sides = cursor.requireInt("the number of faces after 'd'");
            if (diceCount < 1 || diceCount > MAX_DICE) {
                throw new SyntaxException("dice count must be 1-" + MAX_DICE + " (got " + diceCount + ")");
            }
            if (sides < 1 || sides > MAX_SIDES) {
                throw new SyntaxException("dice sides must be 1-" + MAX_SIDES + " (got " + sides + ")");
            }
            Dice dice = new Dice(diceCount, sides);
            parseSuffixes(cursor, dice);
            return new Group(repeat, dice, 0L);
        }

        cursor.i = countMark;
        return new Group(1, null, cursor.requireInt("a number or a dice term"));
    }

    /**
     * Le os sufixos ate encontrar um {@code +} ou {@code -} simples, que pertence
     * ao proximo termo.
     *
     * <p>A distincao entre "sufixo por dado" e "separador de termo" e o
     * unico lugar realmente delicado do parser, e e feita olhando o caractere
     * seguinte: {@code 4d6++2} e dois sinais juntos, entao e {@code ++2} por
     * dado; {@code 4d6+2} tem um sinal so, entao sao dois termos. Sem essa
     * olhadinha, {@code ++} seria lido como "mais, mais nada" e a formula
     * seria aceita com o total errado.
     */
    private static void parseSuffixes(Cursor cursor, Dice dice) throws SyntaxException {
        while (!cursor.atEnd()) {
            char c = cursor.peek();
            if (c == '!') {
                cursor.next();
                // O sufixo entra antes da validacao para que a mensagem de erro
                // mostre o rotulo que o jogador escreveu ("d1!"), e nao so "d1".
                addSuffix(dice, "!", cursor.readIntOrNull());
                // O limiar de explosao respeita o mesmo teto dos outros: '!' e
                // 'c' e '>>' nao podem aceitar numeros que nenhuma face alcanca
                // sem que o jogador perceba que errou.
                checkThreshold(dice.explodeAt(), "!");
                checkExplode(dice, dice.explodeAt());
            } else if (cursor.match("kh")) {
                addSuffix(dice, "kh", readKeep(cursor, "kh"));
            } else if (cursor.match("kl")) {
                addSuffix(dice, "kl", readKeep(cursor, "kl"));
            } else if (cursor.match("dh")) {
                addSuffix(dice, "dh", readKeep(cursor, "dh"));
            } else if (cursor.match("dl")) {
                addSuffix(dice, "dl", readKeep(cursor, "dl"));
            } else if (cursor.match(">>")) {
                int threshold = readThreshold(cursor, ">>");
                rejectBothCounters(dice, ">>");
                addSuffix(dice, ">>", threshold);
            } else if (cursor.match("<<")) {
                int threshold = readThreshold(cursor, "<<");
                rejectBothCounters(dice, "<<");
                addSuffix(dice, "<<", threshold);
            } else if (c == 'c') {
                cursor.next();
                addSuffix(dice, "c", cursor.readIntOrNull());
                checkThreshold(dice.critAt(), "c");
            } else if (c == '+' && cursor.peekAt(1) == '+') {
                cursor.next();
                cursor.next();
                addSuffix(dice, "++", readLimit(cursor, "++"));
            } else if (c == '-' && cursor.peekAt(1) == '-') {
                cursor.next();
                cursor.next();
                addSuffix(dice, "--", -readLimit(cursor, "--"));
            } else {
                return;
            }
        }
    }

    /**
     * Acrescenta um sufixo recusando repeticao.
     *
     * <p><b>Por que recusar:</b> com campo solto, {@code 4d6kh3kh2} sobrescrevia
     * o 3 pelo 2 e o rotulo impresso ficava {@code 4d6kh2} -- o jogador perdia
     * a primeira parte do que digitou e recebia um resultado diferente, sem
     * aviso. Agora a formula inteira e recusada com mensagem.
     */
    private static void addSuffix(Dice dice, String name, Integer value) throws SyntaxException {
        for (Suffix existing : dice.suffixes) {
            if (existing.name().equals(name)) {
                throw new SyntaxException("suffix '" + name + "' was repeated in '"
                        + dice.label() + "'");
            }
        }
        dice.suffixes.add(new Suffix(name, value));
    }

    private static int readKeep(Cursor cursor, String suffix) throws SyntaxException {
        int n = cursor.requireInt("a number after '" + suffix + "'");
        if (n < 1) {
            throw new SyntaxException("'" + suffix + "' needs at least 1 (got " + n + ")");
        }
        return n;
    }

    private static int readThreshold(Cursor cursor, String suffix) throws SyntaxException {
        int n = cursor.requireInt("a number after '" + suffix + "'");
        checkThreshold(n, suffix);
        return n;
    }

    private static int readLimit(Cursor cursor, String suffix) throws SyntaxException {
        int n = cursor.requireInt("a number after '" + suffix + "'");
        if (n < 1 || n > MAX_PER_DIE) {
            throw new SyntaxException("'" + suffix + "' must be 1-" + MAX_PER_DIE + " (got " + n + ")");
        }
        return n;
    }

    private static void checkThreshold(int value, String suffix) throws SyntaxException {
        if (value < 1 || value > MAX_THRESHOLD) {
            throw new SyntaxException("'" + suffix + "' threshold must be 1-" + MAX_THRESHOLD
                    + " (got " + value + ")");
        }
    }

    /**
     * {@code >>} e {@code <<} ja respondem "quantos acertaram"; os dois juntos
     * nao tem resposta definida. Recusar e melhor que escolher um dos dois em
     * silencio e devolver um total que o jogador nao esperava.
     */
    private static void rejectBothCounters(Dice dice, String added) throws SyntaxException {
        if (">>".equals(added) && dice.countLe() >= 0) {
            throw new SyntaxException("'>>' and '<<' count in opposite directions; use only one");
        }
        if ("<<".equals(added) && dice.countGe() >= 0) {
            throw new SyntaxException("'>>' and '<<' count in opposite directions; use only one");
        }
    }

    /**
     * Recusa explosao garantida. Um limiar menor ou igual a 1 acerta sempre,
     * porque dado nenhum rola abaixo de 1, e a cadeia nunca termina. Isso
     * inclui {@code d1!} (limiar padrao = 1 lado) e {@code 1d1!1}.
     *
     * <p>Um limiar <b>maior</b> que as faces nao e erro: o dado simplesmente
     * nunca explode, que e uma rolagem valida e observavel.
     */
    private static void checkExplode(Dice dice, int threshold) throws SyntaxException {
        if (threshold < 2) {
            throw new SyntaxException("'" + dice.label()
                    + "' explodes 100% of the time and would never stop");
        }
    }

    // ------------------------------------------------------------------
    // Avaliacao
    // ------------------------------------------------------------------

    /** Formula lida, pronta para ser rolada quantas vezes o jogador quiser. */
    public static final class Formula {
        private final String source;
        private final List<Term> terms;

        Formula(String source, List<Term> terms) {
            this.source = source;
            this.terms = Collections.unmodifiableList(terms);
        }

        /** Texto normalizado (sem espacos) que o jogador digitou. */
        public String source() {
            return source;
        }

        public List<Term> terms() {
            return terms;
        }

        /**
         * Recusa formula que ja sabemos ser grande demais <b>antes</b> de rolar
         * qualquer coisa, olhando so o que o jogador escreveu.
         *
         * <p>Serve para o caso comum, em que a formula nao tem explosao: ai o
         * total de rolagens e exatamente a soma de {@code count * repeat}, e da
         * para recusar na hora. Quando ha explosao esse numero nao tem limite
         * fechado, e quem barra e o orcamento em tempo de execucao.
         *
         * <p>Os parenteses entram na conta com o {@code repeat} de fora: e o que
         * impede {@code 100#(100d6)} de passar por baixo do teto so porque cada
         * volta, sozinha, cabe nele.
         */
        void checkStaticBudget() throws SyntaxException {
            long declared = countRolls(terms);
            if (declared > MAX_TOTAL_ROLLS) {
                throw new SyntaxException("this formula asks for " + declared
                        + " dice; the limit is " + MAX_TOTAL_ROLLS);
            }
        }

        /**
         * Rola a formula.
         *
         * @param roller recebe o numero de faces e devolve um valor de 1 a faces;
         *               o teste passa um valor fixo para o resultado ser
         *               previsivel
         * @throws SyntaxException se estourar o orcamento de rolagens
         */
        public Outcome evaluate(IntUnaryOperator roller) throws SyntaxException {
            Budget budget = new Budget();
            List<Part> parts = new ArrayList<>();
            long total = rollTerms(terms, parts, roller, budget);
            return new Outcome(total, parts, budget.groupCritical);
        }
    }

    /**
     * Dados declarados pela formula, contando o {@code repeat} em cada nivel.
     *
     * <p>Satura em {@link #MAX_TOTAL_ROLLS} + 1 porque so interessa saber se o
     * orcamento estourou, e porque {@code 100#(100#(100#d6))} multiplica por 100
     * varias vezes -- sem o teto, um parenteses muito aninhado estouraria o
     * {@code long} e voltaria positivo, que e a mesma falha silenciosa que o
     * {@link Cursor#readIntOrNull()} ja evita com numeros digitados.
     */
    private static long countRolls(List<Term> terms) {
        long declared = 0;
        for (Term term : terms) {
            Group group = term.group;
            if (group.formula()) {
                declared = saturate(declared + countRolls(group.terms()) * group.repeat());
            } else if (group.dice != null) {
                declared = saturate(declared + (long) group.dice.count() * group.repeat());
            }
        }
        return declared;
    }

    private static long saturate(long rolls) {
        return rolls > MAX_TOTAL_ROLLS ? MAX_TOTAL_ROLLS + 1L : rolls;
    }

    /**
     * Rola uma lista de termos, somando o total e guardando uma peca por termo.
     *
     * <p><b>O mesmo metodo serve para a formula inteira e para o conteudo de um
     * grupo:</b> e o que faz {@code 4#(2d6+1d8+5)} e {@code 2#d20+5} darem
     * exatamente o mesmo total que o jogador somaria na mao, em vez de repetir
     * so o primeiro dado.
     */
    private static long rollTerms(List<Term> terms, List<Part> parts, IntUnaryOperator roller, Budget budget)
            throws SyntaxException {
        long total = 0;
        for (Term term : terms) {
            total += term.sign() * rollTerm(term, parts, roller, budget);
        }
        return total;
    }

    private static long rollTerm(Term term, List<Part> parts, IntUnaryOperator roller, Budget budget)
            throws SyntaxException {
        Group group = term.group;
        if (group.formula()) {
            return rollFormulaGroup(term, group, parts, roller, budget);
        }

        Dice dice = group.dice;
        if (dice == null) {
            long value = group.flat();
            parts.add(Part.fixed(term.sign(), Math.abs(value)));
            return value;
        }

        long groupTotal;
        if (group.repeat() > 1) {
            // Com repeticao o detalhamento que importa e o subtotal de
            // cada volta, linha a linha; a lista corrida de 24 faces nao
            // ajuda ninguem. O texto puro ainda resume em uma linha.
            List<Round> rounds = new ArrayList<>(group.repeat());
            groupTotal = 0;
            for (int round = 0; round < group.repeat(); round++) {
                List<DieResult> list = rollPool(dice, roller, budget);
                applyKeepDrop(dice, list);
                long subtotal = reduce(dice, list);
                markCritical(list, budget);
                List<Face> faces = facesOf(dice, list);
                // Lista vazia: a volta repetida e um dado so, e nao ha termos
                // internos para a linha separar.
                rounds.add(new Round(dice.label(), faces, List.of(), subtotal, anyKeptCritical(faces)));
                groupTotal += subtotal;
            }
            parts.add(Part.repeated(term.sign(), dice.label(), rounds));
        } else {
            // O rotulo do dado ("d20", "4d6kh3") vem antes das faces.
            // Sem ele o chat mostraria so "[7]" e o jogador nao
            // saberia o que rolou, que era o formato antigo.
            List<DieResult> list = rollPool(dice, roller, budget);
            applyKeepDrop(dice, list);
            groupTotal = reduce(dice, list);
            markCritical(list, budget);
            parts.add(Part.rolled(term.sign(), dice.label(), facesOf(dice, list)));
        }

        return groupTotal;
    }

    /**
     * Grupo repetido que tem formula dentro: cada volta rola a formula inteira
     * de novo, com dados novos, e vira uma linha com o subtotal ja com as
     * constantes do grupo.
     */
    private static long rollFormulaGroup(Term term, Group group, List<Part> parts,
                                         IntUnaryOperator roller, Budget budget) throws SyntaxException {
        if (group.repeat() == 1) {
            // Sem '#' nao ha linha para montar: os termos de dentro entram direto
            // na mensagem, como se o parenteses nao existisse.
            return rollTerms(group.terms(), parts, roller, budget);
        }

        String label = group.formulaLabel();
        List<Round> rounds = new ArrayList<>(group.repeat());
        long groupTotal = 0;
        for (int round = 0; round < group.repeat(); round++) {
            List<Part> roundParts = new ArrayList<>();
            long subtotal = rollTerms(group.terms(), roundParts, roller, budget);
            List<Face> faces = facesOf(roundParts);
            rounds.add(new Round(label, faces, roundParts, subtotal, anyKeptCritical(faces)));
            groupTotal += subtotal;
        }
        parts.add(Part.repeated(term.sign(), label, rounds));
        return groupTotal;
    }

    /** Congela a lista de dados no formato que o chat consome. */
    private static List<Face> facesOf(Dice dice, List<DieResult> list) {
        List<Face> faces = new ArrayList<>(list.size());
        for (DieResult die : list) {
            faces.add(new Face(die.describe(dice), die.discarded, die.critical));
        }
        return faces;
    }

    /**
     * Junta as faces das pecas de uma volta do grupo repetido, na ordem em que
     * sairam, para a linha mostrar tudo que rolou naquela volta.
     */
    private static List<Face> facesOf(List<Part> parts) {
        List<Face> faces = new ArrayList<>();
        for (Part part : parts) {
            faces.addAll(part.faces());
        }
        return faces;
    }

    /**
     * Algum dado <b>mantido</b> da volta foi critico?
     *
     * <p>Usa a face, e nao o {@code Budget}: o {@code groupCritical} do orcamento
     * e acumulado de toda a rolagem para o {@code CRIT} do fim da mensagem, e
     * nao serve para dizer qual das linhas foi a que teve critico.
     */
    private static boolean anyKeptCritical(List<Face> faces) {
        for (Face face : faces) {
            if (!face.discarded() && face.critical()) {
                return true;
            }
        }
        return false;
    }

    /**
     * O CRIT so pode vir dos dados que <b>sobreviveram</b> ao keep/drop.
     *
     * <p><b>Por que so depois do keep/drop:</b> o asterisco aparece apenas nos
     * dados mantidos, entao marcar antes devolveria "CRIT" com nenhum {@code *}
     * visivel na tela -- o chat affirmando uma coisa e o detalhe mostrando
     * outra, num resultado que ninguem questiona.
     */
    private static void markCritical(List<DieResult> list, Budget budget) {
        for (DieResult die : list) {
            if (!die.discarded && die.critical) {
                budget.groupCritical = true;
                return;
            }
        }
    }

    /**
     * Rola os {@code count} dados do grupo, ja com cadeia de explosao e
     * modificador por dado.
     */
    private static List<DieResult> rollPool(Dice dice, IntUnaryOperator roller, Budget budget)
            throws SyntaxException {
        List<DieResult> rolled = new ArrayList<>(dice.count());
        for (int i = 0; i < dice.count(); i++) {
            rolled.add(rollOneDie(dice, roller, budget));
        }
        return rolled;
    }

    /**
     * Converte os dados <b>sobreviventes</b> em um unico numero: a soma, ou a
     * <b>contagem</b> quando a formula pediu {@code >>N} / {@code &lt;&lt;N}.
     *
     * <p>Os descartados ficam na lista para o chat riscar, mas nao entram na
     * conta: somar um dado que o jogador mandou jogar fora daria um total que
     * ele nao aceitaria, e a marcacao visual existe justamente para mostrar que
     * aquele dado nao contou.
     */
    private static long reduce(Dice dice, List<DieResult> list) {
        if (dice.countGe() >= 0) {
            long hits = 0;
            for (DieResult die : list) {
                if (!die.discarded && die.value >= dice.countGe()) {
                    hits++;
                }
            }
            return hits;
        }
        if (dice.countLe() >= 0) {
            long hits = 0;
            for (DieResult die : list) {
                if (!die.discarded && die.value <= dice.countLe()) {
                    hits++;
                }
            }
            return hits;
        }
        long sum = 0;
        for (DieResult die : list) {
            if (!die.discarded) {
                sum += die.value;
            }
        }
        return sum;
    }

    /** Um dado ja rolado: cadeia de explosao, modificador e marca de critico. */
    private static final class DieResult {
        private String faces;
        private long value;
        private boolean critical;
        private boolean discarded;

        DieResult(String faces, long value) {
            this.faces = faces;
            this.value = value;
        }

        /**
         * Como o dado aparece no chat: a cadeia de explosao, o modificador por
         * dado entre parenteses e um {@code *} no critico.
         *
         * <p>O valor final do dado e sempre a soma da cadeia mais o
         * modificador, porque {@code ++N} entra uma vez por dado inicial
         * (ver a ordem de resolucao na classe). Mostrar a soma em vez das
         * faces esconderia o quanto a explosao renderizou, que e justamente o
         * que o jogador quer ver.
         *
         * <p><b>Um dado descartado nunca leva o {@code *}:</b> o asterisco
         * marca "este dado contou e foi critico", e um dado que o jogador
         * mandou jogar fora nao contou. O chat ainda pinta o descartado de
         * vermelho e riscado, que e a marcacao correta dele.
         */
        String describe(Dice dice) {
            StringBuilder sb = new StringBuilder(faces);
            if (dice.perDie() != 0) {
                sb.append('(').append(dice.perDie() > 0 ? "+" : "-")
                        .append(Math.abs(dice.perDie())).append(')');
            }
            if (critical && !discarded) {
                sb.append('*');
            }
            return sb.toString();
        }
    }

    private static DieResult rollOneDie(Dice dice, IntUnaryOperator roller, Budget budget) throws SyntaxException {
        StringBuilder chain = new StringBuilder();
        long sum = 0;
        int face = nextRoll(roller, dice.sides(), budget);
        sum += face;
        chain.append(face);
        while (dice.explodeAt() >= 0 && face >= dice.explodeAt()) {
            face = nextRoll(roller, dice.sides(), budget);
            sum += face;
            chain.append('!').append(face);
        }

        DieResult die = new DieResult(chain.toString(), sum + dice.perDie());
        // Sem "cN" o critico e o maximo do dado (decisao do usuario, 30/09/2026):
        // "2d20" saiu 20 e um 20 e o melhor resultado que o dado pode dar. Com
        // "cN" o limiar escrito e o que vale, porque ai o jogador disse qual
        // numero conta como critico.
        die.critical = dice.critAt() >= 0 ? die.value >= dice.critAt() : die.value >= dice.sides();
        return die;
    }

    private static int nextRoll(IntUnaryOperator roller, int sides, Budget budget) throws SyntaxException {
        if (budget.rolls >= MAX_TOTAL_ROLLS) {
            throw new SyntaxException("this formula rolled more than " + MAX_TOTAL_ROLLS
                    + " dice; the exploding dice did not stop");
        }
        budget.rolls++;
        int value = roller.applyAsInt(sides);
        if (value < 1 || value > sides) {
            throw new SyntaxException("the dice roller returned " + value + " for a d" + sides);
        }
        return value;
    }

    /**
     * Aplica {@code kh}/{@code kl}/{@code dh}/{@code dl} <b>na ordem em que o
     * jogador escreveu</b>, porque cada um muda o conjunto que o proximo
     * enxerga: em {@code 4d6dl1kh3} o descarte vem antes, e o resultado e
     * outro. Uma ordem fixa escondida faria o total nao ser o que foi pedido,
     * e o rotulo impresso nem mostraria a troca.
     *
     * <p><b>A lista nao encolhe: o descarte marca no lugar.</b> Antes os
     * descartados sumiam e viravam um "dropped N" sem dizer <b>qual</b> dado
     * foi. Agora o dado que o jogador mandou jogar fora continua visivel, no
     * slot em que saiu, marcado em {@link DieResult#discarded}.
     */
    private static void applyKeepDrop(Dice dice, List<DieResult> list) {
        for (Suffix suffix : dice.suffixes) {
            switch (suffix.name()) {
                case "kh" -> sliceInPlace(list, suffix.value(), true, false);
                case "kl" -> sliceInPlace(list, suffix.value(), false, false);
                case "dh" -> sliceInPlace(list, suffix.value(), true, true);
                case "dl" -> sliceInPlace(list, suffix.value(), false, true);
                default -> {
                    // Os demais sufixos nao mexem no conjunto de dados.
                }
            }
        }
    }

    /**
     * Corta a lista no lugar, do jeito que o jogador pediu ver.
     *
     * <p><b>A regra dos slots, que e o ponto inteiro:</b> os sobreviventes
     * voltam para os <b>mesmos slots</b> que ocupavam, so o valor dentro deles
     * muda. E o que faz {@code 4d20kh2} continuar mostrando {@code 18,17,14,10}
     * (o {@code kh} ordena de forma decrescente, entao os dois menores caem no
     * fim) e {@code 4d6dl1} continuar mostrando {@code 1,2,3,4} com o 1
     * marcado (o {@code dl} ordena de forma crescente, entao o menor cai no
     * comeco). Se os sobreviventes fossem para o comeco da lista, o
     * {@code dl} viraria {@code 2,3,4,1} e o jogador perderia a leitura de
     * "qual face eu rolei".
     *
     * <p><b>Ordena so quem ainda esta vivo:</b> um sufixo posterior enxerga o
     * conjunto que o anterior deixou, nunca os ja cortados.
     *
     * @param highest ordena de forma decrescente ({@code kh}/{@code dh}) ou
     *                crescente ({@code kl}/{@code dl})
     * @param drop    {@code true} corta a <b>cabeca</b> da ordem
     *                ({@code dh}/{@code dl}), {@code false} mantem so a
     *                cabeca ({@code kh}/{@code kl})
     */
    private static void sliceInPlace(List<DieResult> list, int n, boolean highest, boolean drop) {
        List<Integer> ordered = new ArrayList<>();
        for (int slot = 0; slot < list.size(); slot++) {
            if (!list.get(slot).discarded) {
                ordered.add(slot);
            }
        }
        if (ordered.isEmpty()) {
            return;
        }
        // Empate vai para a frente: foi o que rolou primeiro, que era o
        // criterio do codigo antigo e nao pode mudar agora.
        ordered.sort((a, b) -> {
            int byValue = Long.compare(list.get(a).value, list.get(b).value);
            return byValue != 0 ? (highest ? -byValue : byValue) : Integer.compare(a, b);
        });

        int cut = Math.min(n, ordered.size());
        List<DieResult> keptInOrder = new ArrayList<>(ordered.size());
        for (int position = 0; position < ordered.size(); position++) {
            DieResult die = list.get(ordered.get(position));
            if (drop ? position >= cut : position < cut) {
                // Copia, e nao a referencia: os sobreviventes vao trocar de
                // slot entre si, e ler do slot depois de ja ter escrito nele
                // daria o mesmo valor em dois dados.
                keptInOrder.add(copyOf(die));
            } else {
                die.discarded = true;
            }
        }

        // O valor ordenado volta para os slots em ordem de lista: e o multiset
        // de sobreviventes que interessa para a conta, e o slot e do dado que
        // o jogador ve.
        List<Integer> keptSlots = new ArrayList<>(keptInOrder.size());
        for (int slot = 0; slot < list.size(); slot++) {
            if (!list.get(slot).discarded) {
                keptSlots.add(slot);
            }
        }
        for (int i = 0; i < keptSlots.size(); i++) {
            DieResult target = list.get(keptSlots.get(i));
            DieResult source = keptInOrder.get(i);
            target.faces = source.faces;
            target.value = source.value;
            target.critical = source.critical;
        }
    }

    private static DieResult copyOf(DieResult source) {
        DieResult copy = new DieResult(source.faces, source.value);
        copy.critical = source.critical;
        return copy;
    }

    /**
     * Estado que atravessa a formula inteira: quantas rolagens ja foram feitas
     * (orcamento de seguranca) e se algum dado ate agora foi critico.
     */
    private static final class Budget {
        int rolls;
        boolean groupCritical;
    }

    // ------------------------------------------------------------------
    // Divisao de "nome da pericia + formula"
    // ------------------------------------------------------------------

    /**
     * Minusculas, sem acento e sem espaco extra, so para comparar nomes.
     *
     * <p>Os nomes padrao includem acento (ex.: "pericia 0" no modelo) e o
     * jogador digita sem acento no teclado, entao comparar direto nunca casaria.
     *
     * <p><b>Por que vive aqui e nao no comando:</b> a divisao de
     * "Pericia 0-2" precisa do MESMO criterio de comparacao nos dois lados, e
     * dois criterios diferentes em dois arquivos ja produziram bug silencioso
     * (o nome casava num lado e nao no outro). Definindo uma vez, os dois lados
     * nao podem divergir, e da para testar sem Minecraft.
     */
    public static String normalizeName(String text) {
        String plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return plain.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Acha onde termina um nome de pericia no comeco do texto, para
     * "/rpg roll Pericia 0-2".
     *
     * <p><b>Por que este metodo e puro e vive aqui:</b> e a unica parte do
     * comando que o Minecraft impede de testar, porque depende de ler a ficha
     * do jogador. A regra em si e so texto, entao fica numa classe sem
     * dependencia de Minecraft e ganha teste proprio.
     *
     * <p><b>Por que varrer de tras para frente:</b> o nome pode ter espaco
     * ("Pericia 0"), entao nao da para quebrar no primeiro espaco nem no
     * primeiro separador. Varrendo os separadores do fim para o comeco, o
     * primeiro que casar e sempre o nome mais longo, que e a leitura que o
     * jogador digitou.
     *
     * <p>Os dois lados sao normalizados por {@link #normalizeName} aqui dentro,
     * e nao pelo chamador: quando o criterio era do chamador, um lado
     * normalizou e o outro nao, e nenhum nome casou.
     *
     * @param raw    o que o jogador digitou, com acentos, espacos e maiusculas
     * @param known  os nomes de pericia conhecidos, como o jogador os veria
     * @return o indice do caractere separador, ou {@code -1} se nenhum nome
     * casa no comeco do texto
     */
    public static int findNameSplitPoint(String raw, List<String> known) {
        if (raw == null || known == null || known.isEmpty()) {
            return -1;
        }
        List<String> normalized = new ArrayList<>(known.size());
        for (String name : known) {
            normalized.add(normalizeName(name));
        }
        for (int i = raw.length() - 1; i >= 0; i--) {
            char c = raw.charAt(i);
            if (c != '+' && c != '-') {
                continue;
            }
            String candidate = raw.substring(0, i);
            if (candidate.isBlank()) {
                continue;
            }
            if (normalized.contains(normalizeName(candidate))) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Cursor
    // ------------------------------------------------------------------

    /**
     * Leitura da formula da esquerda para a direita. Precisa ser posicional, e
     * nao por {@code Matcher}, porque {@code ++} so pode ser reconhecido
     * olhando o caractere seguinte.
     */
    private static final class Cursor {
        private final String source;
        private int i;

        Cursor(String source) {
            this.source = source;
        }

        /** O texto original (sem espacos), para as mensagens de erro. */
        String text() {
            return source;
        }

        /** O trecho digitado entre duas posicoes, como o jogador escreveu. */
        String slice(int from, int to) {
            return source.substring(from, to);
        }

        boolean atEnd() {
            return i >= source.length();
        }

        /** Caractere na posicao atual, ou {@code '\0'} no fim. */
        char peek() {
            return peekAt(0);
        }

        char peekAt(int ahead) {
            int at = i + ahead;
            return at < source.length() ? source.charAt(at) : '\0';
        }

        void next() {
            i++;
        }

        boolean match(String literal) {
            if (source.startsWith(literal, i)) {
                i += literal.length();
                return true;
            }
            return false;
        }

        /**
         * Le um inteiro, ou devolve {@code null} se nao ha digito aqui.
         *
         * <p><b>Por que acumular em {@code long}:</b> em {@code int} o numero
         * transborda e volta positivo, e {@code 4294967297d6} seria aceito como
         * {@code 1d6} -- o jogador pediria uma coisa e receberia outra, em
         * silencio. Estourar {@link Integer#MAX_VALUE} e recusado com mensagem,
         * e nao cortado.
         */
        Integer readIntOrNull() throws SyntaxException {
            int start = i;
            long value = 0;
            while (!atEnd() && Character.isDigit(peek())) {
                value = value * 10 + (peek() - '0');
                if (value > Integer.MAX_VALUE) {
                    throw new SyntaxException("number at position " + (start + 1)
                            + " in '" + source + "' is larger than " + Integer.MAX_VALUE);
                }
                next();
            }
            if (i == start) {
                return null;
            }
            return (int) value;
        }

        /** Le um inteiro obrigatorio, com mensagem que diz o que faltou. */
        int requireInt(String what) throws SyntaxException {
            Integer value = readIntOrNull();
            if (value == null) {
                throw new SyntaxException("expected " + what + " at position " + (i + 1)
                        + " in '" + source + "'");
            }
            return value;
        }
    }
}
