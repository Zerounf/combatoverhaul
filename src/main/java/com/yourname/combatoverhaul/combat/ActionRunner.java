package com.yourname.combatoverhaul.combat;

import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.net.CombatMessages;
import com.yourname.combatoverhaul.store.ActionStore;
import com.yourname.damageoverhaul.config.ConfigLoader;
import com.yourname.damageoverhaul.registry.ModComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作运行时（服务端）：计时、判定框 AABB 计算（含前后 tick 扫掠并集）、
 * 命中结算（武器面板 × 各类型倍率 → 主模组 DamageHandler 分布注入钩子 → 走主模组完整伤害链）、
 * 无敌帧拦截、冷却管理。
 *
 * <p>倍率注入原理：调用 {@code target.hurt()} 前
 * {@code DamageHandler.beginDistributionOverride(基础分布×倍率)}，结算后 end。
 * 主模组在解析分布时以最高优先级读取钩子（空手/持械均生效），
 * 护甲抗性/饰品/补正/冲击眩晕等主模组全链路结算原样生效。
 */
public final class ActionRunner {

    /** 当前线程是否处于本模组判定框伤害调用中（无敌帧 blockCustomHitboxes 判定用） */
    public static final ThreadLocal<Boolean> CUSTOM_HITBOX = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 模型中：实体 id → 运行中动作 */
    private static final Map<Integer, ActionRunner> ACTIVE = new ConcurrentHashMap<>();
    /** 冷却：实体 UUID → (动作名 → 到期游戏时间) */
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new ConcurrentHashMap<>();
    /** 积木运行时开关：实体 id -> 被禁用的判定框名 */
    private static final Map<Integer, Set<String>> DISABLED_HITBOXES = new ConcurrentHashMap<>();

    private final LivingEntity actor;
    private final ActionStore.Action action;
    private final ParsedModel entityModel;
    private final ParsedModel weaponModel;
    private final boolean weaponAction;
    private final String modelKey;
    /** 动作开始时的主手物品快照：动作播放期间切武器不应改变伤害分布 */
    private final ItemStack attackWeapon;
    /**
     * 当前动作 tick。初始 -1，服务端 tick 第一次 ++ 后变 0，
     * 这样 hitboxes/iframes 的 0 号 tick 不会被跳过（旧实现第一帧就是 1）。
     */
    private int tick = -1;
    private final Set<Integer> hitIds = new HashSet<>();
    /** 每个判定框本次动作已命中数量（maxTargets 是“单次动作”上限，不是每 tick 上限） */
    private final Map<String, Integer> hitboxHits = new HashMap<>();
    private final Map<String, AABB> prevBoxes = new HashMap<>();
    /** 动作开始时实体位置（锚点位移的基准点） */
    private final net.minecraft.world.phys.Vec3 startPos;
    /** 上 tick 的 root 模型位移；null = 本动作还没建立基准 */
    private Vec3 lastRootPos;

    private ActionRunner(LivingEntity actor, ActionStore.Action action, ActionCache.Entry entry,
                         boolean weaponAction, String modelKey) {
        this.actor = actor;
        this.action = action;
        this.weaponAction = weaponAction;
        this.modelKey = modelKey;
        ParsedModel em = null;
        ParsedModel wm = null;
        if (entry != null && entry.model != null) {
            if (weaponAction) {
                wm = entry.model;
                em = entityModelFor(actor);
            } else {
                em = entry.model;
            }
        }
        this.entityModel = em;
        this.weaponModel = wm;
        this.startPos = actor.position();
        this.attackWeapon = actor.getMainHandItem().copy();
    }

