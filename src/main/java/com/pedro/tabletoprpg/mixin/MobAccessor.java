package com.pedro.tabletoprpg.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expõe os {@code goalSelector} e {@code targetSelector} do {@link Mob}
 * (campos protected) para o {@code CombatController} conseguir remover todos
 * os goals de um monstro controlado pelo mestre, evitando que ele aja por
 * conta própria (atacar, vagar, etc.).
 */
@Mixin(Mob.class)
public interface MobAccessor {

    @Accessor("goalSelector")
    GoalSelector getGoalSelector();

    @Accessor("targetSelector")
    GoalSelector getTargetSelector();
}