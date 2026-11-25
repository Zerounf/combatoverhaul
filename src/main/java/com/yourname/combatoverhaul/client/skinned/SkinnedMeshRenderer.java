package com.yourname.combatoverhaul.client.skinned;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.combatoverhaul.client.bedrock.BedrockPose;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 连续蒙皮渲染：CPU 权重混合，每个顶点可由多根骨骼影响。
 */
public final class SkinnedMeshRenderer {

    private SkinnedMeshRenderer() {
    }

    public static Matrix4f boneMatrix(ParsedModel model, String boneName, BedrockPose animPose) {
        if (model == null || boneName == null) {
            return new Matrix4f();
        }
        Map<ParsedBone, Matrix4f> rest = new IdentityHashMap<>();
        Map<ParsedBone, Matrix4f> animated = new IdentityHashMap<>();
        for (ParsedBone root : model.roots) {
            build(root, null, null, rest, animated, animPose);
        }
        ParsedBone b = model.findBone(boneName);
        return b == null ? new Matrix4f() : animated.getOrDefault(b, new Matrix4f());
    }

    public static void render(VertexConsumer vc, PoseStack poseStack, ParsedModel model,
                              SkinnedMesh mesh, BedrockPose animPose,
                              int packedLight, int packedOverlay) {
        render(vc, poseStack, model, mesh, animPose, packedLight, packedOverlay, 1.0f);
    }

