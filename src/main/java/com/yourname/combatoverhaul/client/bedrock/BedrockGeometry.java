package com.yourname.combatoverhaul.client.bedrock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变（构建后只读）的 Bedrock 几何：骨骼森林 + 名称索引。
 *
 * <p>几何只存基础姿态/立方体/逐面 UV；动画位移/旋转/缩放放在
 * {@link BedrockPose} 中，每个实体/每次播放一份，避免共享可变状态。
 */
public final class BedrockGeometry {

    public final int textureWidth;
    public final int textureHeight;
    public final List<BedrockBone> roots;
    public final Map<String, BedrockBone> bones;

    public BedrockGeometry(int textureWidth, int textureHeight,
                           List<BedrockBone> roots, Map<String, BedrockBone> bones) {
        this.textureWidth = textureWidth <= 0 ? 64 : textureWidth;
        this.textureHeight = textureHeight <= 0 ? 64 : textureHeight;
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.bones = bones == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(bones));
    }

    public BedrockGeometry withTextureSize(int w, int h) {
        return new BedrockGeometry(w, h, new ArrayList<>(roots), new LinkedHashMap<>(bones));
    }

    public BedrockBone bone(String name) {
        return name == null ? null : bones.get(name);
    }
}
