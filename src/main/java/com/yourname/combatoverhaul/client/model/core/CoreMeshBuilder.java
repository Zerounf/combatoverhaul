package com.yourname.combatoverhaul.client.model.core;

import com.yourname.combatoverhaul.client.skinned.SkinnedMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/**
 * CoreBone/CoreCube -> SkinnedMesh 的最小可用几何生成器。
 * 不限制骨骼数量，也不限制自由切分后的父子层级。
 */
public final class CoreMeshBuilder {

    private static final int[][][] CORNERS = {
            {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, // down
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}, // up
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, // west
            {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}}, // east
            {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}}, // north
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}  // south
    };

    private CoreMeshBuilder() {
    }

    public static SkinnedMesh build(Map<String, CoreBone> bones) {
        SkinnedMesh mesh = new SkinnedMesh();
        if (bones == null) {
            return mesh;
        }
        for (CoreBone bone : bones.values()) {
            Matrix4f boneMatrix = bone.globalTransform(bones);
            for (CoreCube cube : bone.cubes) {
                Matrix4f mat = new Matrix4f(boneMatrix).mul(cube.localTransform());
                for (int face = 0; face < 6; face++) {
                    UVUnion.FaceUV faceUv = cube.uv == null ? null : cube.uv.face(face);
                    if (cube.uv != null && cube.uv.kind == UVUnion.Kind.FACES && faceUv == null) {
                        continue;
                    }
                    int base = mesh.vertices.size();
                    for (int corner = 0; corner < 4; corner++) {
                        Vector3f p = corner(cube, face, corner);
                        mat.transformPosition(p);
                        float[] uv = faceUv == null ? new float[]{0f, 0f} : faceUv.corner(corner);
                        float u = uv[0];
                        float v = uv[1];
                        mesh.vertices.add(new SkinnedMesh.Vertex(p.x, p.y, p.z, u, v));
                        mesh.sourceBones.add(bone.name);
                    }
                    mesh.faces.add(new int[]{base, base + 1, base + 2, base + 3});
                }
            }
        }
        return mesh;
    }

    private static Vector3f corner(CoreCube cube, int face, int corner) {
        float x0 = cube.origin.x - cube.inflate;
        float x1 = cube.origin.x + cube.size.x + cube.inflate;
        float y0 = cube.origin.y - cube.inflate;
        float y1 = cube.origin.y + cube.size.y + cube.inflate;
        float z0 = cube.origin.z - cube.inflate;
        float z1 = cube.origin.z + cube.size.z + cube.inflate;
        int[] c = CORNERS[face][corner];
        float x = c[0] == 0 ? x0 : x1;
        float y = c[1] == 0 ? y0 : y1;
        float z = c[2] == 0 ? z0 : z1;
        if (cube.mirror) {
            x = x0 + x1 - x;
        }
        return new Vector3f(x, y, z);
    }
}
