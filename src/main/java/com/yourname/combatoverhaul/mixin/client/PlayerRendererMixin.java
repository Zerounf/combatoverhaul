package com.yourname.combatoverhaul.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import com.yourname.combatoverhaul.client.render.SkinnedPlayerRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 真替换：原版 PlayerModel、盔甲层、手持层都不再走，只画 16 骨蒙皮。 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void combatoverhaul$skinnedPlayer(AbstractClientPlayer player, float yaw, float partialTick,
                                              PoseStack pose, MultiBufferSource buffers, int light,
                                              CallbackInfo ci) {
        if (!ClientAnimHandler.isBedrockRenderEnabled()) {
            return;
        }
        var model = ClientAnimHandler.preparePlayerRender(player, partialTick);
        if (model != null && model.skinnedMesh != null) {
            SkinnedPlayerRenderer.render(player, partialTick, pose, buffers, light);
            ci.cancel();
        }
    }
}
