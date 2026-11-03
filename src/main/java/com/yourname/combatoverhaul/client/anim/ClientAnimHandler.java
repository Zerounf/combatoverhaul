package com.yourname.combatoverhaul.client.anim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.combat.CombatConfig;
import com.yourname.combatoverhaul.combat.HitboxMath;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 客户端动画层：接收 S2C 播放包，把动作姿态按骨骼名匹配到
 * {@code LivingEntityRenderer} 的真实 ModelPart 上。
 *
 * <p>应用时机（关键）：{@code LivingEntityRenderer.render()} 中 {@code RenderLivingEvent.Pre}
 * 先于 {@code setupAnim} 触发，Pre 里改旋转会被原版 setupAnim 覆盖。因此用 mixin
 * （{@code LivingEntityRendererMixin}）在原版 {@code setupAnim} 调用之后注入，
 * 回调 {@link #afterSetupAnim} 应用姿态——不替换 renderer.model 字段，
 * 渲染器的各 Layer（盔甲/手持物/史莱姆外膜等按具体模型类型强转 getParentModel()）依然安全。
 *
 * <p>每次渲染应用的内容（按实体模型 JSON）：
 * <ul>
 *   <li>层级同步（仅一次/每份 JSON）：把 ModelPart children 调整为 JSON 里的父子关系
 *       ——编辑器里「挂接」过的骨骼（如把腿挂到脊柱）在游戏内同样跟随上级弯曲；</li>
 *   <li>基础姿态：JSON pivot/rotation/scale，旋转以「JSON - 原版初始姿态」增量叠加，
 *       保留原版程序动画（走路/摆动）；位置与缩放按 JSON 绝对值生效；</li>
 *   <li>动作姿态：正在播放的动作按骨骼名叠加（+=/*）；武器动作的骨骼名若与实体部件
 *       同名（如 right_arm）同样生效。</li>
 * </ul>
 *
 * <p>另含判定框调试渲染（combat_actions.json 的 debug_hitboxes=true 时，
 * 客户端用与服务端相同的 HitboxMath 计算并画出世界线框）。
 */
public final class ClientAnimHandler {

    /** 每实体播放状态 */
    private static final Map<Integer, State> PLAYING = new HashMap<>();
    /** 玩家本地 locomotion 状态（idle/walk/run），不占用 PLAYING 的战斗动作。 */
    private static final Map<Integer, State> LOCOMOTION = new HashMap<>();
    private static final Map<Integer, String> LOCOMOTION_NAME = new HashMap<>();

    /** 当前正在渲染的实体姿态模型（Pre 设置，redirect/Post 读取；客户端渲染线程） */
    private static final ThreadLocal<ParsedModel> RENDER_MODEL = new ThreadLocal<>();
    /** 当前实体的绝对局部姿态（afterSetupAnim 从 ModelPart 采样，含原版程序动画） */
    private static final ThreadLocal<com.yourname.combatoverhaul.client.bedrock.BedrockPose> RENDER_POSE =
            new ThreadLocal<>();
    /** 当前实体是否走新 Bedrock 渲染（目前仅玩家；其他实体保留旧 ModelPart 覆盖） */
    private static final ThreadLocal<Boolean> RENDER_BEDROCK = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** 实验开关：如新渲染有画面问题，可用 -Dcombatoverhaul.bedrockRender=false 或指令切回旧 ModelPart 渲染 */
    private static volatile boolean bedrockPlayerEnabled =
            !"false".equalsIgnoreCase(System.getProperty("combatoverhaul.bedrockRender", "true"));
    private static boolean loggedBedrockEnabled = false;

    public static boolean isBedrockRenderEnabled() {
        return bedrockPlayerEnabled;
    }

    public static void setBedrockRenderEnabled(boolean enabled) {
        bedrockPlayerEnabled = enabled;
        if (enabled) {
            loggedBedrockEnabled = false;
        }
    }

    /** 动作模型 → 实体 ModelPart 名称表缓存（模型实例为键，弱引用防泄漏） */
    private static final Map<EntityModel<?>, Map<String, ModelPart>> PART_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 已按该份 JSON 同步过层级的模型根部件（弱引用；JSON 重新解析出新实例后自动重同步） */
    private static final Map<ModelPart, ParsedModel> HIERARCHY_SYNCED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 每份 JSON 中「JSON 根骨骼挂到模型根部件下」所需的枢轴补偿（bone 名 → 补偿后局部枢轴） */
    private static final Map<ParsedModel, Map<String, float[]>> ROOT_PIVOT_FIX =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 本帧姿态来源：实体 id → 姿态模型（Pre 放入，mixin 回调 afterSetupAnim 消费，Post 兜底清理） */
    private static final Map<Integer, ParsedModel> PENDING_POSE = new HashMap<>();

    /** 本帧被姿态覆盖前的旋转：实体 id → (part → [xRot,yRot,zRot])（Post 恢复，防止非原版动画部件逐帧累积） */
    private static final Map<Integer, Map<ModelPart, float[]>> FRAME_ROT = new HashMap<>();

    public static final class State {
        public final ParsedModel actionModel;   // 动作所属模型（实体或武器）
        public final ParsedModel entityModel;   // 实体模型（武器动作的挂接链用，可为 null）
        public final List<ActionStore.Action> actions;
        public final int index;
        public final long startGameTime;
        public final float length;
        public final boolean loop;
        public final boolean weaponAction;
        public State previous;
        public long transitionStart;

        State(ParsedModel actionModel, ParsedModel entityModel, List<ActionStore.Action> actions,
              int index, long startGameTime, boolean weaponAction) {
            this.actionModel = actionModel;
            this.entityModel = entityModel;
            this.actions = actions;
            this.index = index;
            this.startGameTime = startGameTime;
            this.weaponAction = weaponAction;
            ActionStore.Action a = actions.get(index);
            this.length = Math.max(1, a.lengthTicks);
            this.loop = a.loop;
        }
    }

    private ClientAnimHandler() {
    }

    // ------------------------------------------------------------------ 播放控制（S2C 入口）

    public static void play(int entityId, String modelKey, String actionName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(entityId);
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity)) {
            return;
        }
        ActionCache.Entry entry = ActionCache.get(modelKey);
        if (entry == null || entry.actions.isEmpty() || entry.model == null) {
            return;
        }
        int index = -1;
        for (int i = 0; i < entry.actions.size(); i++) {
            if (entry.actions.get(i).name.equals(actionName)) {
                index = i;
                break;
            }
        }
        // 玩家动作重定向到 12 段柔化 rig：同名动作存在时，身体/动作都取 rig 版本。
        if (!modelKey.startsWith("W|") && modelKey.contains("|minecraft|player")) {
            String rigPath = isSlimPlayer((net.minecraft.world.entity.LivingEntity) entity)
                    ? "humanoid" : "humanoid_steve";
            ActionCache.Entry rig = ActionCache.get(ActionCache.keyFor("combatoverhaul", rigPath, false));
            if (rig != null && rig.model != null) {
                for (int i = 0; i < rig.actions.size(); i++) {
                    if (rig.actions.get(i).name.equals(actionName)) {
                        entry = rig;
                        index = i;
                        break;
                    }
                }
            }
        }
        if (index < 0) {
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.warn(
                    "[AnimClient] action not found id={} model={} action={} actions={}",
                    entityId, modelKey, actionName, entry.actions.size());
            return;
        }
        com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                "[AnimClient] play id={} model={} action={} index={} bones={}",
                entityId, modelKey, actionName, index, entry.model.bones.size());
        boolean weaponAction = modelKey.startsWith("W|");
        com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                "[AnimClient] resolved id={} entryModel={} entryBones={} first={}",
                entityId, entry.model.entityId, entry.model.bones.size(),
                entry.model.bones.isEmpty() ? "-" : entry.model.bones.get(0).name);
        ParsedModel entityModel = null;
        if (weaponAction) {
            EntityType<?> type = entity.getType();
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (typeId != null) {
                ActionCache.Entry em = ActionCache.get(
                        ActionCache.keyFor(typeId.getNamespace(), typeId.getPath(), false));
                entityModel = em != null ? em.model : null;
            }
        }
        synchronized (PLAYING) {
            PLAYING.put(entityId, new State(entry.model, entityModel, entry.actions, index,
                    entity.level().getGameTime(), weaponAction));
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // 离开世界/服务器后 PLAYING 不清会跨存档残留：新世界实体复用旧 id 时会继承旧动作。
            clearRuntimeState();
            return;
        }
        synchronized (PLAYING) {
            Iterator<Map.Entry<Integer, State>> it = PLAYING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, State> e = it.next();
                Entity entity = mc.level.getEntity(e.getKey());
                State s = e.getValue();
                float t = entity == null ? Float.MAX_VALUE
                        : (entity.level().getGameTime() - s.startGameTime);
                boolean done = !s.loop && t > s.length + 2;
                if (entity == null || entity.isRemoved() || done) {
                    it.remove();
                    // 清掉动作模型的 anim 残留：游戏内手持渲染/后续基础姿态不读取过期动作姿态
                    resetAnims(s.actionModel);
                }
            }
        }
    }

    /** 退出世界/服务器：恢复本帧被改过的 ModelPart 旋转并清空全部播放状态 */
    @SubscribeEvent
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clearRuntimeState();
    }

    private static void clearRuntimeState() {
        synchronized (PLAYING) {
            for (State s : PLAYING.values()) {
                resetAnims(s.actionModel);
            }
            PLAYING.clear();
        }
        for (Map<ModelPart, float[]> rots : FRAME_ROT.values()) {
            for (Map.Entry<ModelPart, float[]> e : rots.entrySet()) {
                ModelPart part = e.getKey();
                float[] p = e.getValue();
                part.xRot = p[0];
                part.yRot = p[1];
                part.zRot = p[2];
            }
        }
        FRAME_ROT.clear();
        PENDING_POSE.clear();
        RENDER_MODEL.remove();
        RENDER_BEDROCK.remove();
        RENDER_POSE.remove();
    }

    /** 本地玩家按键技能等需要即时预览的场景可直接调用（同步预测） */
    public static void playLocal(net.minecraft.world.entity.LivingEntity entity, String modelKey, String actionName) {
        play(entity.getId(), modelKey, actionName);
    }

    /** 当前渲染实体是否走新 Bedrock quad 渲染（由 Pre 事件设置） */
    public static boolean useBedrockRender() {
        return Boolean.TRUE.equals(RENDER_BEDROCK.get());
    }

    /** 当前渲染实体的姿态模型（已应用本帧 ActionStore.apply；无则 null） */
    public static ParsedModel currentRenderModel() {
        return RENDER_MODEL.get();
    }

    /** 当前渲染实体的绝对局部姿态（含原版 setupAnim；仅新 Bedrock 渲染时非 null） */
    public static com.yourname.combatoverhaul.client.bedrock.BedrockPose currentRenderPose() {
        return RENDER_POSE.get();
    }

    // ------------------------------------------------------------------ 实体渲染动画

    // ------------------------------------------------------------------ 实体渲染动画

    private static void applyLocomotionPose(net.minecraft.world.entity.LivingEntity entity,
                                            State s, float t, float partialTick) {
        float alpha = 1f;
        if (s.previous != null) {
            float age = (entity.level().getGameTime() - s.transitionStart + partialTick) / 20f;
            alpha = Math.min(1f, age / 0.18f);
            alpha = alpha * alpha * (3f - 2f * alpha); // smoothstep，避免线性过渡的“弹一下”
            if (alpha < 1f) {
                float prevT = (entity.level().getGameTime() - s.previous.startGameTime + partialTick)
                        * locomotionTimeScale(entity, s.previous);
                ActionStore.applyBlend(s.actions, s.previous.index, prevT,
                        s.index, t, alpha, s.actionModel);
                return;
            }
            s.previous = null;
        }
        ActionStore.apply(s.actions, s.index, t, s.actionModel);
    }

    /** 游戏内实体已经负责真实位移，locomotion 动作只保留原地肢体动画，避免双重位移。 */
    private static void suppressLocomotionRoot(net.minecraft.world.entity.LivingEntity entity, State s) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player) || s == null
                || s.actionModel == null || s.index < 0 || s.index >= s.actions.size()) {
            return;
        }
        String name = s.actions.get(s.index).name;
        if (name == null || !(name.startsWith("Walk") || name.startsWith("Run")
                || name.startsWith("Sneak") || name.startsWith("Idle"))) {
            return;
        }
        ParsedBone pelvis = s.actionModel.findBone("Pelvis");
        if (pelvis != null) {
            pelvis.animPosX = 0f;
            pelvis.animPosY = 0f;
            pelvis.animPosZ = 0f;
            // 方块比例下 ER 的骨盆/脊柱侧摆会被放大成“折腰”，locomotion 先锁直躯干。
            pelvis.animRotX = 0f;
            pelvis.animRotY = 0f;
            pelvis.animRotZ = 0f;
        }
        for (String n : new String[]{"Neck", "Head"}) {
            ParsedBone b = s.actionModel.findBone(n);
            if (b != null) {
                // ER 步行自带头部左右摆；方块头会放大，locomotion 保留 25%。
                b.animRotX *= 0.25f;
                b.animRotY *= 0.25f;
                b.animRotZ *= 0.25f;
            }
        }
        for (String n : new String[]{"Spine", "Spine1", "Spine2"}) {
            ParsedBone b = s.actionModel.findBone(n);
            if (b != null) {
                b.animRotX = 0f;
                b.animRotY = 0f;
                b.animRotZ = 0f;
            }
        }
        for (String n : new String[]{"L_UpperArm", "R_UpperArm", "L_Forearm", "R_Forearm"}) {
            ParsedBone b = s.actionModel.findBone(n);
            if (b != null) {
                // ER 步行手臂带 Z 轴侧摆；方块臂会变成左右甩，只保留前后 X 摆动。
                b.animRotY = 0f;
                b.animRotZ = 0f;
            }
        }
    }

    /** Minecraft 的移动速度相对腿长比 ER 快约 3 倍，locomotion 按实际速度加快播放。 */
    private static float locomotionTimeScale(net.minecraft.world.entity.LivingEntity entity, State s) {
        if (entity == null || s == null || s.index < 0 || s.index >= s.actions.size()) {
            return 1.0f;
        }
        String name = s.actions.get(s.index).name;
        if (name == null) {
            return 1.0f;
        }
        double speed = entity.getDeltaMovement().horizontalDistance();
        double ref;
        if (name.startsWith("Run")) {
            ref = 4.0 / 16.0;
        } else if (name.startsWith("Walk")) {
            ref = 1.5 / 16.0;
        } else if (name.startsWith("Sneak")) {
            ref = 0.9 / 16.0;
        } else {
            return 1.0f;
        }
        if (speed < 0.02) {
            speed = ref;
        }
        float scale = (float) Math.max(0.8, Math.min(3.0, speed / ref));
        if (name.startsWith("Walk")) {
            scale *= 2.0f; // 用户要求：当前走路再快 200%
        }
        return scale;
    }

    private static State locomotionState(net.minecraft.world.entity.LivingEntity entity, ParsedModel base) {        if (!(entity instanceof net.minecraft.world.entity.player.Player player) || base == null
                || !"combatoverhaul:skinned_player_er".equals(base.entityId)) {
            return null;
        }
        ActionCache.Entry entry = ActionCache.get(
                ActionCache.keyFor("combatoverhaul", "skinned_player_er", false));
        com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                "[LocoEntry] base={} entry={} actions={}", base.entityId,
                entry != null, entry == null ? -1 : entry.actions.size());
        if (entry == null || entry.actions.isEmpty()) {
            return null;
        }
        double speedSq = player.getDeltaMovement().horizontalDistanceSqr();
        float walkSpeed = player.walkAnimation.speed();
        boolean inputMoving = false;
        if (player instanceof net.minecraft.client.player.LocalPlayer lp) {
            inputMoving = lp.input.forwardImpulse != 0.0F
                    || lp.input.leftImpulse != 0.0F
                    || lp.input.jumping;
        }
        String want;
        boolean sprintKey = net.minecraft.client.Minecraft.getInstance().options.keySprint.isDown();
        if (player.isShiftKeyDown() || player.isCrouching()) {
            want = "Sneak unarmed";
        } else if (speedSq > 0.0004 || walkSpeed > 0.03 || inputMoving || player.zza != 0f) {
            // 疾跑状态有时在渲染帧还未同步；直接读疾跑键 + 速度双判定。
            boolean sprinting = sprintKey || player.isSprinting()
                    || player.getDeltaMovement().horizontalDistance() > 0.24;
            want = sprinting ? "Run unarmed forward" : "Walk unarmed forward";
        } else {
            want = "Idle unarmed";
        }
        com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                "[Loco] id={} speedSq={} walk={} input={} sprintKey={} sprint={} want={}",
                entity.getId(), speedSq, walkSpeed, inputMoving, sprintKey,
                player.isSprinting(), want);
        int idx = -1;
        for (int i = 0; i < entry.actions.size(); i++) {
            if (entry.actions.get(i).name.equals(want)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        int id = entity.getId();
        State old = LOCOMOTION.get(id);
        if (old != null && want.equals(LOCOMOTION_NAME.get(id)) && old.actions == entry.actions
                && old.index == idx) {
            return old;
        }
        State st = new State(entry.model, null, entry.actions, idx,
                entity.level().getGameTime(), false);
        if (old != null && old.actions == entry.actions) {
            st.previous = old;
            st.transitionStart = entity.level().getGameTime();
        }
        LOCOMOTION.put(id, st);
        LOCOMOTION_NAME.put(id, want);
        return st;
    }

    /** PlayerRenderer HEAD 注入用：在 RenderLivingEvent.Pre 之前准备 skinned 姿态。 */
    public static ParsedModel preparePlayerRender(net.minecraft.world.entity.player.Player player,
                                                  float partialTick) {
        ParsedModel base = baseModelOf(player);
        State s;
        synchronized (PLAYING) {
            s = PLAYING.get(player.getId());
        }
        if (s == null) {
            s = locomotionState(player, base);
        }
        if (s != null) {
            float t = player.level().getGameTime() - s.startGameTime + partialTick;
            t *= locomotionTimeScale(player, s);
            if (!s.loop && t > s.length) {
                t = s.length;
            }
            applyLocomotionPose(player, s, t, partialTick);
            suppressLocomotionRoot(player, s);
        }
        ParsedModel pose = s != null && !s.weaponAction ? s.actionModel : base;
        RENDER_MODEL.set(pose);
        if (pose != null) {
            // 跟随者的 anim 必须在采样 BedrockPose 之前同步，否则游戏内跟随子骨仍是旧姿态。
            pose.syncFollow();
            RENDER_POSE.set(com.yourname.combatoverhaul.client.bedrock.BedrockPose.fromAnim(pose));
        }
        return pose;
    }

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        net.minecraft.world.entity.LivingEntity entity = event.getEntity();
        State s;
        synchronized (PLAYING) {
            s = PLAYING.get(entity.getId());
        }
        ParsedModel base = baseModelOf(entity);
        if (s == null) {
            s = locomotionState(entity, base);
        }
        if (s == null && base == null) {
            RENDER_MODEL.remove();
            RENDER_BEDROCK.set(Boolean.FALSE);
            RENDER_POSE.remove();
            return;
        }
        if (s != null) {
            float t = entity.level().getGameTime() - s.startGameTime + event.getPartialTick();
            t *= locomotionTimeScale(entity, s);
            if (!s.loop && t > s.length) {
                t = s.length;
            }
            applyLocomotionPose(entity, s, t, event.getPartialTick());
            suppressLocomotionRoot(entity, s);
            if (entity instanceof net.minecraft.world.entity.player.Player && s.actionModel != null) {
                ParsedBone th = s.actionModel.findBone("L_Thigh");
                if (th != null) {
                    com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                            "[AnimState] action={} t={} thighX={} thighY={} thighZ={}",
                            s.actions.get(s.index).name, t, th.animRotX, th.animRotY, th.animRotZ);
                }
            }
        }
        // 姿态来源：实体动作=动作所属模型 JSON；武器动作或未播放=实体自身模型 JSON
        ParsedModel pose = s != null && !s.weaponAction ? s.actionModel : base;
        if (pose == null) {
            RENDER_MODEL.remove();
            RENDER_BEDROCK.set(Boolean.FALSE);
            RENDER_POSE.remove();
            return;
        }
        if (s == null || s.weaponAction) {
            resetAnims(pose); // 该模型的 anim 状态本帧未被 ActionStore.apply 刷新，清掉上次播放的残留
        }
        // 新 Bedrock 渲染：目前只接管玩家（贴图/盔甲层沿用原版 renderer，风险最低）。
        // 其他实体仍由 afterSetupAnim 写 ModelPart 的旧路径渲染。
        RENDER_MODEL.set(pose);
        RENDER_POSE.remove();
        boolean bedrockEntity = false;
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (typeId != null) {
            bedrockEntity = CombatConfig.get().bedrockRenderEntities.contains(typeId.toString());
        }
        RENDER_BEDROCK.set(bedrockPlayerEnabled && bedrockEntity);
        if (RENDER_BEDROCK.get() && !loggedBedrockEnabled) {
            loggedBedrockEnabled = true;
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                    "[CombatOverhaul] 新 Bedrock quad 渲染已接管实体 {}（其余实体/关闭时走旧 ModelPart）", typeId);
        }
        // 姿态应用由 mixin 在原版 setupAnim 之后调用 afterSetupAnim（此处仅登记本帧姿态来源）
        PENDING_POSE.put(entity.getId(), pose);
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        int id = event.getEntity().getId();
        RENDER_MODEL.remove();
        RENDER_BEDROCK.set(Boolean.FALSE);
        RENDER_POSE.remove();
        Map<ModelPart, float[]> rots = FRAME_ROT.remove(id);
        PENDING_POSE.remove(id);
        if (rots != null) {
            for (Map.Entry<ModelPart, float[]> e : rots.entrySet()) {
                ModelPart part = e.getKey();
                float[] p = e.getValue();
                part.xRot = p[0];
                part.yRot = p[1];
                part.zRot = p[2];
            }
        }
    }

    /** 由 {@code LivingEntityRendererMixin} 在原版 setupAnim 之后回调：应用基础姿态与动作姿态 */
    public static void afterSetupAnim(net.minecraft.world.entity.Entity entity, EntityModel<?> delegate) {
        ParsedModel pose = PENDING_POSE.remove(entity.getId());
        if (pose == null) {
            return;
        }
        Map<String, ModelPart> parts = partsOf(delegate);
        if (parts == null) {
            return;
        }
        // 12 段柔化 body 由 BedrockRenderer 绘制；这里把分段姿态聚合到原版 6 部件，
        // 让盔甲/外层 HumanoidModel 仍然跟随身体，而不是停在默认 T-pose。
        if (pose.entityId != null && (pose.entityId.startsWith("combatoverhaul:humanoid")
                || pose.entityId.equals("combatoverhaul:skinned_player_er"))) {
            applySoftAggregate(pose, parts);
        }
        com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                "[AnimApply] entity={} poseBones={} parts={} firstBone={}",
                entity.getId(), pose.bones.size(), parts.size(),
                pose.bones.isEmpty() ? "-" : pose.bones.get(0).name);
        syncHierarchy(pose, delegate, parts);
        // 追踪：动作带 track_pitch 标签时，按视线俯仰角（钳制 ±maxPitchOffset）叠加俯仰偏移
        State trackState;
        synchronized (PLAYING) {
            trackState = PLAYING.get(entity.getId());
        }
        ActionStore.Action trackAction = trackState != null ? trackState.actions.get(trackState.index) : null;
        float pitchRad = trackAction != null ? trackPitchRadOf(trackAction, entity) : 0f;
        Map<ModelPart, float[]> savedRot = new IdentityHashMap<>();
        FRAME_ROT.put(entity.getId(), savedRot);
        for (ParsedBone bone : pose.bones) {
            ModelPart part = parts.get(bone.name);
            if (part == null) {
                part = parts.get(pose.semanticName(bone.name));
            }
            if (part == null) {
                continue;
            }
            savedRot.put(part, new float[]{part.xRot, part.yRot, part.zRot});
            applyBone(pose, part, bone);
            if (bone.trackPitch && trackAction != null) {
                part.xRot += pitchRad; // 与原版 head.xRot = getXRot 同向：向上看=上抬
            }
        }
        // 武器动作叠加：武器骨骼名与实体部件同名时生效（可见的挥臂等）
        State s = trackState;
        if (s != null && s.weaponAction) {
            for (ParsedBone bone : s.actionModel.bones) {
                ModelPart part = parts.get(bone.name);
                if (part == null
                        || (bone.animRotX == 0 && bone.animRotY == 0 && bone.animRotZ == 0
                        && bone.animPosX == 0 && bone.animPosY == 0 && bone.animPosZ == 0
                        && bone.animScaleX == 1 && bone.animScaleY == 1 && bone.animScaleZ == 1)) {
                    continue;
                }
                savedRot.putIfAbsent(part, new float[]{part.xRot, part.yRot, part.zRot});
                part.xRot += bone.animRotX;
                part.yRot += bone.animRotY;
                part.zRot += bone.animRotZ;
                if (bone.trackPitch) {
                    part.xRot += pitchRad;
                }
                part.x += bone.animPosX;
                part.y += bone.animPosY;
                part.z += bone.animPosZ;
                part.xScale *= bone.animScaleX;
                part.yScale *= bone.animScaleY;
                part.zScale *= bone.animScaleZ;
            }
        }
        // 新 renderer 需要“最终局部变换”：从 ModelPart 采样，包含原版 setupAnim 的走路/摆动/俯仰，
        // 而不是只用 ParsedModel 的 base+action（否则自绘身体不会走路，盔甲层却会动）。
        if (RENDER_BEDROCK.get()) {
            com.yourname.combatoverhaul.client.bedrock.BedrockPose bp =
                    new com.yourname.combatoverhaul.client.bedrock.BedrockPose().absolute();
            for (ParsedBone bone : pose.bones) {
                ModelPart part = parts.get(bone.name);
                if (part == null) {
                    continue;
                }
                com.yourname.combatoverhaul.client.bedrock.BedrockPose.BonePose p = bp.getOrCreate(bone.name);
                p.position.set(part.x, part.y, part.z);
                p.rotationRad.set(part.xRot, part.yRot, part.zRot);
                p.scale.set(part.xScale, part.yScale, part.zScale);
            }
            RENDER_POSE.set(bp);
        }
    }

    /** 追踪俯仰偏移（弧度）：视线俯仰角钳制 ±maxPitchOffset（上抬=负，与原版 ModelPart.xRot 同向） */
    private static float trackPitchRadOf(ActionStore.Action action, Entity entity) {
        if (!action.trackPitch) {
            return 0f;
        }
        float pitch = net.minecraft.util.Mth.clamp(entity.getXRot(), -action.maxPitchOffset, action.maxPitchOffset);
        return (float) Math.toRadians(pitch);
    }

    /**
     * 应用单根骨骼：旋转 = 原版 setupAnim 结果 +（JSON 基础旋转 − 原版初始姿态）
     * + 动作旋转增量——保留原版程序动画（走路/摆动），JSON 与动作在其上叠加；
     * 位置 = JSON 枢轴 + 动作位移（原版程序动画不改位置）；缩放 = JSON 缩放 × 动作缩放。
     */
    private static void applyBone(ParsedModel pose, ModelPart part, ParsedBone bone) {
        PartPose init = part.getInitialPose();
        part.xRot += bone.rotX - init.xRot + bone.animRotX;
        part.yRot += bone.rotY - init.yRot + bone.animRotY;
        part.zRot += bone.rotZ - init.zRot + bone.animRotZ;
        Map<String, float[]> fixes = ROOT_PIVOT_FIX.get(pose);
        float[] fix = fixes != null ? fixes.get(bone.name) : null;
        part.x = (fix != null ? fix[0] : bone.pivotX) + bone.animPosX;
        part.y = (fix != null ? fix[1] : bone.pivotY) + bone.animPosY;
        part.z = (fix != null ? fix[2] : bone.pivotZ) + bone.animPosZ;
        part.xScale = bone.scaleX * bone.animScaleX;
        part.yScale = bone.scaleY * bone.animScaleY;
        part.zScale = bone.scaleZ * bone.animScaleZ;
    }

    /**
     * 把 ModelPart children 调整为 JSON 里的父子关系（每份 JSON 仅执行一次）。
     * 编辑器「挂接/脱离」后的层级在游戏内同样生效：旋转上级会带动整个子树。
     * JSON 根骨骼挂到模型根部件下（其 pivot 是模型空间值，按根部件初始姿态补偿）。
     * 非层级模型（根部件不可知）跳过。
     */
    @SuppressWarnings("unchecked")
    private static void syncHierarchy(ParsedModel pose, EntityModel<?> model, Map<String, ModelPart> parts) {
        ModelPart rootPart = model instanceof HierarchicalModel<?> hm ? hm.root() : null;
        if (rootPart == null) {
            return;
        }
        if (HIERARCHY_SYNCED.get(rootPart) == pose) {
            return;
        }
        HIERARCHY_SYNCED.put(rootPart, pose);
        Map<String, float[]> fixes = new HashMap<>();
        ROOT_PIVOT_FIX.put(pose, fixes);
        // 每个 part 当前的 children 表（反查现父级用）
        Map<ModelPart, Map<String, ModelPart>> childMaps = new IdentityHashMap<>();
        for (ModelPart p : parts.values()) {
            Map<String, ModelPart> cm = (Map<String, ModelPart>) readTypedField(p, Map.class);
            if (cm != null) {
                childMaps.put(p, cm);
            }
        }
        Map<String, ModelPart> rootMap = childMaps.get(rootPart);
        if (rootMap == null) {
            return;
        }
        for (ParsedBone bone : pose.bones) {
            ModelPart part = parts.get(bone.name);
            if (part == null || part == rootPart) {
                continue;
            }
            ModelPart want = bone.parent != null ? parts.get(bone.parent.name) : rootPart;
            Map<String, ModelPart> wantMap = childMaps.get(want);
            if (wantMap == null) {
                continue;
            }
            Map<String, ModelPart> cur = null;
            for (Map<String, ModelPart> cm : childMaps.values()) {
                if (cm.containsValue(part)) {
                    cur = cm;
                    break;
                }
            }
            if (cur == wantMap && wantMap.get(bone.name) == part) {
                continue;
            }
            if (cur != null) {
                cur.values().removeIf(p -> p == part);
            }
            wantMap.put(bone.name, part);
            if (bone.parent == null) {
                // JSON 根骨骼：编辑器算出的 pivot 是模型空间值，
                // 挂到根部件下需除掉根部件自身的基础变换（初始姿态，通常恒等）
                PartPose rp = rootPart.getInitialPose();
                Matrix4f inv = new Matrix4f()
                        .translate(rp.x, rp.y, rp.z)
                        .rotateZ(rp.zRot)
                        .rotateY(rp.yRot)
                        .rotateX(rp.xRot)
                        .invert();
                Vector3f local = inv.transformPosition(new Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ));
                fixes.put(bone.name, new float[]{local.x, local.y, local.z});
            }
        }
    }

    /** 实体自身的模型 JSON（无导出文件返回 null；ActionCache 内部缓存） */
    public static String playerModelPath(net.minecraft.world.entity.player.Player player) {
        return isSlimPlayer(player) ? "player_slim" : "player";
    }

    /** 客户端状态指令：当前玩家会走自定义渲染还是原版回退。 */
    public static String playerRenderStatus(net.minecraft.world.entity.player.Player player) {
        String path = playerModelPath(player);
        ActionCache.Entry e = ActionCache.get(ActionCache.keyFor("minecraft", path, false));
        if (e == null || e.model == null) {
            return "玩家模型：未加载 minecraft:" + path + " -> 原版渲染回退\n"
                    + "先 /combatoverhaul open minecraft:" + path
                    + " 自动蒙皮并保存模型";
        }
        ParsedModel m = e.model;
        int verts = m.skinnedMesh == null ? 0 : m.skinnedMesh.vertices.size();
        int weights = m.skinnedMesh == null ? 0 : m.skinnedMesh.weightValues.size();
        return "玩家模型：minecraft:" + path
                + "\n骨骼=" + m.bones.size()
                + " 蒙皮网格顶点=" + verts
                + " 权重行=" + weights
                + "\nBedrock 玩家渲染开关=" + (bedrockPlayerEnabled ? "开" : "关")
                + "\n状态=" + (m.skinnedMesh != null ? "会替换原版 PlayerRenderer" : "无 skinnedMesh -> 原版回退");
    }

    private static ParsedModel baseModelOf(net.minecraft.world.entity.LivingEntity entity) {
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (typeId == null) {
            return null;
        }
        // 玩家：使用扫描保存的 minecraft:player / player_slim；没有 skinnedMesh 时调用方回退原版。
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            String path = playerModelPath(player);
            ActionCache.Entry pe = ActionCache.get(ActionCache.keyFor("minecraft", path, false));
            return pe != null && pe.model != null && pe.model.skinnedMesh != null
                    ? pe.model : null;
        }
        ActionCache.Entry e = ActionCache.get(ActionCache.keyFor(typeId.getNamespace(), typeId.getPath(), false));
        return e != null ? e.model : null;
    }

    public static boolean isSlimPlayer(net.minecraft.world.entity.LivingEntity entity) {
        try {
            var renderer = net.minecraft.client.Minecraft.getInstance()
                    .getEntityRenderDispatcher().getRenderer(entity);
            if (renderer instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> ler
                    && ler.getModel() instanceof net.minecraft.client.model.HumanoidModel<?> hm
                    && hm.rightArm != null) {
                for (java.lang.reflect.Field f : net.minecraft.client.model.geom.ModelPart.class.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object value = f.get(hm.rightArm);
                    if (value instanceof java.util.List<?> list && !list.isEmpty()
                            && list.get(0) instanceof net.minecraft.client.model.geom.ModelPart.Cube cube) {
                        return cube.maxX - cube.minX < 3.5f;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }

    private static void resetAnims(ParsedModel model) {
        for (ParsedBone b : model.bones) {
            b.resetAnim();
        }
    }

    /**
     * 根部件 → 目标部件的渲染路径（含两端，未命中返回 null）。
     * 游戏内手持渲染用：路径顺序即渲染递归顺序，逐个应用 translateAndRotate 可得目标骨骼帧。
     */
    public static List<ModelPart> partPath(EntityModel<?> model, String partName) {
        if (model == null || partName == null) {
            return null;
        }
        List<ModelPart> roots = new ArrayList<>();
        if (model instanceof HierarchicalModel<?> hm && hm.root() != null) {
            roots.add(hm.root());
        } else {
            collectFieldParts(model, roots);
        }
        List<ModelPart> path = new ArrayList<>();
        for (ModelPart root : roots) {
            if (findPath(root, partName, path)) {
                return path;
            }
            path.clear();
        }
        return null;
    }

    private static boolean findPath(ModelPart part, String name, List<ModelPart> path) {
        path.add(part);
        Map<?, ?> children = (Map<?, ?>) readTypedField(part, Map.class);
        if (children != null) {
            for (Map.Entry<?, ?> e : children.entrySet()) {
                if (e.getValue() instanceof ModelPart child) {
                    if (name.equals(e.getKey()) && child == part) {
                        continue;
                    }
                    if (name.equals(e.getKey())) {
                        path.add(child);
                        return true;
                    }
                    if (findPath(child, name, path)) {
                        return true;
                    }
                }
            }
        }
        path.remove(path.size() - 1);
        return false;
    }

    /** 名称 → ModelPart 表（按模型实例缓存，反射读 children 私有字段，与混淆映射无关） */
    /** 12 段 rig 的动画姿态聚合到 PlayerModel 的 6 个部件，供盔甲层使用。 */
    private static void applySoftAggregate(ParsedModel pose, Map<String, ModelPart> parts) {
        ModelPart body = parts.get("body");
        ModelPart head = parts.get("head");
        ModelPart rArm = parts.get("right_arm");
        ModelPart lArm = parts.get("left_arm");
        ModelPart rLeg = parts.get("right_leg");
        ModelPart lLeg = parts.get("left_leg");
        if (body == null || head == null || rArm == null || lArm == null || rLeg == null || lLeg == null) {
            return;
        }
        ParsedBone root = pose.findBone("root");
        if (root != null) {
            body.xRot += root.animRotX;
            body.yRot += root.animRotY;
            body.zRot += root.animRotZ;
        }
        // skinned_player_er：ER/Pelvis 骨名。先把原版 setupAnim 的走路/手臂 swing 清零，
        // 再写入 16 骨聚合姿态，否则武器层会继续跟原版手臂动画“自己动”。
        if (pose.findBone("Pelvis") != null) {
            body.xRot = body.yRot = body.zRot = 0f;
            head.xRot = head.yRot = head.zRot = 0f;
            rArm.xRot = rArm.yRot = rArm.zRot = 0f;
            lArm.xRot = lArm.yRot = lArm.zRot = 0f;
            rLeg.xRot = rLeg.yRot = rLeg.zRot = 0f;
            lLeg.xRot = lLeg.yRot = lLeg.zRot = 0f;
            if (root != null) {
                body.xRot = root.animRotX;
                body.yRot = root.animRotY;
                body.zRot = root.animRotZ;
            }
            for (String n : new String[]{"Pelvis", "Spine", "Spine1", "Spine2"}) {
                ParsedBone b = pose.findBone(n);
                if (b != null) {
                    body.xRot += b.animRotX;
                    body.yRot += b.animRotY;
                    body.zRot += b.animRotZ;
                }
            }
            for (String n : new String[]{"Neck", "Head"}) {
                ParsedBone b = pose.findBone(n);
                if (b != null) {
                    head.xRot += b.animRotX;
                    head.yRot += b.animRotY;
                    head.zRot += b.animRotZ;
                }
            }
            for (int side = 0; side < 2; side++) {
                String[] armBones = side == 0
                        ? new String[]{"R_Clavicle", "R_UpperArm", "R_Forearm"}
                        : new String[]{"L_Clavicle", "L_UpperArm", "L_Forearm"};
                String[] legBones = side == 0
                        ? new String[]{"R_Thigh", "R_Calf"} : new String[]{"L_Thigh", "L_Calf"};
                ModelPart arm = side == 0 ? rArm : lArm;
                ModelPart leg = side == 0 ? rLeg : lLeg;
                for (String n : armBones) {
                    ParsedBone b = pose.findBone(n);
                    if (b != null) {
                        arm.xRot += b.animRotX;
                        arm.yRot += b.animRotY;
                        arm.zRot += b.animRotZ;
                    }
                }
                for (String n : legBones) {
                    ParsedBone b = pose.findBone(n);
                    if (b != null) {
                        leg.xRot += b.animRotX;
                        leg.yRot += b.animRotY;
                        leg.zRot += b.animRotZ;
                    }
                }
            }
            return;
        }
        for (String n : new String[]{"body_1", "body_2", "body_3"}) {
            ParsedBone b = pose.findBone(n);
            if (b != null) {
                body.xRot += b.animRotX;
                body.yRot += b.animRotY;
                body.zRot += b.animRotZ;
            }
        }
        ParsedBone h = pose.findBone("head");
        if (h != null) {
            head.xRot += h.animRotX;
            head.yRot += h.animRotY;
            head.zRot += h.animRotZ;
        }
        for (int side = 0; side < 2; side++) {
            ModelPart arm = side == 0 ? rArm : lArm;
            ModelPart leg = side == 0 ? rLeg : lLeg;
            String armPrefix = side == 0 ? "right_arm_" : "left_arm_";
            String legPrefix = side == 0 ? "right_leg_" : "left_leg_";
            for (String suffix : new String[]{"1", "2"}) {
                ParsedBone ab = pose.findBone(armPrefix + suffix);
                if (ab != null) {
                    arm.xRot += ab.animRotX;
                    arm.yRot += ab.animRotY;
                    arm.zRot += ab.animRotZ;
                }
                ParsedBone lb = pose.findBone(legPrefix + suffix);
                if (lb != null) {
                    leg.xRot += lb.animRotX;
                    leg.yRot += lb.animRotY;
                    leg.zRot += lb.animRotZ;
                }
            }
        }
    }

    private static Map<String, ModelPart> partsOf(EntityModel<?> model) {
        if (model == null) {
            return null;
        }
        Map<String, ModelPart> cached = PART_CACHE.get(model);
        if (cached != null) {
            return cached;
        }
        Map<String, ModelPart> out = new HashMap<>();
        IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();
        List<ModelPart> roots = new ArrayList<>();
        if (model instanceof HierarchicalModel<?> hm && hm.root() != null) {
            roots.add(hm.root());
        } else {
            collectFieldParts(model, roots);
        }
        for (ModelPart root : roots) {
            walkPart(root, "root", out, visited);
        }
        // PlayerModel/HumanoidModel 的 root() 通常只有一个根部件；head/body/arms/legs
        // 是独立字段，必须额外收集，否则动作只能匹配到 root，视觉上完全不播放。
        collectFieldPartMap(model, out);
        if (out.isEmpty()) {
            return null;
        }
        PART_CACHE.put(model, out);
        return out;
    }

    private static void walkPart(ModelPart part, String name, Map<String, ModelPart> out,
                                 IdentityHashMap<ModelPart, Boolean> visited) {
        if (visited.put(part, Boolean.TRUE) != null) {
            return;
        }
        out.put(name, part);
        Map<?, ?> children = (Map<?, ?>) readTypedField(part, Map.class);
        if (children != null) {
            for (Map.Entry<?, ?> e : children.entrySet()) {
                if (e.getKey() instanceof String childName && e.getValue() instanceof ModelPart child) {
                    walkPart(child, childName, out, visited);
                }
            }
        }
    }

    private static void collectFieldPartMap(Object holder, Map<String, ModelPart> out) {
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.get(holder) instanceof ModelPart part) {
                        out.putIfAbsent(fieldNameToSemantic(f.getName()), part);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
    }

    /** PlayerModel 字段名 → 动作语义名：rightArm → right_arm。 */
    private static String fieldNameToSemantic(String field) {
        if (field == null) {
            return "root";
        }
        return switch (field) {
            case "rightArm" -> "right_arm";
            case "leftArm" -> "left_arm";
            case "rightLeg" -> "right_leg";
            case "leftLeg" -> "left_leg";
            case "rightSleeve" -> "right_sleeve";
            case "leftSleeve" -> "left_sleeve";
            case "rightPants" -> "right_pants";
            case "leftPants" -> "left_pants";
            default -> field;
        };
    }

    private static void collectFieldParts(Object holder, List<ModelPart> out) {
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!ModelPart.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.get(holder) instanceof ModelPart part) {
                        out.add(part);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
    }

    private static Object readTypedField(Object holder, Class<?> type) {
        for (Class<?> k = holder.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!type.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    return f.get(holder);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 判定框调试渲染

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || !CombatConfig.get().debugHitboxes) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        List<State> active;
        List<net.minecraft.world.entity.LivingEntity> owners = new ArrayList<>();
        synchronized (PLAYING) {
            if (PLAYING.isEmpty()) {
                return;
            }
            active = new ArrayList<>();
            for (Map.Entry<Integer, State> e : PLAYING.entrySet()) {
                Entity entity = mc.level.getEntity(e.getKey());
                if (entity instanceof net.minecraft.world.entity.LivingEntity le && le.isAlive()) {
                    active.add(e.getValue());
                    owners.add(le);
                }
            }
        }
        if (active.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Matrix4f mat = pose.last().pose();

        for (int i = 0; i < active.size(); i++) {
            State s = active.get(i);
            net.minecraft.world.entity.LivingEntity entity = owners.get(i);
            float t = entity.level().getGameTime() - s.startGameTime + event.getPartialTick();
            if (!s.loop && t > s.length) {
                t = s.length;
            }
            ActionStore.Action action = s.actions.get(s.index);
            if (action.hitboxes.isEmpty()) {
                continue;
            }
            HitboxMath.sampleAnim(s.actionModel, s.actions, s.index, t);
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            // 实体动作：entityModel=动作模型（此前误传 null 导致调试框退化为实体中心）；武器动作：actionModel=武器模型
            float pitchAdd = trackPitchRadOf(action, entity);
            for (ActionStore.HitboxDef hb : action.hitboxes) {
                AABB box = HitboxMath.computeBox(entity,
                        s.weaponAction ? s.entityModel : s.actionModel,
                        s.weaponAction ? s.actionModel : null,
                        s.weaponAction, hb, pitchAdd);
                if (box == null) {
                    continue;
                }
                // 颜色按判定框名散列区分
                float hue = (hb.name.hashCode() & 0xFF) / 255f;
                LevelRenderer.renderLineBox(pose, lines,
                        box.move(-cam.x, -cam.y, -cam.z), hue, 1 - hue, 0.2f, 1f);
            }
        }
        buffers.endBatch(RenderType.lines());
    }
}
