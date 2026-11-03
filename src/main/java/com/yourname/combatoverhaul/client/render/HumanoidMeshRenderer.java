package com.yourname.combatoverhaul.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把 vanilla HumanoidModel 的 ModelPart 几何捕获成屏幕空间四边形，
 * 用 GUI 的 MultiBufferSource 画出来。这样查看器显示的是真正的
 * HumanoidModel/PlayerModel part，而不是另一套 ParsedModel 代理。
 */
public final class HumanoidMeshRenderer {

    private HumanoidMeshRenderer() {
    }

    private static final class V {
        float x, y, z, u, v;
    }

    private static final class Quad {
        final V[] v = new V[4];
        float depth;
    }

    private static final class Capture implements VertexConsumer {
        final List<Quad> out = new ArrayList<>();
        Quad cur;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            if (cur == null) {
                cur = new Quad();
            }
            int i = curIndex();
            if (i >= 0 && i < 4) {
                V v = new V();
                v.x = (float) x;
                v.y = (float) y;
                v.z = (float) z;
                cur.v[i] = v;
            }
            return this;
        }

        private int curIndex() {
            if (cur == null) {
                return 0;
            }
            for (int i = 0; i < 4; i++) {
                if (cur.v[i] == null) {
                    return i;
                }
            }
            return 4;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            if (cur != null) {
                int i = curIndex() - 1;
                if (i >= 0 && i < 4 && cur.v[i] != null) {
                    cur.v[i].u = u;
                    cur.v[i].v = v;
                }
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
            if (cur != null && cur.v[0] != null && cur.v[3] != null) {
                out.add(cur);
                cur = null;
            }
        }

    }

    public static void render(GuiGraphics g, HumanoidModel<?> model, ResourceLocation texture,
                              double cx, double cy, double scale) {
        if (model == null || texture == null) {
            return;
        }
        Capture cap = new Capture();
        PoseStack pose = new PoseStack();
        renderPart(model.head, pose, cap);
        if (model instanceof PlayerModel<?> player) {
            renderPart(player.hat, pose, cap);
            renderPart(player.jacket, pose, cap);
            renderPart(player.rightSleeve, pose, cap);
            renderPart(player.leftSleeve, pose, cap);
            renderPart(player.rightPants, pose, cap);
            renderPart(player.leftPants, pose, cap);
        } else {
            renderPart(model.hat, pose, cap);
        }
        renderPart(model.body, pose, cap);
        renderPart(model.rightArm, pose, cap);
        renderPart(model.leftArm, pose, cap);
        renderPart(model.rightLeg, pose, cap);
        renderPart(model.leftLeg, pose, cap);

        if (cap.out.isEmpty()) {
            return;
        }
        for (Quad q : cap.out) {
            float z = 0;
            for (V v : q.v) {
                z += v.z;
            }
            q.depth = z / 4f;
        }
        cap.out.sort(Comparator.comparingDouble(q -> -q.depth));
        RenderSystem.disableDepthTest();
        MultiBufferSource.BufferSource buffers = g.bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        for (Quad q : cap.out) {
            for (int i = 0; i < 4; i++) {
                V v = q.v[i];
                float sx = (float) (cx + v.x * scale);
                float sy = (float) (cy + v.y * scale);
                vc.vertex(sx, sy, 0).color(255, 255, 255, 255)
                        .uv(v.u, v.v).overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
            }
        }
        buffers.endBatch();
        RenderSystem.enableDepthTest();
    }

    private static void renderPart(ModelPart part, PoseStack pose, Capture cap) {
        if (part == null || !part.visible) {
            return;
        }
        part.render(pose, cap, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
    }
}
