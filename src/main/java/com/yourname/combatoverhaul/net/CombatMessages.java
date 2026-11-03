package com.yourname.combatoverhaul.net;

import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.combat.ActionRunner;
import com.yourname.combatoverhaul.combat.ComboStore;
import com.yourname.combatoverhaul.combat.CombatConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 战斗网络通道（combatoverhaul:main）：
 * <ul>
 *   <li>C2S TriggerSkillPacket：客户端按键技能 → 服务端启动动作；</li>
 *   <li>S2C PlayActionPacket：服务端广播动作播放 → 客户端在实体模型上同步动画。</li>
 * </ul>
 */
public final class CombatMessages {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CombatOverhaulMod.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static int id = 0;

    private CombatMessages() {
    }

    public static void register() {
        CHANNEL.registerMessage(id++, TriggerSkillPacket.class,
                TriggerSkillPacket::encode, TriggerSkillPacket::decode, TriggerSkillPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PlayActionPacket.class,
                PlayActionPacket::encode, PlayActionPacket::decode, PlayActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ToggleMiningModePacket.class,
                ToggleMiningModePacket::encode, ToggleMiningModePacket::decode,
                ToggleMiningModePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, GraphFxPacket.class,
                GraphFxPacket::encode, GraphFxPacket::decode, GraphFxPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MiningModePacket.class,
                MiningModePacket::encode, MiningModePacket::decode, MiningModePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 广播动作播放：实体被跟踪的所有玩家 + 自己 */
    public static void sendPlayAction(LivingEntity actor, String modelKey, String actionName) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> actor),
                new PlayActionPacket(actor.getId(), modelKey, actionName));
    }

    // ------------------------------------------------------------------ C2S

    public record TriggerSkillPacket(int skillIndex) {
        public static void encode(TriggerSkillPacket p, FriendlyByteBuf buf) {
            buf.writeVarInt(p.skillIndex());
        }

        public static TriggerSkillPacket decode(FriendlyByteBuf buf) {
            return new TriggerSkillPacket(Math.max(0, buf.readVarInt()));
        }

        public static void handle(TriggerSkillPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !player.isAlive()) {
                    return;
                }
                CombatConfig cfg = CombatConfig.get();
                if (p.skillIndex() < 0 || p.skillIndex() >= cfg.skills.size()) {
                    return;
                }
                CombatConfig.SkillEntry skill = cfg.skills.get(p.skillIndex());
                // SkillEntry.model 允许两种写法：模型 id（minecraft:zombie / weapon:minecraft:sword）
                // 或已经编码好的 ActionCache key（E|ns|path / W|ns|path）。旧实现把非空值直接当 key，
                // 写成 minecraft:zombie 时 parseKey 失败 → 技能静默不触发。
                String modelKey = modelKeyOf(skill.model);
                if (modelKey == null) {
                    modelKey = modelKeyOf(cfg.playerModel);
                    if (modelKey == null) {
                        return;
                    }
                    if (skill.model != null && !skill.model.isBlank()) {
                        CombatOverhaulMod.LOGGER.warn(
                                "[CombatOverhaul] 技能 {} 的 model 写法无效：{}，回退到 player_model",
                                skill.action, skill.model);
                    }
                }
                // 连段：播放中动作在取消窗口内按技能键 → 取消并接续下一段（input=skill）
                ActionRunner cur = ActionRunner.current(player.getId());
                if (cur != null) {
                    String next = ComboStore.nextAction(cur.modelKey(), cur.actionName(), "skill", cur.currentTick());
                    if (next != null && ActionRunner.start(player, cur.modelKey(), next, skill.cooldownTicks)) {
                        return;
                    }
                }
                ActionRunner.start(player, modelKey, skill.action, skill.cooldownTicks);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ S2C

    public record PlayActionPacket(int entityId, String modelKey, String actionName) {
        public static void encode(PlayActionPacket p, FriendlyByteBuf buf) {
            buf.writeVarInt(p.entityId());
            // 动作名来自外部动画文件/配置，长度没有 256 的保证；过短会在 writeUtf 抛异常并断开连接。
            buf.writeUtf(p.modelKey(), 32767);
            buf.writeUtf(p.actionName(), 32767);
        }

        public static PlayActionPacket decode(FriendlyByteBuf buf) {
            return new PlayActionPacket(buf.readVarInt(), buf.readUtf(32767), buf.readUtf(32767));
        }

        public static void handle(PlayActionPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.yourname.combatoverhaul.client.anim.ClientAnimHandler
                            .play(p.entityId(), p.modelKey(), p.actionName())));
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ 事件图客户端表现

    /** S2C：事件图产生的纯客户端表现（粒子），战斗结算仍在服务端。 */
    public record GraphFxPacket(int entityId, String effect, int count, float speed) {
        public static void encode(GraphFxPacket p, FriendlyByteBuf buf) {
            buf.writeVarInt(p.entityId());
            buf.writeUtf(p.effect(), 32767);
            buf.writeVarInt(Math.max(1, Math.min(200, p.count())));
            buf.writeFloat(p.speed());
        }

        public static GraphFxPacket decode(FriendlyByteBuf buf) {
            return new GraphFxPacket(buf.readVarInt(), buf.readUtf(32767),
                    buf.readVarInt(), buf.readFloat());
        }

        public static void handle(GraphFxPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> {
                        var mc = net.minecraft.client.Minecraft.getInstance();
                        if (mc.level == null) {
                            return;
                        }
                        var e = mc.level.getEntity(p.entityId());
                        if (e == null) {
                            return;
                        }
                        try {
                            var type = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE
                                    .get(new ResourceLocation(p.effect()));
                            if (!(type instanceof net.minecraft.core.particles.ParticleOptions opt)) {
                                return;
                            }
                            var rnd = e.level().random;
                            for (int i = 0; i < p.count(); i++) {
                                double dx = (rnd.nextDouble() - 0.5) * 0.8;
                                double dy = rnd.nextDouble() * 1.2;
                                double dz = (rnd.nextDouble() - 0.5) * 0.8;
                                e.level().addParticle(opt,
                                        e.getX() + dx, e.getY() + 0.8 + dy, e.getZ() + dz,
                                        dx * p.speed(), dy * p.speed(), dz * p.speed());
                            }
                        } catch (RuntimeException ignored) {
                        }
                    }));
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendGraphFx(LivingEntity actor, String effect, int count, float speed) {
        if (actor == null || actor.level().isClientSide() || effect == null || effect.isBlank()) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> actor),
                new GraphFxPacket(actor.getId(), effect, count, speed));
    }

    // ------------------------------------------------------------------ 挖掘模式同步

    /** 玩家 NBT 键：是否处于纯战斗模式（服务端权威，跨重登保留） */
    public static final String NBT_COMBAT_ONLY = "combatoverhaul.combat_only";

    /** C2S：请求切换挖掘模式；服务端翻转权威值并回发 */
    public record ToggleMiningModePacket() {
        public static void encode(ToggleMiningModePacket p, FriendlyByteBuf buf) {
        }

        public static ToggleMiningModePacket decode(FriendlyByteBuf buf) {
            return new ToggleMiningModePacket();
        }

        public static void handle(ToggleMiningModePacket p, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer sp = c.getSender();
                if (sp == null) {
                    return;
                }
                boolean now = !sp.getPersistentData().getBoolean(NBT_COMBAT_ONLY);
                sp.getPersistentData().putBoolean(NBT_COMBAT_ONLY, now);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new MiningModePacket(now));
            });
            c.setPacketHandled(true);
        }
    }

    /** S2C：同步权威挖掘模式 */
    public record MiningModePacket(boolean combatOnly) {
        public static void encode(MiningModePacket p, FriendlyByteBuf buf) {
            buf.writeBoolean(p.combatOnly());
        }

        public static MiningModePacket decode(FriendlyByteBuf buf) {
            return new MiningModePacket(buf.readBoolean());
        }

        public static void handle(MiningModePacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.yourname.combatoverhaul.client.MiningGuard
                            .setCombatOnly(p.combatOnly())));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 登录时把玩家的权威模式推给客户端 */
    public static void syncMiningMode(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MiningModePacket(player.getPersistentData().getBoolean(NBT_COMBAT_ONLY)));
    }

    /**
     * 把配置里的模型写法规范成 ActionCache key：
     * <ul>
     *   <li>{@code E|ns|path} / {@code W|ns|path} 原样返回；</li>
     *   <li>{@code weapon:ns:path} → {@code W|ns|path}；</li>
     *   <li>{@code ns:path} → {@code E|ns|path}；</li>
     *   <li>空/非法返回 null。</li>
     * </ul>
     */
    private static String modelKeyOf(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("E|") || s.startsWith("W|")) {
            return s;
        }
        boolean weapon = false;
        if (s.startsWith("weapon:")) {
            weapon = true;
            s = s.substring("weapon:".length());
        }
        int i = s.indexOf(':');
        if (i <= 0 || i >= s.length() - 1) {
            return null;
        }
        return (weapon ? "W|" : "E|") + s.substring(0, i) + "|" + s.substring(i + 1);
    }
}
