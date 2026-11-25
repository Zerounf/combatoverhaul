package com.yourname.combatoverhaul.engine.adapters;

import com.yourname.combatoverhaul.engine.core.AnimationClip;
import com.yourname.combatoverhaul.engine.core.BoneAnimation;
import com.yourname.combatoverhaul.store.ActionStore;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 现有 ActionStore.Action -> 运行时 AnimationClip。 */
public final class ActionClipAdapter {

    private ActionClipAdapter() {
    }

    public static AnimationClip from(ActionStore.Action action) {
        AnimationClip clip = new AnimationClip(action == null ? "" : action.name);
        if (action == null) {
            return clip;
        }
        clip.lengthTicks = Math.max(1f, action.lengthTicks);
        clip.loop = action.loop;
        for (ActionStore.Keyframe keyframe : action.keyframes) {
            float t = Math.max(0f, Math.min(clip.lengthTicks, keyframe.time));
            for (var e : keyframe.bones.entrySet()) {
                String bone = e.getKey();
                ActionStore.BoneChannel c = e.getValue();
                BoneAnimation anim = clip.bone(bone);
                anim.position.add(t, new Vector3f(c.pos[0], c.pos[1], c.pos[2]));
                anim.rotation.add(t, new Quaternionf().rotationZYX(
                        (float) Math.toRadians(c.rotDeg[2]),
                        (float) Math.toRadians(c.rotDeg[1]),
                        (float) Math.toRadians(c.rotDeg[0])));
                anim.scale.add(t, new Vector3f(c.scale[0], c.scale[1], c.scale[2]));
            }
        }
        return clip;
    }
}
