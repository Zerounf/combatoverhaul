package com.yourname.combatoverhaul.client.model;

import com.yourname.combatoverhaul.client.skinned.SkinWeightGenerator;

/**
 * 给原生 player/player_slim 补四肢关节：上臂/前臂、大腿/小腿各切 2 段。
 * 不依赖人形专用动作；外层袖/裤由 BoneSplitter 同步切。
 */
public final class DefaultLimbRig {

    private DefaultLimbRig() {
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public int splitLimbs;
        public int vertices;
        public int twoInfluence;
    }

    public static Result apply(ParsedModel model) {
        Result r = new Result();
        if (model == null) {
            r.error = "没有模型";
            return r;
        }
        String[][] limbs = {
                {"right_arm"}, {"left_arm"}, {"right_leg"}, {"left_leg"}
        };
        for (String[] item : limbs) {
            ParsedBone root = model.findBone(item[0]);
            if (root == null) {
                continue;
            }
            if (hasSplitChild(root)) {
                continue;
            }
            BoneSplitter.Result split = BoneSplitter.split(model, root, 1,
                    new float[]{0.5f, 0.5f});
            if (!split.ok) {
                r.error = item[0] + " 分段失败: " + split.error;
                return r;
            }
            r.splitLimbs++;
        }
        // 躯干三段：胸腔 / 腹部 / 骨盆（按高度 1/3、1/3、1/3）。
        ParsedBone body = model.findBone("body");
        if (body != null && !hasSplitChild(body)) {
            BoneSplitter.Result split = BoneSplitter.split(model, body, 1,
                    new float[]{1f / 3f, 1f / 3f, 1f / 3f});
            if (!split.ok) {
                r.error = "body 三段失败: " + split.error;
                return r;
            }
            r.splitLimbs++;
        }
        if (r.splitLimbs == 0 && !hasAnySplit(model)) {
            r.error = "没有找到可切分的左右臂/腿（需要 humanoid alias）";
            return r;
        }
        addWeaponAnchors(model);
        model.flatten();
        bindHumanoidRig(model);
        model.skinnedMesh = null;
        SkinWeightGenerator.Result skin = SkinWeightGenerator.generate(model);
        if (!skin.ok) {
            r.error = "四肢分段后自动蒙皮失败: " + skin.error;
            return r;
        }
        r.ok = true;
        r.vertices = skin.vertices;
        r.twoInfluence = skin.twoInfluence;
        return r;
    }

    private static void bindHumanoidRig(ParsedModel model) {
        model.setRigId("combatoverhaul:humanoid");
        java.util.List<String> body = splitChildNames(model, "body");
        model.bindRigPoint("root", findReal(model, "body"));
        model.bindRigPoint("chest", findReal(model, "body"));
        model.bindRigPoint("spine", body.size() >= 1 ? body.get(0) : findReal(model, "body"));
        model.bindRigPoint("pelvis", body.size() >= 2 ? body.get(1) : findReal(model, "body"));
        model.bindRigPoint("neck", findReal(model, "head"));
        model.bindRigPoint("head", findReal(model, "head"));

        bindArm(model, "right_arm", "shoulder_r", "elbow_r", "hand_r", "R_Weapon");
        bindArm(model, "left_arm", "shoulder_l", "elbow_l", "hand_l", "L_Weapon");
        bindLeg(model, "right_leg", "hip_r", "knee_r", "foot_r");
        bindLeg(model, "left_leg", "hip_l", "knee_l", "foot_l");
    }

    private static void bindArm(ParsedModel model, String semantic,
                                String shoulder, String elbow, String hand, String anchor) {
        String root = findReal(model, semantic);
        if (root == null) {
            return;
        }
        String lower = splitChildName(model, semantic);
        model.bindRigPoint(shoulder, root);
        model.bindRigPoint(elbow, lower == null ? root : lower);
        model.bindRigPoint(hand, model.findBone(anchor) == null ? (lower == null ? root : lower) : anchor);
    }

