package com.yourname.combatoverhaul.graph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 服务端事件 -> GraphRuntime 事件帽子。 */
public final class GraphEvents {

    private GraphEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            GraphRuntime.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity le) {
            GraphRuntime.signal(le, "on_spawn", "");
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        GraphRuntime.signal(event.getEntity(), "on_hurt", "");
        if (event.getSource().getEntity() instanceof LivingEntity attacker) {
            GraphRuntime.signal(attacker, "on_hit", "");
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            GraphRuntime.signal(event.getEntity(), "on_death", "");
        }
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        Player p = event.getEntity();
        if (!p.level().isClientSide()) {
            GraphRuntime.signal(p, "on_attack_start", "");
        }
    }

    @SubscribeEvent
    public static void onJump(net.minecraftforge.event.entity.living.LivingEvent.LivingJumpEvent event) {
        LivingEntity le = event.getEntity();
        if (!le.level().isClientSide()) {
            GraphRuntime.signal(le, "on_jump", "");
        }
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            GraphRuntime.signal(event.getEntity(), "on_land", "");
        }
    }

    @SubscribeEvent
    public static void onItemUse(PlayerInteractEvent.RightClickItem event) {
        Player p = event.getEntity();
        if (!p.level().isClientSide()) {
            GraphRuntime.signal(p, "on_item_use", "");
        }
    }
}
