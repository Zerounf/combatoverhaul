package com.yourname.combatoverhaul.client.bedrock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Bedrock 几何的直接 quad 渲染：按骨骼链矩阵逐面发射顶点，不经
 * {@code ModelPart.Cube} 的盒式 UV 反推。
 *
 * <p>调用方提供已经绑定好 RenderType/贴图的 {@link VertexConsumer}：
 * <ul>
 *   <li>编辑器预览：{@code buffers.getBuffer(RenderType.entityCutoutNoCull(texture))}；</li>
 *   <li>游戏内：直接复用 {@code LivingEntityRenderer} 原本传给
 *       {@code EntityModel.renderToBuffer} 的 VertexConsumer（贴图/半透明/发光层由原版决定）。</li>
 * </ul>
 *
 * @return 是否至少发射了一个顶点；调用方可用 false 回退原版 ModelPart 渲染。
 */
public final class BedrockRenderer {

    private BedrockRenderer() {
    }

    public static boolean render(VertexConsumer vc, PoseStack poseStack,
                                 BedrockGeometry geometry, BedrockPose pose,
                                 int packedLight, int packedOverlay,
                                 float red, float green, float blue, float alpha) {
        if (vc == null || poseStack == null || geometry == null || geometry.roots.isEmpty()) {
            return false;
        }
        Matrix4f root = new Matrix4f(poseStack.last().pose());
        // BedrockGeometry 的枢轴/顶点沿用内部模型单位（1/16 方块）；poseStack 是方块单位，
        // 所以在根矩阵上统一缩 1/16，骨骼 pivot 与 quad 顶点都不必逐个除。
        root.scale(1f / 16f);
        boolean any = false;
        for (BedrockBone bone : geometry.roots) {
            any |= renderBone(vc, bone, root, pose, packedLight, packedOverlay, red, green, blue, alpha);
        }
        return any;
    }

    private static boolean renderBone(VertexConsumer vc, BedrockBone bone, Matrix4f parent,
                                      BedrockPose pose, int light, int overlay,
                                      float red, float green, float blue, float alpha) {
        if (bone == null || !bone.visible) {
            return false;
        }
        BedrockPose.BonePose bp = pose == null ? null : pose.get(bone.name);

        Vector3f pivot;
        Vector3f rotation;
        Vector3f scale;
        if (pose != null && pose.absolute && bp != null) {
            // ModelPart 的绝对局部值：已经包含原版 setupAnim 的走路/摆动/俯仰 + 本模组动作
            pivot = new Vector3f(bp.position);
            rotation = new Vector3f(bp.rotationRad);
            scale = new Vector3f(bp.scale);
        } else {
            pivot = new Vector3f(bone.pivot);
            rotation = new Vector3f(bone.rotationRad);
            scale = new Vector3f(bone.scale);
            if (bp != null) {
                pivot.add(bp.position);
                rotation.add(bp.rotationRad);
                scale.mul(bp.scale);
            }
        }

        Matrix4f boneMat = new Matrix4f(parent)
                .translate(pivot.x, pivot.y, pivot.z)
                .rotateZYX(rotation.z, rotation.y, rotation.x)
                .scale(scale.x, scale.y, scale.z);
        Matrix3f boneNormal = normalMatrix(boneMat);

        // 关节柔化带：靠近父关节的顶点用“只平移、不旋转”的 rest 位置插值，
        // 远离关节的顶点用完整骨骼旋转；与旧 ModelPreviewRenderer 的 soft_joint 语义一致。
        Matrix4f restMat = bone.softJoint > 0f
                ? new Matrix4f(parent).translate(pivot.x, pivot.y, pivot.z)
                : null;

        boolean any = false;
        for (BedrockCube cube : bone.cubes) {
            Matrix4f cubeMat = boneMat;
            Matrix3f cubeNormal = boneNormal;
            if (cube.hasRotation()) {
                cubeMat = new Matrix4f(boneMat);
                Vector3f cp = cube.rotationPivot;
                cubeMat.translate(cp.x, cp.y, cp.z)
                        .rotateZYX(cube.rotationRad.z, cube.rotationRad.y, cube.rotationRad.x)
                        .translate(-cp.x, -cp.y, -cp.z);
                cubeNormal = normalMatrix(cubeMat);
            }
            float softBand = 0f;
            if (restMat != null && !cube.hasRotation()) {
                softBand = Math.max(1.5f, softExtent(cube, bone.softAxis) * bone.softJoint);
            }
            for (BedrockQuad quad : cube.quads) {
                Vector3f n = new Vector3f(quad.normal);
                cubeNormal.transform(n);
                if (n.lengthSquared() > 1.0e-12f) {
                    n.normalize();
                }
                for (int i = 0; i < 4; i++) {
                    Vector3f p = quad.positions[i];
                    Vector3f animated = cubeMat.transformPosition(new Vector3f(p.x, p.y, p.z));
                    Vector3f pos = animated;
                    if (softBand > 0f) {
                        float d = Math.abs(axisComponent(p, bone.softAxis));
                        float w = Math.min(1f, d / softBand);
                        w = w * w * (3f - 2f * w);
                        Vector3f rest = restMat.transformPosition(new Vector3f(p.x, p.y, p.z));
                        pos = rest.lerp(animated, w);
                    }
                    vc.vertex(pos.x, pos.y, pos.z)
                            .color(red, green, blue, alpha)
                            .uv(quad.uv[i][0], quad.uv[i][1])
                            .overlayCoords(overlay)
                            .uv2(light)
                            .normal(cubeNormal, n.x, n.y, n.z)
                            .endVertex();
                    any = true;
                }
            }
        }

        for (BedrockBone child : bone.children) {
            any |= renderBone(vc, child, boneMat, pose, light, overlay, red, green, blue, alpha);
        }
        return any;
    }

    /** 法线矩阵 = inverse-transpose(3x3)；缩放为 0 时退化为单位矩阵，避免崩溃。 */
    private static Matrix3f normalMatrix(Matrix4f m) {
        Matrix3f n = new Matrix3f(m);
        try {
            n.invert().transpose();
        } catch (RuntimeException e) {
            n.identity();
        }
        return n;
    }

    /** 立方体沿柔化轴的最大局部坐标绝对值（模型单位）；用于计算柔化带宽度。 */
    private static float softExtent(BedrockCube cube, int axis) {
        float ext = 0f;
        for (BedrockQuad q : cube.quads) {
            for (Vector3f p : q.positions) {
                ext = Math.max(ext, Math.abs(axisComponent(p, axis)));
            }
        }
        return ext;
    }

    private static float axisComponent(Vector3f v, int axis) {
        return axis == 0 ? v.x : axis == 2 ? v.z : v.y;
    }
}
