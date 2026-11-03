package com.yourname.combatoverhaul.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import com.yourname.combatoverhaul.client.bedrock.BedrockRuntime;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 游戏内 Bedrock 渲染接管：在原版 {@code LivingEntityRenderer.render} 调用
 * {@code EntityModel.renderToBuffer} 的位置，改为用 {@link BedrockRuntime} 逐面 quad 绘制。
 *
 * <p>为什么不是替换 renderer：
 * <ul>
 *   <li>原版传给 {@code renderToBuffer} 的 {@link VertexConsumer} 已经绑定好贴图、
 *       RenderType、packed light 与 overlay，直接复用最省事；</li>
 *   <li>原版 renderer 的 layers（盔甲/手持/披风/鞘翅）仍按原逻辑渲染，
 *       不会因为换 renderer 而丢失；</li>
 *   <li>旧路径 {@code ClientAnimHandler.afterSetupAnim} 仍把动作姿态写进 ModelPart，
 *       所以盔甲层与自绘身体共用同一套骨骼变换。</li>
 * </ul>
 *
 * <p>目前只接管玩家（{@link ClientAnimHandler#useBedrockRender()} 由 Pre 事件设置），
 * 其他实体继续走旧 ModelPart 覆盖，便于逐步扩大范围。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class BedrockModelRenderMixin {

    @Redirect(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"),
            // 第一版实验注入：如果映射/签名不匹配，允许跳过而不是让游戏启动崩溃；
            // 日志里会看到 mixin apply 失败，旧 ModelPart 渲染照常工作。
            require = 0)
    private void combatoverhaul$renderBedrock(EntityModel<?> model, PoseStack poseStack, VertexConsumer vc,
                                              int packedLight, int packedOverlay,
                                              float red, float green, float blue, float alpha) {
        ParsedModel poseModel = ClientAnimHandler.currentRenderModel();
        boolean softPlayer = poseModel != null
                && poseModel.entityId != null
                && (poseModel.entityId.startsWith("combatoverhaul:humanoid")
                    || poseModel.entityId.equals("combatoverhaul:skinned_player_er"));
        if (!ClientAnimHandler.useBedrockRender() || poseModel == null
                || (model instanceof net.minecraft.client.model.PlayerModel<?> && !softPlayer)
                || (model instanceof net.minecraft.client.model.HumanoidModel<?>
                    && !(model instanceof net.minecraft.client.model.PlayerModel<?>))) {
            // 玩家身体在 softPlayer 时走 12 段 Bedrock；盔甲/外层 HumanoidModel 仍走原版，
            // 由 afterSetupAnim 把 12 段姿态聚合到 6 个部件上。
            model.renderToBuffer(poseStack, vc, packedLight, packedOverlay, red, green, blue, alpha);
            return;
        }
        boolean rendered;
        try {
            com.yourname.combatoverhaul.client.bedrock.BedrockPose pose =
                    ClientAnimHandler.currentRenderPose();
            if (pose == null) {
                // 游戏路径 RENDER_POSE 会被清空；必须从当前 anim 状态重建，
                // 否则 SkinnedMeshRenderer 会一直按 rest pose 绘制。
                pose = com.yourname.combatoverhaul.client.bedrock.BedrockPose.fromAnim(poseModel);
            }
            if (poseModel.skinnedMesh != null) {
                com.yourname.combatoverhaul.client.skinned.SkinnedMeshRenderer.render(
                        vc, poseStack, poseModel, poseModel.skinnedMesh,
                        pose, packedLight, packedOverlay);
                rendered = true;
            } else {
                rendered = BedrockRuntime.render(poseModel, pose,
                        vc, poseStack, packedLight, packedOverlay, red, green, blue, alpha);
            }
        } catch (RuntimeException ex) {
            // 新管线单帧失败不能把整个实体渲染/游戏打崩；回退原版模型。
            com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.error(
                    "[CombatOverhaul] Bedrock 渲染失败，回退原版模型", ex);
            rendered = false;
        }
        if (!rendered) {
            model.renderToBuffer(poseStack, vc, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }
}
