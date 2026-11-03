package com.yourname.combatoverhaul.server;

import com.yourname.combatoverhaul.net.CombatMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 登录时把玩家的权威挖掘模式推给客户端。
 *
 * <p>模式存在玩家持久化 NBT（{@link CombatMessages#NBT_COMBAT_ONLY}），跨重登保留；
 * 客户端只负责显示与本地拦截，权威值始终由服务端下发。
 */
@Mod.EventBusSubscriber(modid = "combatoverhaul", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MiningModeSync {

    private MiningModeSync() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            CombatMessages.syncMiningMode(sp);
        }
    }
}
