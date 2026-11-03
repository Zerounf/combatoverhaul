package com.yourname.combatoverhaul.client.bedrock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 一个可渲染实例：不可变几何 + 当前帧姿态。
 *
 * <p>同一个 {@link BedrockGeometry} 可以被多个实体共享；实例只持有“这次渲染/这个实体”的
 * {@link BedrockPose}。渲染入口收敛到 {@link BedrockRenderer}，调用方不再直接拼矩阵。
 */
public final class BedrockInstance {

    public final BedrockGeometry geometry;
    private BedrockPose pose;

    public BedrockInstance(BedrockGeometry geometry) {
        this.geometry = geometry == null
                ? new BedrockGeometry(64, 64, java.util.List.of(), java.util.Map.of())
                : geometry;
    }

    public BedrockPose pose() {
        return pose;
    }

    public void pose(BedrockPose pose) {
        this.pose = pose;
    }

    public boolean render(VertexConsumer vc, PoseStack poseStack, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        return BedrockRenderer.render(vc, poseStack, geometry, pose,
                packedLight, packedOverlay, red, green, blue, alpha);
    }
}
