package com.yourname.combatoverhaul.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Captures vanilla HumanoidModel ModelPart geometry and draws it in a GUI.
 * Skin and armor are captured in the same coordinate space; the caller can
 * pass all layers together so depth sorting is done across them.
 */
public final class HumanoidMeshRenderer {

    private HumanoidMeshRenderer() {
    }

    public record Layer(HumanoidModel<?> model, ResourceLocation texture) {
    }

    private static final class V {
        float x, y, z, u, v;
    }

    private static final class Quad {
        final V[] v = new V[4];
        ResourceLocation texture;
        float depth;
    }

    private static final class Capture implements VertexConsumer {
        final List<Quad> out = new ArrayList<>();
        final ResourceLocation texture;
        Quad cur;

        Capture(ResourceLocation texture) {
            this.texture = texture;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            if (cur == null) {
                cur = new Quad();
                cur.texture = texture;
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

    public static void render(GuiGraphics g, List<Layer> layers,
                              double cx, double cy, double scale,
                              float yawDeg, float pitchDeg) {
        if (layers == null || layers.isEmpty()) {
            return;
        }
        List<Quad> all = new ArrayList<>();
        PoseStack pose = new PoseStack();
        for (Layer layer : layers) {
            if (layer.model() == null || layer.texture() == null) {
                continue;
            }
            Capture cap = new Capture(layer.texture());
            capture(layer.model(), pose, cap);
            all.addAll(cap.out);
        }
        draw(g, all, cx, cy, scale, yawDeg, pitchDeg);
    }

    private static void draw(GuiGraphics g, List<Quad> quads,
                             double cx, double cy, double scale,
                             float yawDeg, float pitchDeg) {
        if (quads.isEmpty()) {
            return;
        }
        Quaternionf rot = new Quaternionf()
                .rotationY((float) Math.toRadians(yawDeg))
                .rotateX((float) Math.toRadians(pitchDeg));
        for (Quad q : quads) {
            float z = 0;
            for (V v : q.v) {
                // ModelPart vertices are in 1/16 block units in 1.20.1.
                Vector3f p = new Vector3f(v.x, v.y - 0.5f, v.z);
                rot.transform(p);
                v.x = p.x;
                v.y = p.y;
                v.z = p.z;
                z += v.z;
            }
            q.depth = z / 4f;
        }
        quads.sort(Comparator.comparingDouble(q -> q.depth));

        RenderSystem.disableDepthTest();
        MultiBufferSource.BufferSource buffers = g.bufferSource();
        ResourceLocation currentTexture = null;
        VertexConsumer vc = null;
        for (Quad q : quads) {
            if (q.texture == null) {
                continue;
            }
            if (!q.texture.equals(currentTexture)) {
                if (vc != null) {
                    buffers.endBatch();
                }
                currentTexture = q.texture;
                vc = buffers.getBuffer(RenderType.entityCutoutNoCull(currentTexture));
            }
            if (vc == null) {
                continue;
            }
            for (V v : q.v) {
                float sx = (float) (cx + v.x * scale);
                float sy = (float) (cy + v.y * scale);
                vc.vertex(sx, sy, 0).color(255, 255, 255, 255)
                        .uv(v.u, v.v).overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
            }
        }
        if (vc != null) {
            buffers.endBatch();
        }
        RenderSystem.enableDepthTest();
    }

    private static void capture(HumanoidModel<?> model, PoseStack pose, Capture cap) {
        capturePart(model.head, pose, cap);
        if (model instanceof PlayerModel<?> player) {
            capturePart(player.hat, pose, cap);
            capturePart(player.jacket, pose, cap);
            capturePart(player.rightSleeve, pose, cap);
            capturePart(player.leftSleeve, pose, cap);
            capturePart(player.rightPants, pose, cap);
            capturePart(player.leftPants, pose, cap);
        } else {
            capturePart(model.hat, pose, cap);
        }
        capturePart(model.body, pose, cap);
        capturePart(model.rightArm, pose, cap);
        capturePart(model.leftArm, pose, cap);
        capturePart(model.rightLeg, pose, cap);
        capturePart(model.leftLeg, pose, cap);
    }

    private static void capturePart(ModelPart part, PoseStack pose, Capture cap) {
        if (part == null || !part.visible) {
            return;
        }
        part.render(pose, cap, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
    }
}
