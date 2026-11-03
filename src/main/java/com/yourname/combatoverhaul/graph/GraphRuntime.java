package com.yourname.combatoverhaul.graph;

import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.combat.ActionRunner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端权威的事件图执行器。
 *
 * <p>每个实体一个运行栈；图来自 {@link GraphStore}，动作片段通过
 * {@link ActionRunner#start} 播放。广播只发信号，不直接执行别的实体的积木，
 * 由接收实体自己的 on_signal 帽子决定行为。
 */
public final class GraphRuntime {

    private static final Map<Integer, Deque<Task>> RUNNING = new HashMap<>();
    private static final Map<Integer, Integer> WAIT = new HashMap<>();
    private static final int MAX_DEPTH = 32;

    private GraphRuntime() {
    }

    public static final class Task {
        Graph.Block block;
        Graph.Block after;
        Graph.Block loopBody;
        int loopCount;
        int depth;

        Task(Graph.Block block, int depth) {
            this.block = block;
            this.depth = depth;
        }
    }

    public static void signal(LivingEntity entity, String event, String signal) {
        if (entity == null || entity.level().isClientSide() || !entity.isAlive()) {
            return;
        }
        Graph graph = graphOf(entity);
        if (graph == null || graph.scripts.isEmpty()) {
            return;
        }
        for (Graph.Script s : graph.scripts) {
            if (s.event == null || !s.event.equals(event)) {
                continue;
            }
            if ("on_signal".equals(event) && s.signal != null && !s.signal.isBlank()
                    && !s.signal.equals(signal)) {
                continue;
            }
            if (s.first != null) {
                RUNNING.computeIfAbsent(entity.getId(), k -> new ArrayDeque<>())
                        .push(new Task(s.first, 0));
            }
        }
    }

    public static void broadcast(LivingEntity from, String signal, String scope, int radius) {
        if (from == null || from.level().isClientSide()) {
            return;
        }
        signal(from, "on_signal", signal);
        if ("self".equals(scope) || scope == null || scope.isBlank()) {
            return;
        }
        int r = Math.max(1, radius);
        AABB box = from.getBoundingBox().inflate(r);
        for (ServerLevel level : from.getServer().getAllLevels()) {
            for (Entity e : level.getEntities(from, box)) {
                if (!(e instanceof LivingEntity le) || le == from || !le.isAlive()) {
                    continue;
                }
                if ("team".equals(scope) && (from.getTeam() == null || from.getTeam() != le.getTeam())) {
                    continue;
                }
                signal(le, "on_signal", signal);
            }
        }
        if ("global".equals(scope)) {
            for (ServerLevel level : from.getServer().getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    if (e instanceof LivingEntity le && le != from && le.isAlive()) {
                        signal(le, "on_signal", signal);
                    }
                }
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (server == null || RUNNING.isEmpty()) {
            return;
        }
        List<Integer> dead = new ArrayList<>();
        for (var e : RUNNING.entrySet()) {
            Entity entity = findEntity(server, e.getKey());
            if (!(entity instanceof LivingEntity le) || !le.isAlive()) {
                dead.add(e.getKey());
                continue;
            }
            Integer wait = WAIT.get(e.getKey());
            if (wait != null && wait > 0) {
                WAIT.put(e.getKey(), wait - 1);
                continue;
            }
            Deque<Task> stack = e.getValue();
            boolean paused = step(le, stack);
            if (stack.isEmpty()) {
                dead.add(e.getKey());
            } else if (paused) {
                // step 已写入 WAIT
            }
        }
        for (Integer id : dead) {
            RUNNING.remove(id);
            WAIT.remove(id);
        }
    }

    /** @return true=遇到 wait，暂停到下一 tick */
    private static boolean step(LivingEntity entity, Deque<Task> stack) {
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 512) {
            Task t = stack.peek();
            if (t.block == null) {
                if (t.loopCount > 0 && t.loopBody != null) {
                    t.block = t.loopBody;
                    t.loopCount--;
                    continue;
                }
                if (t.after != null) {
                    t.block = t.after;
                    t.after = null;
                    continue;
                }
                stack.pop();
                continue;
            }
            Graph.Block b = t.block;
            t.block = b.next;
            String key = modelKey(entity);
            switch (b.type) {
                case "play" -> {
                    String clip = b.field("clip", "");
                    if (!clip.isBlank()) {
                        ActionRunner.start(entity, key, clip, 0);
                    }
                }
                case "wait" -> {
                    int ticks = parseInt(b.field("ticks", "1"), 1);
                    WAIT.put(entity.getId(), Math.max(1, ticks) - 1);
                    return true;
                }
                case "broadcast" -> broadcast(entity, b.field("signal", ""),
                        b.field("scope", "self"), parseInt(b.field("radius", "16"), 16));
                case "if" -> {
                    boolean ok = condition(entity, b.field("condition", "true"));
                    Graph.Block branch = ok ? b.body : b.body2;
                    if (branch != null && t.depth < MAX_DEPTH) {
                        Task child = new Task(branch, t.depth + 1);
                        stack.push(child);
                    }
                }
                case "repeat" -> {
                    int count = Math.max(0, parseInt(b.field("count", "1"), 1));
                    if (b.body != null && count > 0 && t.depth < MAX_DEPTH) {
                        Task child = new Task(null, t.depth + 1);
                        child.loopBody = b.body;
                        child.loopCount = count;
                        stack.push(child);
                    }
                }
                case "hitbox" -> ActionRunner.setHitboxEnabled(entity,
                        b.field("name", ""), !"false".equalsIgnoreCase(b.field("enabled", "true")));
                case "heal" -> entity.heal(parseFloat(b.field("amount", "1"), 1));
                case "damage" -> {
                    float amount = parseFloat(b.field("amount", "1"), 1);
                    LivingEntity target = target(entity, b.field("target", "nearest"),
                            parseInt(b.field("radius", "8"), 8));
                    if (target != null && amount > 0) {
                        target.hurt(entity.damageSources().mobAttack(entity), amount);
                    }
                }
                case "velocity" -> {
                    float x = parseFloat(b.field("x", "0"), 0);
                    float y = parseFloat(b.field("y", "0"), 0);
                    float z = parseFloat(b.field("z", "0"), 0);
                    entity.setDeltaMovement(x, y, z);
                }
                case "particle" -> com.yourname.combatoverhaul.net.CombatMessages.sendGraphFx(
                        entity, b.field("effect", "minecraft:crit"),
                        parseInt(b.field("count", "8"), 8),
                        parseFloat(b.field("speed", "0.2"), 0.2f));
                case "sound" -> {
                    String sound = b.field("sound", "minecraft:entity.player.attack.sweep");
                    try {
                        entity.level().playSound(null, entity.blockPosition(),
                                BuiltInRegistries.SOUND_EVENT.get(new ResourceLocation(sound)),
                                SoundSource.PLAYERS, 1f, 1f);
                    } catch (RuntimeException ignored) {
                    }
                }
                case "say" -> {
                    if (entity instanceof Player p) {
                        p.displayClientMessage(Component.literal(b.field("text", "")), false);
                    }
                }
                default -> {
                }
            }
            if (t.block == null && t.loopCount <= 0 && t.after == null) {
                stack.pop();
            }
        }
        return false;
    }

    private static boolean condition(LivingEntity entity, String c) {
        if (c == null || c.isBlank() || "true".equalsIgnoreCase(c)) {
            return true;
        }
        if ("false".equalsIgnoreCase(c)) {
            return false;
        }
        if (c.startsWith("tag:")) {
            return entity.getTags().contains(c.substring(4));
        }
        if (c.startsWith("hp<")) {
            return entity.getHealth() < parseFloat(c.substring(3), 0);
        }
        if (c.startsWith("hp>")) {
            return entity.getHealth() > parseFloat(c.substring(3), 0);
        }
        return false;
    }

    private static LivingEntity target(LivingEntity from, String target, int radius) {
        if ("self".equals(target)) {
            return from;
        }
        AABB box = from.getBoundingBox().inflate(radius);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : from.level().getEntities(from, box)) {
            if (e instanceof LivingEntity le && le != from && le.isAlive()) {
                double d = le.distanceToSqr(from);
                if (d < bestD) {
                    bestD = d;
                    best = le;
                }
            }
        }
        return best;
    }

    private static Graph graphOf(LivingEntity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null) {
            return null;
        }
        return GraphStore.load(id.getNamespace(), id.getPath());
    }

    private static String modelKey(LivingEntity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null) {
            return "";
        }
        return ActionCache.keyFor(id.getNamespace(), id.getPath(), false);
    }

    private static Entity findEntity(MinecraftServer server, int id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static float parseFloat(String s, float fallback) {
        try {
            return Float.parseFloat(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
