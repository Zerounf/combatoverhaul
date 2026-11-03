package com.yourname.combatoverhaul.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置测试动作：没有美术动画时，用它们验证游戏内骨骼/蒙皮/接缝。
 *
 * <p>动作使用语义骨名（right_arm / left_leg ...），ActionStore 会按模型 alias 解析。
 */
public final class DemoActions {

    private DemoActions() {
    }

    public static List<ActionStore.Action> actions() {
        List<ActionStore.Action> out = new ArrayList<>();
        out.add(swing());
        out.add(walk());
        out.add(skinStress());
        out.add(idle());
        return out;
    }

    /** 写入当前模型的动作表（保留模型几何/纹理/未知字段）。 */
    public static boolean write(String modid, String path) {
        List<ActionStore.Action> actions = ActionStore.load(modid, path, false);
        actions.removeIf(a -> a.name != null && a.name.startsWith("demo_"));
        actions.addAll(actions());
        return ActionStore.save(modid, path, false, actions);
    }

    /** 写入基础 demo + 肘/膝 demo（使用当前模型实际生成的子骨名）。 */
    public static boolean writeWithJoints(String modid, String path,
                                          com.yourname.combatoverhaul.client.model.ParsedModel model) {
        List<ActionStore.Action> actions = ActionStore.load(modid, path, false);
        actions.removeIf(a -> a.name != null && a.name.startsWith("demo_"));
        actions.addAll(actions());
        String rArm = com.yourname.combatoverhaul.client.model.DefaultLimbRig
                .splitChildName(model, "right_arm");
        String lArm = com.yourname.combatoverhaul.client.model.DefaultLimbRig
                .splitChildName(model, "left_arm");
        String rLeg = com.yourname.combatoverhaul.client.model.DefaultLimbRig
                .splitChildName(model, "right_leg");
        String lLeg = com.yourname.combatoverhaul.client.model.DefaultLimbRig
                .splitChildName(model, "left_leg");
        if (rArm != null || lArm != null || rLeg != null || lLeg != null) {
            actions.add(elbow(rArm, lArm));
            actions.add(knee(rLeg, lLeg));
        }
        java.util.List<String> body = com.yourname.combatoverhaul.client.model.DefaultLimbRig
                .splitChildNames(model, "body");
        if (body.size() >= 2) {
            actions.add(torso(body.get(0), body.get(1)));
        }
        return ActionStore.save(modid, path, false, actions);
    }

    private static ActionStore.Action torso(String upper, String lower) {
        var a = base("demo_torso", 40f, true);
        rot(a, 0f, upper, 0, 0, 0);
        rot(a, 0f, lower, 0, 0, 0);
        rot(a, 10f, upper, 12, 0, 0);
        rot(a, 10f, lower, -12, 0, 0);
        rot(a, 20f, upper, 0, 15, 0);
        rot(a, 20f, lower, 0, -15, 0);
        rot(a, 30f, upper, -12, 0, 0);
        rot(a, 30f, lower, 12, 0, 0);
        rot(a, 40f, upper, 0, 0, 0);
        rot(a, 40f, lower, 0, 0, 0);
        return a;
    }

    private static ActionStore.Action elbow(String right, String left) {
        var a = base("demo_elbow", 30f, true);
        if (right != null) {
            rot(a, 0f, right, 0, 0, 0);
            rot(a, 10f, right, -100, 0, 0);
            rot(a, 20f, right, 0, 0, 0);
            rot(a, 30f, right, 0, 0, 0);
        }
        if (left != null) {
            rot(a, 0f, left, 0, 0, 0);
            rot(a, 10f, left, 100, 0, 0);
            rot(a, 20f, left, 0, 0, 0);
            rot(a, 30f, left, 0, 0, 0);
        }
        return a;
    }

    private static ActionStore.Action knee(String right, String left) {
        var a = base("demo_knee", 30f, true);
        if (right != null) {
            rot(a, 0f, right, 0, 0, 0);
            rot(a, 10f, right, 75, 0, 0);
            rot(a, 20f, right, 0, 0, 0);
            rot(a, 30f, right, 0, 0, 0);
        }
        if (left != null) {
            rot(a, 0f, left, 0, 0, 0);
            rot(a, 10f, left, -75, 0, 0);
            rot(a, 20f, left, 0, 0, 0);
            rot(a, 30f, left, 0, 0, 0);
        }
        return a;
    }

