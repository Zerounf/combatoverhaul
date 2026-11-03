package com.yourname.combatoverhaul.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.yourname.combatoverhaul.client.ClientSetup;
import com.yourname.combatoverhaul.client.model.GeoImporter;
import com.yourname.combatoverhaul.store.AnimImporter;
import com.yourname.combatoverhaul.store.GeoExporter;
import com.yourname.combatoverhaul.store.HumanoidRig;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;

/**
 * 客户端指令 {@code /combatoverhaul}：编辑器入口与 Blockbench 动画导入。
 *
 * <pre>
 *   /combatoverhaul open            打开编辑器（别名 o）
 *   /combatoverhaul anim list       列出 config/combatoverhaul/anim/ 下可导入的动画文件
 *   /combatoverhaul anim import &lt;模型&gt; &lt;文件&gt; [verbatim]
 *       模型写法：minecraft:player，武器模型写 weapon:minecraft:diamond_sword
 *       verbatim 关闭 Y 轴镜面补偿（默认开启，见 {@link AnimImporter}）
 * </pre>
 *
 * <p>编辑器是纯客户端 UI，指令也注册在客户端（{@link RegisterClientCommandsEvent}，
 * 在 Forge 主事件总线上、仅逻辑客户端触发）：单人与多人均可使用，无需服务端安装本模组。
 *
 * <p>打开动作不直接调用 {@code setScreen}：玩家在聊天框回车时聊天界面尚未关闭，
 * 此时开界面会被覆盖。改为向 {@link ClientSetup} 投递“待打开”请求，由客户端 tick 在
 * {@code screen == null} 时真正打开（带超时）。
 */
public final class CombatOverhaulClientCommand {

    /** 指令根名 */
    public static final String ROOT = "combatoverhaul";

    private CombatOverhaulClientCommand() {
    }

    /** 在客户端初始化阶段调用一次，挂上指令注册监听 */
    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(CombatOverhaulClientCommand::onRegisterClientCommands);
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .executes(ctx -> {
                    feedback(ctx.getSource(), usage());
                    return 1;
                });

        // ---- open / o
        root.then(openLiteral("open"));
        root.then(openLiteral("o"));
        // 调试/快速回归：不经过实体列表直接开玩家模型
        root.then(Commands.literal("openplayer").executes(ctx -> {
            if (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) {
                feedback(ctx.getSource(), "需要先进入世界才能打开编辑器");
                return 0;
            }
            ClientSetup.requestOpenEditor("combatoverhaul", "humanoid", false);
            feedback(ctx.getSource(), "正在打开 12 段柔化玩家模型（combatoverhaul:humanoid）…");
            return 1;
        }));

