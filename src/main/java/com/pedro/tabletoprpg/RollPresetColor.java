package com.pedro.tabletoprpg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * As 17 opcoes de cor do Bundle para o Preset de Rolagem.
 *
 * <p><b>01/10/2026:</b> o sprite do item e provisoriamente o do Bundle do vanilla
 * (copiado para {@code assets/tabletop-rpg/textures/item/roll_preset.png}, do mesmo jeito
 * que o Camera Tool copiou o da luneta). A cor entra pelo mesmo caminho do Bundle
 * vanilla, e nao por 17 texturas: {@link #DEFAULT} nao pinta nada e os outros 16 correspondem
 * um a um aos Sixteen {@code DyeColor} do jogo.
 *
 * <p><b>Nao ha cor "magenta"/"rosa" confundidas:</b> {@code PINK} e o rosa claro e
 * {@code MAGENTA} e o roxo-rosado. Os ids seguem o vanilla em minusculas com
 * {@code _} ({@code light_gray}, {@code light_blue}), para o comando aceitar o mesmo
 * nome que o jogador ve no /give e no vanilla.
 */
public enum RollPresetColor {

    /** Sem cor: o sprite fica como esta. */
    DEFAULT("default", "Default", 0xFF8B5A2B),

    WHITE("white", "White", 0xFFF9FFFE),
    LIGHT_GRAY("light_gray", "Light Gray", 0xFF9D9D97),
    GRAY("gray", "Gray", 0xFF474F52),
    BLACK("black", "Black", 0xFF1D1D21),
    BROWN("brown", "Brown", 0xFF835432),
    RED("red", "Red", 0xFFB02E26),
    ORANGE("orange", "Orange", 0xFFF9801D),
    YELLOW("yellow", "Yellow", 0xFFFED83D),
    LIME("lime", "Lime", 0xFF80C71F),
    GREEN("green", "Green", 0xFF5E7C16),
    CYAN("cyan", "Cyan", 0xFF169C9C),
    LIGHT_BLUE("light_blue", "Light Blue", 0xFF3AB3DA),
    BLUE("blue", "Blue", 0xFF3C44AA),
    PURPLE("purple", "Purple", 0xFF8932B1),
    MAGENTA("magenta", "Magenta", 0xFFC74EBD),
    PINK("pink", "Pink", 0xFFF38BAA);

    /** Id usado no comando {@code /rpg preset create <nome> <formula> <cor>}. */
    private final String id;
    /** Nome mostrado no botao "Color" da tela de criacao. */
    private final String displayName;
    /** Cor da amostra desenhada na tela (ARGB). */
    private final int argb;

    RollPresetColor(String id, String displayName, int argb) {
        this.id = id;
        this.displayName = displayName;
        this.argb = argb;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public int argb() {
        return argb;
    }

    /**
     * Procura a cor pelo id, aceitando o que o jogador digitar.
     *
     * <p><b>Por que normaliza:</b> no comando a cor chega como palavra solta e o
     * jogador digita {@code RED}, {@code Red} ou {@code red} com a mesma intencao.
     * O espaco vira {@code _}, para {@code light gray} tambem funcionar.
     *
     * @return a cor, ou {@link Optional#empty()} se o texto nao for nenhuma das 17
     */
    public static Optional<RollPresetColor> byId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (key.isEmpty()) {
            return Optional.of(DEFAULT);
        }
        for (RollPresetColor color : values()) {
            if (color.id.equals(key)) {
                return Optional.of(color);
            }
        }
        return Optional.empty();
    }

    /** Os 17 ids, para a mensagem de erro e para o autocomplete do comando. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (RollPresetColor color : values()) {
            out.add(color.id);
        }
        return out;
    }

    /**
     * Id para mostrar numa linha de erro, mais curto que {@link #ids}.
     *
     * <p>Um preset malformado pode ter vindo de um NBT editado a mao ou de uma versao
     * futura do mod; a cor e so um detalhe visual e nao vale impedir o carregamento da
     * ficha do jogador por causa dela. Por isso a leitura cai em {@link #DEFAULT} em vez
     * de lancar.
     */
    public static RollPresetColor idOrDefault(String raw) {
        return byId(raw).orElse(DEFAULT);
    }
}