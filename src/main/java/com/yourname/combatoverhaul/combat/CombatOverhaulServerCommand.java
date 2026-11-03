package com.yourname.combatoverhaul.combat;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 服务端调试指令：直接把某个模型动作播到执行者身上。
 *
 * <pre>
 *   /combatoverhaul play &lt;modid:path&gt; &lt;action&gt;
 *   /combatoverhaul play minecraft:player imported.er_dodge_player
 * </pre>
 *
 * <p>只用于验证动作/位移；正式触发仍走技能、连段和武器。
 */
public final class CombatOverhaulServerCommand {

    private CombatOverhaulServerCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("combatoverhaul")
                .then(Commands.literal("play")
                        .then(Commands.argument("model", StringArgumentType.string())
                                .then(Commands.argument("action", StringArgumentType.string())
                                        .executes(ctx -> play(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "model"),
                                                StringArgumentType.getString(ctx, "action")))))));
    }

    private static int play(CommandSourceStack source, String model, String action) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("需要由玩家执行"));
            return 0;
        }
        String key;
        String[] p = model.split(":", 2);
        if (p.length == 2) {
            key = ActionCache.keyFor(p[0], p[1], false);
        } else {
            key = model; // 允许直接传 E|modid|path
        }
        boolean ok = ActionRunner.start(player, key, action, 0);
        ActionCache.Entry entry = ActionCache.get(key);
        com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                "[Play] player={} key={} action={} entryActions={} ok={}",
                player.getName().getString(), key, action,
                entry == null ? -1 : entry.actions.size(), ok);
        source.sendSuccess(() -> Component.literal(
                ok ? "播放 " + model + " / " + action
                        : "动作不存在/冷却/模型无动作: " + model + " / " + action), false);
        return ok ? 1 : 0;
    }
}
