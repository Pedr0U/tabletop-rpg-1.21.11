package com.pedro.tabletoprpg.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Controla a c├ómera "cinematic" que gira lentamente em volta do jogador congelado.
 *
 * <p>Quando o jogador est├í "travado" ({@link TabletopRpgClient#locked} == true,
 * ou seja, em modo Investiga├º├úo/Combate e n├úo ├® o turno dele):
 * <ol>
 *   <li>A c├ómera sai suavemente de dentro do personagem at├® uma posi├º├úo atr├ís
 *       dele (transi├º├úo animada, sem corte brusco).</li>
 *   <li>Depois passa a orbitar ao redor do personagem lentamente, mostrando os
 *       arredores.</li>
 * </ol>
 *
 * <p>Durante a cinematic a c├ómera ├® for├ºada para terceira pessoa
 * ({@link CameraType#THIRD_PERSON_BACK}) para o corpo do jogador aparecer no
 * centro da ├│rbita (sem a m├úo em primeira pessoa). Ao sair, a vis├úo anterior ├®
 * restaurada.
 */
public final class CinematicCameraController {

    private static boolean active = false;

    /** Centro da ├│rbita (n├¡vel dos olhos do jogador). */
    private static Vec3 center = Vec3.ZERO;

    /** ├éngulo atual da ├│rbita (em radianos). */
    private static double angle;

    /** Progresso da transi├º├úo de sa├¡da (0 = dentro do personagem, 1 = em ├│rbita). */
    private static float transitionProgress;

    /** Tipo de c├ómera que o jogador tinha antes da cinematic (para restaurar). */
    private static CameraType previousCameraType;

    /** Raio da ├│rbita (dist├óncia horizontal da c├ómera at├® o jogador). */
    private static final double RADIUS = 6.0;

    /** Altura da c├ómera acima do centro (n├¡vel dos olhos). */
    private static final double HEIGHT_OFFSET = 1.6;

    /** Velocidade angular em rad/tick: 1 volta completa em ~28 segundos (bem lento). */
    private static final double ANGULAR_SPEED = (Math.PI * 2.0) / (28.0 * 20.0);

    /** Dura├º├úo da transi├º├úo de sa├¡da em ticks (~2,5 segundos). */
    private static final int TRANSITION_TICKS = 50;

    /** Inclina├º├úo extra para baixo (graus) aplicada ao ├óngulo da c├ómera. */
    private static final float PITCH_BIAS = -10.0f;

    private CinematicCameraController() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Chamado a cada tick do cliente. Liga/desliga a cinematic e avan├ºa a ├│rbita. */
    public static void tick(Minecraft client) {
        boolean locked = TabletopRpgClient.locked;

        // Sem jogador (tela de t├¡tulo, mundo carregando): garante desligamento.
        if (client.player == null) {
            deactivate(client);
            return;
        }

        if (locked) {
            center = client.player.getEyePosition();

            if (!active) {
                active = true;
                transitionProgress = 0f;
                // A c├ómera come├ºa atr├ís do jogador (+180┬░) para a transi├º├úo de sa├¡da.
                angle = Math.toRadians(client.player.getYRot() + 180.0f);

                // For├ºa a terceira pessoa para o corpo do jogador aparecer.
                previousCameraType = client.options.getCameraType();
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);

                Vec3 camPos = computeCameraPos(0f);
                CinematicCameraRig.activate(camPos, computeYaw(camPos), computePitch(camPos));
            } else {
                if (transitionProgress < 1f) {
                    // Ainda saindo do personagem: n├úo gira, s├│ se afasta.
                    transitionProgress += 1f / TRANSITION_TICKS;
                    if (transitionProgress > 1f) {
                        transitionProgress = 1f;
                    }
                } else {
                    // Transi├º├úo conclu├¡da: come├ºa a girar devagar.
                    angle += ANGULAR_SPEED;
                }

                // Posi├º├úo calculada UMA vez por tick (o raycast de colis├úo ├®
                // caro; antes era feito 3x por tick via computeYaw/computePitch).
                Vec3 camPos = computeCameraPos(transitionProgress);
                CinematicCameraRig.update(camPos, computeYaw(camPos), computePitch(camPos));
            }
        } else if (active) {
            deactivate(client);
        }
    }

    /** Desliga a cinematic e restaura a c├ómera do jogador. */
    private static void deactivate(Minecraft client) {
        if (active && previousCameraType != null) {
            client.options.setCameraType(previousCameraType);
        }
        previousCameraType = null;
        active = false;
        CinematicCameraRig.deactivate();
    }

    /** Posi├º├úo alvo da ├│rbita (no c├¡rculo, na altura certa). */
    private static Vec3 orbitPosition() {
        return new Vec3(
            center.x + RADIUS * Math.cos(angle),
            center.y + HEIGHT_OFFSET,
            center.z + RADIUS * Math.sin(angle));
    }

    /** Posi├º├úo interpolada da c├ómera durante a transi├º├úo (sai dos olhos at├® a ├│rbita). */
    private static Vec3 computeCameraPos(float progress) {
        Vec3 orbit = orbitPosition();
        float t = smoothstep(progress);
        return clipToWall(center, center.lerp(orbit, t));
    }

    /**
     * Colis├úo da c├ómera (FASE 0.4): se houver um bloco entre o centro da ├│rbita
     * (olhos do jogador) e a posi├º├úo alvo da c├ómera, a c├ómera ├® recuada para
     * logo antes do bloco ÔÇö igual ao comportamento da terceira pessoa vanilla
     * (F5), que encosta na parede em vez de atravess├í-la.
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
        // Recua 0.2 bloco do ponto de impacto para a c├ómera n├úo "grudar" na parede.
        Vec3 dir = to.subtract(from).normalize();
        return hit.getLocation().subtract(dir.scale(0.2));
    }

    /** Yaw da c├ómera apontando para o centro (jogador). */
    private static float computeYaw(Vec3 cam) {
        double dx = center.x - cam.x;
        double dz = center.z - cam.z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Pitch da c├ómera olhando levemente para o centro (jogador). */
    private static float computePitch(Vec3 cam) {
        double dx = center.x - cam.x;
        double dz = center.z - cam.z;
        double dy = center.y - cam.y;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // Leve inclina├º├úo extra para baixo (PITCH_BIAS), como pedido.
        return (float) Math.toDegrees(Math.atan2(dy, horizontal)) - PITCH_BIAS;
    }

    /** Interpola├º├úo suave (smoothstep) para n├úo haver corte brusco. */
    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}
