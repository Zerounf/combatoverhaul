package com.yourname.combatoverhaul.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@code LivingEntityRenderer.render} 的原版 {@code setupAnim} 调用之后注入，
 * 回调 {@link ClientAnimHandler#afterSetupAnim} 应用动作/基础姿态。
 *
 * <p>为什么不用 RenderLivingEvent.Pre 直接改：Pre 先于 setupAnim，修改会被覆盖；
 * 为什么不替换 renderer.model 字段：各渲染层（盔甲/手持物/史莱姆外膜等）会按
 * 具体模型类型强转 {@code getParentModel()}，换类型即 ClassCastException。
 *
 * <p>源码内使用 official 名（render/setupAnim/model），由 Mixin AP + reobfJar
 * 负责生成 refmap 并重映射到生产环境 SRG 名。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Shadow
    protected EntityModel<?> model;

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                     shift = At.Shift.AFTER))
    private void combatoverhaul$afterSetupAnim(LivingEntity entity, float a, float b,
                                               PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                               CallbackInfo ci) {
        ClientAnimHandler.afterSetupAnim(entity, this.model);
    }
}
