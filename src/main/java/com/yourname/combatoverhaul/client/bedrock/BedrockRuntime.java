package com.yourname.combatoverhaul.client.bedrock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.combatoverhaul.client.model.ParsedModel;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 游戏内 Bedrock 渲染入口：几何按 ParsedModel 实例缓存（ActionCache 失效后会换新实例），
 * 姿态每帧从当前 anim 叠加状态生成。
 *
 * <p>渲染用的 VertexConsumer 由原版 LivingEntityRenderer 提供，所以贴图、发光层、
 * 半透明层和 packed light/overlay 都沿用原版决定，不需要自己解析纹理。
 */
public final class BedrockRuntime {

    private static final Map<ParsedModel, BedrockInstance> INSTANCE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private BedrockRuntime() {
    }

    public static BedrockGeometry geometry(ParsedModel model) {
        return instance(model).geometry;
    }

    public static BedrockInstance instance(ParsedModel model) {
        if (model == null) {
            return new BedrockInstance(new BedrockGeometry(64, 64,
                    java.util.List.of(), java.util.Map.of()));
        }
        synchronized (INSTANCE_CACHE) {
            BedrockInstance cached = INSTANCE_CACHE.get(model);
            if (cached != null) {
                return cached;
            }
            BedrockInstance built = new BedrockInstance(BedrockGeometryAdapter.fromParsedModel(model));
            INSTANCE_CACHE.put(model, built);
            return built;
        }
    }

    public static boolean render(ParsedModel model, VertexConsumer vc, PoseStack poseStack,
                                 int packedLight, int packedOverlay,
                                 float red, float green, float blue, float alpha) {
        return render(model, null, vc, poseStack, packedLight, packedOverlay, red, green, blue, alpha);
    }

    public static boolean render(ParsedModel model, BedrockPose pose, VertexConsumer vc, PoseStack poseStack,
                                 int packedLight, int packedOverlay,
                                 float red, float green, float blue, float alpha) {
        if (model == null || vc == null || poseStack == null) {
            return false;
        }
        BedrockInstance instance = instance(model);
        instance.pose(pose != null ? pose : BedrockPose.fromAnim(model));
        return instance.render(vc, poseStack, packedLight, packedOverlay, red, green, blue, alpha);
    }

    public static void invalidate(ParsedModel model) {
        if (model != null) {
            INSTANCE_CACHE.remove(model);
        }
    }

    public static void clear() {
        INSTANCE_CACHE.clear();
    }
}
