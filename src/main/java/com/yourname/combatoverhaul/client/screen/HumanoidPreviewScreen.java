package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.client.render.HumanoidMeshRenderer;
import com.yourname.combatoverhaul.client.render.HumanoidViewportRenderer;
import com.yourname.combatoverhaul.client.screen.ui.EditorDraw;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.rig.Rig;
import com.yourname.combatoverhaul.rig.RigStore;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.components.EditBox;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 标准人形查看器：直接渲染 vanilla HumanoidModel，套玩家皮肤，
 * 从动作池选动作，按正常游戏 part 位置叠加原版盔甲。
 */
public final class HumanoidPreviewScreen extends Screen {

    private final String modelPath;
    private final List<ActionStore.Action> actions = new ArrayList<>();
    private final PlayerModel<Player> humanoid;
    private final net.minecraft.client.model.HumanoidArmorModel<Player> armorOuter;
    private final net.minecraft.client.model.HumanoidArmorModel<Player> armorInner;
    private final ResourceLocation skin;
    private final HumanoidViewportRenderer viewport = new HumanoidViewportRenderer();
    private ParsedModel poseModel;
    private int actionIndex = -1;
    private float tick;
    private boolean playing = true;
    private int actionScroll;
    private int listW = 140;
    private boolean helmet = true;
    private boolean chest = true;
    private boolean legs = true;
    private boolean boots = true;
    private float viewYaw = 0f;
    private float viewPitch = 0f;
    private double lastDragX;
    private double lastDragY;
    private boolean viewDrag;
    private float viewZoom = 1.0f;
    private Rig rig;
    private String rigId = "combatoverhaul:humanoid";
    private int jointSel = -1;
    private boolean jointMode;
    private boolean jointDrag;
    private EditBox jointName;
    private EditBox jointParent;
    private EditBox jointSemantic;
    private String jointStatus = "";