    private static ParsedModel entityModelFor(LivingEntity actor) {
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType());
        if (typeId == null) {
            return null;
        }
        ActionCache.Entry e = ActionCache.get(ActionCache.keyFor(typeId.getNamespace(), typeId.getPath(), false));
        return e != null ? e.model : null;
    }

    // ------------------------------------------------------------------ 启动/查询

    /**
     * 启动动作（服务端）。冷却未到/无该动作返回 false；成功会替换同实体上一动作并同步播放包。
     *
     * @param modelKey         动作所在模型 key（实体动作或武器动作均可，按前缀自动识别）
     * @param actionName       动作名
     * @param cooldownOverride 额外冷却下限（tick，取与动作自身冷却的较大值）
     */
    public static boolean start(LivingEntity actor, String modelKey, String actionName, int cooldownOverride) {
        if (actor == null || actor.level().isClientSide() || !actor.isAlive() || modelKey == null) {
            return false;
        }
        ActionCache.Entry entry = ActionCache.get(modelKey);
        if (entry == null || entry.actions.isEmpty()) {
            return false;
        }
        ActionStore.Action action = null;
        int index = -1;
        for (int i = 0; i < entry.actions.size(); i++) {
            if (entry.actions.get(i).name.equals(actionName)) {
                action = entry.actions.get(i);
                index = i;
                break;
            }
        }
        if (action == null) {
            return false;
        }
        UUID uuid = actor.getUUID();
        Map<String, Long> cds = COOLDOWNS.computeIfAbsent(uuid, k -> new HashMap<>());
        long now = actor.level().getGameTime();
        // 冷却键含模型 key：不同配置（不同武器/模型）的同名动作冷却互不影响
        String cdKey = modelKey + "#" + action.name;
        Long until = cds.get(cdKey);
        if (until != null && now < until) {
            return false;
        }
        int cooldown = Math.max(action.cooldownTicks, cooldownOverride);
        if (cooldown > 0) {
            cds.put(cdKey, now + cooldown);
        }

        boolean weaponAction = modelKey.startsWith("W|");
        ActionRunner runner = new ActionRunner(actor, action, entry, weaponAction, modelKey);
        ACTIVE.put(actor.getId(), runner);
        CombatMessages.sendPlayAction(actor, modelKey, action.name);
        return true;
    }

    /** 积木控制判定框启停；用动作里的 hitbox name 匹配。 */
    public static void setHitboxEnabled(LivingEntity actor, String hitbox, boolean enabled) {
        if (actor == null || hitbox == null || hitbox.isBlank()) {
            return;
        }
        Set<String> set = DISABLED_HITBOXES.computeIfAbsent(actor.getId(), k -> new HashSet<>());
        if (enabled) {
            set.remove(hitbox);
        } else {
            set.add(hitbox);
        }
    }

    public static boolean isHitboxEnabled(int entityId, String hitbox) {
        Set<String> set = DISABLED_HITBOXES.get(entityId);
        return set == null || !set.contains(hitbox);
    }

    /** 实体是否有动作正在播放 */
    public static boolean isPlaying(int entityId) {
        return ACTIVE.containsKey(entityId);
    }

    /** 当前播放中的动作运行时（无则 null）——连段取消接续查询用 */
    public static ActionRunner current(int entityId) {
        return ACTIVE.get(entityId);
    }

    /** 当前动作名（连段查询用） */
    public String actionName() {
        return action.name;
    }

    /** 当前动作所在的模型 key（连段查询用） */
    public String modelKey() {
        return modelKey;
    }

    /** 当前播放 tick（连段取消窗口判定用） */
    public float currentTick() {
        return tick;
    }

    /** 指定实体的某动作是否在冷却中（作用域 = modelKey + 动作名） */
    public static boolean isOnCooldown(LivingEntity actor, String modelKey, String actionName) {
        Map<String, Long> cds = COOLDOWNS.get(actor.getUUID());
        if (cds == null) {
            return false;
        }
        Long until = cds.get(modelKey + "#" + actionName);
        return until != null && actor.level().getGameTime() < until;
    }

    /** 实体当前是否处于某动作的无敌帧窗口 */
    public static boolean inIframes(LivingEntity entity) {
        ActionRunner r = ACTIVE.get(entity.getId());
        if (r == null || r.action.iframes == null) {
            return false;
        }
        ActionStore.IframesDef f = r.action.iframes;
        return r.tick >= f.startTick && r.tick <= f.endTick;
    }

    // ------------------------------------------------------------------ 主循环

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, ActionRunner>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            ActionRunner r = it.next().getValue();
            r.tick++;
            float length = Math.max(1, r.action.lengthTicks);
            if (!r.actor.isAlive() || r.actor.isRemoved() || r.tick > length) {
                if (r.tick > length && r.actor.isAlive()) {
                    com.yourname.combatoverhaul.graph.GraphRuntime.signal(
                            r.actor, "on_animation_end", r.action.name);
                }
                it.remove();
                continue;
            }
            try {
                r.runHitboxes();
                r.applyAnchorMotion();
                r.applyRootMotion();
            } catch (Exception ex) {
                CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 动作 {} 判定异常，终止播放", r.action.name, ex);
                it.remove();
            }
        }
    }

    private void runHitboxes() {
        if (action.hitboxes.isEmpty() || !(actor.level() instanceof ServerLevel level)) {
            return;
        }
        // 采样动画姿态（武器动作需同时采样武器模型）
        HitboxMath.sampleAnim(entityModel, List.of(), -1, 0); // 无实体动作时保持基础姿态
        if (weaponAction) {
            HitboxMath.sampleAnim(weaponModel, List.of(action), 0, tick);
        } else {
            HitboxMath.sampleAnim(entityModel, List.of(action), 0, tick);
        }

        for (ActionStore.HitboxDef hb : action.hitboxes) {
            float s = Math.min(hb.startTick, hb.endTick);
            float e = Math.max(hb.startTick, hb.endTick);
            if (tick < s || tick > e || !isHitboxEnabled(actor.getId(), hb.name)) {
                prevBoxes.remove(hb.name);
                continue;
            }
            AABB box = HitboxMath.computeBox(actor, entityModel, weaponModel, weaponAction, hb, trackPitchRad());
            if (box == null) {
                continue;
            }
            AABB prev = prevBoxes.put(hb.name, box);
            AABB sweep = prev != null ? box.minmax(prev) : box;
            List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, sweep, t ->
                    t != actor && t.isAlive() && !t.isSpectator() && !hitIds.contains(t.getId()));
            if (targets.isEmpty()) {
                continue;
            }
            targets.sort((a, b) -> Double.compare(a.distanceToSqr(actor), b.distanceToSqr(actor)));
            int already = hitboxHits.getOrDefault(hb.name, 0);
            if (hb.maxTargets > 0 && already >= hb.maxTargets) {
                continue;
            }
            int limit = hb.maxTargets > 0 ? hb.maxTargets - already : targets.size();
            int hit = 0;
            for (LivingEntity target : targets) {
                if (hit >= limit) {
                    break;
                }
                if (damage(target, hb)) {
                    hitIds.add(target.getId());
                    hitboxHits.merge(hb.name, 1, Integer::sum);
                    hit++;
                }
            }
        }
    }

    /** 追踪俯仰偏移（弧度）：动作带追踪标签时按当前视线俯仰角钳制 ±maxPitchOffset（上抬=负，与原版 xRot 同向）；否则 0 */
    private float trackPitchRad() {
        if (!action.trackPitch) {
            return 0f;
        }
        float pitch = Mth.clamp(actor.getXRot(), -action.maxPitchOffset, action.maxPitchOffset);
        return (float) Math.toRadians(pitch);
    }

    // ------------------------------------------------------------------ 锚点（不动点）位移驱动
    /**
     * 锚点位移：播放中每 tick 计算 锚点目标位置-锚骨骼世界位置 的模型空间偏移，
     * 换算为世界方向后把实体从 {@link #startPos} 基准推到位（走原版 move，保留碰撞）。
     * 模型空间→世界空间：渲染管线 scale(-1,-1,1) + YP(180°-yBodyRot)，模型 -z 为实体朝向。
     */
    /**
     * 把动作里的 root/body 局部位移应用到实体。
     * 动作单位是模型像素（16/格），模型空间→世界方向与 anchor 相同。
     */
    private void applyRootMotion() {
        if (weaponAction || entityModel == null) {
            return;
        }
        HitboxMath.sampleAnim(entityModel, List.of(action), 0, tick);
        ParsedBone root = entityModel.findBone("root");
        if (root == null) {
            root = entityModel.findBone("body");
        }
        if (root == null) {
            return;
        }
        if (Math.abs(root.animPosX) + Math.abs(root.animPosY) + Math.abs(root.animPosZ) < 1.0E-4) {
            return;
        }
        Vec3 local = new Vec3(-root.animPosX / 16.0, -root.animPosY / 16.0, root.animPosZ / 16.0)
                .yRot((float) Math.toRadians(180.0f - actor.yBodyRot));
        if (lastRootPos == null) {
            lastRootPos = local;
            return;
        }
        Vec3 delta = local.subtract(lastRootPos);
        lastRootPos = local;
        if (delta.lengthSqr() > 1.0E-7 && delta.lengthSqr() < 4.0) {
            actor.move(MoverType.SELF, delta);
        }
    }

    private void applyAnchorMotion() {
        if (weaponAction || entityModel == null) {
            return;
        }
        boolean hasAnchor = false;
        for (ActionStore.Keyframe kf : action.keyframes) {
            if (kf.anchorBone != null && kf.anchorPos != null) {
                hasAnchor = true;
                break;
            }
        }
        if (!hasAnchor) {
            return;
        }
        HitboxMath.sampleAnim(entityModel, List.of(action), 0, tick);
        float[] info = ActionStore.anchorInfo(List.of(action), 0, tick, entityModel);
        if (info == null) {
            return;
        }
        Vec3 modelOff = new Vec3(-info[0] / 16.0, -info[1] / 16.0, info[2] / 16.0)
                .yRot((float) Math.toRadians(180.0f - actor.yBodyRot));
        Vec3 targetPos = startPos.add(modelOff);
        Vec3 delta = targetPos.subtract(actor.position());
        if (delta.lengthSqr() > 1.0E-7 && !Double.isNaN(delta.x + delta.y + delta.z)) {
            actor.move(MoverType.SELF, delta);
        }
    }

    // ------------------------------------------------------------------ 伤害结算

    /** 对单个目标结算；返回是否实际造成伤害（hurt 成功） */
    private boolean damage(LivingEntity target, ActionStore.HitboxDef hb) {
        // 倍率 <=0 = 该判定框显式禁用伤害：跳过结算（含击退），不算命中
        if (hb.damageScale <= 0) {
            return false;
        }
        float baseAmount = attackDamage(actor);
        float amount = baseAmount * hb.damageScale;
        // 攻击者攻击力为 0 时的保底（倍率 >0 但乘出 0），与倍率 0 的禁用语义区分
        if (amount <= 0) {
            amount = 1;
        }
        DamageSource source = actor instanceof Player p
                ? p.damageSources().playerAttack(p)
                : actor.damageSources().mobAttack(actor);

        Map<String, Float> base = resolveBaseDistribution(actor, attackWeapon);
        Map<String, Float> multiplied = multiplyDistribution(base, hb.multipliers);

        // 判定框倍率注入：主模组 DamageHandler 钩子（空手/持械通用，替代旧「临时写武器 NBT」方案）。
        // hook 分布以最高优先级参与主模组完整结算链（护甲抗性/饰品/补正/冲击眩晕等原样生效）。
        CUSTOM_HITBOX.set(Boolean.TRUE);
        com.yourname.damageoverhaul.event.DamageHandler.beginDistributionOverride(multiplied);
        try {
            if (target.hurt(source, amount)) {
                applyExtraKnockback(target, hb);
                return true;
            }
            return false;
        } finally {
            com.yourname.damageoverhaul.event.DamageHandler.endDistributionOverride();
            CUSTOM_HITBOX.set(Boolean.FALSE);
        }
    }

    private void applyExtraKnockback(LivingEntity target, ActionStore.HitboxDef hb) {
        if (hb.extraKnockback > 0) {
            target.knockback(hb.extraKnockback,
                    actor.getX() - target.getX(), actor.getZ() - target.getZ());
        }
    }

    private static float attackDamage(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        return attr != null ? (float) attr.getValue() : 1.0f;
    }

    /**
     * 解析基础伤害分布（镜像主模组 DamageHandler 近战顺序）：
     * 武器 NBT 组件 → weapon_distributions.json → 种族空手分布 → 空手分布 → 默认钝击 100%。
     */
    private static Map<String, Float> resolveBaseDistribution(LivingEntity actor, ItemStack weapon) {
        Map<String, Float> dist = null;
        if (!weapon.isEmpty()) {
            dist = ModComponents.getDamageDistribution(weapon);
            if (dist == null || dist.isEmpty()) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(weapon.getItem());
                ConfigLoader.WeaponDistribution wd = itemId != null
                        ? ConfigLoader.getWeaponDistribution(itemId) : null;
                dist = wd != null ? wd.damageDistribution : null;
            }
        }
        if (dist == null || dist.isEmpty()) {
            if (actor instanceof Player p) {
                Map<String, Float> raceUnarmed = com.yourname.damageoverhaul.config.RaceConfigLoader
                        .getPlayerUnarmedDistribution(p);
                if (raceUnarmed != null && !raceUnarmed.isEmpty()) {
                    dist = raceUnarmed;
                }
            }
        }
        if (dist == null || dist.isEmpty()) {
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType());
            ConfigLoader.WeaponDistribution ud = typeId != null
                    ? ConfigLoader.getUnarmedDistribution(typeId) : null;
            dist = ud != null ? ud.damageDistribution : null;
        }
        if (dist == null || dist.isEmpty()) {
            dist = new LinkedHashMap<>();
            dist.put("blunt", 1.0f);
        }
        return dist;
    }

    /** 分布 × 各类型倍率（动作未配置的类型保持 1.0） */
    private static Map<String, Float> multiplyDistribution(Map<String, Float> base, Map<String, Float> multipliers) {
        if (multipliers.isEmpty()) {
            return base;
        }
        Map<String, Float> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Float> e : out.entrySet()) {
            Float m = multipliers.get(e.getKey());
            if (m != null) {
                e.setValue(e.getValue() * m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 无敌帧拦截 + 清理

    /** 无敌帧：动作窗口内取消伤害（fullImmunity=全部；blockCustomHitboxes=仅本模组判定框） */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || !inIframes(target)) {
            return;
        }
        ActionStore.IframesDef f = ACTIVE.get(target.getId()).action.iframes;
        boolean block = f.fullImmunity || (f.blockCustomHitboxes && CUSTOM_HITBOX.get());
        if (block && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        ACTIVE.remove(event.getEntity().getId());
        // 玩家跨维度也走此事件：保留玩家冷却，仅在退出登录时清；生物一律清，防长期泄漏
        if (!(event.getEntity() instanceof Player)) {
            COOLDOWNS.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        ACTIVE.remove(event.getEntity().getId());
        if (!(event.getEntity() instanceof Player)) {
            COOLDOWNS.remove(event.getEntity().getUUID());
        }
    }

    /** 玩家退出登录：清理该玩家的冷却记录（跨维度保留的补偿清理点） */
    @SubscribeEvent
    public static void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        COOLDOWNS.remove(event.getEntity().getUUID());
    }
}
