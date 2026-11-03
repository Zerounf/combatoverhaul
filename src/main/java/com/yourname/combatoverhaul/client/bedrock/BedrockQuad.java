package com.yourname.combatoverhaul.client.bedrock;

import org.joml.Vector3f;

/**
 * 一个面（quad）：4 个骨骼局部空间顶点 + 对应 UV + 法线。
 *
 * <p>与 {@code ParsedCube.faces/face_verts} 一一对应；渲染端不再经过
 * 原版 {@code ModelPart.Cube} 的盒式 UV 反推，因此逐面 uv_size、uv_rotation、
 * inflate、mirror 都能原样保留。
 */
public final class BedrockQuad {

    /** 4 个顶点，顺序与 UV 一一对应 */
    public final Vector3f[] positions = new Vector3f[4];
    /** 4 组 [u,v]（0~1 归一化） */
    public final float[][] uv = new float[4][2];
    /** 面法线（骨骼局部空间，未变换） */
    public final Vector3f normal;

    public BedrockQuad(Vector3f[] positions, float[][] uv, Vector3f normal) {
        if (positions == null || positions.length != 4) {
            throw new IllegalArgumentException("quad needs 4 positions");
        }
        if (uv == null || uv.length != 4) {
            throw new IllegalArgumentException("quad needs 4 uvs");
        }
        for (int i = 0; i < 4; i++) {
            this.positions[i] = new Vector3f(positions[i]);
            this.uv[i][0] = uv[i][0];
            this.uv[i][1] = uv[i][1];
        }
        this.normal = normal == null ? new Vector3f(0, 1, 0) : new Vector3f(normal);
    }
}