    private static ActionStore.Action base(String name, float length, boolean loop) {
        var a = new ActionStore.Action();
        a.name = name;
        a.lengthTicks = length;
        a.loop = loop;
        a.zone = "full";
        return a;
    }

    private static void rot(ActionStore.Action a, float time, String bone,
                            float x, float y, float z) {
        var kf = a.atOrCreate(time);
        var c = kf.bones.computeIfAbsent(bone, k -> new ActionStore.BoneChannel());
        c.rotDeg = new float[]{x, y, z};
    }

    private static ActionStore.Action swing() {
        var a = base("demo_swing", 20f, false);
        rot(a, 0f, "right_arm", 0, 0, 0);
        rot(a, 0f, "left_arm", 0, 0, 0);
        rot(a, 5f, "right_arm", -95, -15, 0);
        rot(a, 5f, "left_arm", 15, 0, 0);
        rot(a, 10f, "right_arm", 25, 15, 0);
        rot(a, 10f, "left_arm", -10, 0, 0);
        rot(a, 20f, "right_arm", 0, 0, 0);
        rot(a, 20f, "left_arm", 0, 0, 0);
        return a;
    }

    private static ActionStore.Action walk() {
        var a = base("demo_walk", 20f, true);
        rot(a, 0f, "right_leg", -30, 0, 0);
        rot(a, 0f, "left_leg", 30, 0, 0);
        rot(a, 0f, "right_arm", 25, 0, 0);
        rot(a, 0f, "left_arm", -25, 0, 0);
        rot(a, 10f, "right_leg", 30, 0, 0);
        rot(a, 10f, "left_leg", -30, 0, 0);
        rot(a, 10f, "right_arm", -25, 0, 0);
        rot(a, 10f, "left_arm", 25, 0, 0);
        rot(a, 20f, "right_leg", -30, 0, 0);
        rot(a, 20f, "left_leg", 30, 0, 0);
        rot(a, 20f, "right_arm", 25, 0, 0);
        rot(a, 20f, "left_arm", -25, 0, 0);
        return a;
    }

    private static ActionStore.Action skinStress() {
        var a = base("demo_skin", 40f, true);
        rot(a, 0f, "right_arm", 0, 0, 0);
        rot(a, 0f, "left_arm", 0, 0, 0);
        rot(a, 10f, "right_arm", -45, 30, 70);
        rot(a, 10f, "left_arm", -20, -30, -70);
        rot(a, 10f, "head", 0, 35, 0);
        rot(a, 20f, "right_arm", 40, -30, -60);
        rot(a, 20f, "left_arm", 40, 30, 60);
        rot(a, 20f, "head", 0, -35, 0);
        rot(a, 30f, "right_arm", -45, 30, 70);
        rot(a, 30f, "left_arm", -20, -30, -70);
        rot(a, 30f, "head", 0, 35, 0);
        rot(a, 40f, "right_arm", 0, 0, 0);
        rot(a, 40f, "left_arm", 0, 0, 0);
        rot(a, 40f, "head", 0, 0, 0);
        return a;
    }

    private static ActionStore.Action idle() {
        var a = base("demo_idle", 60f, true);
        rot(a, 0f, "body", 0, 0, 0);
        rot(a, 0f, "head", 0, 0, 0);
        rot(a, 0f, "right_arm", 0, 0, 5);
        rot(a, 0f, "left_arm", 0, 0, -5);
        rot(a, 30f, "body", 1.5f, 0, 0);
        rot(a, 30f, "head", -2f, 8f, 0);
        rot(a, 30f, "right_arm", 2f, 0, 8);
        rot(a, 30f, "left_arm", 2f, 0, -8);
        rot(a, 60f, "body", 0, 0, 0);
        rot(a, 60f, "head", 0, 0, 0);
        rot(a, 60f, "right_arm", 0, 0, 5);
        rot(a, 60f, "left_arm", 0, 0, -5);
        return a;
    }
}