    public static void render(VertexConsumer vc, PoseStack poseStack, ParsedModel model,
                              SkinnedMesh mesh, BedrockPose animPose,
                              int packedLight, int packedOverlay, float alpha) {
        if (model == null || mesh == null || mesh.vertices.isEmpty()) {
            return;
        }
        model.syncFollow();
        Map<ParsedBone, Matrix4f> rest = new IdentityHashMap<>();
        Map<ParsedBone, Matrix4f> animated = new IdentityHashMap<>();
        for (ParsedBone root : model.roots) {
            build(root, null, null, rest, animated, animPose);
        }
        // body 对平级 root 的运动传播用“动画增量”，不能把它们重新挂到 body 的
        // rest 矩阵下：root 的 pivot 是模型绝对坐标，直接做父矩阵会二次平移。
        ParsedBone flatBody = flatBody(model);
        if (flatBody != null) {
            Matrix4f bodyRest = rest.get(flatBody);
            Matrix4f bodyAnim = animated.get(flatBody);
            if (bodyRest != null && bodyAnim != null) {
                Matrix4f delta = new Matrix4f(bodyAnim).mul(new Matrix4f(bodyRest).invert());
                for (ParsedBone root : model.roots) {
                    if (root != flatBody) {
                        propagate(root, animated, delta);
                    }
                }
            }
        }
        int n = model.bones.size();
        Matrix4f[] skin = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            ParsedBone b = model.bones.get(i);
            Matrix4f r = rest.get(b);
            Matrix4f a = animated.get(b);
            if (r == null || a == null) {
                skin[i] = new Matrix4f();
            } else {
                skin[i] = new Matrix4f(a).mul(new Matrix4f(r).invert());
            }
        }
        int[][] boneIdx = new int[mesh.vertices.size()][4];
        for (int i = 0; i < mesh.vertices.size(); i++) {
            String[] names = mesh.weightBones.get(i);
            for (int k = 0; k < 4; k++) {
                boneIdx[i][k] = mesh.resolveBone(model, names[k]);
            }
        }
        Matrix4f base = poseStack.last().pose();
        Matrix3f normalMat = poseStack.last().normal();
        for (int[] face : mesh.faces) {
            if (face.length < 3) {
                continue;
            }
            int count = face[3] < 0 ? 3 : 4;
            Vector3f[] pos = new Vector3f[count];
            for (int k = 0; k < count; k++) {
                int vi = face[k];
                if (vi < 0 || vi >= mesh.vertices.size()) {
                    pos[k] = new Vector3f();
                    continue;
                }
                SkinnedMesh.Vertex v = mesh.vertices.get(vi);
                float x = 0, y = 0, z = 0;
                float[] w = mesh.weightValues.get(vi);
                for (int j = 0; j < 4; j++) {
                    if (w[j] <= 0f) {
                        continue;
                    }
                    int bi = boneIdx[vi][j];
                    if (bi < 0 || bi >= skin.length) {
                        continue;
                    }
                    Vector3f p = skin[bi].transformPosition(new Vector3f(v.x, v.y, v.z), new Vector3f());
                    x += p.x * w[j];
                    y += p.y * w[j];
                    z += p.z * w[j];
                }
                pos[k] = new Vector3f(x, y, z);
            }
            // 逐面法线：旧代码所有面用 (0,1,0)，侧脸会被环境光压暗，整张皮肤看起来偏暗。
            Vector3f normal = new Vector3f(0, 1, 0);
            if (count >= 3) {
                Vector3f edge1 = new Vector3f(pos[1]).sub(pos[0]);
                Vector3f edge2 = new Vector3f(pos[2]).sub(pos[0]);
                normal = edge1.cross(edge2);
                if (normal.lengthSquared() > 1.0e-12f) {
                    normal.normalize();
                } else {
                    normal.set(0, 1, 0);
                }
                normal.mul(normalMat);
                if (normal.lengthSquared() > 1.0e-12f) {
                    normal.normalize();
                }
            }
            for (int k = 0; k < count; k++) {
                int vi = face[k];
                if (vi < 0 || vi >= mesh.vertices.size()) {
                    continue;
                }
                SkinnedMesh.Vertex v = mesh.vertices.get(vi);
                vc.vertex(base, pos[k].x / 16.0f, pos[k].y / 16.0f, pos[k].z / 16.0f)
                        .color(1f, 1f, 1f, alpha)
                        .uv(v.u, v.v)
                        .overlayCoords(packedOverlay)
                        .uv2(packedLight)
                        .normal(normal.x, normal.y, normal.z)
                        .endVertex();
            }
        }
    }

    /**
     * 盔甲专用刚性绑定：每个顶点跟随 source_map 指定的一根玩家骨骼，
     * 不做权重混合。好处是胸甲不会被最近骨段拉偏，位置严格等于模型 JSON。
     */
    public static void renderRigid(VertexConsumer vc, PoseStack poseStack, ParsedModel model,
                                   SkinnedMesh mesh, BedrockPose animPose,
                                   int packedLight, int packedOverlay,
                                   Map<String, String> sourceMap) {
        if (model == null || mesh == null || mesh.vertices.isEmpty()) {
            return;
        }
        model.syncFollow();
        Map<ParsedBone, Matrix4f> rest = new IdentityHashMap<>();
        Map<ParsedBone, Matrix4f> animated = new IdentityHashMap<>();
        for (ParsedBone root : model.roots) {
            build(root, null, null, rest, animated, animPose);
        }
        int n = model.bones.size();
        Matrix4f[] skin = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            ParsedBone b = model.bones.get(i);
            Matrix4f r = rest.get(b);
            Matrix4f a = animated.get(b);
            skin[i] = r == null || a == null ? new Matrix4f()
                    : new Matrix4f(a).mul(new Matrix4f(r).invert());
        }
        boolean hasSource = mesh.sourceBones.size() == mesh.vertices.size();
        Matrix4f base = poseStack.last().pose();
        Matrix3f normalMat = poseStack.last().normal();
        for (int[] face : mesh.faces) {
            if (face.length < 3) {
                continue;
            }
            int count = face[3] < 0 ? 3 : 4;
            Vector3f[] pos = new Vector3f[count];
            for (int k = 0; k < count; k++) {
                int vi = face[k];
                if (vi < 0 || vi >= mesh.vertices.size()) {
                    pos[k] = new Vector3f();
                    continue;
                }
                SkinnedMesh.Vertex v = mesh.vertices.get(vi);
                String src = hasSource ? mesh.sourceBones.get(vi) : "";
                String target = sourceMap == null ? src : sourceMap.getOrDefault(src, src);
                ParsedBone bone = model.findBone(target);
                int bi = -1;
                if (bone != null) {
                    bi = model.bones.indexOf(bone);
                }
                pos[k] = bi >= 0 ? skin[bi].transformPosition(new Vector3f(v.x, v.y, v.z))
                        : new Vector3f(v.x, v.y, v.z);
            }
            Vector3f normal = new Vector3f(0, 1, 0);
            if (count >= 3) {
                normal = new Vector3f(pos[1]).sub(pos[0])
                        .cross(new Vector3f(pos[2]).sub(pos[0]));
                if (normal.lengthSquared() > 1.0e-12f) {
                    normal.normalize().mul(normalMat);
                    if (normal.lengthSquared() > 1.0e-12f) {
                        normal.normalize();
                    }
                }
            }
            for (int k = 0; k < count; k++) {
                int vi = face[k];
                if (vi < 0 || vi >= mesh.vertices.size()) {
                    continue;
                }
                SkinnedMesh.Vertex v = mesh.vertices.get(vi);
                vc.vertex(base, pos[k].x / 16f, pos[k].y / 16f, pos[k].z / 16f)
                        .color(1f, 1f, 1f, 1f)
                        .uv(v.u, v.v)
                        .overlayCoords(packedOverlay)
                        .uv2(packedLight)
                        .normal(normal.x, normal.y, normal.z)
                        .endVertex();
            }
        }
    }

    private static void propagate(ParsedBone bone, Map<ParsedBone, Matrix4f> animated,
                                  Matrix4f delta) {
        Matrix4f a = animated.get(bone);
        if (a != null) {
            animated.put(bone, new Matrix4f(delta).mul(a));
        }
        for (ParsedBone child : bone.children) {
            propagate(child, animated, delta);
        }
    }

    /** 扁平扫描模型（原版 Quadruped/Player）的躯干根；优先 alias/body，其次最大 cube。 */
    private static ParsedBone flatBody(ParsedModel model) {
        if (model == null || model.roots.size() < 2) {
            return null;
        }
        for (ParsedBone root : model.roots) {
            String semantic = model.semanticName(root.name);
            if ("body".equalsIgnoreCase(semantic) || "torso".equalsIgnoreCase(semantic)
                    || "chest".equalsIgnoreCase(semantic)) {
                return root;
            }
        }
        ParsedBone best = null;
        float bestVolume = -1f;
        for (ParsedBone root : model.roots) {
            float[] b = root.cubeBounds();
            if (b == null) {
                continue;
            }
            float volume = Math.max(0f, b[3] - b[0]) * Math.max(0f, b[4] - b[1])
                    * Math.max(0f, b[5] - b[2]);
            if (volume > bestVolume) {
                bestVolume = volume;
                best = root;
            }
        }
        return bestVolume > 0f ? best : null;
    }

    private static void build(ParsedBone bone, Matrix4f restParent, Matrix4f animParent,
                              Map<ParsedBone, Matrix4f> rest,
                              Map<ParsedBone, Matrix4f> animated, BedrockPose pose) {
        // rest 链和 animated 链必须分开：rest 用于 inverse bind，不能拿 animated 父矩阵算。
        Matrix4f restBase = restParent == null ? new Matrix4f() : new Matrix4f(restParent);
        Matrix4f restRot = null;
        if (bone.restMatrix != null && bone.restMatrix.length >= 9) {
            restRot = new Matrix4f().set3x3(new Matrix3f(
                    bone.restMatrix[0], bone.restMatrix[3], bone.restMatrix[6],
                    bone.restMatrix[1], bone.restMatrix[4], bone.restMatrix[7],
                    bone.restMatrix[2], bone.restMatrix[5], bone.restMatrix[8]));
        }
        Matrix4f r;
        if (restRot != null) {
            r = new Matrix4f(restBase).translate(bone.pivotX, bone.pivotY, bone.pivotZ)
                    .mul(restRot).scale(bone.scaleX, bone.scaleY, bone.scaleZ);
        } else {
            r = new Matrix4f(restBase)
                    .translate(bone.pivotX, bone.pivotY, bone.pivotZ)
                    .rotateZYX(bone.rotZ, bone.rotY, bone.rotX)
                    .scale(bone.scaleX, bone.scaleY, bone.scaleZ);
        }
        rest.put(bone, r);

        BedrockPose.BonePose bp = pose == null ? null : pose.get(bone.name);
        float px = bone.pivotX + (bp == null ? 0f : bp.position.x);
        float py = bone.pivotY + (bp == null ? 0f : bp.position.y);
        float pz = bone.pivotZ + (bp == null ? 0f : bp.position.z);
        float rx = bone.rotX + (bp == null ? 0f : bp.rotationRad.x);
        float ry = bone.rotY + (bp == null ? 0f : bp.rotationRad.y);
        float rz = bone.rotZ + (bp == null ? 0f : bp.rotationRad.z);
        float sx = bone.scaleX * (bp == null ? 1f : bp.scale.x);
        float sy = bone.scaleY * (bp == null ? 1f : bp.scale.y);
        float sz = bone.scaleZ * (bp == null ? 1f : bp.scale.z);
        Matrix4f animBase = animParent == null ? new Matrix4f() : new Matrix4f(animParent);
        Matrix4f a;
        if (restRot != null) {
            // ER 标准：rest 朝向由矩阵给出，动画只在其局部轴上叠加。
            a = new Matrix4f(animBase).translate(px, py, pz).mul(restRot)
                    .rotateZYX(rz, ry, rx).scale(sx, sy, sz);
        } else {
            a = new Matrix4f(animBase).translate(px, py, pz).rotateZYX(rz, ry, rx).scale(sx, sy, sz);
        }
        animated.put(bone, a);
        for (ParsedBone child : bone.children) {
            build(child, r, a, rest, animated, pose);
        }
    }
}
