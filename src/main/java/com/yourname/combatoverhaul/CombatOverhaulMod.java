package com.yourname.combatoverhaul;

import com.mojang.logging.LogUtils;
import com.yourname.combatoverhaul.client.ClientSetup;
import com.yourname.combatoverhaul.combat.ActionRunner;
import com.yourname.combatoverhaul.combat.CombatOverhaulServerCommand;
import com.yourname.combatoverhaul.combat.ServerCombatEvents;
import com.yourname.combatoverhaul.net.CombatMessages;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Combat Overhaul —— damageoverhaul 的附属战斗编辑器模组（独立 jar）。
 *
 * <p>按键 O 打开编辑器主窗口。P1：实体模型扫描导出/游戏内编辑；
 * P2：3D 预览/关键帧动画/武器体素挤出；P3：判定框、伤害倍率、无敌帧与三种触发方式。
 */
@Mod(CombatOverhaulMod.MOD_ID)
public class CombatOverhaulMod {
    public static final String MOD_ID = "combatoverhaul";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CombatOverhaulMod() {
        // 网络通道：双端注册（C2S 技能触发 / S2C 动作播放同步）
        CombatMessages.register();

        // 服务端战斗运行时：动作计时判定、无敌帧、近战接管、生物 AI Goal
        MinecraftForge.EVENT_BUS.register(ActionRunner.class);
        MinecraftForge.EVENT_BUS.register(ServerCombatEvents.class);
        MinecraftForge.EVENT_BUS.register(com.yourname.combatoverhaul.graph.GraphEvents.class);
        MinecraftForge.EVENT_BUS.register(com.yourname.combatoverhaul.combat.ServerArmorTest.class);
        MinecraftForge.EVENT_BUS.addListener(CombatOverhaulServerCommand::onRegister);

        // 编辑器为纯客户端 UI：仅客户端注册按键与界面，服务端不加载任何 client 类
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientSetup::onRegisterKeyMappings);
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientSetup::onClientSetup);
            MinecraftForge.EVENT_BUS.register(com.yourname.combatoverhaul.client.anim.ClientAnimHandler.class);
            // 游戏内手持物品渲染（实体模型 JSON 的 held_items，随骨骼动画）
            MinecraftForge.EVENT_BUS.register(com.yourname.combatoverhaul.client.render.HeldItemRenderer.class);
        }
    }
}
