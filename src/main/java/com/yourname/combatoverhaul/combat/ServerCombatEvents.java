package com.yourname.combatoverhaul.combat;

import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 服务端战斗触发入口：
 * <ul>
 *   <li>替换原版近战：玩家左键攻击且主手武器配置了动作 → 取消原版攻击，播放动作由判定框结算；</li>
 *   <li>生物 AI：实体加入世界时挂载 MobActionGoal（按 combat_actions.json 的 mobs 段）。</li>
 * </ul>
 */
public final class ServerCombatEvents {

    private ServerCombatEvents() {
    }

    // ------------------------------------------------------------------ 替换原版近战

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.isSpectator()) {
            return;
        }
        CombatConfig cfg = CombatConfig.get();
        if (!cfg.meleeEnabled) {
            return;
        }

        // 连段：正在播放的动作在取消窗口内按下攻击 → 取消并接续下一段
        // （窗口未命中或无接续时不取消事件，保持原语义：放行原版弱攻击）
        ActionRunner cur = ActionRunner.current(player.getId());
        if (cur != null) {
            String next = ComboStore.nextAction(cur.modelKey(), cur.actionName(), "attack", cur.currentTick());
            // 只有真正接续成功才取消原版攻击；start 可能因冷却/动作不存在返回 false，
            // 此时若已经取消事件，玩家会既没有动作也没有原版攻击（连段按键直接吞掉）。
            if (next != null && ActionRunner.start(player, cur.modelKey(), next, 0)) {
                event.setCanceled(true);
                player.resetAttackStrengthTicker();
            }
            return;
        }

        ItemStack held = player.getMainHandItem();
        ResourceLocation itemId = held.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(held.getItem());

        // 1) 玩家模型实体动作：weapons 绑定手持物品的动作优先；徒手用 weapons 为空的兜底动作
        String[] pm = cfg.playerModel.split(":", 2);
        if (pm.length == 2) {
            String playerKey = ActionCache.keyFor(pm[0], pm[1], false);
            ActionCache.Entry pEntry = ActionCache.get(playerKey);
            if (pEntry != null && !pEntry.actions.isEmpty()) {
                ActionStore.Action picked = null;
                if (itemId != null) {
                    picked = pickBound(pEntry, itemId.toString());
                } else {
                    picked = pickUnarmed(pEntry);
                }
                if (picked != null) {
                    if (ActionRunner.isOnCooldown(player, playerKey, picked.name) || ActionRunner.isPlaying(player.getId())) {
                        return; // 冷却中 → 放行原版弱攻击
                    }
                    event.setCanceled(true);
                    player.resetAttackStrengthTicker();
                    ActionRunner.start(player, playerKey, picked.name, 0);
                    return;
                }
            }
        }
        if (itemId == null) {
            return; // 徒手且玩家模型无空 weapons 动作 → 原版拳击
        }

        // 2) 武器模型动作：weapons 显式绑定优先，否则 auto_weapons 取第一个非循环动作
        String weaponKey = ActionCache.keyFor(itemId.getNamespace(), itemId.getPath(), true);
        ActionCache.Entry entry = ActionCache.get(weaponKey);
        if (entry == null || entry.actions.isEmpty()) {
            return; // 武器没有动作文件 → 原版攻击照常
        }

        String actionName = null;
        int cooldown = 0;
        CombatConfig.MeleeEntry me = cfg.meleeWeapons.get(itemId.toString());
        if (me != null && !me.action.isEmpty()) {
            // 旧显式指定（仅在迁移失败残留时生效）：动作必须存在才接管
            if (entry.actions.stream().noneMatch(a -> a.name.equals(me.action))) {
                return;
            }
            if (ActionRunner.isOnCooldown(player, weaponKey, me.action) || ActionRunner.isPlaying(player.getId())) {
                return; // 冷却中 → 放行原版弱攻击
            }
            actionName = me.action;
            cooldown = me.cooldownTicks;
        } else {
            ActionStore.Action bound = pickBound(entry, itemId.toString());
            if (bound != null) {
                actionName = bound.name;
            } else if (cfg.meleeAutoWeapons) {
                // 自动接管：第一个非循环动作
                for (var a : entry.actions) {
                    if (!a.loop) {
                        actionName = a.name;
                        break;
                    }
                }
                if (actionName == null) {
                    actionName = entry.actions.get(0).name;
                }
            }
            if (actionName != null && (ActionRunner.isOnCooldown(player, weaponKey, actionName)
                    || ActionRunner.isPlaying(player.getId()))) {
                return;
            }
        }
        if (actionName == null) {
            return;
        }

        event.setCanceled(true);
        player.resetAttackStrengthTicker();
        ActionRunner.start(player, weaponKey, actionName, cooldown);
    }

    /** 动作集中找 weapons 绑定了该物品的动作（非循环优先） */
    private static ActionStore.Action pickBound(ActionCache.Entry entry, String itemId) {
        ActionStore.Action fallback = null;
        for (ActionStore.Action a : entry.actions) {
            if (a.weapons.contains(itemId)) {
                if (!a.loop) {
                    return a;
                }
                if (fallback == null) {
                    fallback = a;
                }
            }
        }
        return fallback;
    }

    /** 动作集中找 weapons 为空的徒手/通用动作（非循环优先；翻滚类动作只走技能槽，不抢空手左键） */
    private static ActionStore.Action pickUnarmed(ActionCache.Entry entry) {
        ActionStore.Action fallback = null;
        for (ActionStore.Action a : entry.actions) {
            if (!a.weapons.isEmpty()
                    || a.name.toLowerCase(java.util.Locale.ROOT).contains("dodge")) {
                continue;
            }
            if (!a.loop) {
                return a;
            }
            if (fallback == null) {
                fallback = a;
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------ 生物 AI 动作

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        // 不能用持久化 NBT 标记去重：ForgeData 会随实体存盘，重新加载后标记还在，
        // 但 goalSelector 是新实例、MobActionGoal 已经没了 → 生物永远失去战斗 AI。
        // 直接查当前 goalSelector 是否已挂过本 Goal（新实例没有，加载后会自动补挂）。
        if (mob.goalSelector.getAvailableGoals().stream()
                .anyMatch(w -> w.getGoal() instanceof MobActionGoal)) {
            return;
        }
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (typeId == null) {
            return;
        }
        // 动作池 = combat_actions.json 的 mobs 段 ∪ 实体动作文件 entities 勾选了该生物的动作
        // （同名动作 mobs 段参数优先；勾选动作默认权重 1.0）
        CombatConfig.EntityActions cfgEa = CombatConfig.get().mobs.get(typeId.toString());
        CombatConfig.EntityActions merged = new CombatConfig.EntityActions();
        java.util.Set<String> named = new java.util.HashSet<>();
        if (cfgEa != null) {
            merged.actions.addAll(cfgEa.actions);
            for (var e : cfgEa.actions) {
                named.add(e.action);
            }
        }
        int granted = 0;
        ActionCache.Entry entry = ActionCache.get(
                ActionCache.keyFor(typeId.getNamespace(), typeId.getPath(), false));
        if (entry != null) {
            // 通配授权 "*" 只对人形生物生效（模型带 bone_aliases，语义骨骼名可解析）
            boolean humanoid = entry.model != null && !entry.model.boneAliases.isEmpty();
            for (ActionStore.Action a : entry.actions) {
                boolean bound = a.entities.contains(typeId.toString())
                        || (humanoid && a.entities.contains(ActionCache.WILDCARD_ENTITY));
                if (bound && !named.contains(a.name)) {
                    CombatConfig.MobActionEntry m = new CombatConfig.MobActionEntry();
                    m.action = a.name;
                    m.weight = 1.0;
                    m.cooldownTicks = Math.max(m.cooldownTicks, a.cooldownTicks);
                    merged.actions.add(m);
                    granted++;
                }
            }
        }
        if (merged.actions.isEmpty()) {
            return;
        }
        int priority = merged.actions.get(0).goalPriority;
        mob.goalSelector.addGoal(priority, new MobActionGoal(mob, merged));
        CombatOverhaulMod.LOGGER.debug("[CombatOverhaul] {} 挂载 AI 动作 Goal（配置 {} 项 + 勾选 {} 项）",
                typeId, cfgEa != null ? cfgEa.actions.size() : 0, granted);
    }
}
