package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.TabletopRpg;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTICO BUG D (rodada 4): registra no log do servidor o instante exato em
 * que o spawn de um mob de camera e ENVIADO para um jogador (addPairing) e
 * quando deixa de ser enviado (removePairing).
 *
 * Objetivo: provar de forma direta se, no JOIN de um jogador preso (locked), o
 * bundle de ClientboundAddEntityPacket chega a ser enviado ou nao. Os logs da
 * rodada 3 provaram por contradicao que o cliente ficava sem as entidades, mas
 * nao davam o elo que falha (servidor nao enviou x cliente descartou).
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "addPairing", at = @At("HEAD"))
    private void tabletopRpg$logAddPairing(ServerPlayer player, CallbackInfo ci) {
        this.tabletopRpg$logCameraSpawn(player, "ENVIADO");
    }

    @Inject(method = "removePairing", at = @At("HEAD"))
    private void tabletopRpg$logRemovePairing(ServerPlayer player, CallbackInfo ci) {
        this.tabletopRpg$logCameraSpawn(player, "REMOVIDO");
    }

    /** Loga apenas mobs de camera, para nao poluir o log do servidor. */
    private void tabletopRpg$logCameraSpawn(ServerPlayer player, String acao) {
        if (this.entity instanceof Mob mob && mob.getTags().contains("tabletoprpg_camera")) {
            TabletopRpg.LOGGER.info(
                    "[TabletopRPG] DIAG spawn {}: jogador={} mob={} id={} pos=({}, {}, {})",
                    acao,
                    player.getName().getString(),
                    mob.getType().getDescriptionId(),
                    mob.getId(),
                    String.format("%.2f", mob.getX()),
                    String.format("%.2f", mob.getY()),
                    String.format("%.2f", mob.getZ()));
        }
    }
}
