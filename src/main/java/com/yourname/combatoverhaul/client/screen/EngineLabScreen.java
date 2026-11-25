package com.yourname.combatoverhaul.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.client.render.ModelPreviewRenderer;
import com.yourname.combatoverhaul.client.screen.ui.EditorDraw;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.engine.adapters.ActionClipAdapter;
import com.yourname.combatoverhaul.engine.adapters.SkeletonAdapter;
import com.yourname.combatoverhaul.engine.core.AnimationRuntime;
import com.yourname.combatoverhaul.engine.core.BoneCollider;
import com.yourname.combatoverhaul.engine.core.ColliderRuntime;
import com.yourname.combatoverhaul.engine.core.Pose;
import com.yourname.combatoverhaul.engine.core.Skeleton;
import com.yourname.combatoverhaul.engine.core.SkeletonPose;
import com.yourname.combatoverhaul.engine.core.Transform;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 实验性 Spark 风格引擎实验室：状态机 + Montage + 骨骼碰撞体预览。 */
public final class EngineLabScreen extends Screen {

    private final ParsedModel model;
    private final Skeleton skeleton;
    private final AnimationRuntime runtime;
    private final List<ActionStore.Action> actions = new ArrayList<>();
    private final List<BoneCollider> colliders = new ArrayList<>();
    private final ModelPreviewRenderer preview = new ModelPreviewRenderer();

    private int actionIndex;
    private boolean playing = true;
    private boolean showBones = true;
    private boolean showColliders = true;
    private String status = "";

