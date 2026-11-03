package com.yourname.combatoverhaul.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 挖掘守卫：控制原版左键攻击是否真的去挖方块。
 *
 * <p>两种模式（按 X 切换）：
 * <ul>
 *   <li><b>混合</b>：视线命中方块时放行原版挖掘；命中实体时放行原版攻击事件，
 *       由服务端 {@code ServerCombatEvents} 决定替换成动作还是原版伤害；
 *       空挥时取消原版，交给本模组的技能键；</li>
 *   <li><b>纯战斗</b>：命中方块时取消原版挖掘；命中实体时同样放行服务端攻击事件；
 *       空挥时取消，交给技能键。</li>
 * </ul>
 *
 * <p>取消 {@link InputEvent.InteractionKeyMappingTriggered} 只影响原版的攻击判定，
 * 不影响本模组自己注册的攻击热键（它们走独立的 KeyMapping 与网络包）。
 */
@Mod.EventBusSubscriber(modid = "combatoverhaul", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MiningGuard {

    /** 是否处于纯战斗模式（禁止一切挖掘） */
    private static boolean combatOnly = false;
    /** 重攻击修饰键：与 ClientSetup.HEAVY_MODIFIER 保持一致 */
    private static final int HEAVY_MODIFIER = GLFW.GLFW_KEY_LEFT_ALT;

    private MiningGuard() {
    }

    /** 左 Alt 是否按住：重攻击由技能键处理，不能走服务端轻攻击分支 */
    private static boolean heavyModifierDown(Minecraft mc) {
        return mc.getWindow() != null
                && InputConstants.isKeyDown(mc.getWindow().getWindow(), HEAVY_MODIFIER);
    }

    public static boolean isCombatOnly() {
        return combatOnly;
    }

    /**
     * 原版攻击是否应当放行（不取消 InteractionKeyMappingTriggered）。
     *
     * <p>命中实体时放行，让服务端 {@code AttackEntityEvent} 决定“取消并播放动作”还是原版伤害；
     * 混合模式命中方块时放行挖掘；其余（纯战斗挖方块、空挥）由本模组的技能键接管。
     */
    public static boolean vanillaAttackAllowed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return true;
        }
        // 重攻击（Alt+左键）由技能包发送，必须拦截原版，避免服务端再走轻攻击/挖掘
        if (heavyModifierDown(mc)) {
            return false;
        }
        if (mc.hitResult instanceof EntityHitResult) {
            return true;
        }
        return !combatOnly && mc.hitResult instanceof BlockHitResult;
    }

    /** 由服务端同步下来的权威状态写入；同时给玩家一个提示 */
    public static void setCombatOnly(boolean value) {
        combatOnly = value;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    value ? "战斗模式：禁止挖掘" : "混合模式：瞄准可采掘方块时才挖掘"), true);
        }
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 命中实体：普通左键放行原版攻击，让服务端 AttackEntityEvent 决定替换/原版伤害。
        // 若这里取消，服务端永远收不到攻击包，武器动作和 attack 连段全部失效。
        // 但 Alt+左键是重攻击，必须取消原版、交给技能包（服务端不知道 Alt 状态）。
        boolean heavy = heavyModifierDown(mc);
        if (mc.hitResult instanceof EntityHitResult && !heavy) {
            return;
        }
        if (heavy || combatOnly || !canMine(mc)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /**
     * 混合模式：只要视线命中方块就允许原版挖掘——不判断能否破坏、也不看手持物品。
     *
     * <p>这样连基岩、以及模组里“只能硬挖”的特殊方块都会放行；视线没命中方块
     * （空气或生物）时才取消攻击，交给战斗动作。
     */
    private static boolean canMine(Minecraft mc) {
        return mc.hitResult instanceof BlockHitResult;
    }
}
