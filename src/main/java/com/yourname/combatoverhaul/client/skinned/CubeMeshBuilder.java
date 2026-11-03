package com.yourname.combatoverhaul.client.skinned;

import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedCube;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 通用 cube → 连续网格：把任意已有骨骼下的 cube 面细分成带 UV 的四边形网格。
 *
 * <p>不是玩家专用：原版 player / Alex、GeckoLib、其他 cube 模型都走同一套。
 * 生成后的网格交给 {@link SkinWeightGenerator} 自动蒙皮；目的不是“方块刚性旋转”，
 * 而是给关节带留出足够顶点，使肩/髋等位置可以连续混合。
 *
 * <p>细分步长按面边长自适应：约每 {@value #TARGET_STEP} 模型像素一行/列，
 * 上限 {@value #MAX_SUBDIV}，避免大模型爆炸。
 */
public final class CubeMeshBuilder {

    private static final float TARGET_STEP = 1.5f;
    private static final int MAX_SUBDIV = 16;

    private CubeMeshBuilder() {
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public int cubes;
        public int faces;
        public int vertices;
        public int quads;
    }

    /**
     * 若模型还没有 skinnedMesh，则从 cube 几何生成一个。
     * 已存在网格时返回错误，调用方应直接复用。
     */
    public static Result build(ParsedModel model) {
        Result r = new Result();
        if (model == null) {
            r.error = "模型为空";
            return r;
        }
        if (model.skinnedMesh != null) {
            r.error = "模型已经有连续网格";
            return r;
        }
        if (model.roots.isEmpty()) {
            r.error = "模型没有骨骼";
            return r;
        }

        Map<ParsedBone, Matrix4f> boneMatrices = boneMatrices(model);

        SkinnedMesh mesh = new SkinnedMesh();
        for (ParsedBone bone : model.bones) {
            if (!bone.visible || bone.cubes.isEmpty()) {
                continue;
            }
            Matrix4f boneMat = boneMatrices.get(bone);
            if (boneMat == null) {
                continue;
            }
            for (ParsedCube cube : bone.cubes) {
                r.cubes++;
                Matrix4f cubeMat = cubeMatrix(boneMat, cube);
                for (int f = 0; f < 6; f++) {
                    if (!cube.hasFace(f)) {
                        continue;
                    }
                    r.faces++;
                    appendFace(mesh, cube, f, cubeMat, bone.name);
                }
            }
        }
        if (mesh.vertices.isEmpty() || mesh.faces.isEmpty()) {
            r.error = "cube 几何没有可生成的网格面";
            return r;
        }
        mesh.warnings.add("由 CubeMeshBuilder 从 " + r.cubes + " 个 cube 生成");
        model.skinnedMesh = mesh;
        r.vertices = mesh.vertices.size();
        r.quads = mesh.faces.size();
        r.ok = true;
        return r;
    }

    private static void appendFace(SkinnedMesh mesh, ParsedCube cube, int face, Matrix4f mat,
                                  String sourceBone) {
        float[][] local = new float[4][3];
        for (int k = 0; k < 4; k++) {
            local[k] = facePoint(cube, face, k);
        }
        float[][] world = new float[4][3];
        for (int k = 0; k < 4; k++) {
            org.joml.Vector3f p = mat.transformPosition(
                    new org.joml.Vector3f(local[k][0], local[k][1], local[k][2]));
            world[k][0] = p.x;
            world[k][1] = p.y;
            world[k][2] = p.z;
        }
        float[][] uv = new float[4][2];
        for (int k = 0; k < 4; k++) {
            uv[k] = cube.uv(face, k);
        }

        int cols = subdivisions(world[0], world[1]);
        int rows = subdivisions(world[0], world[3]);
        int base = mesh.vertices.size();
        for (int r = 0; r <= rows; r++) {
            float v = r / (float) rows;
            for (int c = 0; c <= cols; c++) {
                float u = c / (float) cols;
                float[] p = bilinear3(world, u, v);
                float[] t = bilinear2(uv, u, v);
                mesh.vertices.add(new SkinnedMesh.Vertex(p[0], p[1], p[2], t[0], t[1]));
                mesh.sourceBones.add(sourceBone);
            }
        }
        int stride = cols + 1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i0 = base + r * stride + c;
                int i1 = i0 + 1;
                int i2 = i0 + stride + 1;
                int i3 = i0 + stride;
                mesh.faces.add(new int[]{i0, i1, i2, i3});
            }
        }
    }

    /** 面角点的 cube 局部坐标（含 face_verts / mirror 语义）。 */
    static float[] facePoint(ParsedCube cube, int face, int corner) {
        if (cube.hasFaceVerts(face)) {
            return new float[]{
                    cube.vertComp(face, corner, 0),
                    cube.vertComp(face, corner, 1),
                    cube.vertComp(face, corner, 2)
            };
        }
        float x = cube.cornerX(face, corner);
        float y = cube.cornerY(face, corner);
        float z = cube.cornerZ(face, corner);
        if (cube.mirror) {
            x = cube.originX * 2 + cube.sizeX - x;
        }
        return new float[]{x, y, z};
    }

    private static int subdivisions(float[] a, float[] b) {
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        float dz = b[2] - a[2];
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = (int) Math.ceil(len / TARGET_STEP);
        return Math.max(1, Math.min(MAX_SUBDIV, n));
    }

    private static float[] bilinear3(float[][] q, float u, float v) {
        float w0 = (1f - u) * (1f - v);
        float w1 = u * (1f - v);
        float w2 = u * v;
        float w3 = (1f - u) * v;
        return new float[]{
                q[0][0] * w0 + q[1][0] * w1 + q[2][0] * w2 + q[3][0] * w3,
                q[0][1] * w0 + q[1][1] * w1 + q[2][1] * w2 + q[3][1] * w3,
                q[0][2] * w0 + q[1][2] * w1 + q[2][2] * w2 + q[3][2] * w3
        };
    }

    private static float[] bilinear2(float[][] q, float u, float v) {
        float w0 = (1f - u) * (1f - v);
        float w1 = u * (1f - v);
        float w2 = u * v;
        float w3 = (1f - u) * v;
        return new float[]{
                q[0][0] * w0 + q[1][0] * w1 + q[2][0] * w2 + q[3][0] * w3,
                q[0][1] * w0 + q[1][1] * w1 + q[2][1] * w2 + q[3][1] * w3
        };
    }

    static Matrix4f cubeMatrix(Matrix4f boneMat, ParsedCube cube) {
        if (!cube.hasRot()) {
            return boneMat;
        }
        return new Matrix4f(boneMat)
                .translate(cube.pivotX, cube.pivotY, cube.pivotZ)
                .rotateZYX(cube.rotZ, cube.rotY, cube.rotX)
                .translate(-cube.pivotX, -cube.pivotY, -cube.pivotZ);
    }

    /** 所有骨骼的 rest 链矩阵（含 rest_matrix），供蒙皮/包围盒共用。 */
    static Map<ParsedBone, Matrix4f> boneMatrices(ParsedModel model) {
        Map<ParsedBone, Matrix4f> out = new IdentityHashMap<>();
        for (ParsedBone root : model.roots) {
            buildBoneMatrix(root, new Matrix4f(), out);
        }
        return out;
    }

    private static void buildBoneMatrix(ParsedBone bone, Matrix4f parent,
                                        Map<ParsedBone, Matrix4f> out) {
        Matrix4f local = new Matrix4f(parent)
                .translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        if (bone.restMatrix != null && bone.restMatrix.length >= 9) {
            float[] m = bone.restMatrix;
            local.mul(new Matrix4f().set3x3(new Matrix3f(
                    m[0], m[3], m[6],
                    m[1], m[4], m[7],
                    m[2], m[5], m[8])));
        } else {
            local.rotateZYX(bone.rotZ, bone.rotY, bone.rotX);
        }
        local.scale(bone.scaleX, bone.scaleY, bone.scaleZ);
        out.put(bone, local);
        for (ParsedBone child : bone.children) {
            buildBoneMatrix(child, local, out);
        }
    }
}
