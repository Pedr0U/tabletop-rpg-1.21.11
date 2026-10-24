package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

    /**
     * Modos de câmera de espectador (ciclo da tecla V).
     *
     * <p><b>Quem pode usar cada um (decisão do usuário em 26/09/2026):</b> a
     * câmera <b>FREE</b> está liberada a qualquer momento, em todos os modos da
     * sessão. Nos modos Investigação e Combate o ciclo do jogador travado passa
     * pelas quatro (terceira, primeira, de cima e livre); o jogador que está no
     * seu turno alterna apenas entre a câmera do jogo e a livre.
     */
    public enum Mode {
        THIRD_PERSON("3rd Person"),
        FIRST_PERSON("1st Person"),
        TOP_DOWN("Top-Down"),
        FREE("Free");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static Mode mode = Mode.THIRD_PERSON;

    /**
     * Se o jogador estava travado no tick anterior.
     *
     * <p>Usado para detectar o instante em que o turno chega (travado -&gt; livre),
     * que é quando a câmera volta sozinha para a câmera do jogo. A câmera livre
     * continua liberada durante o próprio turno.
     */
    private static boolean wasLocked = false;

    /** Id da entidade do jogador no ultimo tick; 0 = ainda nao se conectou. */
    private static int lastPlayerEntityId = 0;

    /**
     * Modo da sessao visto no tick anterior, para saber se ele mudou.
     *
     * <p>O servidor reenvia {@code locked=false} para todo mundo tanto numa
     * troca de turno quanto quando o mestre muda o modo da sessao. Sem esta
     * guarda, trocar Combate -&gt; Livre derrubaria a camera livre de todo
     * jogador travado, como se o turno deles tivesse comecado.
     */
    private static int lastGameModeOrdinal = -1;

    /**
     * Escolhe um modo.
     *
     * <p>Todos os modos valem em todos os modos da sessão: a câmera livre está
     * liberada a qualquer momento (decisão do usuário em 26/09/2026).
     *
     * @return true se o modo foi aceito
     */
    public static boolean requestMode(Mode newMode) {
        if (newMode == null) {
            return false;
        }
        mode = newMode;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            resetTransition(client);
        }
        return true;
    }

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

    /**
     * O Mestre ligou o modo de câmera de espectador na mão (tecla propria).
     *
     * <p><b>Por que existe (PARTE 3):</b> a câmera de espectador liga sozinha
     * para o jogador <b>travado</b> (o turno e de outra pessoa). O Mestre nunca
     * e travado pelo servidor (ele sempre pode agir), entao sem esta flag ele
     * nunca entraria no mesmo modo -- que e justamente o que o Mestre precisa
     * para assistir a mesa.
     *
     * <p>Com a flag ligada, o cliente trata o Mestre como travado: os quatro
     * modos de câmera (tecla V), o carrossel de alvos (clique esquerdo/direito)
     * e o F5 bloqueado entram pelo mesmo caminho do jogador travado.
     */
    private static boolean masterSpectator = false;

    /** O Mestre esta no modo de câmera de espectador? */
    public static boolean isMasterSpectator() {
        return masterSpectator;
    }

    /**
     * Liga/desliga o modo de câmera de espectador do Mestre.
     *
     * <p>Só o Mestre pode usar: o papel e decidido pelo servidor e chega no
     * cliente pelo payload (retrato das trancas no JOIN, ou o menu da sessao
     * na tecla R).
     *
     * <p>A trava local ({@link TabletopRpgClient#locked}) e o que liga os
     * controles de espectador do cliente (carrossel de cliques e F5, no
     * {@code MinecraftMixin}) e o modo espectador da câmera. Por isso ela e
     * ligada aqui: o servidor nunca trava o Mestre.
     */
    public static void toggleMasterSpectator() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (!TabletopRpgClient.isMaster) {
            client.player.displayClientMessage(
                    Component.translatable("message.tabletop-rpg.spectator_only_master"), true);
            return;
        }
        masterSpectator = !masterSpectator;
        // A trava local e a chave dos controles de espectador. O Mestre nunca
        // e travado pelo servidor, entao esta e a unica forma de ligar o
        // carrossel de cliques e os modos de câmera para ele.
        TabletopRpgClient.locked = masterSpectator;
        client.player.displayClientMessage(
                Component.translatable("message.tabletoprpg.camera_spectator", masterSpectator ? "ON" : "OFF"), true);
        TabletopRpgClient.LOGGER.info("[TabletopRPG-Client] Camera de espectador do Mestre: {}",
                masterSpectator ? "ON" : "OFF");
    }

    /**
     * Índice do alvo atual: 0 = si mesmo, 1+ = targets.get(index - 1).
     *
     * <p>Para o <b>Mestre</b> a posição 0 nao e ele mesmo (ele nunca e alvo
     * de si mesmo): o índice é o da lista do servidor direto. Ver
     * {@link #listIndex(int)}.
     */
    private static int targetIndex = 0;

    /** Contador do heartbeat diagnostico do carrossel (08/10/2026). */
    private static int heartbeatTicks = 0;

    private SpectatorCameraController() {
    }

    /**
     * Zera o estado derivado da sessao na desconexao (decisao do usuario em
     * 27/09/2026). O {@code entityId} do jogador muda a cada login, entao um
     * {@code targetIndex} guardado apontaria para outra pessoa ao reconectar
     * (ou para indice fora da lista). Zera tambem {@code wasLocked} e
     * {@code lastGameModeOrdinal} para o JOIN nao parecer troca de turno/modo.
     * As preferencias do jogador ({@code mode}, {@code orbitalEnabled},
     * {@code topDownZoom}, {@code transitionSpeed}) sao preservadas. Nao chama
     * {@code deactivate} (que exige {@link Minecraft}): o {@link #tick} ja o
     * faz quando {@code client.player == null}.
     */
    public static void reset() {
        targetIndex = 0;
        active = false;
        wasLocked = false;
        lastGameModeOrdinal = -1;
        freeCamInitialized = false;
        thirdPersonLookInitialized = false;
        freeCamPos = null;
        // A câmera de espectador do Mestre é estado da sessão: ela não
        // sobrevive a uma saída do mundo.
        masterSpectator = false;
        // O id zerado e proposital (08/10/2026): o tick trata "0 -> id novo" como
        // troca de entidade e re-pede a lista de alvos ao servidor no primeiro tick da
        // sessao nova. Sem este re-arma, quem saia no meio de um modo e voltava ficava
        // com o carrossel da sessao anterior -- mobs com camera sumiam (feedback da
        // jogadora em 08/10/2026).
        lastPlayerEntityId = 0;
    }

    /**
 * Re-arma a câmera quando a <b>entidade do jogador é trocada</b>: cair e voltar, ou
 * trocar de dimensão.
 *
 * <p><b>Por que isto existe (05/10/2026, bug da jogadora):</b> nada no cliente reagia
 * à troca de entidade, e o servidor não empurra a lista de alvos nesse momento. O
 * sintoma era: cair, voltar, e as câmeras dos mobs deixavam de ser espectáveis até a
 * jogadora tirar o modo e colocar de novo — porque o toggle é o que fazia o servidor
 * reenviar.
 *
 * <p><b>O que morre com a entidade antiga:</b> a lista de alvos (filtrada pelo id do
 * jogador antigo), o índice do carrossel, a posição da câmera livre e os flags de
 * inicialização. Todos guardam referência ao `LocalPlayer` que deixou de existir, e
 * reaproveitá-los é pior do que recomeçar: o `getCurrentTarget` cairia no
 * {@code return client.player} em silêncio, e a jogadora veria a própria câmera em vez
 * de uma pista de erro.
 *
 * <p><b>O que sobrevive de propósito:</b> {@code mode} e {@code active}. A preferência
 * de câmera é da <b>sessão</b>, não da entidade; soltar a câmera FREE no meio do turno
 * seria mais invasivo do que recolocá-la. Se for preciso voltar ao normal, é uma tecla.
 *
 * <p>Ao fim, pede a lista nova ao servidor em vez de tentar consertar a velha: a lista
 * antiga foi filtrada pelo id de quem não existe mais, e o servidor a reconstrói a
 * partir do mundo atual.
 */
    public static void onPlayerEntityChanged() {
        targets = List.of();
        targetIndex = 0;
        freeCamPos = null;
        freeCamInitialized = false;
        thirdPersonLookInitialized = false;
        wasLocked = false;
        lastGameModeOrdinal = -1;

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            // Log diagnostico (08/10/2026): marca no log do cliente que o re-pedido
            // da lista aconteceu, para o repro separar "pediu" de "recebeu".
            TabletopRpgClient.LOGGER.info(
                    "[TabletopRPG-Client] Entidade do jogador trocada -> pedindo lista de alvos ao servidor.");
            ClientPlayNetworking.send(new RpgNetworking.SpectatorTargetsRequestPayload());
        }
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * true quando a câmera <b>livre</b> está de fato voando agora.
     *
     * <p><b>Por que os mixins dependem disto (decisão do usuário em 25/09/2026):
     * </b> no modo Livre da sessão <b>ninguém fica travado</b>, então a trava não
     * pode ser a condição de "o corpo está parado". Com a câmera livre ligado,
     * as teclas WASD movem a <i>câmera</i> — e sem este sinal, o jogador
     * andaria ao mesmo tempo, e o "corpo parado" não aconteceria.
     *
     * <p>É {@code active && modo == FREE} e não só o modo: o usuário pode ter a
     * câmera Livre selecionada e ainda não estar com ela ligada (fora de um
     * modo válido, por exemplo). Sem {@code active}, o jogador ficaria preso
     * sem poder andar logo após desligar a câmera com V.
     */
    public static boolean isFreeCameraActive() {
        return active && mode == Mode.FREE;
    }

    public static Mode getMode() {
        return mode;
    }

    /** Define o modo diretamente (menu de configurações). */
    public static void setMode(Mode newMode) {
        requestMode(newMode);
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
     * Quantas posições tem o carrossel.
     *
     * <p>Para o jogador comum a posição 0 é ele mesmo. Para o <b>Mestre</b>
     * essa posição não existe: o mestre nunca é alvo de ninguém (nem de si
     * mesmo), então o carrossel dele começa direto na lista do servidor.
     */
    private static int carouselSize() {
        return masterSpectator ? targets.size() : targets.size() + 1;
    }

    /**
     * Índice do alvo dentro da lista do servidor.
     *
     * <p>O índice 0 do carrossel é o próprio jogador -- exceto para o Mestre,
     * que nunca é alvo de si mesmo: para ele o índice do carrossel e o índice
     * da lista.
     */
    private static int listIndex(int index) {
        return masterSpectator ? index : index - 1;
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
        int size = carouselSize();
        if (targetIndex >= size) {
            targetIndex = Math.max(0, size - 1);
        }
    }

    /**
     * Cicla o modo de câmera (tecla V / menu). O modo persiste como preferência.
     *
     * <p><b>A câmera livre está liberada em todos os modos da sessão</b> (decisão
     * do usuário em 26/09/2026). Antes ela só existia no modo Livre e era
     * recusada em Investigação/Combate.
     *
     * <p><b>Fora do modo espectador, V só alterna entre a câmera livre e a
     * câmera normal do Minecraft.</b> Sem espectador, primeira/terceira pessoa é
     * justamente o F5 do próprio Minecraft, então o V fica com duas posições:
     * <ul>
     *   <li>câmera normal → <b>câmera livre</b>;</li>
     *   <li>câmera livre → <b>volta ao normal</b> (o {@code deactivate} restaura
     *       a perspectiva que o jogador escolheu com F5, então ela é
     *       preservada).</li>
     * </ul>
     * Vale nos três modos da sessão e também durante o próprio turno.
     *
     * <p><b>No modo espectador o ciclo passa pelas quatro</b>: terceira → primeira
     * → de cima → livre → terceira.
     */
    public static void cycleMode() {
        Minecraft client = Minecraft.getInstance();
        boolean espectador = TabletopRpgClient.locked;

        if (!espectador) {
            if (isFreeCameraActive()) {
                // Sai da camera livre. O tick() ve que o modo ja nao e FREE e
                // chama deactivate(), que restaura a perspectiva vanilla.
                mode = Mode.THIRD_PERSON;
                if (client.player != null) {
                    client.player.displayClientMessage(
                            Component.translatable("message.tabletoprpg.camera_normal"), true);
                }
                return;
            }
            mode = Mode.FREE;
            if (client.player != null) {
                resetTransition(client);
                client.player.displayClientMessage(
                        Component.translatable("message.tabletoprpg.camera_mode", Mode.FREE.getDisplayName()), true);
            }
            return;
        }

        Mode[] modes = Mode.values();
        mode = modes[(mode.ordinal() + 1) % modes.length];
        if (client.player != null) {
            resetTransition(client);
            client.player.displayClientMessage(
                    Component.translatable("message.tabletoprpg.camera_mode", mode.getDisplayName()), true);
        }
    }

    /** Carrossel: próximo alvo (clique esquerdo). */
    public static void cycleNext() {
        int size = carouselSize();
        if (size <= (masterSpectator ? 0 : 1)) {
            // Log diagnostico (08/10/2026): prova que o clique chegou e a lista
            // estava vazia -- o sintoma "nao consigo trocar a camera" da jogadora.
            TabletopRpgClient.LOGGER.info("[TabletopRPG-Client] CycleNext ignorado: carrossel sem alvos.");
            return;
        }
        targetIndex = (targetIndex + 1) % size;
        logCycle("next");
        onTargetChanged();
    }

    /** Carrossel: alvo anterior (clique direito). */
    public static void cyclePrev() {
        int size = carouselSize();
        if (size <= (masterSpectator ? 0 : 1)) {
            TabletopRpgClient.LOGGER.info("[TabletopRPG-Client] CyclePrev ignorado: carrossel sem alvos.");
            return;
        }
        targetIndex = (targetIndex - 1 + size) % size;
        logCycle("prev");
        onTargetChanged();
    }

    /**
     * Log diagnostico (08/10/2026): registra TODO clique do carrossel (mesmo o
     * bem-sucedido, que antes era silencioso) e se o alvo escolhido resolve em
     * uma entidade real no cliente. `resolvido=NULO` prova que a lista esta
     * certa mas a entidade nao existe no cliente; `indice 0` prova que o clique
     * avancou so ate o proprio jogador.
     */
    private static void logCycle(String direcao) {
        Minecraft client = Minecraft.getInstance();
        int idx = listIndex(targetIndex);
        if (idx < 0) {
            TabletopRpgClient.LOGGER.info(
                    "[TabletopRPG-Client] Clique carrossel ({}): indice 0 = jogador proprio.", direcao);
            return;
        }
        if (client.level == null || idx >= targets.size()) {
            TabletopRpgClient.LOGGER.info(
                    "[TabletopRPG-Client] Clique carrossel ({}): indice {} fora da lista (size={}).",
                    direcao, targetIndex, targets.size());
            return;
        }
        RpgNetworking.SpectatorTargetsPayload.TargetData t = targets.get(idx);
        Entity entity = client.level.getEntity(t.entityId());
        if (entity != null) {
            TabletopRpgClient.LOGGER.info(
                    "[TabletopRPG-Client] Clique carrossel ({}): indice {}/{} alvoId={} resolvido={} pos=({}).",
                    direcao, targetIndex, carouselSize(), t.entityId(),
                    entity.getType().getDescriptionId(),
                    String.format("%.1f, %.1f, %.1f", entity.getX(), entity.getY(), entity.getZ()));
        } else {
            TabletopRpgClient.LOGGER.info(
                    "[TabletopRPG-Client] Clique carrossel ({}): indice {}/{} alvoId={} NULO (entidade nao existe no cliente).",
                    direcao, targetIndex, carouselSize(), t.entityId());
        }
    }

    /** Chamado a cada tick do cliente. Liga/desliga a câmera de espectador. */
    public static void tick(Minecraft client) {
        boolean locked = TabletopRpgClient.locked;
        // Instante em que o turno chega para quem estava travado. Precisa ser lido
        // antes de `wasLocked` e `lastGameModeOrdinal` serem sobrescritos abaixo.
        // Mudanca do modo da sessao tambem reenvia locked=false, entao nao conta
        // como turno: senao o mestre derrubaria a camera de todo mundo ao trocar
        // de modo.
        boolean sessionModeChanged = lastGameModeOrdinal != TabletopRpgClient.gameModeOrdinal;
        lastGameModeOrdinal = TabletopRpgClient.gameModeOrdinal;
        boolean turnJustStarted = !locked && wasLocked && !sessionModeChanged;
        wasLocked = locked;

        // Sem jogador (tela de título, mundo carregando): garante desligamento.
        if (client.player == null) {
            deactivate(client);
            return;
        }

        // A entidade do cliente foi trocada: cair e voltar, mudar de dimensao, ou uma
        // sessao nova depois do `reset()` da desconexao (08/10/2026).
        //
        // <b>Por que comparar id e nao usar evento (05/10/2026):</b> a versao da
        // Fabric API deste projeto (0.141.6) nao tem evento de "o jogador do cliente
        // foi trocado" -- so existe o equivalente server-side, `ServerEntityWorldChangeEvents`,
        // que nao roda aqui. O `tick` ja roda a cada quadro e o `id` da entidade do
        // jogador muda justamente nos dois casos que importam.
        //
        // <b>O caso da reconexao (08/10/2026, feedback da jogadora):</b> sair no meio
        // de um modo (investigacao/combate) e voltar NAO muda o id de uma vez so: o
        // `reset()` zera `lastPlayerEntityId` na desconexao, e com a guarda antiga o
        // tick nunca pedia a lista de novo -- o carrossel ficava com a lista velha
        // (alvos que nao existem mais) e o mob com camera sumia. Tratar "0 -> id novo"
        // como a mesma troca de entidade re-arma a camera no primeiro tick da sessao
        // nova (limpa a lista velha + pede a lista atual ao servidor). O push do JOIN
        // pode chegar duplicado e e inofensivo: `setServerTargets` so substitui.
        int playerEntityId = client.player.getId();
        if (lastPlayerEntityId == 0 || playerEntityId != lastPlayerEntityId) {
            onPlayerEntityChanged();
        }
        lastPlayerEntityId = playerEntityId;

        // Heartbeat diagnostico (08/10/2026): a cada 5s, mostra quantos alvos do
        // carrossel resolvem em entidades REAIS no cliente. `resolviveis=0` com a
        // lista certa prova que as entidades nao chegam ao cliente; `5/5` prova
        // que o problema nao e de entidade (e sim da camera/visualizacao).
        heartbeatTicks++;
        if (heartbeatTicks >= 100) {
            heartbeatTicks = 0;
            if (client.level != null) {
                int resolviveis = 0;
                for (RpgNetworking.SpectatorTargetsPayload.TargetData t : targets) {
                    if (client.level.getEntity(t.entityId()) != null) {
                        resolviveis++;
                    }
                }
                TabletopRpgClient.LOGGER.info(
                        "[TabletopRPG-Client] Carrossel heartbeat: idx={}/{} alvos resolviveis={}/{} locked={} active={} modo={}",
                        targetIndex, carouselSize(), resolviveis, targets.size(),
                        locked, active, mode);
            }
        }

        // A camara de espectador liga em dois casos:
        //  1. o jogador esta travado (nao e o turno dele) -- cameras de
        //     terceira/primeira/cima/livre, em qualquer modo da sessao;
        //  2. a camera LIVRE esta selecionada e o jogador age normalmente
        //     (turno dele, ou sessao no modo Livre, onde ninguem trava).
        boolean freeCamSelected = mode == Mode.FREE;

        // Regra do turno (decisao do usuario em 26/09/2026): quando o turno chega
        // para quem estava travado, a camera volta sozinha para a camera do jogo.
        // A camera livre continua liberada durante o proprio turno -- o jogador
        // que esta no turno alterna entre a camera do jogo e a livre, e o ciclo
        // do modo espectador (com as quatro cameras) e' so do jogador travado.
        if (turnJustStarted && freeCamSelected) {
            mode = Mode.THIRD_PERSON;
            freeCamSelected = false;
            resetTransition(client);
            client.player.displayClientMessage(
                    Component.translatable("message.tabletoprpg.camera_normal"), true);
        }

        if (!locked && !freeCamSelected) {
            deactivate(client);
            return;
        }

        Entity target = getCurrentTarget(client);
        if (target == null) {
            // O MESTRE nunca e alvo de si mesmo: se a lista de alvos esta
            // vazia (ou o alvo sumiu do mundo) nao ha o que espectar. A
            // camera LIVRE e a unica que nao precisa de alvo -- ela voa a
            // partir do proprio corpo.
            if (!(masterSpectator && mode == Mode.FREE)) {
                deactivate(client);
                return;
            }
        }

        if (!active) {
            active = true;
            previousCameraType = client.options.getCameraType();
            transitionProgress = 0f;
            transitionStart = client.player.getEyePosition();
            freeCamInitialized = false;
            thirdPersonLookInitialized = false;
            client.setCameraEntity(client.player);
            // Log diagnostico (08/10/2026): em que estado a camera ligou apos o
            // rejoin (indice do carrossel e modo de camera).
            TabletopRpgClient.LOGGER.info(
                    "[TabletopRPG-Client] Camera de espectador ativada: modo={} idx={}/{}",
                    mode, targetIndex, carouselSize());
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
                    alignDownedTarget(target, thirdPersonYaw);
                    Vec3 look = lookFromYawPitch(thirdPersonYaw, thirdPersonPitch);
                    Vec3 camPos = clipToWall(center, center.subtract(look.scale(RADIUS)));
                    ensureRigActive(camPos, thirdPersonYaw, thirdPersonPitch);
                } else {
                    // Órbita automática: alvo fora do turno (ou mob) com
                    // "Câmera Orbital: Sim".
                    angle += ANGULAR_SPEED;
                    Vec3 camPos = clipToWall(center, orbitPosition());
                    // Alinha o caído pelo yaw REAL da câmera (o mesmo que vai
                    // para o rig), e não por `angle`, que é um ângulo de órbita
                    // com origem diferente.
                    alignDownedTarget(target, computeYaw(camPos));
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
     * Vira o corpo e a cabeça do alvo CAÍDO na direção para onde a câmera olha.
     *
     * <p><b>FACT (feedback do usuário em 25/09/2026):</b> cancelar o
     * {@code Entity.turn} ({@code EntityTurnMixin}) resolveu a cabeça girar
     * 360 graus, mas deixou o <b>braço</b> parado: o corpo deitado tem um yaw
     * fixo no mundo, então conforme a câmera orbitava para a direita o braço
     * ia "para trás" e sumia da tela.
     *
     * <p><b>Por que só o caído:</b> em pé, girar o corpo junto com a câmera é o
     * comportamento normal do vanilla. Deitado, o modelo está deitado no eixo do
     * corpo, então a defasagem entre o yaw do corpo e o yaw da câmera é muito
     * mais visível. O usuário pediu o ajuste apenas nesse caso.
     *
     * <p><b>Só no cliente:</b> são campos de renderização. O servidor tem a
     * própria cópia e não é afetado — o {@code EntityTurnMixin} continua
     * cancelando a rotação de verdade, então o corpo não "gira" de fato, apenas
     * é desenhado voltado para a câmera.
     */
    private static void alignDownedTarget(Entity target, float camYaw) {
        if (target instanceof Player player && TabletopRpgClient.isDowned(player)) {
            player.yBodyRot = camYaw;
            player.yHeadRot = camYaw;
        }
    }

    /**
     * Garante que o rig da câmera esteja ativo antes de atualizá-lo. O
     * {@link CinematicCameraRig#update} é um no-op quando inativo, então ao
     * ativar a câmera de espectador (ou voltar de um modo que desativa o rig,
     * como a 1ª pessoa do próprio jogador) é preciso chamar {@code activate}
     * primeiro.
     */
    /**
     * Ultimo yaw realmente enviado ao rig da camera. E a fonte da verdade para
     * alinhar o visual do jogador caido: todos os caminhos de camera passam por
     * {@link #ensureRigActive}, entao este valor e sempre o yaw que o usuario
     * esta vendo - incluindo a orbita automatica, cujo angulo bruto tem origem
     * diferente da camera.
     */
    private static float currentYaw;

    /** Yaw efetivo da camera de espectador (ultimo enviado ao rig). */
    public static float getCurrentYaw() {
        return currentYaw;
    }

    private static void ensureRigActive(Vec3 pos, float yaw, float pitch) {
        currentYaw = yaw;
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
        int idx = listIndex(targetIndex);
        if (idx < 0 || idx >= targets.size()) {
            // O jogador comum volta para si mesmo (a posicao 0 do carrossel).
            // O MESTRE nao pode ser alvo de si mesmo: sem alvo valido a
            // camera de espectador dele simplesmente nao liga.
            return masterSpectator ? null : client.player;
        }
        Entity entity = client.level.getEntity(targets.get(idx).entityId());
        return entity != null ? entity : (masterSpectator ? null : client.player);
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