package com.yourname.combatoverhaul.client.bedrock;

import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个实体/单次播放的动画姿态：骨骼局部空间的增量位移/旋转/缩放。
 *
 * <p>基础姿态在 {@link BedrockGeometry} 里；这里只存动作/程序动画的叠加量，
 * 因此同一份几何可以被多个实体安全共享，不再出现 {@code ParsedBone.anim*}
 * 被客户端渲染和整合服判定同时改写的问题。
 */
public final class BedrockPose {

    public static final class BonePose {
        public final Vector3f position = new Vector3f();
        public final Vector3f rotationRad = new Vector3f();
        public final Vector3f scale = new Vector3f(1, 1, 1);
    }

    private final Map<String, BonePose> poses = new HashMap<>();
    /** true = position/rotation/scale 已是 ModelPart 的绝对局部值（含原版程序动画）；false = 在几何基础姿态上叠加增量 */
    public boolean absolute;

    public BonePose get(String boneName) {
        return boneName == null ? null : poses.get(boneName);
    }

    public BedrockPose absolute() {
        this.absolute = true;
        return this;
    }

    public BonePose getOrCreate(String boneName) {
        return poses.computeIfAbsent(boneName == null ? "" : boneName, k -> new BonePose());
    }

    public Map<String, BonePose> all() {
        return poses;
    }

    /** 预览用：对整份姿态做 X 反射（仅位置 x 取反，旋转保持原方向）。 */
    public void mirrorX() {
        for (BonePose p : poses.values()) {
            p.position.x = -p.position.x;
        }
    }

    /** 从当前 ParsedModel 的 anim 叠加状态生成（渲染/预览前调用）。 */
    public static BedrockPose fromAnim(ParsedModel model) {
        BedrockPose pose = new BedrockPose();
        if (model == null) {
            return pose;
        }
        for (ParsedBone bone : model.bones) {
            BonePose p = new BonePose();
            p.position.set(bone.animPosX, bone.animPosY, bone.animPosZ);
            p.rotationRad.set(bone.animRotX, bone.animRotY, bone.animRotZ);
            p.scale.set(bone.animScaleX, bone.animScaleY, bone.animScaleZ);
            pose.poses.put(bone.name, p);
        }
        return pose;
    }

    /**
     * 玩家专用：把原版 {@code PlayerModel.setupAnim} + 本模组写入 ModelPart 的动作姿态
     * 转成绝对局部 pose。原版盔甲/披风/手持层也用同一套 ModelPart，因此不会再错位。
     */
    public static BedrockPose fromPlayerModel(
            net.minecraft.client.model.PlayerModel<?> pm, ParsedModel model) {
        BedrockPose pose = new BedrockPose();
        if (pm == null || model == null) {
            return pose;
        }
        pose.absolute = true;
        for (ParsedBone bone : model.bones) {
            String semantic = model.boneAliases.get(bone.name);
            if (semantic == null) {
                continue;
            }
            boolean outer = semantic.equals("hat") || semantic.equals("jacket")
                    || semantic.endsWith("_sleeve") || semantic.endsWith("_pants");
            // 已迁移的外层是主骨骼子节点：identity，继承父骨骼；
            // 旧 JSON 里外层还是根节点：给绝对 pose，至少能跟原版同姿态。
            if (outer && bone.parent != null) {
                pose.poses.put(bone.name, new BonePose());
                continue;
            }
            net.minecraft.client.model.geom.ModelPart part = switch (semantic) {
                case "head" -> pm.head;
                case "hat" -> pm.hat;
                case "body" -> pm.body;
                case "jacket" -> pm.jacket;
                case "right_arm" -> pm.rightArm;
                case "left_arm" -> pm.leftArm;
                case "right_sleeve" -> pm.rightSleeve;
                case "left_sleeve" -> pm.leftSleeve;
                case "right_leg" -> pm.rightLeg;
                case "left_leg" -> pm.leftLeg;
                case "right_pants" -> pm.rightPants;
                case "left_pants" -> pm.leftPants;
                default -> null;
            };
            if (part == null) {
                continue;
            }
            BonePose p = new BonePose();
            p.position.set(part.x, part.y, part.z);
            p.rotationRad.set(part.xRot, part.yRot, part.zRot);
            p.scale.set(part.xScale, part.yScale, part.zScale);
            pose.poses.put(bone.name, p);
        }
        return pose;
    }
}
