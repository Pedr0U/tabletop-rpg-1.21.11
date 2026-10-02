package com.pedro.tabletoprpg;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fichas de Ameaca do Mestre, por UUID, em memoria.
 *
 * <p><b>02/10/2026.</b> Copia exata do padrao do {@link RollPresetStore}, e pelo mesmo
 * motivo: a ficha pertence ao Mestre e o vanilla ja garante que o NBT dele e escrito no
 * logout/save. A gravacao e a leitura ficam no
 * {@link com.pedro.tabletoprpg.mixin.PlayerThreatSheetPersistenceMixin}.
 *
 * <p><b>Quem tem acesso:</b> so o Mestre. A tela esconde o botao e o
 * {@link com.pedro.tabletoprpg.RpgNetworking} recusa o payload de quem nao e, porque
 * esconder botao no cliente nao e permissao.
 */
public final class ThreatSheetStore {

    /** Chave no NBT do jogador. */
    public static final String NBT_KEY = "tabletop_rpg_threat_sheets";

    /** NBT: lista de fichas. O codec ja corta o que passou do limite. */
    public static final com.mojang.serialization.Codec<List<ThreatSheet>> CODEC =
            ThreatSheet.CODEC.listOf();

    /**
     * Teto de fichas por Mestre.
     *
     * <p>200 porque cada ficha e uma tela inteira de edicao: acima disso a lista da
     * tela ja e rolagem pura e o Mestre nao acha ameaca nenhuma.
     */
    public static final int MAX_SHEETS = 200;

    private static final Map<UUID, List<ThreatSheet>> BY_PLAYER = new ConcurrentHashMap<>();

    private ThreatSheetStore() {
    }

    /** Lista na ordem em que o Mestre salvou. Cópia defensiva. */
    public static List<ThreatSheet> snapshot(UUID uuid) {
        if (uuid == null) {
            return List.of();
        }
        return BY_PLAYER.getOrDefault(uuid, List.of());
    }

    /** Substitui tudo. Usado no load do NBT. */
    public static void replaceAll(UUID uuid, List<ThreatSheet> sheets) {
        if (uuid == null) {
            return;
        }
        if (sheets == null || sheets.isEmpty()) {
            BY_PLAYER.remove(uuid);
            return;
        }
        List<ThreatSheet> copy = new ArrayList<>();
        for (ThreatSheet sheet : sheets) {
            if (sheet != null) {
                copy.add(sheet);
            }
            if (copy.size() >= MAX_SHEETS) {
                break;
            }
        }
        BY_PLAYER.put(uuid, Collections.unmodifiableList(copy));
    }

    /** A ficha com essa chave, ou {@code null}. */
    public static ThreatSheet find(UUID uuid, String key) {
        String wanted = RollPreset.normalizeKey(key == null ? "" : key.trim());
        if (wanted.isEmpty()) {
            return null;
        }
        for (ThreatSheet sheet : snapshot(uuid)) {
            if (sheet.key().equals(wanted)) {
                return sheet;
            }
        }
        return null;
    }

    /**
     * Salva uma ficha, criando ou substituindo.
     *
     * <p><b>Por que o nome e a identidade:</b> a lista da tela e a unica coisa que
     * identifica a ficha quando o Mestre edita, entao renomear e trocar o nome no
     * lugar. {@code originalName} vazio significa "criar".
     *
     * @return o motivo da recusa, ou {@link SaveResult#OK}
     */
    public static SaveResult save(ServerPlayer player, ThreatSheet sheet, String originalName) {
        if (player == null || sheet == null) {
            return SaveResult.EMPTY_NAME;
        }
        String key = sheet.key();
        if (key.isEmpty()) {
            return SaveResult.EMPTY_NAME;
        }

        List<ThreatSheet> current = new ArrayList<>(snapshot(player.getUUID()));
        boolean isNew = originalName == null || originalName.isBlank();
        int index = -1;
        if (!isNew) {
            String originalKey = RollPreset.normalizeKey(originalName.trim());
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).key().equals(originalKey)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                // A ficha que o Mestre estava editando sumiu do servidor. Nao criar uma
                // nova no lugar dela: isso perderia a ordem e criaria duplicata sem ele
                // ter pedido.
                return SaveResult.NOT_FOUND;
            }
        }

        // Colisao de nome: outra ficha, nao a que esta sendo editada.
        for (int i = 0; i < current.size(); i++) {
            if (i != index && current.get(i).key().equals(key)) {
                return SaveResult.NAME_TAKEN;
            }
        }
        if (index < 0 && current.size() >= MAX_SHEETS) {
            return SaveResult.LIMIT_REACHED;
        }

        if (index >= 0) {
            // A chave antiga precisa ser lida ANTES da troca. Depois de current.set a
            // ficha anterior deixa de existir na lista, e comparar a chave nova com ela
            // dava UPDATED em toda renomeacao, tornando SaveResult.RENAMED inalcancavel.
            String previousKey = current.get(index).key();
            current.set(index, sheet);
            BY_PLAYER.put(player.getUUID(), Collections.unmodifiableList(current));
            return previousKey.equals(key) ? SaveResult.UPDATED : SaveResult.RENAMED;
        }
        current.add(sheet);
        BY_PLAYER.put(player.getUUID(), Collections.unmodifiableList(current));
        return SaveResult.CREATED;
    }

    /** Apaga pela chave. Devolve a ficha removida, ou {@code null}. */
    public static ThreatSheet remove(UUID uuid, String key) {
        List<ThreatSheet> current = snapshot(uuid);
        if (current.isEmpty()) {
            return null;
        }
        String wanted = RollPreset.normalizeKey(key == null ? "" : key.trim());
        List<ThreatSheet> out = new ArrayList<>();
        ThreatSheet removed = null;
        for (ThreatSheet sheet : current) {
            if (removed == null && sheet.key().equals(wanted)) {
                removed = sheet;
                continue;
            }
            out.add(sheet);
        }
        if (removed == null) {
            return null;
        }
        if (out.isEmpty()) {
            BY_PLAYER.remove(uuid);
        } else {
            BY_PLAYER.put(uuid, Collections.unmodifiableList(out));
        }
        return removed;
    }

    /** Limpa o cache. Existe para teste e limpeza explicita; o logout nao chama. */
    public static void forget(UUID uuid) {
        BY_PLAYER.remove(uuid);
    }

    /** Motivo da recusa do save, com frase ja pronta para a tela e para o chat. */
    public enum SaveResult {
        CREATED("Ficha de ameaça criada."),
        UPDATED("Ficha de ameaça atualizada."),
        RENAMED("Ficha de ameaça renomeada."),
        EMPTY_NAME("O nome da ameaça está vazio."),
        NAME_TAKEN("Já existe uma ficha de ameaça com esse nome."),
        LIMIT_REACHED("Limite de fichas de ameaça atingido."),
        NOT_FOUND("Essa ficha de ameaça não existe mais.");

        private final String message;

        SaveResult(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }

        public boolean ok() {
            return this == CREATED || this == UPDATED || this == RENAMED;
        }
    }
}