package com.pedro.tabletoprpg.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exp├Áe o campo privado {@code awaitingPositionFromClient} do
 * {@link ServerGamePacketListenerImpl}: quando n├úo-nulo, h├í um teleporte
 * pendente aguardando confirma├º├úo do cliente.
 *
 * <p>Usado pela barreira da aura (FASE 0.5) para n├úo floodar
 * {@code ClientboundPlayerPositionPacket}: s├│ enviamos um novo teleporte
 * quando o anterior j├í foi aceito pelo cliente.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {

    @Accessor("awaitingPositionFromClient")
    Vec3 awaitingPositionFromClient();
}
