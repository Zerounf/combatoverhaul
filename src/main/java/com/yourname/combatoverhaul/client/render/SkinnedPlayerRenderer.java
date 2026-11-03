package com.yourname.combatoverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import com.yourname.combatoverhaul.client.bedrock.BedrockPose;
import com.yourname.combatoverhaul.client.skinned.SkinnedMeshRenderer;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.Player;

/** 完全替换玩家原版 PlayerModel 的 16 骨连续蒙皮渲染。 */
public final class SkinnedPlayerRenderer {

    private SkinnedPlayerRenderer() {
    }

    public static void render(Player player, float partialTick, PoseStack pose,
                              MultiBufferSource buffers, int light) {
        ParsedModel model = ClientAnimHandler.currentRenderModel();
        if (model == null || model.skinnedMesh == null) {
            return;
        }
        float bodyYaw = net.minecraft.util.Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F - bodyYaw));
        pose.scale(-1.0F, -1.0F, 1.0F);
        // PlayerRenderer HEAD 在 LivingEntityRenderer.render 之前，原版的
        // scale(-1,-1,1) + translate(0,-1.501,0) 必须由这里完整复制。
        pose.translate(0.0F, -1.501F, 0.0F);

        BedrockPose bp = ClientAnimHandler.currentRenderPose();
        if (bp == null) {
            model.syncFollow();
            bp = BedrockPose.fromAnim(model);
        }
        applyHeadLook(player, model, bp, partialTick);
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.resources.ResourceLocation skin = player instanceof net.minecraft.client.player.AbstractClientPlayer acp
                ? acp.getSkinTextureLocation()
                : mc.player.getSkinTextureLocation();
        VertexConsumer vc = buffers.getBuffer(
                RenderType.entityCutoutNoCull(skin));
        // 和原版实体一样使用世界传入的 packed light，让皮肤受环境光影响。
        SkinnedMeshRenderer.render(vc, pose, model, model.skinnedMesh, bp,
                light, OverlayTexture.NO_OVERLAY);
        if (buffers instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }
        renderHand(pose, buffers, light, player.getMainHandItem(), "hand_r", true, model, bp);
        renderHand(pose, buffers, light, player.getOffhandItem(), "hand_l", false, model, bp);
        SkinnedArmorRenderer.render(player, pose, buffers, light, bp);
        pose.popPose();
    }

    /**
     * 自定义玩家没有原版 PlayerModel.setupAnim 的头部注视，补上 head 相对身体的 yaw/pitch。
     */
    private static void applyHeadLook(Player player, ParsedModel model, BedrockPose bp,
                                      float partialTick) {
        String headName = model.rigBone("head");
        ParsedBone head = headName == null ? null : model.findBone(headName);
        if (head == null || bp == null) {
            return;
        }
        float bodyYaw = net.minecraft.util.Mth.rotLerp(partialTick,
                player.yBodyRotO, player.yBodyRot);
        float headYaw = net.minecraft.util.Mth.rotLerp(partialTick,
                player.yHeadRotO, player.yHeadRot);
        float yaw = net.minecraft.util.Mth.wrapDegrees(headYaw - bodyYaw);
        float pitch = net.minecraft.util.Mth.lerp(partialTick, player.xRotO, player.getXRot());
        yaw = net.minecraft.util.Mth.clamp(yaw, -75.0f, 75.0f);
        pitch = net.minecraft.util.Mth.clamp(pitch, -60.0f, 60.0f);
        BedrockPose.BonePose p = bp.getOrCreate(head.name);
        p.rotationRad.y += (float) Math.toRadians(yaw);
        // 实机：抬头时 player.xRot 为负，模型 head 需要同号 X 才会抬头。
        p.rotationRad.x += (float) Math.toRadians(pitch);
    }

    private static void renderHand(PoseStack pose, MultiBufferSource buffers, int light,
                                   net.minecraft.world.item.ItemStack stack, String point,
                                   boolean right, ParsedModel model, BedrockPose bp) {
        if (stack.isEmpty()) {
            return;
        }
        // 先走 rig 的点（例如 hand_r -> R_Weapon）；没有 rig 的旧模型再回退到原骨名。
        String useBone = model.rigBone(point);
        boolean armFallback = false;
        if (useBone == null) {
            useBone = model.findBone(right ? "R_Weapon" : "L_Weapon") != null
                    ? (right ? "R_Weapon" : "L_Weapon") : null;
        }
        if (useBone == null) {
            // 原生 player 模型没有 R_Weapon/L_Weapon：退到 right_arm/left_arm，
            // 再沿手臂局部 Y 移到手末端，避免物品出现在脚底。
            String semantic = right ? "right_arm" : "left_arm";
            for (ParsedBone b : model.bones) {
                if (semantic.equals(model.semanticName(b.name))) {
                    useBone = b.name;
                    armFallback = true;
                    break;
                }
            }
        }
        if (useBone == null) {
            return;
        }
        org.joml.Matrix4f bm = SkinnedMeshRenderer.boneMatrix(model, useBone, bp);
        pose.pushPose();
        // 和 HeldItemRenderer 同一套：先缩放到方块单位，再在骨骼局部 +10px 到手掌，
        // 最后才是 XP(-90)/YP(180) 的手持物品朝向修正。
        float handOffset = armFallback ? 10.0f : 0.0f;
        // 只把骨骼平移从模型单位换到方块单位；不能把整个矩阵缩放 1/16，
        // 否则 ItemRenderer 的武器模型也会被缩成一点点（只剩一个小点）。
        org.joml.Matrix4f hand = new org.joml.Matrix4f(bm);
        hand.m30(bm.m30() / 16.0f);
        hand.m31(bm.m31() / 16.0f);
        hand.m32(bm.m32() / 16.0f);
        if (armFallback) {
            hand.translate(0.0f, 10.0f / 16.0f, 0.0f);
        }
        pose.mulPoseMatrix(hand);
        // 一些武器物品模型是 16x 模型单位导出的，THIRD_PERSON display 仍按方块单位，
        // 会显得像一根小木棍；按模型尺度补偿后再外移一点。
        // 不再额外放大武器；只把手持点轻微前移，保持原物品模型尺寸。
        pose.translate(right ? -0.02f : 0.02f, 0.0f, -0.07f);
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0f));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f));
        if (buffers instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        Minecraft mc = Minecraft.getInstance();
        mc.getItemRenderer().renderStatic(stack,
                right ? net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                        : net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                light,
                OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
        if (buffers instanceof MultiBufferSource.BufferSource bs) {
            bs.endBatch();
        }
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        pose.popPose();
    }
}
