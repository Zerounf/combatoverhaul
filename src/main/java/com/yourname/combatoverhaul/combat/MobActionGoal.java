package com.yourname.combatoverhaul.combat;

import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * 生物 AI 动作 Goal：目标处于配置的距离段/位置条件内时，按权重随机选择动作并启动。
 * 动作期间锁定移动。动作池 = combat_actions.json 的 mobs 段 ∪ 动作 entities 勾选（挂载时合并）。
 *
 * <p>持械过滤：主手物品被任一候选动作 weapons 绑定时，仅抽取绑定了该武器的动作；
 * 无绑定时全部候选参与（weapons 为空的动作作为徒手/通用兜底）。
 */
public class MobActionGoal extends Goal {

    private final Mob mob;
    private final CombatConfig.EntityActions cfg;
    private final List<CombatConfig.MobActionEntry> candidates = new ArrayList<>();
    private long endGameTime;

    public MobActionGoal(Mob mob, CombatConfig.EntityActions cfg) {
        this.mob = mob;
        this.cfg = cfg;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (ActionRunner.isPlaying(mob.getId())) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        double dist = mob.distanceTo(target);
        boolean onGround = mob.onGround();
        double dy = target.getY() - mob.getY();

        candidates.clear();
        for (CombatConfig.MobActionEntry e : cfg.actions) {
            if (dist < e.minDist || dist > e.maxDist) {
                continue;
            }
            if (e.requireOnGround && !onGround) {
                continue;
            }
            if (e.requireLineOfSight && !mob.getSensing().hasLineOfSight(target)) {
                continue;
            }
            if (e.targetAbove != null) {
                boolean above = dy > 0.5;
                if (e.targetAbove && !above) {
                    continue;
                }
                // targetAbove=false 表示“只在目标更低时使用”：目标不低于 -0.5 就不能用
                if (!e.targetAbove && dy >= -0.5) {
                    continue;
                }
            }
            if (!actionExists(e.action)) {
                continue;
            }
            candidates.add(e);
        }
        filterByHeldWeapon();
        if (candidates.isEmpty()) {
            return false;
        }
        // 全部候选都在冷却时不要启动 Goal：否则每 tick 都 start/fail，
        // Goal.Flag.MOVE 被反复占用，原版追击/近战 Goal 会被饿死（生物站着不动）。
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (typeId == null) {
            return false;
        }
        String modelKey = ActionCache.keyFor(typeId.getNamespace(), typeId.getPath(), false);
        for (CombatConfig.MobActionEntry e : candidates) {
            if (!ActionRunner.isOnCooldown(mob, modelKey, e.action)) {
                return true;
            }
        }
        return false;
    }

    /** 持有绑定武器时，把候选限制为绑定了该武器的动作；无绑定时保持全部候选 */
    private void filterByHeldWeapon() {
        ItemStack held = mob.getMainHandItem();
        if (held.isEmpty()) {
            return;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(held.getItem());
        if (itemId == null) {
            return;
        }
        String id = itemId.toString();
        List<CombatConfig.MobActionEntry> bound = new ArrayList<>();
        for (CombatConfig.MobActionEntry e : candidates) {
            ActionStore.Action a = findAction(e.action);
            if (a != null && a.weapons.contains(id)) {
                bound.add(e);
            }
        }
        if (!bound.isEmpty()) {
            candidates.clear();
            candidates.addAll(bound);
        }
    }

    private ActionStore.Action findAction(String actionName) {
        ActionCache.Entry entry = entityEntry();
        if (entry == null) {
            return null;
        }
        for (ActionStore.Action a : entry.actions) {
            if (a.name.equals(actionName)) {
                return a;
            }
        }
        return null;
    }

    private ActionCache.Entry entityEntry() {
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (typeId == null) {
            return null;
        }
        return ActionCache.get(ActionCache.keyFor(typeId.getNamespace(), typeId.getPath(), false));
    }

    private boolean actionExists(String actionName) {
        ActionCache.Entry entry = entityEntry();
        return entry != null && entry.actions.stream().anyMatch(a -> a.name.equals(actionName));
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isAlive() && !mob.level().isClientSide()
                && ActionRunner.isPlaying(mob.getId())
                && mob.level().getGameTime() < endGameTime;
    }

    @Override
    public void start() {
        // 按权重不放回抽取：冷却中的动作 start 失败后自动尝试下一个
        List<CombatConfig.MobActionEntry> pool = new ArrayList<>(candidates);
        net.minecraft.util.RandomSource rng = mob.getRandom();
        long now = mob.level().getGameTime();
        while (!pool.isEmpty()) {
            double total = 0;
            for (CombatConfig.MobActionEntry e : pool) {
                total += Math.max(0.001, e.weight);
            }
            double roll = rng.nextDouble() * total;
            CombatConfig.MobActionEntry picked = pool.get(pool.size() - 1);
            for (CombatConfig.MobActionEntry e : pool) {
                roll -= Math.max(0.001, e.weight);
                if (roll <= 0) {
                    picked = e;
                    break;
                }
            }
            pool.remove(picked);
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
            String modelKey = ActionCache.keyFor(typeId.getNamespace(), typeId.getPath(), false);
            if (ActionRunner.start(mob, modelKey, picked.action, picked.cooldownTicks)) {
                ActionCache.Entry entry = ActionCache.get(modelKey);
                float length = 0;
                for (var a : entry.actions) {
                    if (a.name.equals(picked.action)) {
                        length = a.lengthTicks;
                        break;
                    }
                }
                endGameTime = now + (long) Math.ceil(length) + 2;
                mob.getNavigation().stop();
                return;
            }
        }
    }

    @Override
    public void tick() {
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
