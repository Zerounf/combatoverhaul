package com.yourname.combatoverhaul.rig;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 派生关节集合：关节 + 可绘制骨段线。
 *
 * <p>只读、不可变。构造时去重并跳过零长度/非 deform/挂点线。
 * 渲染、自动蒙皮、导出都应从这里取拓扑，避免各处各写一套父链规则。
 */
public final class RigJointSet {

    /** 一条父关节 → 子关节的骨段线 */
    public record BoneLine(String parent, String child) {
    }

    private static final RigJointSet EMPTY = new RigJointSet(List.of(), List.of());

    private final List<RigJoint> joints;
    private final List<BoneLine> lines;
    private final Map<String, RigJoint> byName;
    private final Set<String> lineKeys;

    /**
     * @param joints 关节列表；顺序保留
     * @param lines  骨段线；调用方应已过滤，构造器仍会去重
     */
    public RigJointSet(List<RigJoint> joints, List<BoneLine> lines) {
        this.joints = List.copyOf(joints);
        this.lines = List.copyOf(lines);
        Map<String, RigJoint> map = new LinkedHashMap<>();
        for (RigJoint j : this.joints) {
            if (j != null && j.name() != null) {
                map.put(j.name(), j);
            }
        }
        this.byName = Map.copyOf(map);
        Set<String> keys = new LinkedHashSet<>();
        for (BoneLine line : this.lines) {
            if (line != null && line.parent() != null && line.child() != null) {
                keys.add(key(line.parent(), line.child()));
            }
        }
        this.lineKeys = Set.copyOf(keys);
    }

    public static RigJointSet empty() {
        return EMPTY;
    }

    public List<RigJoint> joints() {
        return joints;
    }

    public List<BoneLine> lines() {
        return lines;
    }

    public RigJoint joint(String name) {
        return name == null ? null : byName.get(name);
    }

    public boolean hasLine(String parent, String child) {
        return parent != null && child != null && lineKeys.contains(key(parent, child));
    }

    private static String key(String parent, String child) {
        return parent + '\u0000' + child;
    }
}
