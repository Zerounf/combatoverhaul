package com.yourname.combatoverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.combat.ActionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Quaternionf;

import java.util.List;

/**
 * 游戏内手持物品渲染：实体模型 JSON 配了 held_items 时，在各挂接骨骼的当前渲染帧上
 * 以原版物品渲染（第三人称右手 display 变换为基准，关闭背面剔除）绘制物品，
 * 随骨骼动画（含本模组动作）运动。
 * 不同骨骼可同时各持一件物品（同一骨骼最多一条）。
 *
 * <p>挂接点 = 骨骼帧 → 挂点偏移（默认=骨骼包围盒最长轴远端端面中心，可配置）
 * → 原版手持修正 → 附加旋转。骨骼帧从真实 {@link ModelPart} 字段重放
 * translateAndRotate 链获得，与渲染完全一致（原版程序动画与本模组动作姿态均已写入字段）。
 *
 * <p>{@link EventPriority#HIGH}：必须抢在 {@code ClientAnimHandler.onRenderLivingPost}
 * 恢复 ModelPart 旋转（FRAME_ROT）之前读取动画值。
 */
public final class HeldItemRenderer {

    private HeldItemRenderer() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        ParsedModel skinned = ClientAnimHandler.currentRenderModel();
        if (entity instanceof net.minecraft.world.entity.player.Player
                && ClientAnimHandler.useBedrockRender()
                && Boolean.getBoolean("combatoverhaul.customSkinnedHands")) {
            if (skinned == null || skinned.skinnedMesh == null) {
                ActionCache.Entry se = ActionCache.get(ActionCache.keyFor("combatoverhaul", "skinned_player_er", false));
                if (se != null) {
                    skinned = se.model;
                }
            }
            if (skinned != null && skinned.skinnedMesh != null) {
                renderSkinnedHands(event, skinned);
                return;
            }
        }
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (typeId == null) {
            return;
        }
        ActionCache.Entry entry = ActionCache.get(
                ActionCache.keyFor(typeId.getNamespace(), typeId.getPath(), false));
        ParsedModel em = entry != null ? entry.model : null;
        if (em == null || em.heldItems.isEmpty()) {
            return;
        }
        EntityModel<?> model = event.getRenderer().getModel();
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers =
                (MultiBufferSource.BufferSource) event.getMultiBufferSource();
        int light = event.getPackedLight();

