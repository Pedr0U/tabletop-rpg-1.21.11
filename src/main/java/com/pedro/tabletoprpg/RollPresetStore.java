package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Presets de Rolagem de cada jogador, em memoria.
 *
 * <p><b>01/10/2026.</b> A persistencia em disco e do
 * {@code PlayerRollPresetPersistenceMixin}, no padrao ja usado pela ficha
 * ({@code ValueOutput.store} / {@code ValueInput.read} com a chave
 * {@value #NBT_KEY}); aqui fica so o cache, como o {@link SessionManager} faz com a ficha.
 *
 * <p><b>Por que um dicionario e nao uma lista:</b> o preset e buscado pelo nome a cada
 * uso do item e a cada {@code /rpg preset use}. Com lista, buscar seria O(n) e deletar
 * seria O(n) com remocao no meio. Com dicionario as duas sao O(1).
 *
 * <p><b>Por que a chave e o {@link RollPreset#key()} e nao o nome cru:</b> ver o metodo
 * {@code key}. E o que faz {@code Ataque}, {@code ataque} e {@code ATAQUE} acharem o
 * mesmo preset.
 */
public final class RollPresetStore {

    /** Chave deste bloco dentro do NBT do jogador. */
    public static final String NBT_KEY = "tabletoprpg_roll_presets";

    /**
     * Teto por jogador. Nao e uma regra do jogo: e o que segura um NBT editado a mao de
     * meter 1 milhao de presets e travar a carga do jogador. O comando avisa quando bate.
     */
    public static final int MAX_PRESETS = 64;

    public static final Codec<Map<String, RollPreset>> CODEC =
            Codec.unboundedMap(Codec.STRING, RollPreset.CODEC);

    private static final Map<UUID, Map<String, RollPreset>> PRESETS = new HashMap<>();

    private RollPresetStore() {
    }

    /** Todos os presets da jogadora, ordenados por nome (ordem estavel, sem depender do NBT). */
    public static List<RollPreset> list(UUID playerUuid) {
        List<RollPreset> out = new ArrayList<>();
        if (playerUuid == null) {
            return out;
        }
        Map<String, RollPreset> map = PRESETS.get(playerUuid);
        if (map != null) {
            out.addAll(map.values());
        }
        out.sort(Comparator.comparing(preset -> preset.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Procura pelo nome digitado, ja normalizado. */
    public static Optional<RollPreset> find(UUID playerUuid, String rawName) {
        if (playerUuid == null || rawName == null) {
            return Optional.empty();
        }
        Map<String, RollPreset> map = PRESETS.get(playerUuid);
        if (map == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(map.get(RollPreset.normalizeKey(rawName)));
    }

    public static boolean exists(UUID playerUuid, String rawName) {
        return find(playerUuid, rawName).isPresent();
    }

    public static int count(UUID playerUuid) {
        Map<String, RollPreset> map = playerUuid == null ? null : PRESETS.get(playerUuid);
        return map == null ? 0 : map.size();
    }

    /**
     * Grava (ou substitui) um preset.
     *
     * @return o preset anterior com a mesma chave, ou {@link Optional#empty()}
     */
    public static Optional<RollPreset> put(UUID playerUuid, RollPreset preset) {
        Map<String, RollPreset> map = PRESETS.computeIfAbsent(playerUuid, uuid -> new HashMap<>());
        return Optional.ofNullable(map.put(preset.key(), preset));
    }

    /** @return o preset removido, ou {@link Optional#empty()} se nao havia nenhum */
    public static Optional<RollPreset> remove(UUID playerUuid, String rawName) {
        Map<String, RollPreset> map = playerUuid == null ? null : PRESETS.get(playerUuid);
        if (map == null) {
            return Optional.empty();
        }
        RollPreset removed = map.remove(RollPreset.normalizeKey(rawName));
        return Optional.ofNullable(removed);
    }

    /** Substitui o cache inteiro de uma jogadora, usado na carga do NBT. */
    public static void replaceAll(UUID playerUuid, Map<String, RollPreset> loaded) {
        if (playerUuid == null) {
            return;
        }
        if (loaded == null || loaded.isEmpty()) {
            PRESETS.remove(playerUuid);
            return;
        }
        PRESETS.put(playerUuid, new HashMap<>(loaded));
    }

    /** Solta o cache de quem saiu. O NBT ja foi gravado pelo proprio logout do vanilla. */
    public static void forget(UUID playerUuid) {
        if (playerUuid != null) {
            PRESETS.remove(playerUuid);
        }
    }

    /** Mapa pronto para o {@link #CODEC}, chaveado pelo {@link RollPreset#key()}. */
    public static Map<String, RollPreset> snapshot(UUID playerUuid) {
        Map<String, RollPreset> map = playerUuid == null ? null : PRESETS.get(playerUuid);
        return map == null ? new HashMap<>() : new HashMap<>(map);
    }
}