package com.pedro.tabletoprpg.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pedro.tabletoprpg.RpgNetworking;
import com.pedro.tabletoprpg.item.ModItems;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.BlockLockAuraRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aura dos blocos trancados, visivel apenas para o Mestre.
 *
 * <p>Estado e desenho vivem juntos aqui, e nao no TabletopRpgClient: e a unica parte
 * que sabe a lista de trancas, e assim nao ha dois lugares que podem divergir.
 *
 * <p>A flag de Mestre chega no payload, decidida pelo servidor, porque o servidor e a
 * unica parte que sabe quem e o Mestre. O cliente nao tenta deduzir isso.
 */
public final class BlockLockAura {
    /** Ambar translucido: alpha 0x73 (~45%), RGB 0xFFB000. */
    private static final int AURA_COLOR = 0x73FFB000;
    private static final float AURA_LINE_WIDTH = 2.0F;

    private static boolean isMaster;
    private static final Map<String, Set<BlockPos>> locksByDimension = new HashMap<>();

    private BlockLockAura() {
    }

    public static void register() {
        // END_MAIN e o ultimo evento do terreno: a aura passa por cima da agua, do
        // vidro e do terreno, e ainda fica abaixo da HUD. Nao existe AFTER_TRANSLUCENT
        // nem LAST nesta versao.
        WorldRenderEvents.END_MAIN.register(BlockLockAura::render);
    }

    public static void setState(boolean master, List<RpgNetworking.BlockLockEntry> locks) {
        isMaster = master;
        locksByDimension.clear();

        if (locks == null) {
            return;
        }

        for (RpgNetworking.BlockLockEntry entry : locks) {
            if (entry == null || entry.dimension() == null || entry.pos() == null) {
                continue;
            }
            locksByDimension.computeIfAbsent(entry.dimension(), key -> new HashSet<>()).add(entry.pos());
        }
    }

    public static void clear() {
        isMaster = false;
        locksByDimension.clear();
    }

    private static boolean holdingLockItem(LocalPlayer player) {
        return ModItems.isBlockLock(player.getMainHandItem())
                || ModItems.isBlockLock(player.getOffhandItem());
    }

    private static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        Level level = client.level;

        if (player == null || level == null) {
            return;
        }

        String dimensionKey = level.dimension().identifier().toString();

        if (!isMaster) {
            return;
        }

        Set<BlockPos> locks = locksByDimension.get(dimensionKey);
        if (locks == null || locks.isEmpty()) {
            return;
        }

        if (!holdingLockItem(player)) {
            return;
        }

        PoseStack poseStack = context.matrices();
        Vec3 camera = client.gameRenderer.getMainCamera().position();

        RenderType auraType = BlockLockAuraRenderType.linesThroughWalls();
        MultiBufferSource consumers = context.consumers();
        VertexConsumer consumer = consumers.getBuffer(auraType);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        for (BlockPos pos : locks) {
            ShapeRenderer.renderShape(
                    poseStack, consumer, Shapes.block(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    AURA_COLOR, AURA_LINE_WIDTH);
        }

        poseStack.popPose();

        // The interface only exposes getBuffer; endBatch lives on the BufferSource
        // implementation, which is what vanilla world rendering actually hands over.
        if (consumers instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch(auraType);
        }
    }
}