package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Controla a câmera de espectador (FASE 2) quando o jogador está "travado"
 * (modo investigação/combate e não é o turno dele).
 *
 * <p>Substitui o antigo {@code CinematicCameraController} (órbita fixa ao
 * redor do próprio jogador) por um sistema completo de espectador:
 * <ul>
 *   <li><b>Carrossel de alvos</b>: clique esquerdo = próximo alvo, clique
 *       direito = alvo anterior. O alvo 0 é sempre o próprio jogador; os
 *       demais vêm do servidor ({@link RpgNetworking.SpectatorTargetsPayload}):
 *       todos os jogadores conectados + mobs invocados com câmera
 *       (cam_perm=true).</li>
 *   <li><b>Modos de câmera</b> (tecla V ou menu de configurações):
 *       <ul>
 *         <li>3ª Pessoa: órbita ao redor do alvo. Com "Câmera Orbital: Sim"
 *             (padrão) e o alvo FORA do turno, a órbita gira sozinha; se o
 *             alvo estiver no turno dele (ou orbital desligada), o mouse do
 *             espectador controla a câmera.</li>
 *         <li>1ª Pessoa: visão pelos olhos do alvo (o próprio jogador vê
 *             normalmente; outro alvo usa a rotação dele).</li>
 *         <li>TopDown: câmera 15 blocos acima do alvo, olhando para baixo.</li>
 *         <li>Livre: câmera solta com WASD + espaço/shift, olhando com o
 *             mouse; o corpo do jogador fica parado.</li>
 *       </ul></li>
 *   <li><b>Corpo do alvo</b>: ao espectar outro alvo, a entidade da câmera é
 *       trocada ({@link Minecraft#setCameraEntity}) para o corpo do alvo ser
 *       renderizado (verificado: o campo só muda via setter, não é resetado
 *       no tick).</li>
 *   <li><b>Transição suave</b>: ao ativar/trocar alvo/modo, a câmera desliza
 *       da posição atual até o novo alvo (50 ticks, smoothstep).</li>
 *   <li><b>Saída instantânea</b>: ao receber o turno (destravar), a câmera
 *       anterior (1ª/3ª pessoa) é restaurada imediatamente; o modo escolhido
 *       persiste como preferência.</li>
 * </ul>
 */
public final class SpectatorCameraController {

    /** Modos de câmera de espectador (ciclo da tecla V). */
    public enum Mode {
        THIRD_PERSON("3ª Pessoa"),
        FIRST_PERSON("1ª Pessoa"),
        TOP_DOWN("TopDown"),
        FREE("Livre");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static Mode mode = Mode.THIRD_PERSON;

    /** "Câmera Orbital: Sim/Não" — se false, o mouse controla a 3ª pessoa. */
    private static boolean orbitalEnabled = true;

    /**
     * Zoom da câmera TopDown (offset em blocos × 0.1; -100..100, padrão 0).
     * Altura final = TOP_DOWN_HEIGHT + topDownZoom * 0.1 (15 → 5..25 blocos).
     */
    private static int topDownZoom = 0;

    /**
     * Velocidade de transição (0..100, padrão 50). Duração em ticks =
     * 100 - transitionSpeed: 50 → 50 ticks (comportamento atual), 100 → 1
     * tick (instantâneo), 0 → 100 ticks (lento).
     */
    private static int transitionSpeed = 50;

    /** Yaw/pitch próprios da câmera Livre (o jogador NÃO gira ao espectar). */
    private static float freeCamYaw;

    /** Yaw/pitch próprios da câmera Livre (o jogador NÃO gira ao espectar). */
    private static float freeCamPitch;

    /** Yaw/pitch próprios da câmera 3ª pessoa controlada pelo mouse (o jogador NÃO gira). */
    private static float thirdPersonYaw;

    /** Yaw/pitch próprios da câmera 3ª pessoa controlada pelo mouse (o jogador NÃO gira). */
    private static float thirdPersonPitch;

    /** Se o look da 3ª pessoa já foi inicializado (da rotação do jogador). */
    private static boolean thirdPersonLookInitialized = false;

    private static boolean active = false;

    /** Centro da órbita (nível dos olhos do alvo espectado). */
    private static Vec3 center = Vec3.ZERO;

    /** Ângulo atual da órbita (radianos). */
    private static double angle;

    /** Progresso da transição (0 = saindo da posição atual, 1 = no alvo). */
    private static float transitionProgress = 1f;

    /** Posição de onde a transição começou. */
    private static Vec3 transitionStart = Vec3.ZERO;

    /** Tipo de câmera antes de travar (para restaurar ao destravar). */
    private static CameraType previousCameraType;

    /** Posição da câmera livre (modo Livre). */
    private static Vec3 freeCamPos = Vec3.ZERO;

    /** Se a posição da câmera livre já foi inicializada (nos olhos do jogador). */
    private static boolean freeCamInitialized = false;

    /** Raio da órbita (distância horizontal da câmera até o alvo). */
    private static final double RADIUS = 6.0;

    /** Altura da câmera acima do centro (nível dos olhos). */
    private static final double HEIGHT_OFFSET = 1.6;

    /** Velocidade angular em rad/tick: 1 volta completa em ~28 segundos. */
    private static final double ANGULAR_SPEED = (Math.PI * 2.0) / (28.0 * 20.0);

    /** Inclinação extra para baixo (graus) aplicada ao ângulo da câmera. */
    private static final float PITCH_BIAS = -10.0f;

    /** Altura da câmera TopDown acima do alvo. */
    private static final double TOP_DOWN_HEIGHT = 15.0;

    /** Velocidade da câmera livre (blocos por tick). */
    private static final double FREE_SPEED = 0.6;

    /** Alvos do carrossel vindos do servidor (sem incluir a si mesmo). */
    private static List<RpgNetworking.SpectatorTargetsPayload.TargetData> targets = List.of();

    /** Índice do alvo atual: 0 = si mesmo, 1+ = targets.get(index - 1). */
    private static int targetIndex = 0;

    private SpectatorCameraController() {
    }

    public static boolean isActive() {
        return active;
    }

    public static Mode getMode() {
        return mode;
    }

    /** Define o modo diretamente (menu de configurações). */
    public static void setMode(Mode newMode) {
        mode = newMode;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            resetTransition(client);
        }
    }

    public static boolean isOrbitalEnabled() {
        return orbitalEnabled;
    }

    public static void setOrbitalEnabled(boolean enabled) {
        orbitalEnabled = enabled;
    }

    public static int getTopDownZoom() {
        return topDownZoom;
    }

    public static void setTopDownZoom(int zoom) {
        topDownZoom = Math.max(-100, Math.min(100, zoom));
    }

    public static int getTransitionSpeed() {
        return transitionSpeed;
    }

    public static void setTransitionSpeed(int speed) {
        transitionSpeed = Math.max(0, Math.min(100, speed));
    }

    /**
     * Deltas de rotação do mouse repassados pelo {@code EntityTurnMixin}
     * quando o jogador está espectando. O jogador NÃO gira (o turn é
     * cancelado); os deltas movem a câmera do modo atual, que tem yaw/pitch
     * próprios (mesmo fator 0.15 do {@code Entity.turn} vanilla):
     * <ul>
     *   <li>Livre: a câmera Livre olha com o mouse;</li>
     *   <li>3ª Pessoa: a câmera controlada pelo mouse orbita o alvo (o ramo
     *       da órbita automática não usa estes campos).</li>
     * </ul>
     */
    public static void onMouseLook(double yawDelta, double pitchDelta) {
        if (mode == Mode.FREE) {
            freeCamYaw += (float) (yawDelta * 0.15);
            freeCamPitch += (float) (pitchDelta * 0.15);
            freeCamPitch = Math.max(-90f, Math.min(90f, freeCamPitch));
        } else if (mode == Mode.THIRD_PERSON) {
            thirdPersonYaw += (float) (yawDelta * 0.15);
            thirdPersonPitch += (float) (pitchDelta * 0.15);
            thirdPersonPitch = Math.max(-90f, Math.min(90f, thirdPersonPitch));
        }
    }

    /**
     * Atualiza a lista de alvos do carrossel vinda do servidor. O próprio
     * jogador é filtrado (ele é sempre o alvo 0 localmente).
     */
    public static void setServerTargets(List<RpgNetworking.SpectatorTargetsPayload.TargetData> serverList, int selfEntityId) {
        List<RpgNetworking.SpectatorTargetsPayload.TargetData> filtered = new ArrayList<>();
        if (serverList != null) {
            for (RpgNetworking.SpectatorTargetsPayload.TargetData t : serverList) {
                if (t.entityId() != selfEntityId) {
                    filtered.add(t);
                }
            }
        }
        targets = filtered;
        int size = targets.size() + 1;
        if (targetIndex >= size) {
            targetIndex = Math.max(0, size - 1);
        }
    }

    /** Cicla o modo de câmera (tecla V / menu). O modo persiste como preferência. */
    public static void cycleMode() {
        Mode[] modes = Mode.values();
        mode = modes[(mode.ordinal() + 1) % modes.length];
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            resetTransition(client);
            client.player.displayClientMessage(Component.literal("§b[Câmera] §f" + mode.getDisplayName()), true);
        }
    }

    /** Carrossel: próximo alvo (clique esquerdo). */
    public static void cycleNext() {
        int size = targets.size() + 1;
        if (size <= 1) {
            return;
        }
        targetIndex = (targetIndex + 1) % size;
        onTargetChanged();
    }

    /** Carrossel: alvo anterior (clique direito). */
    public static void cyclePrev() {
        int size = targets.size() + 1;
        if (size <= 1) {
            return;
        }
        targetIndex = (targetIndex - 1 + size) % size;
        onTargetChanged();
    }

    /** Chamado a cada tick do cliente. Liga/desliga a câmera de espectador. */
    public static void tick(Minecraft client) {
        boolean locked = TabletopRpgClient.locked;

        // Sem jogador (tela de título, mundo carregando): garante desligamento.
        if (client.player == null) {
            deactivate(client);
            return;
        }

        // Destravado (turno do jogador ou fora de modo): volta para a câmera
        // normal imediatamente.
        if (!locked) {
            deactivate(client);
            return;
        }

        Entity target = getCurrentTarget(client);
        if (target == null) {
            deactivate(client);
            return;
        }

        if (!active) {
            active = true;
            previousCameraType = client.options.getCameraType();
            transitionProgress = 0f;
            transitionStart = client.player.getEyePosition();
            freeCamInitialized = false;
            thirdPersonLookInitialized = false;
            client.setCameraEntity(client.player);
        }

        switch (mode) {
            case FIRST_PERSON -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                if (target == client.player) {
                    // 1ª pessoa normal: o jogador vê pelos próprios olhos.
                    client.setCameraEntity(client.player);
                    CinematicCameraRig.deactivate();
                } else {
                    // Olhos do alvo: câmera na posição dos olhos dele, com a
                    // rotação dele (o corpo do alvo é renderizado via
                    // setCameraEntity).
                    client.setCameraEntity(target);
                    advanceTransition();
                    Vec3 eye = target.getEyePosition();
                    ensureRigActive(transitionPos(eye), target.getYRot(), target.getXRot());
                }
            }
            case THIRD_PERSON -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                client.setCameraEntity(target);
                center = target.getEyePosition();
                // Look próprio da câmera 3ª pessoa controlada pelo mouse: o
                // jogador NÃO gira ao espectar (EntityTurnMixin cancela o
                // turn), então a câmera tem yaw/pitch próprios, inicializados
                // da rotação do jogador e atualizados pelo mouse via
                // onMouseLook. (A rotação do jogador fica congelada enquanto
                // especta, então re-inicializar é inofensivo.)
                if (!thirdPersonLookInitialized) {
                    thirdPersonYaw = client.player.getYRot();
                    thirdPersonPitch = client.player.getXRot();
                    thirdPersonLookInitialized = true;
                }
                // Estado final do modo: mouse do espectador (vanilla) ou
                // órbita automática.
                boolean mouseControlled = !(orbitalEnabled && !isTargetOnTurn(target));
                if (transitionProgress < 1f) {
                    // Transição: vai direto para o estado final do modo
                    // (posição do mouse ou da órbita), sem girar ainda —
                    // evita o snap posicional/rotacional no fim da transição.
                    advanceTransition();
                    Vec3 camPos;
                    float yaw;
                    float pitch;
                    if (mouseControlled) {
                        Vec3 look = lookFromYawPitch(thirdPersonYaw, thirdPersonPitch);
                        camPos = clipToWall(center, transitionPos(center.subtract(look.scale(RADIUS))));
                        yaw = thirdPersonYaw;
                        pitch = thirdPersonPitch;
                    } else {
                        camPos = clipToWall(center, transitionPos(orbitPosition()));
                        yaw = computeYaw(camPos);
                        pitch = computePitch(camPos);
                    }
                    ensureRigActive(camPos, yaw, pitch);
                } else if (mouseControlled) {
                    // Mouse do espectador: comportamento vanilla — a câmera
                    // fica atrás do alvo na direção do look da câmera
                    // (yaw/pitch próprios, controlados pelo mouse) e olha na
                    // MESMA direção, com o alvo centralizado na tela. O
                    // jogador não gira.
                    Vec3 look = lookFromYawPitch(thirdPersonYaw, thirdPersonPitch);
                    Vec3 camPos = clipToWall(center, center.subtract(look.scale(RADIUS)));
                    ensureRigActive(camPos, thirdPersonYaw, thirdPersonPitch);
                } else {
                    // Órbita automática: alvo fora do turno (ou mob) com
                    // "Câmera Orbital: Sim".
                    angle += ANGULAR_SPEED;
                    Vec3 camPos = clipToWall(center, orbitPosition());
                    ensureRigActive(camPos, computeYaw(camPos), computePitch(camPos));
                }
            }
            case TOP_DOWN -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                client.setCameraEntity(target);
                center = target.getEyePosition();
                advanceTransition();
                // Altura base 15 blocos + zoom configurado (slider -100..100,
                // cada unidade = 0.1 bloco → 5..25 blocos).
                double height = TOP_DOWN_HEIGHT + topDownZoom * 0.1;
                Vec3 camPos = transitionPos(new Vec3(center.x, center.y + height, center.z));
                ensureRigActive(camPos, 0f, 90f);
            }
            case FREE -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                // Corpo do jogador fica parado; a câmera é livre.
                client.setCameraEntity(client.player);
                if (!freeCamInitialized) {
                    freeCamPos = client.player.getEyePosition();
                    freeCamYaw = client.player.getYRot();
                    freeCamPitch = client.player.getXRot();
                    freeCamInitialized = true;
                }
                tickFreeCamera(client);
            }
        }
    }

    /**
     * Garante que o rig da câmera esteja ativo antes de atualizá-lo. O
     * {@link CinematicCameraRig#update} é um no-op quando inativo, então ao
     * ativar a câmera de espectador (ou voltar de um modo que desativa o rig,
     * como a 1ª pessoa do próprio jogador) é preciso chamar {@code activate}
     * primeiro.
     */
    private static void ensureRigActive(Vec3 pos, float yaw, float pitch) {
        if (CinematicCameraRig.isActive()) {
            CinematicCameraRig.update(pos, yaw, pitch);
        } else {
            CinematicCameraRig.activate(pos, yaw, pitch);
        }
    }

    /** Movimento da câmera livre (WASD + espaço/shift, olhar com o mouse). */
    private static void tickFreeCamera(Minecraft client) {
        double forward = 0, strafe = 0, up = 0;
        if (client.options.keyUp.isDown()) forward += 1;
        if (client.options.keyDown.isDown()) forward -= 1;
        if (client.options.keyLeft.isDown()) strafe -= 1;
        if (client.options.keyRight.isDown()) strafe += 1;
        if (client.options.keyJump.isDown()) up += 1;
        if (client.options.keyShift.isDown()) up -= 1;

        // O jogador NÃO gira ao espectar (EntityTurnMixin cancela o turn);
        // a câmera Livre tem yaw/pitch próprios, atualizados pelo mouse via
        // onMouseLook. O corpo do jogador fica completamente estático.
        float yaw = freeCamYaw;
        float pitch = freeCamPitch;
        double rad = Math.toRadians(yaw);
        Vec3 fwd = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
        Vec3 right = new Vec3(-Math.cos(rad), 0, -Math.sin(rad));
        Vec3 move = fwd.scale(forward).add(right.scale(strafe)).add(new Vec3(0, up, 0));
        if (move.lengthSqr() > 0) {
            Vec3 newPos = freeCamPos.add(move.normalize().scale(FREE_SPEED));
            // Colisão com blocos: a câmera livre NÃO atravessa paredes
            // (feedback do usuário). Raycast da posição atual até a nova;
            // se houver bloco no caminho, para logo antes dele.
            freeCamPos = collideFreeCamera(freeCamPos, newPos);
        }
        // ensureRigActive (não update direto): o rig pode estar inativo ao
        // entrar no modo Livre (ex.: vindo da 1ª pessoa do próprio jogador,
        // que desativa o rig) e update() é no-op quando inativo.
        ensureRigActive(freeCamPos, yaw, pitch);
    }

    /**
     * Colisão da câmera livre: raycast da posição anterior até a nova posição.
     * Se houver um bloco sólido no caminho, a câmera para logo antes dele
     * (desliza pela parede, sem atravessar).
     */
    private static Vec3 collideFreeCamera(Vec3 from, Vec3 to) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return to;
        }
        BlockHitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, Minecraft.getInstance().player));
        if (hit.getType() == HitResult.Type.MISS) {
            return to;
        }
        Vec3 dir = to.subtract(from).normalize();
        return hit.getLocation().subtract(dir.scale(0.1));
    }

    /** Desliga a câmera de espectador e restaura a câmera do jogador. */
    private static void deactivate(Minecraft client) {
        if (active && previousCameraType != null) {
            client.options.setCameraType(previousCameraType);
        }
        if (client.player != null) {
            client.setCameraEntity(client.player);
        }
        previousCameraType = null;
        active = false;
        freeCamInitialized = false;
        CinematicCameraRig.deactivate();
    }

    /** Alvo atual do carrossel (0 = si mesmo; entidade ausente = si mesmo). */
    private static Entity getCurrentTarget(Minecraft client) {
        if (client.level == null || client.player == null) {
            return null;
        }
        if (targetIndex == 0) {
            return client.player;
        }
        int idx = targetIndex - 1;
        if (idx < 0 || idx >= targets.size()) {
            return client.player;
        }
        Entity entity = client.level.getEntity(targets.get(idx).entityId());
        return entity != null ? entity : client.player;
    }

    /**
     * True se o alvo é o jogador ativo (no turno dele). Mobs nunca estão "no
     * turno" (o mestre rola por eles), então sempre orbitam automaticamente.
     */
    private static boolean isTargetOnTurn(Entity target) {
        if (!(target instanceof Player)) {
            return false;
        }
        String activeUuid = TabletopRpgClient.activePlayerUuid;
        return !activeUuid.isEmpty() && activeUuid.equals(target.getUUID().toString());
    }

    /** Reinicia a transição a partir da posição atual da câmera. */
    private static void resetTransition(Minecraft client) {
        transitionProgress = 0f;
        transitionStart = CinematicCameraRig.isActive()
                ? CinematicCameraRig.getInterpolatedPosition(0f)
                : client.player.getEyePosition();
        freeCamInitialized = false;
        // Re-inicializa o look da 3ª pessoa da rotação do jogador (que fica
        // congelada enquanto especta — inofensivo e mantém a câmera estável
        // ao trocar de alvo/modo).
        thirdPersonLookInitialized = false;
    }

    /** Ao trocar de alvo: transição suave da posição atual da câmera. */
    private static void onTargetChanged() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            resetTransition(client);
        }
    }

    private static void advanceTransition() {
        if (transitionProgress < 1f) {
            transitionProgress += 1f / transitionTicks();
            if (transitionProgress > 1f) {
                transitionProgress = 1f;
            }
        }
    }

    /**
     * Duração da transição em ticks conforme a velocidade configurada
     * (slider 0..100, padrão 50 = 50 ticks atual; 100 = 1 tick, instantâneo).
     */
    private static int transitionTicks() {
        return Math.max(1, 100 - transitionSpeed);
    }

    /** Posição interpolada da transição (smoothstep). */
    private static Vec3 transitionPos(Vec3 targetPos) {
        if (transitionProgress >= 1f) {
            return targetPos;
        }
        return transitionStart.lerp(targetPos, smoothstep(transitionProgress));
    }

    /** Posição alvo da órbita (no círculo, na altura certa). */
    private static Vec3 orbitPosition() {
        return new Vec3(
                center.x + RADIUS * Math.cos(angle),
                center.y + HEIGHT_OFFSET,
                center.z + RADIUS * Math.sin(angle));
    }

    /**
     * Vetor de direção do look a partir de yaw/pitch — mesma fórmula do
     * vanilla ({@code Entity.calculateViewVector(pitch, yaw)}). Usado pela
     * câmera 3ª pessoa controlada pelo mouse, que tem yaw/pitch próprios.
     */
    private static Vec3 lookFromYawPitch(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    /**
     * Colisão da câmera: se houver um bloco entre o centro (olhos do alvo) e a
     * posição alvo da câmera, a câmera é recuada para logo antes do bloco —
     * igual ao comportamento da terceira pessoa vanilla (F5).
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

    /** Yaw da câmera apontando para o centro (alvo). */
    private static float computeYaw(Vec3 cam) {
        double dx = center.x - cam.x;
        double dz = center.z - cam.z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Pitch da câmera olhando levemente para o centro (alvo). */
    private static float computePitch(Vec3 cam) {
        double dx = center.x - cam.x;
        double dz = center.z - cam.z;
        double dy = center.y - cam.y;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) Math.toDegrees(Math.atan2(dy, horizontal)) - PITCH_BIAS;
    }

    /** Interpolação suave (smoothstep) para não haver corte brusco. */
    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}