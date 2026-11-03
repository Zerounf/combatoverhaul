package com.yourname.combatoverhaul.client.bedrock;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个立方体：一组逐面 quad + 可选的立方体自身旋转（Bedrock cube rotation）。
 *
 * <p>顶点保持在骨骼局部空间；渲染时先应用 cube rotation，再应用骨骼链。
 */
public final class BedrockCube {

    public final List<BedrockQuad> quads = new ArrayList<>(6);
    /** 立方体自转枢轴（骨骼局部空间，模型单位） */
    public final Vector3f rotationPivot = new Vector3f();
    /** 立方体自转（弧度，Z→Y→X，与 ModelPart 一致） */
    public final Vector3f rotationRad = new Vector3f();
    public boolean hasRotation;

    public void add(BedrockQuad quad) {
        if (quad != null) {
            quads.add(quad);
        }
    }

    public boolean hasRotation() {
        return hasRotation;
    }
}