    public HumanoidPreviewScreen() {
        super(Component.literal("标准人形 HumanoidModel"));
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        modelPath = p == null ? "player_slim"
                : ClientAnimHandler.playerModelPath(p);
        skin = p instanceof net.minecraft.client.player.AbstractClientPlayer acp
                ? acp.getSkinTextureLocation()
                : new ResourceLocation("minecraft:textures/entity/player/slim/alex.png");
        boolean slim = ClientAnimHandler.isSlimPlayer(p);
        var layer = mc.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER);
        humanoid = new PlayerModel<>(layer, slim);
        var outerLayer = mc.getEntityModels().bakeLayer(
                slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR);
        var innerLayer = mc.getEntityModels().bakeLayer(
                slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR);
        armorOuter = new net.minecraft.client.model.HumanoidArmorModel<>(outerLayer);
        armorInner = new net.minecraft.client.model.HumanoidArmorModel<>(innerLayer);
        // EntityModel 构造器默认 young=true；不关会走 AgeableListModel 的幼年缩放：
        // 头放大 1.5/2、身体缩半、腿缩短，看起来就是幼年僵尸。
        humanoid.young = false;
        armorOuter.young = false;
        armorInner.young = false;
        actions.addAll(ActionStore.load("minecraft", modelPath, false));
        ActionCache.Entry e = ActionCache.get(ActionCache.keyFor("minecraft", modelPath, false));
        poseModel = e == null ? null : e.model;
        if (poseModel != null && poseModel.rigId != null && !poseModel.rigId.isBlank()) {
            rigId = poseModel.rigId;
        }
        try {
            Rig loaded = RigStore.get(new ResourceLocation(rigId));
            rig = loaded == null ? Rig.humanoid() : loaded;
        } catch (RuntimeException ex) {
            rig = Rig.humanoid();
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        listW = Math.min(150, Math.max(108, width / 4));
        int rw = Math.max(112, Math.min(180, width / 3));
        int bx = width - rw + 2;
        addRenderableWidget(Button.builder(Component.literal(playing ? "暂停" : "播放"), b -> {
            playing = !playing;
            b.setMessage(Component.literal(playing ? "暂停" : "播放"));
        }).bounds(bx, 6, rw - 6, 18).build());
        addRenderableWidget(Button.builder(Component.literal("盔" + onOff(helmet)),
                b -> { helmet = !helmet; b.setMessage(Component.literal("盔" + onOff(helmet))); })
                .bounds(bx, 28, rw - 6, 18).build());
        addRenderableWidget(Button.builder(Component.literal("胸" + onOff(chest)),
                b -> { chest = !chest; b.setMessage(Component.literal("胸" + onOff(chest))); })
                .bounds(bx, 50, rw - 6, 18).build());
        addRenderableWidget(Button.builder(Component.literal("腿" + onOff(legs)),
                b -> { legs = !legs; b.setMessage(Component.literal("腿" + onOff(legs))); })
                .bounds(bx, 72, rw - 6, 18).build());
        addRenderableWidget(Button.builder(Component.literal("靴" + onOff(boots)),
                b -> { boots = !boots; b.setMessage(Component.literal("靴" + onOff(boots))); })
                .bounds(bx, 94, rw - 6, 18).build());
        addRenderableWidget(Button.builder(Component.literal("关节"), b -> toggleJointMode())
                .bounds(bx, 116, rw - 6, 18).build());
        addRenderableWidget(Button.builder(Component.literal("加点"), b -> addJoint())
                .bounds(bx, 138, rw - 6, 18).build());
        addRenderableWidget(Button.builder(Component.literal("删点"), b -> deleteJoint())
                .bounds(bx, 160, rw - 6, 18).build());
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> saveJoints())
                .bounds(bx, 182, rw - 6, 18).build());

        jointName = new EditBox(font, 6, height - 88, Math.max(40, listW - 12), 14,
                Component.literal("name"));
        jointParent = new EditBox(font, 6, height - 66, Math.max(40, listW - 12), 14,
                Component.literal("parent"));
        jointSemantic = new EditBox(font, 6, height - 44, Math.max(40, listW - 12), 14,
                Component.literal("semantic"));
        for (EditBox b : new EditBox[]{jointName, jointParent, jointSemantic}) {
            addRenderableWidget(b);
            b.visible = false;
        }
        jointName.setResponder(v -> setJointString(0, v));
        jointParent.setResponder(v -> setJointString(1, v));
        jointSemantic.setResponder(v -> setJointString(2, v));
        setJointWidgetsVisible(false);

        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(4, height - 26, Math.max(40, listW - 8), 20).build());
    }

    private static String onOff(boolean b) {
        return b ? "开" : "关";
    }

    private Rig.Point joint() {
        if (rig == null || rig.points.isEmpty()) {
            return null;
        }
        jointSel = Math.max(0, Math.min(jointSel, rig.points.size() - 1));
        return rig.points.get(jointSel);
    }

    private void toggleJointMode() {
        jointMode = !jointMode;
        setJointWidgetsVisible(jointMode);
        if (jointMode) {
            jointSel = rig.points.isEmpty() ? -1 : 0;
            syncJointFields();
        }
    }

    private void setJointWidgetsVisible(boolean visible) {
        if (jointName != null) {
            jointName.visible = visible;
            jointParent.visible = visible;
            jointSemantic.visible = visible;
        }
    }

    private void syncJointFields() {
        Rig.Point p = joint();
        if (p == null) {
            return;
        }
        if (jointName != null && !jointName.isFocused()) {
            jointName.setValue(p.name == null ? "" : p.name);
        }
        if (jointParent != null && !jointParent.isFocused()) {
            jointParent.setValue(p.parent == null ? "" : p.parent);
        }
        if (jointSemantic != null && !jointSemantic.isFocused()) {
            jointSemantic.setValue(p.semantic == null ? "" : p.semantic);
        }
    }

    private void setJointString(int which, String text) {
        Rig.Point p = joint();
        if (p == null) {
            return;
        }
        String v = text == null ? "" : text.trim();
        if (which == 0) {
            if (v.isBlank() || (!v.equals(p.name) && rig.byName.containsKey(v))) {
                return;
            }
            String old = p.name;
            rig.byName.remove(old);
            p.name = v;
            rig.byName.put(v, p);
            for (Rig.Point other : rig.points) {
                if (old.equals(other.parent)) {
                    other.parent = v;
                }
            }
        } else if (which == 1) {
            if (v.isBlank() || rig.byName.containsKey(v)) {
                p.parent = v;
            }
        } else {
            p.semantic = v;
        }
    }

    private void addJoint() {
        if (rig == null) {
            return;
        }
        String name = "joint_" + (rig.points.size() + 1);
        while (rig.byName.containsKey(name)) {
            name += "_";
        }
        Rig.Point p = new Rig.Point();
        p.name = name;
        Rig.Point parent = joint();
        p.parent = parent == null ? "" : parent.name;
        p.pivot = parent == null ? new float[]{0, 0, 0}
                : new float[]{parent.pivot[0], parent.pivot[1] + 1f, parent.pivot[2]};
        rig.points.add(p);
        rig.byName.put(name, p);
        jointSel = rig.points.size() - 1;
        syncJointFields();
        jointStatus = "已添加 " + name;
    }

    private void deleteJoint() {
        if (rig == null || rig.points.isEmpty()) {
            return;
        }
        Rig.Point removed = joint();
        if (removed == null) {
            return;
        }
        rig.points.remove(jointSel);
        rig.byName.remove(removed.name);
        for (Rig.Point p : rig.points) {
            if (removed.name.equals(p.parent)) {
                p.parent = "";
            }
        }
        jointSel = Math.max(0, Math.min(jointSel, rig.points.size() - 1));
        syncJointFields();
        jointStatus = "已删除 " + removed.name;
    }

    private void saveJoints() {
        try {
            jointStatus = RigStore.save(new ResourceLocation(rigId), rig)
                    ? "已保存 " + rigId : "保存失败";
        } catch (RuntimeException e) {
            jointStatus = "rig id 无效";
        }
    }

    private double viewScale() {
        return Math.min(width, height) * 1.7 / 24.0 * viewZoom * 3.5;
    }

    private double viewCx() {
        return width * 0.56;
    }

    private double viewCy() {
        return height * 0.54;
    }

    /** 与 HumanoidMeshRenderer 相同的相机投影。 */
    private float[] projectJoint(Rig.Point p, double cx, double cy, double scale) {
        float x = p.pivot[0] / 16f;
        float y = p.pivot[1] / 16f - 0.5f;
        float z = p.pivot[2] / 16f;
        org.joml.Quaternionf rot = new org.joml.Quaternionf()
                .rotationY((float) Math.toRadians(viewYaw))
                .rotateX((float) Math.toRadians(viewPitch));
        org.joml.Vector3f v = new org.joml.Vector3f(x, y, z);
        rot.transform(v);
        return new float[]{(float) (cx + v.x * scale), (float) (cy + v.y * scale)};
    }

    @Override
    public void tick() {
        if (playing && actionIndex >= 0 && actionIndex < actions.size()) {
            ActionStore.Action a = actions.get(actionIndex);
            tick += 1f;
            if (tick > a.lengthTicks) {
                tick = 0;
            }
        }
    }

    private void syncPose() {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p != null) {
            // 基础层：没有对应自定义动画时保留原版 HumanoidModel 的走路/待机程序姿态。
            humanoid.setupAnim(p, p.walkAnimation.position(), p.walkAnimation.speed(),
                    p.tickCount + tick, p.getYHeadRot() - p.yBodyRot, p.getXRot());
        }
        if (poseModel == null) {
            return;
        }
        ActionStore.Action action = actionIndex >= 0 && actionIndex < actions.size()
                ? actions.get(actionIndex) : null;
        if (action != null) {
            ActionStore.apply(actions, actionIndex, tick, poseModel);
        }
        if (action == null) {
            return;
        }
        copyIfAnimated(action, "head", humanoid.head);
        copyIfAnimated(action, "body", humanoid.body);
        copyIfAnimated(action, "right_arm", humanoid.rightArm);
        copyIfAnimated(action, "left_arm", humanoid.leftArm);
        copyIfAnimated(action, "right_leg", humanoid.rightLeg);
        copyIfAnimated(action, "left_leg", humanoid.leftLeg);
        humanoid.hat.xRot = humanoid.head.xRot;
        humanoid.hat.yRot = humanoid.head.yRot;
        humanoid.hat.zRot = humanoid.head.zRot;
        humanoid.jacket.xRot = humanoid.body.xRot;
        humanoid.jacket.yRot = humanoid.body.yRot;
        humanoid.jacket.zRot = humanoid.body.zRot;
        humanoid.rightSleeve.xRot = humanoid.rightArm.xRot;
        humanoid.rightSleeve.yRot = humanoid.rightArm.yRot;
        humanoid.rightSleeve.zRot = humanoid.rightArm.zRot;
        humanoid.leftSleeve.xRot = humanoid.leftArm.xRot;
        humanoid.leftSleeve.yRot = humanoid.leftArm.yRot;
        humanoid.leftSleeve.zRot = humanoid.leftArm.zRot;
        humanoid.rightPants.xRot = humanoid.rightLeg.xRot;
        humanoid.rightPants.yRot = humanoid.rightLeg.yRot;
        humanoid.rightPants.zRot = humanoid.rightLeg.zRot;
        humanoid.leftPants.xRot = humanoid.leftLeg.xRot;
        humanoid.leftPants.yRot = humanoid.leftLeg.yRot;
        humanoid.leftPants.zRot = humanoid.leftLeg.zRot;
    }

    private void copyIfAnimated(ActionStore.Action action, String semantic,
                                net.minecraft.client.model.geom.ModelPart part) {
        if (animates(action, semantic)) {
            copy(semantic, part);
        }
    }

    private boolean animates(ActionStore.Action action, String semantic) {
        if (action == null || poseModel == null) {
            return false;
        }
        ParsedBone target = poseModel.findBone(semantic);
        String targetName = target == null ? semantic : target.name;
        for (ActionStore.Keyframe kf : action.keyframes) {
            for (String bone : kf.bones.keySet()) {
                ParsedBone b = poseModel.findBone(bone);
                if ((b != null && b.name.equals(targetName)) || bone.equals(semantic)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void copy(String semantic, net.minecraft.client.model.geom.ModelPart part) {
        ParsedBone b = poseModel == null ? null : poseModel.findBone(semantic);
        if (b != null) {
            part.xRot = b.fx();
            part.yRot = b.fy();
            part.zRot = b.fz();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(0, 0, width, height, 0xEE17191F);
        g.fill(0, 0, listW, height, 0xFF20242C);
        g.drawString(font, "标准人形 HumanoidModel", 8, 8, 0xFFFFFFFF, false);
        g.drawString(font, modelPath, 8, 22, 0xFF9FA8B8, false);
        int y = 42;
        for (int i = 0; i < actions.size(); i++) {
            if (i < actionScroll) {
                continue;
            }
            if (y > height - 50) {
                break;
            }
            ActionStore.Action a = actions.get(i);
            boolean sel = i == actionIndex;
            g.fill(4, y, listW - 4, y + 16, sel ? 0xFF3A4A6A : 0x00000000);
            g.drawString(font, (sel ? "> " : "  ") + a.name + "  " + (int) a.lengthTicks + "t",
                    8, y + 4, sel ? 0xFFFFFFFF : 0xFFB8C0D0, false);
            y += 17;
        }
        g.drawString(font, "点击动作池；滚轮翻页", 8, height - 18, 0xFF6F7784, false);

        syncPose();
        int vx0 = listW + 6;
        int vy0 = 34;
        int vx1 = width - Math.max(112, Math.min(180, width / 3)) + 2;
        int vy1 = height - 36;
        List<HumanoidViewportRenderer.Layer> layers = new ArrayList<>();
        layers.add(new HumanoidViewportRenderer.Layer(humanoid, skin));
        if (helmet || chest || legs || boots) {
            syncArmorVisibility();
            layers.add(new HumanoidViewportRenderer.Layer(armorOuter,
                    new ResourceLocation("minecraft:textures/models/armor/diamond_layer_1.png")));
            layers.add(new HumanoidViewportRenderer.Layer(armorInner,
                    new ResourceLocation("minecraft:textures/models/armor/diamond_layer_2.png")));
        }
        g.flush();
        viewport.render(g, vx0, vy0, vx1 - vx0, vy1 - vy0,
                viewYaw, viewPitch, viewZoom, layers);
        g.flush();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (jointMode) {
            // 关节线下一阶段直接画进 RenderTarget；这里先保留状态显示。
            g.drawString(font, "关节模式（3D overlay 待接）", listW + 8, height - 34,
                    0xFFFFCC66, false);
        }
        EditorDraw.vline(g, listW, 0, height, 0xFF3A4250);
        g.drawString(font, "动作 tick: " + (int) tick, listW + 8, height - 18, 0xFF9FA8B8, false);
        if (actionIndex >= 0 && actionIndex < actions.size()) {
            g.drawString(font, actions.get(actionIndex).name, listW + 8, height - 32, 0xFFFFFFFF, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
        drawRightLabels(g);
    }

    /** 兜底：某些 GUI 状态下 Button 自带文字会被纹理状态吃掉，这里直接补标签。 */
    private void drawRightLabels(GuiGraphics g) {
        int rw = Math.max(112, Math.min(180, width / 3));
        int bx = width - rw + 2;
        String[] labels = {
                playing ? "暂停" : "播放",
                "盔" + onOff(helmet),
                "胸" + onOff(chest),
                "腿" + onOff(legs),
                "靴" + onOff(boots),
                "关节", "加点", "删点", "保存"
        };
        int[] ys = {6, 28, 50, 72, 94, 116, 138, 160, 182};
        for (int i = 0; i < labels.length; i++) {
            int tw = font.width(labels[i]);
            g.drawString(font, labels[i], bx + (rw - 6 - tw) / 2, ys[i] + 5,
                    0xFFFFFFFF, false);
        }
    }

    private void syncArmorVisibility() {
        // 直接走原版 HumanoidModel.copyPropertiesTo：pivot/旋转/缩放的复制规则
        // 与 HumanoidArmorLayer 完全一致，避免手写 copy 漏字段。
        humanoid.copyPropertiesTo(armorOuter);
        humanoid.copyPropertiesTo(armorInner);
        armorOuter.setAllVisible(false);
        armorOuter.head.visible = helmet;
        armorOuter.body.visible = chest;
        armorOuter.rightArm.visible = chest;
        armorOuter.leftArm.visible = chest;
        armorOuter.rightLeg.visible = boots;
        armorOuter.leftLeg.visible = boots;

        armorInner.setAllVisible(false);
        armorInner.body.visible = legs;
        armorInner.rightLeg.visible = legs;
        armorInner.leftLeg.visible = legs;
    }

    private void copyPart(net.minecraft.client.model.geom.ModelPart src,
                          net.minecraft.client.model.geom.ModelPart outer,
                          net.minecraft.client.model.geom.ModelPart inner) {
        copyPart(src, outer);
        copyPart(src, inner);
    }

    private void copyPart(net.minecraft.client.model.geom.ModelPart src,
                          net.minecraft.client.model.geom.ModelPart dst) {
        if (src == null || dst == null) {
            return;
        }
        dst.x = src.x;
        dst.y = src.y;
        dst.z = src.z;
        dst.xRot = src.xRot;
        dst.yRot = src.yRot;
        dst.zRot = src.zRot;
        dst.xScale = src.xScale;
        dst.yScale = src.yScale;
        dst.zScale = src.zScale;
    }

    private void renderJoints(GuiGraphics g, double cx, double cy, double scale) {
        if (rig == null) {
            return;
        }
        for (Rig.Point p : rig.points) {
            float[] a = projectJoint(p, cx, cy, scale);
            Rig.Point parent = p.parent == null || p.parent.isBlank() ? null : rig.point(p.parent);
            if (parent != null) {
                float[] b = projectJoint(parent, cx, cy, scale);
                EditorDraw.line(g, (int) a[0], (int) a[1], (int) b[0], (int) b[1], 0xFF3A6A9A);
            }
            boolean sel = p == joint();
            int r = sel ? 4 : 3;
            g.fill((int) a[0] - r, (int) a[1] - r, (int) a[0] + r + 1, (int) a[1] + r + 1,
                    sel ? 0xFFFFFF55 : 0xFF55CCFF);
            if (sel) {
                g.drawString(font, p.name, (int) a[0] + 6, (int) a[1] - 4, 0xFFFFEE88, false);
            }
        }
        if (!jointStatus.isBlank()) {
            g.drawString(font, jointStatus, listW + 8, height - 34, 0xFF80FF80, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (jointMode && rig != null && button == 0) {
            double scale = viewScale();
            for (int i = 0; i < rig.points.size(); i++) {
                float[] a = projectJoint(rig.points.get(i), viewCx(), viewCy(), scale);
                if (Math.hypot(mx - a[0], my - a[1]) <= 8) {
                    jointSel = i;
                    jointDrag = true;
                    syncJointFields();
                    return true;
                }
            }
        }
        if (mx < listW && my >= 42 && actionIndex < actions.size()) {
            int idx = actionScroll + (int) ((my - 42) / 17);
            if (idx >= 0 && idx < actions.size()) {
                actionIndex = idx;
                tick = 0;
                return true;
            }
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button == 0 && mx > listW && my > 70) {
            viewDrag = true;
            lastDragX = mx;
            lastDragY = my;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (jointDrag && button == 0) {
            Rig.Point p = joint();
            if (p != null) {
                double scale = Math.max(1.0, viewScale());
                org.joml.Quaternionf rot = new org.joml.Quaternionf()
                        .rotationY((float) Math.toRadians(viewYaw))
                        .rotateX((float) Math.toRadians(viewPitch));
                org.joml.Vector3f d = new org.joml.Vector3f(
                        (float) (dx / scale), (float) (dy / scale), 0);
                rot.conjugate().transform(d);
                p.pivot[0] += d.x * 16f;
                p.pivot[1] += d.y * 16f;
                p.pivot[2] += d.z * 16f;
            }
            return true;
        }
        if (viewDrag && button == 0) {
            viewYaw += (float) (mx - lastDragX) * 0.8f;
            viewPitch = Math.max(-80f, Math.min(80f,
                    viewPitch + (float) (my - lastDragY) * 0.8f));
            lastDragX = mx;
            lastDragY = my;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (jointDrag && button == 0) {
            jointDrag = false;
            return true;
        }
        if (viewDrag && button == 0) {
            viewDrag = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < listW) {
            actionScroll = Math.max(0, Math.min(Math.max(0, actions.size() - 1),
                    actionScroll - (int) Math.signum(delta)));
            return true;
        }
        viewZoom = Math.max(0.35f, Math.min(3.0f,
                viewZoom * (float) Math.pow(1.12, Math.signum(delta))));
        return true;
    }

    @Override
    public void onClose() {
        viewport.close();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
