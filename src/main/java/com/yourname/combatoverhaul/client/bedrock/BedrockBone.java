package com.yourname.combatoverhaul.client.bedrock;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 骨骼：局部枢轴/基础旋转/缩放 + 立方体 + 子骨骼。
 *
 * <p>坐标与当前编辑器/原版 {@code ModelPart} 一致（模型空间 +Y 朝下），
 * 因此可以直接复用现有相机、实体变换和动作数据。
 */
public final class BedrockBone {

    public final String name;
    public final BedrockBone parent;
    /** 相对父级的枢轴（模型单位） */
    public final Vector3f pivot = new Vector3f();
    /** 基础旋转（弧度） */
    public final Vector3f rotationRad = new Vector3f();
    /** 基础缩放 */
    public final Vector3f scale = new Vector3f(1, 1, 1);
    public final List<BedrockCube> cubes = new ArrayList<>();
    public final List<BedrockBone> children = new ArrayList<>();
    public boolean visible = true;

    /** 关节柔化（与 ParsedBone 语义一致），0 = 刚性 */
    public float softJoint;
    /** 0=x 1=y 2=z */
    public int softAxis = 1;

    public BedrockBone(String name, BedrockBone parent) {
        this.name = name == null ? "" : name;
        this.parent = parent;
    }
}
