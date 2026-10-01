package com.pedro.tabletoprpg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * As 16 cores de Bundle para o Preset de Rolagem.
 *
 * <p><b>01/10/2026:</b> o sprite do item e provisoriamente o do Bundle do vanilla
 * (copiado para {@code assets/tabletop-rpg/textures/item/roll_preset.png}, do mesmo jeito
 * que o Camera Tool copiou o da luneta), convertido em mascara de escala de cinza para
 * que o tint {@code minecraft:dye} possa pintar. A cor entra por componente
 * ({@code DataComponents.DYED_COLOR}), e nao por 17 texturas.
 *
 * <p><b>Feedback do usuario em 01/10/2026: a cor "default" saiu.</b> Antes havia uma
 * opcao "default" que deixava o item sem pintar, com o marrom do Bundle vazio. Para quem
 * abre a lista de cores, "default" e "brown" pareciam a mesma coisa -- e marrom nao e uma
 * cor que se escolha de proposito. Agora toda cor e uma cor de verdade, e o preset
 * sempre nasce pintado.
 *
 * <p><b>Por que os ids continuam em minusculo com {@code _}:</b> assim o comando aceita
 * o mesmo nome que o jogador ve no vanilla e no {@code /give}.
 *
 * <p><b>Nao ha cor "magenta"/"rosa" confundidas:</b> {@code PINK} e o rosa claro e
 * {@code MAGENTA} e o roxo-rosado.
 */
public enum RollPresetColor {

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
    /** Nome mostrado na lista de cores da tela de presets. */
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
     * @return a cor, ou {@link Optional#empty()} se o texto nao for nenhuma das 16
     */
    public static Optional<RollPresetColor> byId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (key.isEmpty()) {
            // Cor ausente: cai na primeira da lista. Antes caia em DEFAULT, que saiu em
            // 01/10/2026; escolher WHITE mantem o preset visivel em vez de sumir.
            return Optional.of(WHITE);
        }
        for (RollPresetColor color : values()) {
            if (color.id.equals(key)) {
                return Optional.of(color);
            }
        }
        return Optional.empty();
    }

    /** Os 16 ids, para a mensagem de erro e para o autocomplete do comando. */
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
     * ficha do jogador por causa dela. Por isso a leitura cai em {@link #WHITE} em vez de
     * lancar. Este caminho tambem e o que faz os presets salvos com o antigo id
     * {@code default} continuarem carregando em vez de sumirem da lista.
     */
    public static RollPresetColor idOrDefault(String raw) {
        return byId(raw).orElse(WHITE);
    }
}