    private static void bindLeg(ParsedModel model, String semantic,
                                String hip, String knee, String foot) {
        String root = findReal(model, semantic);
        if (root == null) {
            return;
        }
        String lower = splitChildName(model, semantic);
        model.bindRigPoint(hip, root);
        model.bindRigPoint(knee, lower == null ? root : lower);
        model.bindRigPoint(foot, lower == null ? root : lower);
    }

    private static String findReal(ParsedModel model, String semantic) {
        ParsedBone b = model.findBone(semantic);
        return b == null ? null : b.name;
    }

    private static void addWeaponAnchors(ParsedModel model) {
        addWeaponAnchor(model, "right_arm", "R_Weapon");
        addWeaponAnchor(model, "left_arm", "L_Weapon");
    }

    private static void addWeaponAnchor(ParsedModel model, String armSemantic, String anchorName) {
        if (model.findBone(anchorName) != null) {
            return;
        }
        ParsedBone arm = model.findBone(armSemantic);
        if (arm == null) {
            return;
        }
        ParsedBone parent = arm;
        String lower = splitChildName(model, armSemantic);
        if (lower != null) {
            ParsedBone p = model.findBone(lower);
            if (p != null) {
                parent = p;
            }
        }
        ParsedBone anchor = new ParsedBone(anchorName);
        anchor.parent = parent;
        anchor.hasPivot = true;
        // 上臂根：手末端约 +10px；前臂子段：子段长 6px。
        anchor.pivotY = parent == arm ? 10f : 6f;
        anchor.rotX = anchor.rotY = anchor.rotZ = 0f;
        anchor.scaleX = anchor.scaleY = anchor.scaleZ = 1f;
        anchor.visible = true;
        anchor.deform = false;
        anchor.hasDeform = true;
        anchor.attach = true;
        anchor.hasAttach = true;
        parent.children.add(anchor);
    }

    public static boolean hasSplitChild(ParsedBone bone) {
        if (bone == null) {
            return false;
        }
        for (ParsedBone child : bone.children) {
            if (isSplitChild(bone, child)) {
                return true;
            }
        }
        return false;
    }

    public static String splitChildName(ParsedModel model, String semantic) {
        java.util.List<String> names = splitChildNames(model, semantic);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 返回该根的整条分段子链（body 三段时返回两个子骨）。 */
    public static java.util.List<String> splitChildNames(ParsedModel model, String semantic) {
        java.util.List<String> out = new java.util.ArrayList<>();
        ParsedBone bone = model == null ? null : model.findBone(semantic);
        if (bone == null) {
            return out;
        }
        ParsedBone parent = bone;
        for (int guard = 0; guard < 8; guard++) {
            ParsedBone next = null;
            for (ParsedBone child : parent.children) {
                // 链式分段的第二段名仍以原根开头（f_102810_3），不能只按直接父名判断。
                if (isSplitOfRoot(bone, child)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            out.add(next.name);
            parent = next;
        }
        return out;
    }

    private static boolean hasAnySplit(ParsedModel model) {
        for (ParsedBone b : model.bones) {
            for (ParsedBone c : b.children) {
                if (isSplitChild(b, c)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSplitOfRoot(ParsedBone root, ParsedBone child) {
        if (root == null || child == null) {
            return false;
        }
        String p = root.name;
        if (p.endsWith("_")) {
            return child.name.length() > p.length()
                    && child.name.startsWith(p)
                    && Character.isDigit(child.name.charAt(p.length()));
        }
        return child.name.startsWith(p + "__");
    }

    private static boolean isSplitChild(ParsedBone parent, ParsedBone child) {
        if (parent == null || child == null) {
            return false;
        }
        String p = parent.name;
        if (p.endsWith("_")) {
            return child.name.length() > p.length()
                    && child.name.startsWith(p)
                    && Character.isDigit(child.name.charAt(p.length()));
        }
        return child.name.startsWith(p + "__");
    }
}
