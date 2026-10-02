package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.FriendlyByteBuf;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Um Preset de Rolagem: nome, formula e cor.
 *
 * <p><b>01/10/2026.</b> O preset e pessoal (fica no NBT do jogador) e o item do Bundle
 * e so a apresentacao dele. Por isso preset e item sao coisas separadas: deletar o
 * preset nao tira o item do inventario, e usar um item cujo preset morreu so avisa.
 *
 * @param name    nome escolhido pela jogadora, como ela digitou (espacos colapsados)
 * @param formula formula ja normalizada, sem espaco (ex.: {@code 1d20+5})
 * @param colorId id de {@link RollPresetColor} (ex.: {@code red}, {@code light_blue})
 */
public record RollPreset(String name, String formula, String colorId) {

    public static final int MAX_NAME = 32;
    public static final int MAX_FORMULA = 128;

    /**
     * O preset na rede (01/10/2026), usado pela tela de presets.
     *
     * <p>Existe porque o {@link #CODEC} e do NBT e o NBT nao viaja em pacote. O
     * record e o mesmo nas duas pontas, entao o que o servidor manda e o que o
     * cliente guarda.
     */
    public static final StreamCodec<FriendlyByteBuf, RollPreset> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_NAME), RollPreset::name,
                    ByteBufCodecs.stringUtf8(MAX_FORMULA), RollPreset::formula,
                    ByteBufCodecs.stringUtf8(32), RollPreset::colorId,
                    RollPreset::new
            );

    // Sem limite de tamanho por campo: nesta versao do DFU o `Codec.STRING` nao tem
