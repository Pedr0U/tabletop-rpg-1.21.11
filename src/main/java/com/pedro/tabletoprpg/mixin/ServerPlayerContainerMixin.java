package com.pedro.tabletoprpg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.pedro.tabletoprpg.MasterCommands;
import com.pedro.tabletoprpg.TabletopRpg;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mantém menus de container abertos remotamente pelo Mestre da sessão.
 *
 * <p>Vanilla fecha o menu no tick quando {@code !containerMenu.stillValid(this)}.
 * Para menus remotos aprovados, forçamos stillValid=true nesse check.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerContainerMixin {

    @WrapOperation(
            method = {"doTick()V", "tick()V"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;stillValid(Lnet/minecraft/world/entity/player/Player;)Z"),
            require = 0)
    private boolean tabletoprpg$remoteContainerStillValid(AbstractContainerMenu menu, Player player, Operation<Boolean> original) {
        if (MasterCommands.isRemoteContainerMenu(menu)) {
            TabletopRpg.LOGGER.debug("[TabletopRPG] remote container stillValid forced true for {}", player.getScoreboardName());
            return true;
        }
        return original.call(menu, player);
    }

    // Nao suprime closeContainer/doCloseContainer: o jogador deve conseguir fechar o menu.
    // O keep-open e feito forcando stillValid=true no tick; quando o jogador fecha, o
    // unmark abaixo permite que o fechamento normal rode e a animacao do bau finalize.

    // Animação/som remotos são suprimidos em ContainerOpenersCounterMixin.

    @Inject(method = "closeContainer()V", at = @At("HEAD"))
    private void tabletoprpg$clearRemoteContainerOnClose(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        MasterCommands.unmarkRemoteContainerMenu(self.containerMenu);
    }
}
