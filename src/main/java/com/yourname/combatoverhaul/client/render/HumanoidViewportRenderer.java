package com.yourname.combatoverhaul.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * 3D 预览视口：把 HumanoidModel / 盔甲层画到离屏 RenderTarget，
 * GUI 只负责显示这张纹理。避免把 3D 模型直接塞进 GUI 投影。
 *
 * <p>架构参考 Epic Fight 的 ModelPreviewer，但代码独立实现。
 */
public final class HumanoidViewportRenderer {

    public record Layer(HumanoidModel<?> model, ResourceLocation texture) {
    }

    private TextureTarget target;
    private int targetW;
    private int targetH;

    public void render(GuiGraphics g, int x, int y, int w, int h, float yaw, float pitch,
                       float zoom, List<Layer> layers) {
        if (w <= 0 || h <= 0 || layers == null || layers.isEmpty()) {
            return;
        }
        ensureTarget(w, h);
        Minecraft mc = Minecraft.getInstance();

        float[] oldColor = RenderSystem.getShaderColor().clone();
        ShaderInstance previousShader = RenderSystem.getShader();
        Matrix4f previousProjection = RenderSystem.getProjectionMatrix();

        mc.getMainRenderTarget().unbindWrite();
        target.setClearColor(0.075f, 0.085f, 0.105f, 1f);
        target.clear(true);
        target.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        Matrix4f projection = new Matrix4f().setPerspective(
                (float) Math.toRadians(45f), (float) w / (float) h, 0.05f, 100f);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.getModelViewStack().pushPose();
        RenderSystem.getModelViewStack().setIdentity();
        RenderSystem.applyModelViewMatrix();

        PoseStack pose = new PoseStack();
        pose.translate(0f, 0.4f, -5.2f / Math.max(0.35f, zoom));
        // HumanoidModel 是 y 向下；实体渲染同样用 (-1,-1,1) 翻到世界空间。
        pose.scale(-1f, -1f, 1f);
        pose.mulPose(Axis.XP.rotationDegrees(-pitch));
        pose.mulPose(Axis.YP.rotationDegrees(180f + yaw));

        MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        for (Layer layer : layers) {
            if (layer.model() == null || layer.texture() == null) {
                continue;
            }
            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(layer.texture()));
            layer.model().renderToBuffer(pose, vc, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
        }
        buffers.endBatch();

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(previousProjection, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.setShader(() -> previousShader);
        target.unbindWrite();
        mc.getMainRenderTarget().bindWrite(true);

        blit(g, x, y, w, h);
        RenderSystem.setShaderColor(oldColor[0], oldColor[1], oldColor[2], oldColor[3]);
    }

    private void ensureTarget(int w, int h) {
        int scale = (int) Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
        int pw = Math.max(1, w * scale);
        int ph = Math.max(1, h * scale);
        if (target == null) {
            target = new TextureTarget(pw, ph, true, false);
            targetW = pw;
            targetH = ph;
        } else if (targetW != pw || targetH != ph) {
            target.resize(pw, ph, false);
            targetW = pw;
            targetH = ph;
        }
        // 皮肤/盔甲是像素图：放大必须最近邻，不能线性过滤。
        GlStateManager._bindTexture(target.getColorTextureId());
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._bindTexture(0);
    }

    private void blit(GuiGraphics g, int x, int y, int w, int h) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Matrix4f mat = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(mat, x, y + h, 0).uv(0, 0).color(255, 255, 255, 255).endVertex();
        bb.vertex(mat, x + w, y + h, 0).uv(1, 0).color(255, 255, 255, 255).endVertex();
        bb.vertex(mat, x + w, y, 0).uv(1, 1).color(255, 255, 255, 255).endVertex();
        bb.vertex(mat, x, y, 0).uv(0, 1).color(255, 255, 255, 255).endVertex();
        BufferUploader.drawWithShader(bb.end());
    }

    public void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
