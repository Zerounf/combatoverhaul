package com.yourname.combatoverhaul.client.bedrock;

import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedCube;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把现有 {@link ParsedModel}（编辑器内部格式 / GeckoModelLoader 转换结果）
 * 适配成 {@link BedrockGeometry}。
 *
 * <p>第一版先复用已经验证过的内部几何（逐面 face_verts + faces UV），
 * 新渲染管线不再依赖 {@code ModelPart.Cube}；后续可以直接从 .geo.json 构建。
 */
public final class BedrockGeometryAdapter {

    private BedrockGeometryAdapter() {
    }

    public static BedrockGeometry fromParsedModel(ParsedModel model) {
        return fromParsedModel(model, false);
    }

    /**
     * @param mirrorX 仅预览使用：把几何沿模型 X 轴镜像（顶点取反、四边形反转、法线 X 取反）。
     *                不修改 ParsedModel，也不影响游戏内渲染。
     */
    public static BedrockGeometry fromParsedModel(ParsedModel model, boolean mirrorX) {
        if (model == null) {
            return new BedrockGeometry(64, 64, List.of(), Map.of());
        }
        List<BedrockBone> roots = new ArrayList<>();
        Map<String, BedrockBone> byName = new LinkedHashMap<>();
        // bone_aliases 是“真实骨骼名 -> 语义名”；玩家/人形模型的外层 hat/jacket/sleeves/pants
        // 在原版里带 CubeDeformation(0.25)，旧扫描 JSON 可能只存了未膨胀包围盒。
        Map<String, String> aliases = model.boneAliases;
        for (ParsedBone root : model.roots) {
            roots.add(buildBone(root, null, byName, aliases.get(root.name), mirrorX));
        }
        return new BedrockGeometry(64, 64, roots, byName);
    }

    private static BedrockBone buildBone(ParsedBone src, BedrockBone parent,
                                         Map<String, BedrockBone> byName, String alias, boolean mirrorX) {
        BedrockBone bone = new BedrockBone(src.name, parent);
        bone.pivot.set(mirrorX ? -src.pivotX : src.pivotX, src.pivotY, src.pivotZ);
        bone.rotationRad.set(src.rotX, src.rotY, src.rotZ);
        bone.scale.set(src.scaleX, src.scaleY, src.scaleZ);
        bone.visible = src.visible;
        bone.softJoint = src.softJoint;
        bone.softAxis = src.softAxis;

        float outerInflate = outerLayerInflate(alias);
        for (ParsedCube cube : src.cubes) {
            BedrockCube bc = buildCube(cube, outerInflate, mirrorX);
            if (!bc.quads.isEmpty()) {
                bone.cubes.add(bc);
            }
        }
        byName.put(src.name, bone);
        for (ParsedBone child : src.children) {
            bone.children.add(buildBone(child, bone, byName, null, mirrorX));
        }
        return bone;
    }

    private static float outerLayerInflate(String alias) {
        if (alias == null) {
            return 0f;
        }
        return switch (alias) {
            case "hat", "jacket", "left_sleeve", "right_sleeve", "left_pants", "right_pants" -> 0.25f;
            default -> 0f;
        };
    }

    private static BedrockCube buildCube(ParsedCube c, float inflate, boolean mirrorX) {
        BedrockCube cube = new BedrockCube();
        cube.rotationPivot.set(mirrorX ? -c.pivotX : c.pivotX, c.pivotY, c.pivotZ);
        cube.rotationRad.set(c.rotX, c.rotY, c.rotZ);
        cube.hasRotation = c.hasRot();

        // 旧版 player.json 的 face_verts 可能已经是膨胀后的；只有完全落在 origin..origin+size
        // 内时才补膨胀，避免新扫描文件被二次放大。
        float grow = inflate;
        if (grow > 0f && isInflated(c)) {
            grow = 0f;
        }

        for (int f = 0; f < 6; f++) {
            if (!c.hasFace(f)) {
                continue;
            }
            Vector3f[] pos = new Vector3f[4];
            float[][] uv = new float[4][2];
            for (int k = 0; k < 4; k++) {
                float x;
                float y;
                float z;
                if (c.hasFaceVerts(f)) {
                    x = c.vertComp(f, k, 0);
                    y = c.vertComp(f, k, 1);
                    z = c.vertComp(f, k, 2);
                } else {
                    x = c.cornerX(f, k);
                    y = c.cornerY(f, k);
                    z = c.cornerZ(f, k);
                    if (c.mirror) {
                        x = c.originX * 2 + c.sizeX - x;
                    }
                }
                if (grow > 0f) {
                    x = inflateAxis(x, c.originX, c.sizeX, grow);
                    y = inflateAxis(y, c.originY, c.sizeY, grow);
                    z = inflateAxis(z, c.originZ, c.sizeZ, grow);
                }
                pos[k] = new Vector3f(mirrorX ? -x : x, y, z);
                float[] faceUv = c.uv(f, k);
                uv[k][0] = faceUv[0];
                uv[k][1] = faceUv[1];
            }
            float[] n = ParsedCube.FACE_NORMALS[f];
            Vector3f normal = new Vector3f(mirrorX ? -n[0] : n[0], n[1], n[2]);
            if (mirrorX) {
                // 镜像反转绕序；同时反转顶点顺序，保证法线仍朝外。
                Vector3f[] rp = {pos[0], pos[3], pos[2], pos[1]};
                float[][] ru = {uv[0], uv[3], uv[2], uv[1]};
                cube.add(new BedrockQuad(rp, ru, normal));
            } else {
                cube.add(new BedrockQuad(pos, uv, normal));
            }
        }
        return cube;
    }

    /** face_verts 是否已经超出 origin..origin+size（即已带 CubeDeformation）。 */
    private static boolean isInflated(ParsedCube c) {
        for (int f = 0; f < 6; f++) {
            if (!c.hasFaceVerts(f)) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                float x = c.vertComp(f, k, 0);
                float y = c.vertComp(f, k, 1);
                float z = c.vertComp(f, k, 2);
                if (x < c.originX - 0.001f || x > c.originX + c.sizeX + 0.001f
                        || y < c.originY - 0.001f || y > c.originY + c.sizeY + 0.001f
                        || z < c.originZ - 0.001f || z > c.originZ + c.sizeZ + 0.001f) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float inflateAxis(float value, float origin, float size, float grow) {
        if (size <= 1.0e-6f) {
            return value;
        }
        return origin - grow + (value - origin) / size * (size + 2f * grow);
    }
}