        // ---- 本地动画调试：绕过服务端/网络，直接把动作挂到当前玩家
        root.then(Commands.literal("playlocal")
                .then(Commands.argument("model", StringArgumentType.string())
                        .then(Commands.argument("action", StringArgumentType.string())
                                .executes(ctx -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.player == null) {
                                        feedback(ctx.getSource(), "需要先进入世界");
                                        return 0;
                                    }
                                    String raw = StringArgumentType.getString(ctx, "model");
                                    String action = StringArgumentType.getString(ctx, "action");
                                    String[] p = raw.split(":", 2);
                                    String key = p.length == 2
                                            ? com.yourname.combatoverhaul.combat.ActionCache.keyFor(p[0], p[1], false)
                                            : raw;
                                    com.yourname.combatoverhaul.client.anim.ClientAnimHandler.playLocal(mc.player, key, action);
                                    feedback(ctx.getSource(), "本地播放 " + raw + " / " + action);
                                    return 1;
                                }))));

        // ---- humanoid：标准 HumanoidModel 查看器（动作池 + 原版盔甲）
        root.then(Commands.literal("humanoid").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                feedback(ctx.getSource(), "需要先进入世界");
                return 0;
            }
            mc.setScreen(new com.yourname.combatoverhaul.client.screen.HumanoidPreviewScreen());
            return 1;
        }));

        // ---- rig：打开当前玩家模型引用的点状 rig 编辑器
        root.then(Commands.literal("rig").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                feedback(ctx.getSource(), "需要先进入世界");
                return 0;
            }
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            var entry = com.yourname.combatoverhaul.combat.ActionCache.get(
                    com.yourname.combatoverhaul.combat.ActionCache.keyFor("minecraft", path, false));
            String rigId = entry != null && entry.model != null ? entry.model.rigId : "";
            if (rigId == null || rigId.isBlank()) {
                rigId = "combatoverhaul:humanoid";
            }
            mc.setScreen(new com.yourname.combatoverhaul.client.screen.RigEditorScreen(rigId));
            return 1;
        }));

        // ---- graph：打开当前玩家模型的 Scratch 事件图
        root.then(Commands.literal("graph").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                feedback(ctx.getSource(), "需要先进入世界");
                return 0;
            }
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            mc.setScreen(new com.yourname.combatoverhaul.client.screen.GraphEditorScreen("minecraft", path));
            return 1;
        }));

        // ---- defaultrig：给当前玩家模型补左右肘/膝关节并生成肘膝 demo
        root.then(Commands.literal("defaultrig").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                feedback(ctx.getSource(), "需要先进入世界");
                return 0;
            }
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            var entry = com.yourname.combatoverhaul.combat.ActionCache.get(
                    com.yourname.combatoverhaul.combat.ActionCache.keyFor("minecraft", path, false));
            if (entry == null || entry.model == null) {
                feedback(ctx.getSource(), "找不到 minecraft:" + path + "，先打开/扫描该模型");
                return 0;
            }
            var rig = com.yourname.combatoverhaul.client.model.DefaultLimbRig.apply(entry.model);
            if (!rig.ok) {
                feedback(ctx.getSource(), "默认四肢关节失败：" + rig.error);
                return 0;
            }
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .disableHtmlEscaping().create().toJson(entry.model.toJson());
            if (!com.yourname.combatoverhaul.store.ModelStore.write("minecraft", path, json)) {
                feedback(ctx.getSource(), "写回模型失败，见日志");
                return 0;
            }
            boolean actions = com.yourname.combatoverhaul.store.DemoActions.writeWithJoints(
                    "minecraft", path, entry.model);
            com.yourname.combatoverhaul.combat.ActionCache.invalidate("minecraft", path, false);
            feedback(ctx.getSource(), "默认四肢关节完成：切了 " + rig.splitLimbs
                    + " 条肢体，蒙皮顶点=" + rig.vertices + " 双骨=" + rig.twoInfluence
                    + "；demo_elbow/demo_knee/demo_torso 写入=" + actions
                    + "\n/combatoverhaul playlocal minecraft:" + path + " demo_elbow"
                    + "\n/combatoverhaul playlocal minecraft:" + path + " demo_torso");
            return 1;
        }));

        // ---- demoactions：给当前玩家模型生成内置测试动作
        root.then(Commands.literal("demoactions").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                feedback(ctx.getSource(), "需要先进入世界");
                return 0;
            }
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            if (!com.yourname.combatoverhaul.store.DemoActions.write("minecraft", path)) {
                feedback(ctx.getSource(), "写入 demo 动作失败，见日志");
                return 0;
            }
            feedback(ctx.getSource(), "已写入 demo 动作到 minecraft:" + path
                    + "\n/combatoverhaul playlocal minecraft:" + path + " demo_swing"
                    + "\n/combatoverhaul playlocal minecraft:" + path + " demo_walk"
                    + "\n/combatoverhaul playlocal minecraft:" + path + " demo_skin");
            return 1;
        }));

        // ---- player：玩家渲染诊断 + 模型缓存重载
        root.then(Commands.literal("player").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                feedback(ctx.getSource(), "需要先进入世界");
                return 0;
            }
            feedback(ctx.getSource(), com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerRenderStatus(mc.player));
            return 1;
        }));
        root.then(Commands.literal("playerstatus").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                feedback(ctx.getSource(), "需要先进入世界");
                return 0;
            }
            feedback(ctx.getSource(), com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerRenderStatus(mc.player));
            return 1;
        }));
        root.then(Commands.literal("reloadplayer").executes(ctx -> {
            com.yourname.combatoverhaul.combat.ActionCache.invalidate("minecraft", "player", false);
            com.yourname.combatoverhaul.combat.ActionCache.invalidate("minecraft", "player_slim", false);
            feedback(ctx.getSource(), "已重载 minecraft:player / player_slim 缓存；下一帧重新解析");
            return 1;
        }));
        // 实机验证辅助：为当前玩家模型生成 skinnedMesh 并写回 config
        root.then(Commands.literal("skinplayer").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                feedback(ctx.getSource(), "需要先进入世界");
                return 0;
            }
            String path = com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                    .playerModelPath(mc.player);
            com.yourname.combatoverhaul.combat.ActionCache.Entry entry =
                    com.yourname.combatoverhaul.combat.ActionCache.get(
                            com.yourname.combatoverhaul.combat.ActionCache.keyFor(
                                    "minecraft", path, false));
            if (entry == null || entry.model == null) {
                feedback(ctx.getSource(), "找不到 minecraft:" + path + "，先在编辑器扫描/导出该玩家模型");
                return 0;
            }
            com.yourname.combatoverhaul.client.skinned.SkinWeightGenerator.Result r =
                    com.yourname.combatoverhaul.client.skinned.SkinWeightGenerator.generate(entry.model);
            if (!r.ok) {
                feedback(ctx.getSource(), "自动蒙皮失败：" + r.error);
                return 0;
            }
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .disableHtmlEscaping().create().toJson(entry.model.toJson());
            if (!com.yourname.combatoverhaul.store.ModelStore.write("minecraft", path, json)) {
                feedback(ctx.getSource(), "写回 minecraft:" + path + " 失败，见日志");
                return 0;
            }
            com.yourname.combatoverhaul.combat.ActionCache.invalidate("minecraft", path, false);
            feedback(ctx.getSource(), "已生成并保存 minecraft:" + path
                    + " 蒙皮：顶点=" + r.vertices + " 双骨=" + r.twoInfluence
                    + "；下一帧重载");
            return 1;
        }));

        // ---- bedrock：新 quad 渲染开关（玩家）
        root.then(Commands.literal("bedrock")
                .executes(ctx -> {
                    feedback(ctx.getSource(), bedrockStatus());
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    com.yourname.combatoverhaul.client.anim.ClientAnimHandler.setBedrockRenderEnabled(true);
                    feedback(ctx.getSource(), "新 Bedrock quad 渲染：开（玩家；下一帧生效）");
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    com.yourname.combatoverhaul.client.anim.ClientAnimHandler.setBedrockRenderEnabled(false);
                    feedback(ctx.getSource(), "新 Bedrock quad 渲染：关（回到旧 ModelPart 渲染；下一帧生效）");
                    return 1;
                })));

        // ---- geo：Bedrock .geo.json 几何导入
        root.then(Commands.literal("geo")
                .executes(ctx -> {
                    feedback(ctx.getSource(), "用法：/combatoverhaul geo list | /combatoverhaul geo import <模型> <文件>");
                    return 1;
                })
                .then(Commands.literal("list").executes(ctx -> {
                    listGeoFiles(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("import")
                        .then(Commands.argument("model", StringArgumentType.string())
                                .then(Commands.argument("file", StringArgumentType.string())
                                        .suggests((ctx, b) ->
                                                SharedSuggestionProvider.suggest(GeoImporter.listFiles(), b))
                                        .executes(ctx -> doGeoImport(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "model"),
                                                StringArgumentType.getString(ctx, "file")))))));

        // ---- anim
        root.then(Commands.literal("anim")
                .executes(ctx -> {
                    feedback(ctx.getSource(), usage());
                    return 1;
                })
                .then(Commands.literal("list").executes(ctx -> {
                    listAnimFiles(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("import")
                        .then(Commands.argument("model", StringArgumentType.string())
                                .then(Commands.argument("file", StringArgumentType.string())
                                        .suggests((ctx, b) ->
                                                SharedSuggestionProvider.suggest(AnimImporter.listFiles(), b))
                                        .executes(ctx -> doImport(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "model"),
                                                StringArgumentType.getString(ctx, "file"), true))
                                        .then(Commands.literal("verbatim")
                                                .executes(ctx -> doImport(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "model"),
                                                        StringArgumentType.getString(ctx, "file"), false)))))));

        // ---- export：导出给 Blockbench 用的 geo.json
        root.then(Commands.literal("export")
                .executes(ctx -> doExport(ctx.getSource(), "all"))
                .then(Commands.argument("target", StringArgumentType.string())
                        .executes(ctx -> doExport(ctx.getSource(),
                                StringArgumentType.getString(ctx, "target")))));

        // ---- rig：生成通用人形骨架
        root.then(Commands.literal("rig").executes(ctx -> {
            boolean ok = HumanoidRig.write();
            feedback(ctx.getSource(), ok
                    ? "已生成通用人形骨架 " + HumanoidRig.MODID + ":" + HumanoidRig.PATH
                    + "\n导出给 Blockbench：/combatoverhaul export " + HumanoidRig.MODID + ":" + HumanoidRig.PATH
                    + "\n动画导入：/combatoverhaul anim import humanoid <文件>（自动授权所有金形生物）"
                    : "生成骨架失败，见日志");
            return ok ? 1 : 0;
        }));

        event.getDispatcher().register(root);
    }

    /** 一个“打开编辑器”的叶子节点（open / o 两条别名共用实现） */
    private static LiteralArgumentBuilder<CommandSourceStack> openLiteral(String name) {
        return Commands.literal(name)
                .executes(ctx -> openEditor(ctx.getSource()))
                .then(Commands.argument("model", StringArgumentType.string())
                        .executes(ctx -> openEditorModel(ctx.getSource(),
                                StringArgumentType.getString(ctx, "model"))));
    }

    private static int openEditorModel(CommandSourceStack source, String rawModel) {
        String raw = rawModel;
        boolean weapon = false;
        if (raw.startsWith("weapon:")) {
            weapon = true;
            raw = raw.substring("weapon:".length());
        }
        String ns = "minecraft";
        String path = raw;
        int i = raw.indexOf(':');
        if (i >= 0) {
            ns = raw.substring(0, i);
            path = raw.substring(i + 1);
        }
        if (ns.isEmpty() || path.isEmpty()) {
            feedback(source, "模型写法不对，应为 ns:path（武器写 weapon:ns:path）");
            return 0;
        }
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) {
            feedback(source, "需要先进入世界才能打开编辑器");
            return 0;
        }
        ClientSetup.requestOpenEditor(ns, path, weapon);
        feedback(source, "正在打开模型 " + ns + ":" + path + "…");
        return 1;
    }

    private static int openEditor(CommandSourceStack source) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            feedback(source, "需要先进入世界才能打开编辑器");
            return 0;
        }
        ClientSetup.requestOpenEditor();
        feedback(source, "正在打开战斗工坊编辑器…");
        return 1;
    }

    // ------------------------------------------------------------------ 几何导入

    private static void listGeoFiles(CommandSourceStack source) {
        List<String> files = GeoImporter.listFiles();
        if (files.isEmpty()) {
            feedback(source, "geo 目录为空：把 Blockbench 导出的 .geo.json 放到 " + GeoImporter.geoDir());
            return;
        }
        StringBuilder sb = new StringBuilder("可导入 geo 文件（" + files.size() + "）：\n");
        for (String f : files) {
            sb.append("  · ").append(f).append('\n');
        }
        sb.append("用法：/combatoverhaul geo import <模型> <文件名>（模型写 weapon:ns:path 表示武器）");
        feedback(source, sb.toString());
    }

    private static int doGeoImport(CommandSourceStack source, String rawModel, String fileName) {
        String raw = rawModel;
        boolean weapon = false;
        if (raw.startsWith("weapon:")) {
            weapon = true;
            raw = raw.substring("weapon:".length());
        }
        String ns = "minecraft";
        String path = raw;
        int i = raw.indexOf(':');
        if (i >= 0) {
            ns = raw.substring(0, i);
            path = raw.substring(i + 1);
        }
        if (ns.isEmpty() || path.isEmpty()) {
            feedback(source, "模型写法不对，应为 ns:path（武器写 weapon:ns:path）");
            return 0;
        }

        GeoImporter.Result r = GeoImporter.importFile(ns, path, weapon, fileName);
        if (!r.ok) {
            feedback(source, "geo 导入失败：" + r.error);
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已把 geo 导入到 ").append(ns).append(':').append(path)
                .append("（").append(r.bones).append(" 骨骼 / ").append(r.quads).append(" 面）\n");
        sb.append("原 actions / held_items / texture 已保留（若存在）。\n");
        for (String w : r.warnings) {
            sb.append("  ! ").append(w).append('\n');
        }
        sb.append("在编辑器里打开该模型即可预览；游戏内渲染走新 Bedrock quad 管线。");
        feedback(source, sb.toString());
        return 1;
    }

    // ------------------------------------------------------------------ 动画导入

    private static void listAnimFiles(CommandSourceStack source) {
        List<String> files = AnimImporter.listFiles();
        if (files.isEmpty()) {
            feedback(source, "动画目录为空：把 Blockbench 导出的 .animation.json 放到 " + AnimImporter.animDir());
            return;
        }
        StringBuilder sb = new StringBuilder("可导入动画文件（" + files.size() + "）：\n");
        for (String f : files) {
            sb.append("  · ").append(f).append('\n');
        }
        sb.append("用法：/combatoverhaul anim import <模型> <文件名>");
        feedback(source, sb.toString());
    }

    /**
     * @param rawModel ns:path，武器模型写 weapon:ns:path
     * @param flip     是否做 Y 轴镜面补偿
     */
    private static int doImport(CommandSourceStack source, String rawModel, String fileName, boolean flip) {
        String raw = rawModel;
        boolean weapon = false;
        if ("humanoid".equalsIgnoreCase(raw)) {
            raw = HumanoidRig.MODID + ":" + HumanoidRig.PATH;
        }
        if (raw.startsWith("weapon:")) {
            weapon = true;
            raw = raw.substring("weapon:".length());
        }
        String ns = "minecraft";
        String path = raw;
        int i = raw.indexOf(':');
        if (i >= 0) {
            ns = raw.substring(0, i);
            path = raw.substring(i + 1);
        }
        if (ns.isEmpty() || path.isEmpty()) {
            feedback(source, "模型写法不对，应为 ns:path（例如 minecraft:player）");
            return 0;
        }

        AnimImporter.Result r = AnimImporter.importFile(ns, path, weapon, fileName, flip);
        if (!r.ok) {
            feedback(source, "导入失败：" + r.error);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已导入到 ").append(ns).append(':').append(path)
                .append("（").append(r.imported.size()).append(" 个动作")
                .append(r.baked ? "，含非线性缓动已逐 tick 烘焙" : "，线性关键帧原样保留")
                .append("）\n");
        for (String s : r.imported) {
            sb.append("  · ").append(s).append('\n');
        }
        if (!r.warnings.isEmpty()) {
            sb.append("提示：\n");
            for (int k = 0; k < Math.min(6, r.warnings.size()); k++) {
                sb.append("  ! ").append(r.warnings.get(k)).append('\n');
            }
            if (r.warnings.size() > 6) {
                sb.append("  ! …还有 ").append(r.warnings.size() - 6).append(" 条，见日志\n");
                r.warnings.forEach(w -> com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.warn(
                        "[CombatOverhaul] 动画导入提示: {}", w));
            }
        }
        sb.append("在编辑器里打开该模型即可预览播放。");
        feedback(source, sb.toString());
        return 1;
    }

    // ------------------------------------------------------------------ 模型导出

    /** @param target all 或 ns:path */
    private static int doExport(CommandSourceStack source, String target) {
        GeoExporter.Result r;
        if ("all".equalsIgnoreCase(target)) {
            r = GeoExporter.exportAll(64, 64);
        } else {
            String ns = "minecraft";
            String path = target;
            int i = target.indexOf(':');
            if (i >= 0) {
                ns = target.substring(0, i);
                path = target.substring(i + 1);
            }
            if (ns.isEmpty() || path.isEmpty()) {
                feedback(source, "模型写法不对，应为 ns:path 或 all");
                return 0;
            }
            r = GeoExporter.exportOne(ns, path, 64, 64);
        }
        if (r.error != null) {
            feedback(source, "导出失败：" + r.error);
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已导出 ").append(r.exported).append(" 个 .geo.json");
        if (r.skipped > 0) {
            sb.append("，跳过 ").append(r.skipped);
        }
        if (r.failed > 0) {
            sb.append("，失败 ").append(r.failed);
        }
        sb.append("\n目录：").append(r.dir)
                .append("\nUV 按 64×64 写出；若该实体贴图不是 64×64，需按比例调整后再用")
                .append("\nBlockbench 导入时格式选 GeckoLib Animated Model");
        feedback(source, sb.toString());
        return 1;
    }

    private static String bedrockStatus() {
        boolean on = com.yourname.combatoverhaul.client.anim.ClientAnimHandler.isBedrockRenderEnabled();
        return "新 Bedrock quad 渲染（玩家）：" + (on ? "开" : "关")
                + "\n/combatoverhaul bedrock on|off —— 切换（下一帧生效；关=旧 ModelPart 渲染）";
    }

    private static String usage() {
        return "战斗工坊指令：\n"
                + "  /combatoverhaul open [模型] —— 打开编辑器（可直达 ns:path / weapon:ns:path）\n"
                + "  /combatoverhaul openplayer —— 直接打开玩家模型编辑器\n"
                + "  /combatoverhaul export [all|<ns:path>] —— 导出给 Blockbench 的 geo.json\n"
                + "  /combatoverhaul anim list —— 列出可导入动画\n"
                + "  /combatoverhaul anim import <模型> <文件> —— 导入 Blockbench 动画\n"
                + "  /combatoverhaul geo list —— 列出可导入 .geo.json\n"
                + "  /combatoverhaul geo import <模型> <文件> —— 导入 Bedrock 几何\n"
                + "  /combatoverhaul player —— 玩家模型/蒙皮/渲染状态\n"
                + "  /combatoverhaul reloadplayer —— 重载玩家模型缓存\n"
                + "  /combatoverhaul skinplayer —— 为当前玩家模型生成蒙皮并写回\n"
                + "  /combatoverhaul humanoid —— 标准 HumanoidModel 查看器（动作池 + 原版盔甲）\n"
                + "  /combatoverhaul rig —— 打开当前模型引用的点状 rig 编辑器\n"
                + "  /combatoverhaul graph —— 打开当前模型的 Scratch 事件图\n"
                + "  /combatoverhaul defaultrig —— 补左右肘/膝关节并生成肘膝 demo\n"
                + "  /combatoverhaul demoactions —— 写入 demo_swing/walk/skin/idle 测试动作\n"
                + "  /combatoverhaul bedrock [on|off] —— 新 Bedrock 渲染开关（玩家）";
    }

    /** 客户端指令的反馈走聊天栏（ClientCommandSourceStack.sendSuccess 内部即 player.sendSystemMessage） */
    private static void feedback(CommandSourceStack source, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Component msg = Component.literal(text);
        source.sendSuccess(() -> msg, false);
    }
}
