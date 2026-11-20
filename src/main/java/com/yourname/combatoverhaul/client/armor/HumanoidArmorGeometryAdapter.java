package com.yourname.combatoverhaul.client.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.ArrayList;
import java.util.List;

/**
 * 从已 baked 的 HumanoidModel（含 HumanoidArmorModel / Forge getHumanoidArmorModel）
 * 捕获 rest 姿态几何，转成 ArmorGeometry。
 *
 * <p>调用前应传入未动画化的 model；本适配器只读 part visible/pivot/cube，不修改 pose。
 */
public final class HumanoidArmorGeometryAdapter {

    private HumanoidArmorGeometryAdapter() {
    }

    public static ArmorGeometry capture(String id, String slot, boolean inner,
                                        String texture, HumanoidModel<?> model) {
        ArmorGeometry out = new ArmorGeometry();
        out.id = id == null ? "" : id;
        out.slot = slot == null ? "" : slot;
        out.inner = inner;
        out.texture = texture == null ? "" : texture;
        if (model == null) {
            return out;
        }
        // 盔甲 layer 在渲染前才按槽位隐藏 part；几何捕获必须看完整模型。
        model.setAllVisible(true);

        addBone(out, "head", null, model.head);
        addBone(out, "hat", "head", model.hat);
        addBone(out, "body", null, model.body);
        if (model instanceof PlayerModel<?> player) {
            addBone(out, "jacket", "body", player.jacket);
            addBone(out, "right_arm", null, model.rightArm);
            addBone(out, "left_arm", null, model.leftArm);
            addBone(out, "right_sleeve", "right_arm", player.rightSleeve);
            addBone(out, "left_sleeve", "left_arm", player.leftSleeve);
            addBone(out, "right_leg", null, model.rightLeg);
            addBone(out, "left_leg", null, model.leftLeg);
            addBone(out, "right_pants", "right_leg", player.rightPants);
            addBone(out, "left_pants", "left_leg", player.leftPants);
        } else {
            addBone(out, "right_arm", null, model.rightArm);
            addBone(out, "left_arm", null, model.leftArm);
            addBone(out, "right_leg", null, model.rightLeg);
            addBone(out, "left_leg", null, model.leftLeg);
        }
        return out;
    }

    private static void addBone(ArmorGeometry out, String name, String parent, ModelPart part) {
        if (part == null) {
            return;
        }
        out.bones.add(new ArmorGeometry.Bone(name, parent, part.x, part.y, part.z));
        if (!part.visible) {
            return;
        }
        part.skipDraw = false;
        Capture cap = new Capture(out, name);
        part.render(new PoseStack(), cap, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
    }

    private static final class Capture implements VertexConsumer {
        final ArmorGeometry out;
        final String bone;
        final List<ArmorGeometry.Vertex> cur = new ArrayList<>(4);

        Capture(ArmorGeometry out, String bone) {
            this.out = out;
            this.bone = bone;
        }

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a,
                           float u, float v, int overlay, int light,
                           float nx, float ny, float nz) {
            if (cur.size() < 4) {
                cur.add(new ArmorGeometry.Vertex(x, y, z, u, v, bone));
            }
            if (cur.size() == 4) {
                endVertex();
            }
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            if (cur.size() < 4) {
                cur.add(new ArmorGeometry.Vertex((float) x, (float) y, (float) z, 0, 0, bone));
            }
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            if (!cur.isEmpty()) {
                ArmorGeometry.Vertex vertex = cur.get(cur.size() - 1);
                vertex.u = u;
                vertex.v = v;
            }
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }

        @Override
        public void endVertex() {
            if (cur.size() == 4) {
                int base = out.vertices.size();
                out.vertices.addAll(cur);
                out.faces.add(new ArmorGeometry.Face(base, base + 1, base + 2, base + 3));
            }
            cur.clear();
        }
    }
}