        // RenderLivingEvent.Post 是在 LivingEntityRenderer.render() 内部 popPose() 之后触发的，
        // 此时 PoseStack 只到实体脚下（EntityRenderDispatcher 的 translate），
        // 不含 renderer 的 bodyYaw / scale(-1,-1,1) / translate(0,-1.501,0)。
        // 必须补回这三步，否则手持物品会以“模型空间 Y 向上”的坐标画在错误的高度和朝向。
        float bodyYaw = net.minecraft.util.Mth.rotLerp(event.getPartialTick(),
                entity.yBodyRotO, entity.yBodyRot);
        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F - bodyYaw));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0.0F, -1.501F, 0.0F);

        for (ParsedModel.HeldEntry held : em.heldItems) {
            if (held.bone == null || held.bone.isEmpty() || held.item == null || held.item.isEmpty()) {
                continue;
            }
            List<ModelPart> path = ClientAnimHandler.partPath(model, held.bone);
            if (path == null || path.isEmpty()) {
                continue;
            }
            ItemStack stack = ItemStack.EMPTY;
            try {
                var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(held.item));
                if (item != null) {
                    stack = new ItemStack(item);
                }
            } catch (RuntimeException ignored) {
            }
            if (stack.isEmpty()) {
                continue;
            }

            pose.pushPose();
            // 根 → 目标骨骼：重放 translateAndRotate（translate(x/16) → rotationZYX(z,y,x) → scale）
            for (ModelPart part : path) {
                pose.translate(part.x / 16.0f, part.y / 16.0f, part.z / 16.0f);
                if (part.xRot != 0 || part.yRot != 0 || part.zRot != 0) {
                    pose.mulPose(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
                }
                if (part.xScale != 1 || part.yScale != 1 || part.zScale != 1) {
                    pose.scale(part.xScale, part.yScale, part.zScale);
                }
            }
            // 挂点：偏移（模型单位）→ 手持朝向修正（XP(-90°)→YP(180°)，与原版 ItemInHandLayer 一致）
            // → 用户附加旋转（度）。物品朝向以 THIRD_PERSON_RIGHT_HAND 自身 display 变换为基准，
            // 不额外缩放。渲染时关闭背面剔除，避免过旋转导致正面被剔除而看到内侧。
            float[] off = em.heldAttachOffset(held);
            pose.translate(off[0] / 16.0f, off[1] / 16.0f, off[2] / 16.0f);
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0f));
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f));
            if (held.rotDeg != null) {
                pose.mulPose(new Quaternionf().rotationZYX(
                        (float) Math.toRadians(held.rotDeg[2]),
                        (float) Math.toRadians(held.rotDeg[1]),
                        (float) Math.toRadians(held.rotDeg[0])));
            }
            // 共享缓冲与实体批次分隔，避免 RenderType 切换导致面丢失
            buffers.endBatch();
            // 关闭背面剔除：物品经 XP/YP + display 变换后可能出现正面朝内的情况，
            // 禁用剔除保证所有面都可见，渲染后恢复剔除状态。
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    light, OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
            buffers.endBatch();
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            pose.popPose();
        }
        pose.popPose();
    }

    private static void renderSkinnedHands(RenderLivingEvent.Post<?, ?> event, ParsedModel skinned) {
        net.minecraft.world.entity.player.Player player =
                (net.minecraft.world.entity.player.Player) event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers =
                (MultiBufferSource.BufferSource) event.getMultiBufferSource();
        int light = event.getPackedLight();
        float bodyYaw = net.minecraft.util.Mth.rotLerp(event.getPartialTick(),
                player.yBodyRotO, player.yBodyRot);
        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F - bodyYaw));
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0.0F, -1.501F, 0.0F);

        com.yourname.combatoverhaul.client.bedrock.BedrockPose bp =
                com.yourname.combatoverhaul.client.bedrock.BedrockPose.fromAnim(skinned);
        com.yourname.combatoverhaul.CombatOverhaulMod.LOGGER.info(
                "[HeldSkin] player={} main={} off={} bones={}",
                player.getName().getString(), player.getMainHandItem(), player.getOffhandItem(),
                skinned.bones.size());
        renderSkinnedHand(pose, buffers, light, player, player.getMainHandItem(),
                "R_Forearm", true, skinned, bp);
        renderSkinnedHand(pose, buffers, light, player, player.getOffhandItem(),
                "L_Forearm", false, skinned, bp);
        pose.popPose();
    }

    private static void renderSkinnedHand(PoseStack pose, MultiBufferSource.BufferSource buffers,
                                          int light, net.minecraft.world.entity.player.Player player,
                                          ItemStack stack, String bone, boolean right,
                                          ParsedModel model,
                                          com.yourname.combatoverhaul.client.bedrock.BedrockPose bp) {
        if (stack.isEmpty()) {
            return;
        }
        org.joml.Matrix4f bm =
                com.yourname.combatoverhaul.client.skinned.SkinnedMeshRenderer.boneMatrix(model, bone, bp);
        pose.pushPose();
        // 骨骼局部末端偏移必须发生在 boneMatrix 之前：scale * boneMatrix * translate(hand)
        pose.mulPoseMatrix(new org.joml.Matrix4f().scale(1.0f / 16.0f).mul(bm)
                .translate(0.0f, 6.0f, 0.0f));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0f));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0f));
        buffers.endBatch();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        Minecraft.getInstance().getItemRenderer().renderStatic(stack,
                right ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                light, OverlayTexture.NO_OVERLAY, pose, buffers, player.level(), 0);
        buffers.endBatch();
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        pose.popPose();
    }
}
