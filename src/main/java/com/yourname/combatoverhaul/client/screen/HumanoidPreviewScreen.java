package com.yourname.combatoverhaul.client.screen;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.render.HumanoidMeshRenderer;
import com.yourname.combatoverhaul.client.render.ModelPreviewRenderer;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
    private final ModelPreviewRenderer preview = new ModelPreviewRenderer();
    private ParsedModel poseModel;
    private ParsedModel armorModel;
    private int actionIndex = -1;
    private float tick;
    private boolean playing = true;
    private int actionScroll;
    private int listW = 140;
    private boolean helmet = true;
    private boolean chest = true;
    private boolean legs = true;
    private boolean boots = true;

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
        actions.addAll(ActionStore.load("minecraft", modelPath, false));
        ActionCache.Entry e = ActionCache.get(ActionCache.keyFor("minecraft", modelPath, false));
        poseModel = e == null ? null : e.model;
        if (poseModel != null) {
            preview.skinningEnabled = poseModel.skinnedMesh != null;
            try {
                armorModel = ParsedModel.parse(poseModel.toJson());
                armorModel.skinnedMesh = null;
                armorModel.textureSpec = "minecraft:textures/models/armor/diamond_layer_1.png";
                armorModel.resolvedTexture = new ResourceLocation(armorModel.textureSpec);
            } catch (RuntimeException ignored) {
            }
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
        addRenderableWidget(Button.builder(Component.literal("头盔 " + onOff(helmet)),
                b -> { helmet = !helmet; b.setMessage(Component.literal("头盔 " + onOff(helmet))); })
                .bounds(bx, 28, (rw - 8) / 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("胸甲 " + onOff(chest)),
                b -> { chest = !chest; b.setMessage(Component.literal("胸甲 " + onOff(chest))); })
                .bounds(bx + (rw - 8) / 2 + 2, 28, (rw - 8) / 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("护腿 " + onOff(legs)),
                b -> { legs = !legs; b.setMessage(Component.literal("护腿 " + onOff(legs))); })
                .bounds(bx, 50, (rw - 8) / 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("靴子 " + onOff(boots)),
                b -> { boots = !boots; b.setMessage(Component.literal("靴子 " + onOff(boots))); })
                .bounds(bx + (rw - 8) / 2 + 2, 50, (rw - 8) / 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(bx, height - 26, rw - 6, 20).build());
        if (poseModel != null) {
            preview.fitView(poseModel, listW + 6, 34,
                    width - Math.max(112, Math.min(180, width / 3)) + 2, height - 36);
        }
    }

    private static String onOff(boolean b) {
        return b ? "开" : "关";
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
            if (armorModel != null) {
                ActionStore.apply(actions, actionIndex, tick, armorModel);
            }
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
        double cx = width * 0.56;
        double cy = height * 0.54;
        double scale = Math.min(width, height) * 1.7 / 24.0;
        HumanoidMeshRenderer.render(g, humanoid, skin, cx, cy, scale);
        if (helmet || chest || legs || boots) {
            syncArmorVisibility();
            HumanoidMeshRenderer.render(g, armorOuter,
                    new ResourceLocation("minecraft:textures/models/armor/diamond_layer_1.png"),
                    cx, cy, scale);
            HumanoidMeshRenderer.render(g, armorInner,
                    new ResourceLocation("minecraft:textures/models/armor/diamond_layer_2.png"),
                    cx, cy, scale);
        }
        drawLine(g, listW, 0, listW, height, 0xFF3A4250);
        g.drawString(font, "动作 tick: " + (int) tick, listW + 8, height - 18, 0xFF9FA8B8, false);
        if (actionIndex >= 0 && actionIndex < actions.size()) {
            g.drawString(font, actions.get(actionIndex).name, listW + 8, height - 32, 0xFFFFFFFF, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void syncArmorModelVisibility() {
        if (armorModel == null) {
            return;
        }
        for (ParsedBone b : armorModel.bones) {
            b.visible = false;
        }
        if (helmet) {
            show("head");
            show("hat");
        }
        if (chest) {
            show("body");
            show("right_arm");
            show("left_arm");
        }
        if (legs) {
            show("right_leg");
            show("left_leg");
        }
        if (boots) {
            show("right_leg");
            show("left_leg");
        }
    }

    private void show(String semantic) {
        ParsedBone b = armorModel == null ? null : armorModel.findBone(semantic);
        if (b != null) {
            showTree(b);
        }
    }

    private static void showTree(ParsedBone bone) {
        bone.visible = true;
        for (ParsedBone child : bone.children) {
            showTree(child);
        }
    }

    private void syncArmorVisibility() {
        copyArmor("head", armorOuter.head, armorInner.head);
        copyArmor("body", armorOuter.body, armorInner.body);
        copyArmor("right_arm", armorOuter.rightArm, armorInner.rightArm);
        copyArmor("left_arm", armorOuter.leftArm, armorInner.leftArm);
        copyArmor("right_leg", armorOuter.rightLeg, armorInner.rightLeg);
        copyArmor("left_leg", armorOuter.leftLeg, armorInner.leftLeg);
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

    private void copyArmor(String semantic, net.minecraft.client.model.geom.ModelPart outer,
                           net.minecraft.client.model.geom.ModelPart inner) {
        copy(semantic, outer);
        copy(semantic, inner);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (mx < listW && my >= 42 && actionIndex < actions.size()) {
            int idx = actionScroll + (int) ((my - 42) / 17);
            if (idx >= 0 && idx < actions.size()) {
                actionIndex = idx;
                tick = 0;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < listW) {
            actionScroll = Math.max(0, Math.min(Math.max(0, actions.size() - 1),
                    actionScroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x0 + 1, y1, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
