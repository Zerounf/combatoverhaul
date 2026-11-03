package com.yourname.combatoverhaul.combat;

import com.yourname.combatoverhaul.CombatOverhaulMod;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 实机测试：服务端装备钻石胸甲，确保客户端盔甲层能真实查询到。 */
public final class ServerArmorTest {

    private static final Set<UUID> DONE = new HashSet<>();

    private ServerArmorTest() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!"true".equalsIgnoreCase(System.getProperty("combatoverhaul.armorTest"))) {
            return;
        }
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        if (!DONE.add(player.getUUID())) {
            return;
        }
        player.setItemSlot(EquipmentSlot.CHEST,
                new ItemStack(Items.DIAMOND_CHESTPLATE));
        CombatOverhaulMod.LOGGER.info("[CombatOverhaul][ArmorTest] server equipped diamond chestplate");
    }
}
