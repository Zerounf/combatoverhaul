package com.yourname.combatoverhaul.engine.adapters;

import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.engine.core.Skeleton;
import com.yourname.combatoverhaul.engine.core.SkeletonBone;
import org.joml.Quaternionf;

/** 编辑器 ParsedModel -> 运行时 Skeleton。 */
public final class SkeletonAdapter {

    private SkeletonAdapter() {
    }

    public static Skeleton from(ParsedModel model) {
        Skeleton skeleton = new Skeleton();
        if (model == null) {
            return skeleton;
        }
        for (ParsedBone bone : model.bones) {
            String parent = bone.parent == null ? "" : bone.parent.name;
            SkeletonBone out = skeleton.add(bone.name, parent);
            out.pivot.set(bone.pivotX, bone.pivotY, bone.pivotZ);
            out.rotation.set(new Quaternionf().rotationZYX(bone.rotZ, bone.rotY, bone.rotX));
            out.scale.set(bone.scaleX, bone.scaleY, bone.scaleZ);
        }
        return skeleton;
    }
}
