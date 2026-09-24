package com.pedro.tabletoprpg.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Controla a câmera "cinematic" que gira lentamente em volta do jogador congelado.
 *
 * <p>Quando o jogador está "travado" ({@link TabletopRpgClient#locked} == true,
 * ou seja, em modo Investigação/Combate e não é o turno dele):
 * <ol>
 *   <li>A câmera sai suavemente de dentro do personagem até uma posição atrás
 *       dele (transição animada, sem corte brusco).</li>
 *   <li>Depois passa a orbitar ao redor do personagem lentamente, mostrando os
 *       arredores.</li>
 * </ol>
 *
 * <p>Durante a cinematic a câmera é forçada para terceira pessoa
 * ({@link CameraType#THIRD_PERSON_BACK}) para o corpo do jogador aparecer no
 * centro da órbita (sem a mão em primeira pessoa). Ao sair, a visão anterior é
 * restaurada.
 */
public final class CinematicCameraController {

    private static boolean active = false;

    /** Centro da órbita (nível dos olhos do jogador). */
    private static Vec3 center = Vec3.ZERO;

    /** Ângulo atual da órbita (em radianos). */
    private static double angle;

    /** Progresso da transição de saída (0 = dentro do personagem, 1 = em órbita). */
    private static float transitionProgress;

    /** Tipo de câmera que o jogador tinha antes da cinematic (para restaurar). */
    private static CameraType previousCameraType;

    /** Raio da órbita (distância horizontal da câmera até o jogador). */
    private static final double RADIUS = 6.0;

    /** Altura da câmera acima do centro (nível dos olhos). */
    private static final double HEIGHT_OFFSET = 1.6;

    /** Velocidade angular em rad/tick: 1 volta completa em ~28 segundos (bem lento). */
    private static final double ANGULAR_SPEED = (Math.PI * 2.0) / (28.0 * 20.0);

    /** Duração da transição de saída em ticks (~2,5 segundos). */
    private static final int TRANSITION_TICKS = 50;

    /** Inclinação extra para baixo (graus) aplicada ao ângulo da câmera. */
    private static final float PITCH_BIAS = -10.0f;

    private CinematicCameraController() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Chamado a cada tick do cliente. Liga/desliga a cinematic e avança a órbita. */
    public static void tick(Minecraft client) {
        boolean locked = TabletopRpgClient.locked;

        // Sem jogador (tela de título, mundo carregando): garante desligamento.
        if (client.player == null) {
            deactivate(client);
            return;
        }

        if (locked) {
            center = client.player.getEyePosition();

            if (!active) {
                active = true;
                transitionProgress = 0f;
                // A câmera começa atrás do jogador (+180°) para a transição de saída.
                angle = Math.toRadians(client.player.getYRot() + 180.0f);

                // Força a terceira pessoa para o corpo do jogador aparecer.
                previousCameraType = client.options.getCameraType();
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);

                Vec3 camPos = computeCameraPos(0f);
                CinematicCameraRig.activate(camPos, computeYaw(camPos), computePitch(camPos));
            } else {
                if (transitionProgress < 1f) {
                    // Ainda saindo do personagem: não gira, só se afasta.
                    transitionProgress += 1f / TRANSITION_TICKS;
                    if (transitionProgress > 1f) {
                        transitionProgress = 1f;
                    }
                } else {
                    // Transição concluída: começa a girar devagar.
                    angle += ANGULAR_SPEED;
                }

                // Posição calculada UMA vez por tick (o raycast de colisão é
                // caro; antes era feito 3x por tick via computeYaw/computePitch).
                Vec3 camPos = computeCameraPos(transitionProgress);
                CinematicCameraRig.update(camPos, computeYaw(camPos), computePitch(camPos));
            }
        } else if (active) {
            deactivate(client);
        }
    }

    /** Desliga a cinematic e restaura a câmera do jogador. */
    private static void deactivate(Minecraft client) {
        if (active && previousCameraType != null) {
            client.options.setCameraType(previousCameraType);
        }
        previousCameraType = null;
        active = false;
        CinematicCameraRig.deactivate();
    }

    /** Posição alvo da órbita (no círculo, na altura certa). */
    private static Vec3 orbitPosition() {
        return new Vec3(
            center.x + RADIUS * Math.cos(angle),
            center.y + HEIGHT_OFFSET,
            center.z + RADIUS * Math.sin(angle));
    }

    /** Posição interpolada da câmera durante a transição (sai dos olhos até a órbita). */
    private static Vec3 computeCameraPos(float progress) {
        Vec3 orbit = orbitPosition();
        float t = smoothstep(progress);
        return clipToWall(center, center.lerp(orbit, t));
    }

    /**
     * Colisão da câmera (FASE 0.4): se houver um bloco entre o centro da órbita
     * (olhos do jogador) e a posição alvo da câmera, a câmera é recuada para
     * logo antes do bloco — igual ao comportamento da terceira pessoa vanilla
     * (F5), que encosta na parede em vez de atravessá-la.
     */
    private static Vec3 clipToWall(Vec3 from, Vec3 to) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return to;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, Minecraft.getInstance().player));
        if (hit.getType() == HitResult.Type.MISS) {
            return to;
        }
        // Recua 0.2 bloco do ponto de impacto para a câmera não "grudar" na parede.
        Vec3 dir = to.subtract(from).normalize();
        return hit.getLocation().subtract(dir.scale(0.2));
    }

    /** Yaw da câmera apontando para o centro (jogador). */
    private static float computeYaw(Vec3 cam) {
        double dx = center.x - cam.x;
        double dz = center.z - cam.z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Pitch da câmera olhando levemente para o centro (jogador). */
    private static float computePitch(Vec3 cam) {
        double dx = center.x - cam.x;
        double dz = center.z - cam.z;
        double dy = center.y - cam.y;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // Leve inclinação extra para baixo (PITCH_BIAS), como pedido.
        return (float) Math.toDegrees(Math.atan2(dy, horizontal)) - PITCH_BIAS;
    }

    /** Interpolação suave (smoothstep) para não haver corte brusco. */
    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}