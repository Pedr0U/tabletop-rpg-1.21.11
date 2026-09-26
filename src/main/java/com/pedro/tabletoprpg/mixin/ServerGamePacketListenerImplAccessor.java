package com.pedro.tabletoprpg.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expóe o campo privado {@code awaitingPositionFromClient} do
 * {@link ServerGamePacketListenerImpl}: quando não-nulo, há um teleporte
 * pendente aguardando confirmação do cliente.
 *
 * <p>Usado pela barreira da aura (FASE 0.5) para não floodar
 * {@code ClientboundPlayerPositionPacket}: só enviamos um novo teleporte
 * quando o anterior já foi aceito pelo cliente.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {

    @Accessor("awaitingPositionFromClient")
    Vec3 awaitingPositionFromClient();
}
