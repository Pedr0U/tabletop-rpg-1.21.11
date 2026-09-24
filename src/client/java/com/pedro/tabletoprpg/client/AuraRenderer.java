package com.pedro.tabletoprpg.client;

import com.pedro.tabletoprpg.RpgNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Renderiza as auras azuis (círculos de limite de movimentação) no chão.
 *
 * <p>Os círculos são ancorados nas posições iniciais (não seguem entidades)
 * e delimitam até onde o monstro selecionado e o jogador ativo podem andar.
 * As posições vêm do servidor via {@link RpgNetworking.AuraStatePayload}.
 *
 * <p>IMPORTANTE (1.21.11): o sistema de gizmos do vanilla é o jeito correto
 * de desenhar linhas visíveis no mundo (o próprio DebugRenderer usa
 * {@code Gizmos.circle/line/rect}). O collector de gizmos está ativo durante
 * o {@link WorldRenderEvents.AFTER_ENTITIES}: ele é registrado no
 * {@code Minecraft.runTick} (antes do render) e finalizado no fim do main
 * pass (depois deste evento), tudo no mesmo thread do render.
 *
 * <p>O círculo segue o relevo: cada segmento amostra a altura do bloco mais
 * alto (heightmap MOTION_BLOCKING) naquela posição, então a aura sobe/desce
 * junto com o terreno em vez de entrar no chão em áreas com desnível.
 */
public final class AuraRenderer {

    /** Última contagem de auras logada (para não spammar o log a cada frame). */
    private static int lastLoggedCount = -1;

    private AuraRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            List<RpgNetworking.AuraStatePayload.AuraData> auras = TabletopRpgClient.auras;
            if (auras.isEmpty()) {
                return;
            }
            if (auras.size() != lastLoggedCount) {
                lastLoggedCount = auras.size();
                TabletopRpgClient.LOGGER.info("[TabletopRPG] AuraRenderer: renderizando {} aura(s): {}", auras.size(), auras);
            }
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            // Azul semi-transparente (ARGB).
            int color = ARGB.colorFromFloat(0.9F, 0.3F, 0.6F, 1.0F);
            for (RpgNetworking.AuraStatePayload.AuraData aura : auras) {
                try {
                    drawAura(level, aura, color);
                } catch (Throwable t) {
                    TabletopRpgClient.LOGGER.error("[TabletopRPG] AuraRenderer: falha ao adicionar gizmo", t);
                }
            }
        });
    }

    /**
     * Desenha o círculo da aura como segmentos de linha, amostrando a altura
     * do terreno em cada vértice. 60 segmentos (~1.5 bloco cada para raio 15)
     * deixam o círculo suave mesmo em relevo.
     */
    private static void drawAura(ClientLevel level, RpgNetworking.AuraStatePayload.AuraData aura, int color) {
        int segments = 60;
        double cx = aura.x();
        double cz = aura.z();
        double r = aura.radius();
        double prevX = cx + r;
        double prevZ = cz;
        double prevY = groundY(level, prevX, prevZ);
        for (int i = 1; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            double x = cx + r * Math.cos(angle);
            double z = cz + r * Math.sin(angle);
            double y = groundY(level, x, z);
            // persistForMillis: o collector de gizmos NUNCA é limpo (só remove
            // expirados). Sem expiração, a lista cresceria 1 gizmo por frame.
            // Com 200ms, os gizmos são re-adicionados a cada frame e os antigos
            // expiram sozinhos (lista fica limitada).
            Gizmos.line(new Vec3(prevX, prevY, prevZ), new Vec3(x, y, z), color, 2.0F).persistForMillis(200);
            prevX = x;
            prevY = y;
            prevZ = z;
        }
    }

    /**
     * Altura do topo do bloco mais alto em (x, z) + pequeno offset
     * anti-z-fighting. {@code getHeight} retorna o Y do bloco; o topo fica em
     * Y+1, e +0.05 evita o círculo "brilhar" dentro do bloco.
     */
    private static double groundY(ClientLevel level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)) + 1.05;
    }
}