// `maxLen`. O teto do NBT nao e o que segura um preset enorme -- e o clamp do
// construtor compacto abaixo, que corta depois de decodificar.
    public static final Codec<RollPreset> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(RollPreset::name),
            Codec.STRING.fieldOf("formula").forGetter(RollPreset::formula),
            Codec.STRING.fieldOf("color").forGetter(RollPreset::colorId)
    ).apply(i, RollPreset::new));

    /**
     * Nunca lanca: e o caminho do {@link Codec}, que roda sobre NBT de mundo antigo ou
     * editado a mao. Corta o que passou do limite e cai em {@code default} numa cor
     * desconhecida. Quem valida de verdade e {@link #create}.
     */
    public RollPreset {
        name = clamp(name, MAX_NAME);
        formula = clamp(formula, MAX_FORMULA);
        colorId = RollPresetColor.idOrDefault(colorId).id();
    }

    public RollPresetColor color() {
        return RollPresetColor.idOrDefault(colorId);
    }

    /**
     * Chave de busca: minuscula, sem acento, sem espaco e com {@code _}.
     *
     * <p><b>Por que normalizar:</b> o nome vem de digitacao humana em dois lugares
     * ({@code /rpg preset use} e o campo da tela). Sem normalizar, {@code Ataque} e
     * {@code ataque} seriam dois presets diferentes e o jogador perderia o preset sem
     * entender por que. O acento tambem entra: {@code Físico} e {@code fisico} sao o
     * mesmo preset.
     */
    public String key() {
        return normalizeKey(name);
    }

    /**
     * O nome na forma que o comando aceita: espaco vira {@code _}, o resto fica igual.
     *
     * <p><b>Por que espaco quebra o comando:</b> no Brigadier 1.3.10 o
     * {@code StringArgumentType.string()} deixou de ser guloso. Ele le uma palavra sem
     * aspas ou uma frase entre aspas, entao {@code /rpg preset use Dano Espada} falha no
     * parse, antes deste mod ver o nome. A chave de busca ja trocava espaco por
     * {@code _} ({@link #normalizeKey}), entao {@code Dano_Espada} encontra exatamente o
     * mesmo preset: e a forma que a jogadora precisa ver na sugestao e e a que o botao
     * Use precisa mandar.
     *
     * <p><b>Por que nao devolver a {@link #key()}:</b> a chave e minuscula e sem
     * acento porque serve para comparar. Aqui o texto volta para o chat da jogadora, e
     * "dano espada" lido em voz alta seria outro preset.
     */
    public String commandName() {
        return commandName(name);
    }

    /** Igual a {@link #commandName()}, para um nome que ainda nao virou preset. */
    public static String commandName(String rawName) {
        return rawName == null ? "" : rawName.trim().replaceAll("\\s+", "_");
    }

    /**
     * Normaliza um nome digitado para virar chave de busca.
     *
     * <p><b>Estatico de proposito:</b> buscar e deletar precisam desta regra com um nome
     * que ainda NAO virou preset. Se o metodo dependesse do registro, buscar teria que
     * fabricar um {@code RollPreset} descartavel so para chamar {@code key}.
     */
    public static String normalizeKey(String rawName) {
        if (rawName == null) {
            return "";
        }
        String stripped = Normalizer.normalize(rawName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    /**
     * Cria um preset validando o que a jogadora digitou.
     *
     * <p><b>Por que a validacao mora aqui e nao no comando nem na tela:</b> os dois
     * caminhos precisam dar exatamente a mesma mensagem para a mesma entrada. Se cada
     * um validasse por conta, a tela e o comando divergiriam na proxima edicao.
     *
     * @param rawFormula formula como digitada; espaco e tab Sao removidos antes do parse,
     *                   porque e o que o jogador ve na rolagem ("d20 + d10 + 9")
     * @throws PresetException se o nome estiver vazio/grande demais ou a formula nao for
     *                        uma rolagem que o {@link DiceFormula} reconhece
     */
    public static RollPreset create(String rawName, String rawFormula, String rawColorId)
            throws PresetException {

        String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new PresetException("preset name is empty");
        }
        if (name.length() > MAX_NAME) {
            throw new PresetException("preset name must be at most " + MAX_NAME
                    + " characters (got " + name.length() + ")");
        }

        String formula = rawFormula == null ? "" : rawFormula.replaceAll("[ \\t]", "");
        if (formula.isEmpty()) {
            throw new PresetException("formula is empty");
        }
        if (formula.length() > MAX_FORMULA) {
            throw new PresetException("formula must be at most " + MAX_FORMULA
                    + " characters (got " + formula.length() + ")");
        }

        // Nomes de atributo e pericia sao conferidos AQUI, e nao na hora da rolagem.
        // O motivo e a mesma razao da validacao de estrutura logo abaixo: se o erro so
        // aparecesse ao rolar, a jogadora descobriria o nome errado tarde demais, e o
        // preset pareceria valido na tela. Recusar no Save com a mensagem do
        // FormulaResolver e o que mantem as duas portas (comando e tela) dizendo a
        // mesma coisa.
        //
        // O `sheet` e null de proposito: aqui so se confere o NOME. Se o id existir
        // no modelo mas a ficha da jogadora nao tiver o valor, isso e problema da
        // ficha e nao do preset -- e a resolucao recusa com o aviso certo na rolagem.
        String forParsing;
        try {
            // O `placeholderFormula` faz as DUAS coisas de uma vez: recusa nome
            // desconhecido e devolve a formula com os nomes trocados por 0.
            forParsing = FormulaResolver.placeholderFormula(formula, SheetModelHolder.current());
        } catch (FormulaResolver.ResolveException e) {
            throw new PresetException(e.getMessage());
        }

        // A estrutura da formula passa pelo MESMO parser que a rolagem usa, mas
        // sobre a formula com os NOMES trocados por 0 (02/10/2026).
        //
        // <b>Por que nao a `formula` crua:</b> o DiceFormula so entende numeros e
        // dados. Passando `1d6+Strength` para ele, a formula seria recusada como
        // sintaxe invalida e o recurso inteiro ficaria inalcancavel pela tela e pelo
        // comando. O `forParsing` e o que sobra quando cada nome vira 0, e sobra
        // exatamente a parte que o parser sabe julgar: dados e sinais.
        //
        // E o `forParsing` que o preset NAO guarda: o preset guarda a `formula` com o
        // nome, porque a resolucao de verdade acontece na rolagem, com a ficha de quem
        // esta rolando.
        try {
            DiceFormula.parse(forParsing);
        } catch (DiceFormula.SyntaxException e) {
            // A mensagem do parser cita a string que ele recebeu, que e o placeholder.
            // Trocar o trecho de volta pela formula original evita que a jogadora
            // veja "unexpected '+' ... in '1d6+0+'" e fique procurando um zero que
            // ela nunca digitou. Quando a formula nao tem nome nenhum, as duas
            // strings sao iguais e a troca nao muda nada.
            throw new PresetException(e.getMessage().replace(forParsing, formula));
        }

        // Cor ausente cai em WHITE: a opcao "default" saiu em 01/10/2026 porque marrom
        // sem tingir parecia a cor "brown" da lista, e o preset ficava indistinguivel.
        String colorId = rawColorId == null || rawColorId.isBlank()
                ? RollPresetColor.WHITE.id()
                : RollPresetColor.byId(rawColorId).orElseThrow(
                        () -> new PresetException("unknown color '" + rawColorId
                                + "'. Valid colors: " + String.join(", ", RollPresetColor.ids()))).id();

        return new RollPreset(name, formula, colorId);
    }

    /** Erro de criacao com frase ja pronta para o chat (sem prefixo de cor). */
    public static class PresetException extends Exception {
        public PresetException(String message) {
            super(message);
        }
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}