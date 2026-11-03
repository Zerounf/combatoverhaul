package com.yourname.combatoverhaul.rig;

/**
 * 从已有骨骼层级派生的只读关节。
 *
 * <p>坐标是基础姿态（rest pose）的模型空间位置，单位与模型一致（16px = 1 block）。
 * 不修改 {@code ParsedBone} 树；关节只是可视化/蒙皮/导出共同使用的派生数据。
 *
 * @param name     真实骨骼名（不是语义 alias）
 * @param parent   直接父骨骼名；根为 null
 * @param x        基础姿态世界 X
 * @param y        基础姿态世界 Y
 * @param z        基础姿态世界 Z
 * @param localX   相对父级的局部 pivot X（缺少 pivot 时为回退值）
 * @param localY   相对父级的局部 pivot Y
 * @param localZ   相对父级的局部 pivot Z
 * @param deform   是否参与蒙皮/骨段
 * @param attach   是否武器/饰品挂点（不参与蒙皮与骨段线）
 * @param hasPivot 源骨骼是否显式提供 pivot；false 表示坐标来自回退规则
 */
public record RigJoint(String name, String parent, float x, float y, float z,
                       float localX, float localY, float localZ,
                       boolean deform, boolean attach, boolean hasPivot) {
}
