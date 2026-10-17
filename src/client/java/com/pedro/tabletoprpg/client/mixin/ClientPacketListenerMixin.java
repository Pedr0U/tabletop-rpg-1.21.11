package com.pedro.tabletoprpg.client.mixin;

import com.pedro.tabletoprpg.client.TabletopRpgClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTICO BUG D (rodada 4): registra no log do cliente o instante em que um
 * pacote de spawn de creeper/golem chega (handleAddEntity) e quando um pacote de
 * remocao de entidades chega (handleRemoveEntities).
 *
 * Objetivo: separar as duas hipoteses que sobraram -- "o servidor nao enviou o
 * spawn" de "o cliente recebeu e perdeu/removeu a entidade".
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleAddEntity", at = @At("HEAD"))
    private void tabletopRpg$logAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        EntityType<?> type = packet.getType();
        if (type == EntityType.CREEPER || type == EntityType.IRON_GOLEM) {
            TabletopRpgClient.LOGGER.info(
                    "[TabletopRPG-Client] DIAG spawn RECEBIDO: type={} id={} pos=({}, {}, {})",
                    EntityType.getKey(type),
                    packet.getId(),
                    String.format("%.2f", packet.getX()),
                    String.format("%.2f", packet.getY()),
                    String.format("%.2f", packet.getZ()));
        }
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"))
    private void tabletopRpg$logRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        TabletopRpgClient.LOGGER.info(
                "[TabletopRPG-Client] DIAG remocao RECEBIDA: ids={}",
                packet.getEntityIds());
    }
}
