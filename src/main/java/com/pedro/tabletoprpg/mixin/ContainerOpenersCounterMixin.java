package com.pedro.tabletoprpg.mixin;

import com.pedro.tabletoprpg.MasterCommands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suprime animação/som de abertura de container para aberturas remotas do Mestre.
 * Interações presenciais normais continuam vanilla.
 */
@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersCounterMixin {

    @Inject(method = "incrementOpeners(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;D)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void tabletoprpg$skipRemoteIncrement(LivingEntity entity, Level level, BlockPos pos, BlockState state, double maxDistance, CallbackInfo ci) {
        if (entity instanceof ServerPlayer sp && MasterCommands.isRemoteOpeningPlayer(sp.getUUID())) {
            MasterCommands.markRemoteAnimationSuppressed(sp.getUUID());
            com.pedro.tabletoprpg.TabletopRpg.LOGGER.debug("[TabletopRPG] remote open: suppressed incrementOpeners for {}", sp.getScoreboardName());
            ci.cancel();
        }
    }

    @Inject(method = "decrementOpeners(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void tabletoprpg$skipRemoteDecrement(LivingEntity entity, Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (entity instanceof ServerPlayer sp && MasterCommands.consumeRemoteAnimationSuppressed(sp.getUUID())) {
            com.pedro.tabletoprpg.TabletopRpg.LOGGER.debug("[TabletopRPG] remote close: suppressed decrementOpeners for {}", sp.getScoreboardName());
            ci.cancel();
        }
    }
}
