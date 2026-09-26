package com.pedro.tabletoprpg.client;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Estado da câmera cinematográfica que orbita em volta do jogador congelado.
 *
 * <p>Inspirado no mod "AFK Cinematics": guarda a posição/rotação do tick
 * anterior e do tick atual, e permite obter valores interpolados pela
 * {@code partialTick} do render. Isso garante movimento suave da câmera,
 * mesmo que a lógica de órbita só rode 20x por segundo.
 *
 * <p>Todas as referências são estáticas (single-instance), pois só existe
 * uma câmera por cliente.
 */
public final class CinematicCameraRig {

    private static boolean active = false;

    private static Vec3 previousPosition = Vec3.ZERO;
    private static Vec3 currentPosition = Vec3.ZERO;

    private static float previousYaw;
    private static float currentYaw;
    private static float previousPitch;
    private static float currentPitch;

    private CinematicCameraRig() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Posição interpolada da câmera para o frame atual. */
    public static Vec3 getInterpolatedPosition(float partialTick) {
        return previousPosition.lerp(currentPosition, partialTick);
    }

    /** Yaw interpolado (leva em conta o menor caminho da rotação). */
    public static float getInterpolatedYaw(float partialTick) {
        return Mth.rotLerp(partialTick, previousYaw, currentYaw);
    }

    // ==== DIAG_TEMP (26/09/2026) =============================================
    // Ultimo yaw/parcialTick que a camera REALMENTE aplicou, para o
    // DownedBodyAlignMixin comparar com o que ele calcula no render da
    // entidade. mora AQUI, e nao no CameraMixin, porque campo e metodo
    // estatico de mixin tem que ser private: com `public` o jogo morre em
    // "InvalidMixinException: contains non-private static method/field".
    // REMOVER junto com o bloco DIAG_TEMP dos dois mixins.
    private static float lastAppliedYaw = Float.NaN;
    private static float lastAppliedPartialTick = Float.NaN;

    public static void tabletopRpg$recordApplied(float yaw, float partialTick) {
        lastAppliedYaw = yaw;
        lastAppliedPartialTick = partialTick;
    }

    public static float tabletopRpg$lastAppliedYaw() {
        return lastAppliedYaw;
    }

    public static float tabletopRpg$lastAppliedPartialTick() {
        return lastAppliedPartialTick;
    }
    // ==== /DIAG_TEMP ==========================================================

    /** Pitch interpolado (leva em conta o menor caminho da rotação). */
    public static float getInterpolatedPitch(float partialTick) {
        return Mth.rotLerp(partialTick, previousPitch, currentPitch);
    }

    /** Inicia a cinematic definindo o estado inicial (sem interpolação). */
    public static void activate(Vec3 pos, float yaw, float pitch) {
        active = true;
        previousPosition = pos;
        currentPosition = pos;
        previousYaw = yaw;
        currentYaw = yaw;
        previousPitch = pitch;
        currentPitch = pitch;
    }

    /** Atualiza o alvo da câmera para o próximo tick. */
    public static void update(Vec3 pos, float yaw, float pitch) {
        if (!active) {
            return;
        }
        previousPosition = currentPosition;
        currentPosition = pos;
        previousYaw = currentYaw;
        currentYaw = yaw;
        previousPitch = currentPitch;
        currentPitch = pitch;
    }

    /** Desliga a cinematic e devolve o controle da câmera ao jogador. */
    public static void deactivate() {
        active = false;
    }
}