    public EngineLabScreen(ParsedModel model) {
        super(Component.literal("Engine Lab"));
        this.model = model;
        this.skeleton = SkeletonAdapter.from(model);
        this.runtime = new AnimationRuntime(skeleton);
        if (model != null) {
            for (ParsedBone bone : model.bones) {
                float[] b = bone.cubeBounds();
                if (b == null) {
                    continue;
                }
                BoneCollider c = new BoneCollider(bone.name);
                c.center.set((b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f);
                c.halfExtents.set((b[3] - b[0]) / 2f, (b[4] - b[1]) / 2f, (b[5] - b[2]) / 2f);
                colliders.add(c);
            }
        }
        ActionCache.Entry entry = ActionCache.get(ActionCache.keyFor("minecraft",
                Minecraft.getInstance().player == null ? "player"
                        : ClientAnimHandler.playerModelPath(Minecraft.getInstance().player), false));
        if (entry != null && entry.actions != null) {
            for (ActionStore.Action action : entry.actions) {
                if (action != null && action.keyframes != null && !action.keyframes.isEmpty()) {
                    actions.add(action);
                    runtime.states().add(action.name, ActionClipAdapter.from(action));
                }
            }
        }
        if (!actions.isEmpty()) {
            runtime.states().play(actions.get(0).name, 0f);
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        int x = width - 132;
        addRenderableWidget(Button.builder(Component.literal(playing ? "暂停" : "播放"), b -> {
            playing = !playing;
            b.setMessage(Component.literal(playing ? "暂停" : "播放"));
        }).bounds(x, 12, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一个动作"), b -> nextAction())
                .bounds(x, 38, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("骨骼"), b -> showBones = !showBones)
                .bounds(x, 64, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("碰撞体"), b -> showColliders = !showColliders)
                .bounds(x, 90, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(x, height - 30, 120, 20).build());
        if (model != null) {
            preview.fitView(model, 8, 30, width - 144, height - 42);
        }
    }

    private void nextAction() {
        if (actions.isEmpty()) {
            return;
        }
        actionIndex = (actionIndex + 1) % actions.size();
        ActionStore.Action a = actions.get(actionIndex);
        runtime.states().play(a.name, 3f);
        status = "状态机 -> " + a.name;
    }

    @Override
    public void tick() {
        if (playing) {
            runtime.update(1f);
            applyPose(runtime.sample());
        }
    }

    private void applyPose(Pose pose) {
        if (model == null) {
            return;
        }
        for (ParsedBone bone : model.bones) {
            bone.resetAnim();
        }
        for (Map.Entry<String, Transform> e : pose.bones().entrySet()) {
            ParsedBone bone = model.findBone(e.getKey());
            if (bone == null) {
                continue;
            }
            Transform t = e.getValue();
            bone.animPosX = t.position.x;
            bone.animPosY = t.position.y;
            bone.animPosZ = t.position.z;
            Vector3f euler = t.rotation.getEulerAnglesZYX(new Vector3f());
            bone.animRotX = euler.x;
            bone.animRotY = euler.y;
            bone.animRotZ = euler.z;
            bone.animScaleX = t.scale.x;
            bone.animScaleY = t.scale.y;
            bone.animScaleZ = t.scale.z;
        }
        model.flatten();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(0, 0, width, height, 0xEE151820);
        g.drawString(font, "ENGINE LAB  /  " + (model == null ? "-" : model.entityId),
                8, 8, 0xFFFFFFFF, false);
        g.drawString(font, "Action: " + (actions.isEmpty() ? "-"
                        : actions.get(actionIndex).name) + "   " + status,
                8, 20, 0xFFB8C0D0, false);

        model.flatten();
        int vx0 = 8, vy0 = 30, vx1 = width - 144, vy1 = height - 42;
        preview.render(g, model, vx0, vy0, vx1, vy1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        if (showBones) {
            drawBones(g, vx0, vy0, vx1, vy1);
        }
        if (showColliders) {
            drawColliders(g, vx0, vy0, vx1, vy1);
        }
        g.drawString(font, "Bone " + skeleton.bones().size() + "  Collider " + colliders.size(),
                8, height - 16, 0xFF7F8A99, false);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawBones(GuiGraphics g, int vx0, int vy0, int vx1, int vy1) {
        if (skeleton == null) {
            return;
        }
        SkeletonPose pose = runtime.sampleMatrices();
        float cx = (vx0 + vx1) / 2f;
        float cy = (vy0 + vy1) / 2f;
        for (var b : skeleton.bones().values()) {
            if (b.parent == null || b.parent.isBlank() || skeleton.bone(b.parent) == null) {
                continue;
            }
            Vector3f a = new Vector3f();
            Vector3f c = new Vector3f();
            pose.global(b.parent).getTranslation(a);
            pose.global(b.name).getTranslation(c);
            float[] pa = preview.project(model, a.x, a.y, a.z, cx, cy);
            float[] pc = preview.project(model, c.x, c.y, c.z, cx, cy);
            EditorDraw.line(g, (int) pa[0], (int) pa[1], (int) pc[0], (int) pc[1], 0xFFFFCC66);
            g.fill((int) pc[0] - 2, (int) pc[1] - 2, (int) pc[0] + 2, (int) pc[1] + 2, 0xFF66CCFF);
        }
    }

    private void drawColliders(GuiGraphics g, int vx0, int vy0, int vx1, int vy1) {
        SkeletonPose pose = runtime.sampleMatrices();
        float cx = (vx0 + vx1) / 2f;
        float cy = (vy0 + vy1) / 2f;
        for (BoneCollider local : colliders) {
            BoneCollider world = ColliderRuntime.world(local, pose, local.bone);
            Vector3f[] pts = new Vector3f[8];
            for (int i = 0; i < 8; i++) {
                Vector3f p = new Vector3f(
                        (i & 1) == 0 ? -1 : 1,
                        (i & 2) == 0 ? -1 : 1,
                        (i & 4) == 0 ? -1 : 1);
                p.mul(world.halfExtents);
                world.rotation.transform(p);
                p.add(world.center);
                pts[i] = p;
            }
            int[][] edges = {{0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
                    {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}};
            for (int[] e : edges) {
                float[] a = preview.project(model, pts[e[0]].x, pts[e[0]].y, pts[e[0]].z, cx, cy);
                float[] b = preview.project(model, pts[e[1]].x, pts[e[1]].y, pts[e[1]].z, cx, cy);
                EditorDraw.line(g, (int) a[0], (int) a[1], (int) b[0], (int) b[1], 0x99FF5566);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
