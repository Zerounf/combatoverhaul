package com.yourname.combatoverhaul.client;

import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.client.command.CombatOverhaulClientCommand;
import com.yourname.combatoverhaul.client.screen.EntityBrowserScreen;
import com.yourname.combatoverhaul.client.screen.ModelEditorScreen;
import com.yourname.combatoverhaul.combat.CombatConfig;
import com.yourname.combatoverhaul.net.CombatMessages;
import com.yourname.combatoverhaul.net.CombatMessages.TriggerSkillPacket;
import com.yourname.damageoverhaul.config.ConfigLoader;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口：注册客户端指令与按键，技能键发送 C2S 触发包。
 *
 * <p>编辑器有两种打开方式：
 * <ul>
 *   <li>指令 {@code /combatoverhaul open}（别名 {@code o}）——默认主入口，见
 *       {@link CombatOverhaulClientCommand}；</li>
 *   <li>热键——默认不绑定任何按键（{@link GLFW#GLFW_KEY_UNKNOWN}），且额外受
 *       combat_actions.json 的 {@code open_editor_hotkey} 开关控制（默认 false）。
 *       需要热键时：在控制设置里给“打开战斗编辑器”分配按键，再把开关改为 true。</li>
 * </ul>
 */
public final class ClientSetup {
    /** 编辑器热键：默认不绑定（GLFW_KEY_UNKNOWN），避免误触；主入口改为指令 */
    private static final KeyMapping OPEN_EDITOR = new KeyMapping(
            "key.combatoverhaul.open_editor", GLFW.GLFW_KEY_UNKNOWN, "key.categories.combatoverhaul");
    /** 攻击热键：右手轻=左键 / 左手轻=右键 / 右手重=左Alt+左键 / 左手重=默认空 */
    private static final KeyMapping SKILL_1 = new KeyMapping(
            "key.combatoverhaul.skill1", GLFW.GLFW_MOUSE_BUTTON_LEFT, "key.categories.combatoverhaul");
    private static final KeyMapping SKILL_2 = new KeyMapping(
            "key.combatoverhaul.skill2", GLFW.GLFW_MOUSE_BUTTON_RIGHT, "key.categories.combatoverhaul");
    private static final KeyMapping SKILL_3 = new KeyMapping(
            "key.combatoverhaul.skill3", GLFW.GLFW_KEY_UNKNOWN, "key.categories.combatoverhaul");
    private static final KeyMapping SKILL_4 = new KeyMapping(
            "key.combatoverhaul.skill4", GLFW.GLFW_KEY_UNKNOWN, "key.categories.combatoverhaul");

    /** 技能 1/2/3 的备用热键（默认空） */
    private static final KeyMapping SKILL_1_ALT = new KeyMapping(
            "key.combatoverhaul.skill1_alt", GLFW.GLFW_KEY_UNKNOWN, "key.categories.combatoverhaul");
    private static final KeyMapping SKILL_2_ALT = new KeyMapping(
            "key.combatoverhaul.skill2_alt", GLFW.GLFW_KEY_UNKNOWN, "key.categories.combatoverhaul");
    private static final KeyMapping SKILL_3_ALT = new KeyMapping(
            "key.combatoverhaul.skill3_alt", GLFW.GLFW_KEY_UNKNOWN, "key.categories.combatoverhaul");

    /** 重攻击修饰键：按住它再按右键轻 → 右手重 */
    private static final int HEAVY_MODIFIER = GLFW.GLFW_KEY_LEFT_ALT;

    /** 混合 / 纯战斗模式切换（默认 X） */
    private static final KeyMapping TOGGLE_MODE = new KeyMapping(
            "key.combatoverhaul.toggle_mode", GLFW.GLFW_KEY_X, "key.categories.combatoverhaul");

    /** 翻滚（默认 V）：不能继续用左 Shift——它和原版潜行共用物理键，每次蹲下都会触发翻滚 */
    private static final KeyMapping DODGE = new KeyMapping(
            "key.combatoverhaul.dodge", GLFW.GLFW_KEY_V, "key.categories.combatoverhaul");

    /** 待打开编辑器的剩余等待 tick（指令触发 → 等聊天界面关闭后再开）；<=0 表示没有请求 */
    private static int pendingOpenTicks = 0;
    /** 指令 /combatoverhaul open <模型>：直接打开指定模型；null 表示打开浏览器 */
    private static String pendingModid;
    private static String pendingPath;
    private static boolean pendingWeapon;
    /** 仅 -Dcombatoverhaul.uiTest=true 时使用：世界加载后自动开模型编辑器，便于截图回归 */
    private static boolean uiTestOpened;
    private static boolean animImportTested;
    private static boolean skinPlayerTested;
    private static boolean defaultRigTested;
    private static boolean graphTested;
    private static boolean rigTested;
    private static boolean hitboxTested;
    private static boolean armorTested;
    private static boolean humanoidTested;

    private ClientSetup() {
    }

    /**
     * 请求打开编辑器（由 /combatoverhaul 指令调用）。
     *
     * <p>不在调用点直接 setScreen：玩家在聊天栏回车时聊天界面通常还没关闭，
     * 直接开会被随后关闭的聊天界面覆盖，因此交给客户端 tick 在界面清空后打开。
     */
    public static void requestOpenEditor() {
        pendingModid = null;
        pendingPath = null;
        pendingWeapon = false;
        pendingOpenTicks = 60;
    }

    /** 直接打开某个模型（/combatoverhaul open <ns:path>），便于快速检查预览方向/皮肤。 */
    public static void requestOpenEditor(String modid, String path, boolean weapon) {
        pendingModid = modid;
        pendingPath = path;
        pendingWeapon = weapon;
        pendingOpenTicks = 60;
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_EDITOR);
        event.register(SKILL_1);
        event.register(SKILL_2);
        event.register(SKILL_3);
        event.register(SKILL_4);
        event.register(SKILL_1_ALT);
        event.register(SKILL_2_ALT);
        event.register(SKILL_3_ALT);
        event.register(TOGGLE_MODE);
        event.register(DODGE);
    }

    public static void onClientSetup(FMLClientSetupEvent event) {
        // 验证与主模组的编译期/运行期连接：读取伤害类型列表
        CombatOverhaulMod.LOGGER.info("[CombatOverhaul] 已连接主模组 damageoverhaul，当前伤害类型数量: {}",
                ConfigLoader.getDamageTypes().size());

        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(ClientSetup::onClientTick);
        // 客户端指令 /combatoverhaul open|o —— 编辑器的主入口
        CombatOverhaulClientCommand.register();
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        // 测试用：-Dcombatoverhaul.animImport=<file> 时自动导入到玩家模型
        String animImport = System.getProperty("combatoverhaul.animImport");
        if (!animImportTested && animImport != null && mc.level != null && mc.player != null) {
            animImportTested = true;
            var result = com.yourname.combatoverhaul.store.AnimImporter.importFile(
                    "minecraft", "player", false, animImport, true);
            CombatOverhaulMod.LOGGER.info(
                    "[CombatOverhaul][AnimTest] ok={} imported={} error={} warnings={}",
                    result.ok, result.imported, result.error, result.warnings);
        }

        // 实机 UI 测试钩子：-Dcombatoverhaul.humanoidTest=true 打开标准 HumanoidModel 查看器
        if (!humanoidTested && mc.level != null && mc.player != null && mc.screen == null
                && "true".equalsIgnoreCase(System.getProperty("combatoverhaul.humanoidTest"))) {
            humanoidTested = true;
            mc.setScreen(new com.yourname.combatoverhaul.client.screen.HumanoidPreviewScreen());
        }

        // 实机测试钩子：-Dcombatoverhaul.armorTest=true 客户端装备钻石胸甲，验证自定义盔甲蒙皮
        if (!armorTested && mc.level != null && mc.player != null
                && "true".equalsIgnoreCase(System.getProperty("combatoverhaul.armorTest"))) {
            armorTested = true;
            mc.player.getInventory().armor.set(2,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE));
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            CombatOverhaulMod.LOGGER.info("[CombatOverhaul][ArmorTest] equipped diamond chestplate + third person");
        }

        // 实机 UI 测试钩子：-Dcombatoverhaul.hitboxTest=true 打开模型编辑器判定框工具
        if (!hitboxTested && mc.level != null && mc.player != null && mc.screen == null
                && "true".equalsIgnoreCase(System.getProperty("combatoverhaul.hitboxTest"))) {
            hitboxTested = true;
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            var screen = new com.yourname.combatoverhaul.client.screen.ModelEditorScreen(
                    null, "minecraft", path, false);
            mc.setScreen(screen);
            screen.openHitboxToolForTest();
        }

        // 实机 UI 测试钩子：-Dcombatoverhaul.rigTest=true 自动打开 rig 点编辑器
        if (!rigTested && mc.level != null && mc.player != null && mc.screen == null
                && "true".equalsIgnoreCase(System.getProperty("combatoverhaul.rigTest"))) {
            rigTested = true;
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            var entry = com.yourname.combatoverhaul.combat.ActionCache.get(
                    com.yourname.combatoverhaul.combat.ActionCache.keyFor("minecraft", path, false));
            String rigId = entry != null && entry.model != null && entry.model.rigId != null
                    && !entry.model.rigId.isBlank() ? entry.model.rigId : "combatoverhaul:humanoid";
            mc.setScreen(new com.yourname.combatoverhaul.client.screen.RigEditorScreen(rigId));
        }

        // 实机 UI 测试钩子：-Dcombatoverhaul.graphTest=true 自动打开事件图
        if (!graphTested && mc.level != null && mc.player != null && mc.screen == null
                && "true".equalsIgnoreCase(System.getProperty("combatoverhaul.graphTest"))) {
            graphTested = true;
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            mc.setScreen(new com.yourname.combatoverhaul.client.screen.GraphEditorScreen("minecraft", path));
        }

        // 实机回归测试钩子：-Dcombatoverhaul.defaultRig=true 时自动补四肢/躯干关节并播放 torso demo
        if (!defaultRigTested && mc.level != null && mc.player != null
                && "true".equalsIgnoreCase(System.getProperty("combatoverhaul.defaultRig"))) {
            defaultRigTested = true;
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            var entry = com.yourname.combatoverhaul.combat.ActionCache.get(
                    com.yourname.combatoverhaul.combat.ActionCache.keyFor("minecraft", path, false));
            if (entry != null && entry.model != null) {
                var rig = com.yourname.combatoverhaul.client.model.DefaultLimbRig.apply(entry.model);
                if (rig.ok) {
                    String json = new com.google.gson.GsonBuilder().setPrettyPrinting()
                            .disableHtmlEscaping().create().toJson(entry.model.toJson());
                    boolean written = com.yourname.combatoverhaul.store.ModelStore
                            .write("minecraft", path, json);
                    boolean actions = com.yourname.combatoverhaul.store.DemoActions
                            .writeWithJoints("minecraft", path, entry.model);
                    com.yourname.combatoverhaul.combat.ActionCache.invalidate("minecraft", path, false);
                    CombatOverhaulMod.LOGGER.info(
                            "[CombatOverhaul][RigTest] path={} limbs={} vertices={} written={} actions={}",
                            path, rig.splitLimbs, rig.vertices, written, actions);
                    com.yourname.combatoverhaul.client.anim.ClientAnimHandler.playLocal(
                            mc.player,
                            com.yourname.combatoverhaul.combat.ActionCache.keyFor("minecraft", path, false),
                            "demo_torso");
                } else {
                    CombatOverhaulMod.LOGGER.warn("[CombatOverhaul][RigTest] failed: {}", rig.error);
                }
            }
        }

        // 实机回归测试钩子：-Dcombatoverhaul.skinPlayer=true 时自动给当前玩家模型生成蒙皮并写回
        if (!skinPlayerTested && mc.level != null && mc.player != null
                && "true".equalsIgnoreCase(System.getProperty("combatoverhaul.skinPlayer"))) {
            skinPlayerTested = true;
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            var entry = com.yourname.combatoverhaul.combat.ActionCache.get(
                    com.yourname.combatoverhaul.combat.ActionCache.keyFor("minecraft", path, false));
            if (entry != null && entry.model != null) {
                var skin = com.yourname.combatoverhaul.client.skinned.SkinWeightGenerator
                        .generate(entry.model);
                if (skin.ok) {
                    String json = new com.google.gson.GsonBuilder().setPrettyPrinting()
                            .disableHtmlEscaping().create().toJson(entry.model.toJson());
                    boolean written = com.yourname.combatoverhaul.store.ModelStore
                            .write("minecraft", path, json);
                    com.yourname.combatoverhaul.combat.ActionCache.invalidate("minecraft", path, false);
                    CombatOverhaulMod.LOGGER.info(
                            "[CombatOverhaul][SkinTest] path={} vertices={} two={} written={}",
                            path, skin.vertices, skin.twoInfluence, written);
                } else {
                    CombatOverhaulMod.LOGGER.warn("[CombatOverhaul][SkinTest] failed: {}", skin.error);
                }
            }
        }

        // UI 回归测试钩子：生产默认不触发
        if (!uiTestOpened && mc.level != null && mc.screen == null
                && "true".equalsIgnoreCase(System.getProperty("combatoverhaul.uiTest"))) {
            uiTestOpened = true;
            var win = mc.getWindow();
            CombatOverhaulMod.LOGGER.info(
                    "[CombatOverhaul][UI] win={}x{} screen={}x{} gui={}x{} scale={}",
                    win.getWidth(), win.getHeight(), win.getScreenWidth(), win.getScreenHeight(),
                    win.getGuiScaledWidth(), win.getGuiScaledHeight(), win.getGuiScale());
            mc.setScreen(new ModelEditorScreen(new EntityBrowserScreen(), "minecraft", "player", false));
        }

        // 指令请求的编辑器：等聊天界面关闭后再打开；离开世界则放弃
        if (pendingOpenTicks > 0) {
            if (mc.level == null) {
                pendingOpenTicks = 0;
                pendingModid = null;
                pendingPath = null;
            } else if (mc.screen == null) {
                pendingOpenTicks = 0;
                if (pendingModid != null && pendingPath != null) {
                    mc.setScreen(new ModelEditorScreen(new EntityBrowserScreen(),
                            pendingModid, pendingPath, pendingWeapon));
                    pendingModid = null;
                    pendingPath = null;
                } else {
                    mc.setScreen(new EntityBrowserScreen());
                }
            } else {
                pendingOpenTicks--;
            }
        }

        while (OPEN_EDITOR.consumeClick()) {
            // 热键默认关闭：需 combat_actions.json 的 open_editor_hotkey = true，且玩家自己在控制设置里绑了键
            if (CombatConfig.get().openEditorHotkey && mc.screen == null && mc.level != null) {
                mc.setScreen(new EntityBrowserScreen());
            }
        }
        // 左 Alt 按住时，左键从「轻」切到「重」
        boolean heavyMod = InputConstants.isKeyDown(mc.getWindow().getWindow(), HEAVY_MODIFIER);
        // 左键技能只在 MiningGuard 会拦截原版攻击时发送；否则“挖方块”会同时触发攻击动作。
        while (SKILL_1.consumeClick()) {
            if (!MiningGuard.vanillaAttackAllowed()) {
                sendSkill(heavyMod ? 2 : 0);
            }
        }
        // 右键技能只在视线为空（不挖方块、不交互）时发送；否则放方块/开箱子/吃东西会触发攻击。
        while (SKILL_2.consumeClick()) {
            if (rightClickCombat()) {
                sendSkill(1);
            }
        }
        while (SKILL_3.consumeClick()) {
            sendSkill(2);
        }
        while (SKILL_4.consumeClick()) {
            sendSkill(3);
        }
        while (SKILL_1_ALT.consumeClick()) {
            if (!MiningGuard.vanillaAttackAllowed()) {
                sendSkill(0);
            }
        }
        while (SKILL_2_ALT.consumeClick()) {
            if (rightClickCombat()) {
                sendSkill(1);
            }
        }
        while (SKILL_3_ALT.consumeClick()) {
            sendSkill(2);
        }
        while (TOGGLE_MODE.consumeClick()) {
            // 权威状态在服务端：发请求，等服务端回发 S2C 再更新本地
            com.yourname.combatoverhaul.net.CombatMessages.CHANNEL.sendToServer(
                    new com.yourname.combatoverhaul.net.CombatMessages.ToggleMiningModePacket());
        }
        while (DODGE.consumeClick()) {
            sendSkill(4);      // skills[4] = 翻滚（泛用，不绑定武器）
        }
    }

    /** 右键是否处于“空挥”战斗情境：不指向方块/实体、也不在使用食物/弓/盾等可蓄力物品 */
    private static boolean rightClickCombat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isUsingItem()) {
            return false;
        }
        if (!mc.player.getMainHandItem().isEmpty()
                && mc.player.getMainHandItem().getItem().getUseDuration(mc.player.getMainHandItem()) > 0) {
            return false;
        }
        return mc.hitResult == null
                || mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** 发送技能触发包；索引对应 combat_actions.json 里 skills 数组的顺序 */
    private static void sendSkill(int index) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null) {
            CombatMessages.CHANNEL.sendToServer(new TriggerSkillPacket(index));
        }
    }
}
