# CombatOverhaul 交接文档

最后更新：2026-11-13 16:56（Asia/Shanghai）
工程根目录：`C:\Users\Zero\Desktop\more_damage\combatoverhaul`
当前构建：`build/libs/combatoverhaul-1.0.0.jar`，564,559 bytes，2026-11-13 16:56:13
当前自检：`gradlew coreCheck --offline` PASS（默认 + ER + 原生 player/Alex + 多段比例切分 + source_bones + bind_bones + 双层皮肤切割 + 显式跟随映射 + 接缝柔化 + 玩家渲染回接）
部署状态：已同步到 `E:\.minecraft\versions\DLC测试\mods\` 与 `prod_run\mods\`（SHA-256 一致）

---

## 1. 当前一句话状态

工程已经具备可运行的 Minecraft 1.20.1 Forge 模型/动作编辑器、JSON 模型解析、Bedrock 方块渲染、CPU 线性混合蒙皮渲染、动作库、动作时间轴和模型预览器。

**2026-10-30 已完成数据闭环 + 自动关节/骨线 + 通用自动蒙皮（含原生 player/Alex）**：骨骼未知字段（`deform`/`attach`/`rest_matrix`/自定义字段）与 `pivot` 缺失语义都能无损保存；网格 `vertices/faces/weights` 会从内存重新写出并严格校验；新增 `rig/RigJoint`、`rig/RigJointSet`、`client/model/ModelRigJoints`，按已有 bone 层级派生关节与骨段线；新增 `client/skinned/CubeMeshBuilder`，把任意 cube 模型细分成连续网格；新增 `client/skinned/SkinWeightGenerator`，按骨段/骨骼 AABB + 父/子相邻关节带生成 1~2 骨权重；新增编辑器“自动蒙皮”按钮。

**原生 player/Alex 已验证**：扫描树是扁平根（head/body/arms/legs 同级），`ModelRigJoints` 只在派生 skin 拓扑里用标准人形 alias 把 head/手臂/腿挂到 body，不修改模型骨骼树；`SkinWeightGenerator` 对 cube 骨骼使用世界 AABB，避免身体中心线把肩/髋判错。

**“分段关节”已替代旧“插入空关节”**：`client/model/BoneSplitter` 会把选中 cube 骨骼沿最长轴在 50% 处真的切成上下两段，原骨骼保留名称/动作通道，新子骨骼持有下半段；随后清空旧 mesh、从 cube 重建网格并自动蒙皮。旧版只插入空节点、不切几何，正是截图里“多一根怪关节 + 大环”的来源。

**自动蒙皮粘身问题已修**：`CubeMeshBuilder` 为每个顶点写入 `source_bones`（来自哪个 cube 骨骼）；`SkinWeightGenerator` 优先跟随源骨骼/其父骨，第二影响只允许出现在共享关节 4px 内。这样玩家手臂与身体 AABB 重叠时，手臂顶点不会再被 body 抢走并拉成三角面。

**新增“绑定”工具**：R 键/工具按钮切到第 4 个工具；选中骨骼后在视口左键刷选顶点，写入 `bind_bones` 手动主骨种子；Shift+左键擦除。刷选后立即重算权重，手动种子优先于 source_bones/AABB。`bind_bones` 随模型 JSON 保存。

**分段关节改为设置页**：点“分段关节”打开 `JointSplitScreen`，可选 X/Y/Z 轴、2~8 段、每段百分比；默认仍是最长轴 + 50/50，但不再直接执行。确认后才切 cube、建子骨骼链并自动蒙皮；比例和、段数、空段都有校验。

**蒙皮改为可选预览**：cube 模型默认显示方块/BedrockRenderer；工具栏“蒙皮：关/开”切换 skinnedMesh 渲染。纯 mesh 模型没有 cube 可退，仍必须走蒙皮。点“自动蒙皮”或分段成功后自动切到“开”。

**预览不再做玩家专用局部镜像**：之前只镜像纹理/部分骨线、不镜像旋转，导致分段后线框和纹理左右运动相反。现在纹理、骨线、选中框、万向节、绑定笔刷统一使用模型自身坐标。

**设置页按钮可见性已修**：`JointSplitScreen` 原来固定 380x340，GUI scale 较大/窗口较矮时“确认/取消”被推到屏幕外。现在面板宽度/高度按窗口收缩，确认/取消固定在面板底部；Enter 也可确认。`WhiteboxCreateScreen` 的 8 行卦限输入同步压缩行高，避免“创建/取消”掉出屏幕。

**骨骼跟随（完整子树镜像）**：骨骼 JSON 新增 `follow`；选中骨骼后，在父级输入框填目标名并点“跟随”，会把跟随者挂到目标同一父级、复制目标基础姿态，并在渲染前同步目标及其相对路径子树的动画。目标被分段时，跟随者按同轴同比例同步分段，子骨 rest 从目标子骨复制。`FollowCheck` 验证根骨动画同步、目标分段后跟随者子骨出现且 pivot 一致、`follow` 保存重开仍在。原生 `FollowNativeCheck` 验证右袖跟随右臂：`f_103375_ -> f_103375_2`，父级 `f_102811_2`，pivot 一致。这也是双层皮肤的主要用法。

**跟随子骨显式映射**：分段同步时给外层子骨写入 `follow=目标子骨名`（如 `f_103375_2.follow=f_102811_2`），不再只靠子骨索引对应。目标子骨顺序变化时不会再把袖段同步到手掌/其他子骨。

**接缝柔化**：`BoneSplitter` 生成的主骨/外层子骨带 `rigid_joint=true` 和 `soft_axis`；`SkinWeightGenerator` 在普通关节保留原有带，在分段接缝只对切面 `0.35px` 内的一圈顶点做 50/50 混合（`applySplitSeamWeights`）。这样只有接缝一圈柔化，整条上下臂不会变成模糊过渡带；肩、髋等解剖关节仍是正常平滑权重。

**蒙皮偏暗**：`SkinnedMeshRenderer` 原来所有面都写固定法线 `(0,1,0)`，侧脸光照与 BedrockRenderer 不一致。现在按每个面蒙皮后的顶点计算逐面法线，再乘 pose 法线矩阵。

**双层皮肤连接已修（两层都切）**：`BoneSplitter` 现在除了切主骨，还会同步切开直接子级外层骨 sleeve/pants/jacket/hat 的 cube；外层第 2..N 段挂到对应主骨第 2..N 段。因此方块渲染和蒙皮渲染都能让外层跟内层一起弯，不再只靠权重硬拉。若外层骨带自身旋转/缩放/rest_matrix，则跳过自动切（保守）。同时保留权重侧 `resolveSourceBest` 作为补充。

**历史 ER 模型的 pivot/rest_matrix 坐标系已修复**：旧 canonical 文件把 pivot 存成模型空间偏移，但 renderer 按父级 rest 局部坐标使用。现在加载时检测 `pivot_space`，自动 rebase 到 `parent_rest`，保存时写回标记；否则关节位置、自动蒙皮和变形会使用两套坐标空间。

**玩家游戏内骨骼替换已重新接入（条件替换）**：只有已保存且存在 `skinnedMesh` 的 `minecraft:player`/`player_slim` 才替换原版；没有模型或无网格自动回退。下一步：实机验证玩家蒙皮/阴影/盔甲/手持，再补导出导入。

**点状 Rig（新架构，进行中）**：`rig/Rig` 只存 point / parent / pivot / semantic / socket，不含几何；模型 JSON 用 `"rig": "combatoverhaul:humanoid"` 引用，再用 `"rig_points": {"hand_r": "R_Weapon", ...}` 一一绑定。默认人形 rig 生成左右肘/膝/手/脚和 R_Weapon/L_Weapon socket；玩家渲染的 head、hand_r/hand_l 已走 `ParsedModel.rigBone(...)`，模型自身不再硬编码具体骨名。`DefaultLimbRig` 会自动写 rig + 18 个绑定点。模型文件路径仍是 `config/combatoverhaul/entities/minecraft/player_slim.json`。

**Scratch 事件图（新架构，进行中）**：`graph/Graph` + `GraphStore` + 服务端 `GraphRuntime` + `GraphEvents`。每个模型一张 `config/combatoverhaul/graphs/<namespace>/<path>.json`；事件帽子支持 on_spawn/on_tick/on_attack_start/on_hit/on_hurt/on_death/on_signal/on_animation_end/on_item_use/on_jump/on_land；积木支持 play/wait/broadcast/if/repeat/hitbox/damage/heal/velocity/sound/say/particle；广播范围 self/team/附近/global。`/combatoverhaul graph` 打开全屏积木编辑器：左侧积木、事件列、参数面板、事件类型循环、保存/删除/上下移动；左侧积木支持拖到事件列插入；已有积木支持拖动到其他事件列或重新排序；拖到 `if/repeat` 的主体区域会插入该主体，拖动时显示浮动块；单击积木仍会直接追加。动作正常结束时服务端会发 `on_animation_end` 信号。`particle` 积木走 S2C `GraphFxPacket`，服务端只负责广播，客户端在实体位置生成粒子；伤害/治疗/速度仍是服务端权威。

**判定框视口编辑（进行中）**：`ModelEditorScreen` 的“动作/战斗”页新增“判定框拖动”开关。打开后视口按当前选中 action + hitbox 画 12 边线框、中心点和 6 个面手柄（X 红 / Y 绿 / Z 蓝）：拖动面手柄直接改该轴 size 并保持对面不动；拖中心改 offset XY，Ctrl+中心改 Z；Shift 缩放 XY，Shift+Ctrl 缩放 Z。仍可用完整战斗面板改尺寸/偏移/起止 tick。判定框启停由 Scratch 的 `hitbox` 积木控制（`name` 匹配 `ActionStore.HitboxDef.name`）。

**自定义盔甲测试（已跑通一版）**：`config/combatoverhaul/armor/minecraft/diamond_chestplate.json` 使用 9×12×5 胸甲 cube、6 个 `faces` 和 `source_map: {"armor_chest":"body"}`。`ServerArmorTest` 服务端装备后，实机日志：
```text
[ArmorRender] chest=1 diamond_chestplate ...
[ArmorRender] load path=...diamond_chestplate.json exists=true
[ArmorRender] build ok=true mesh=...
[ArmorRender] bind ok=true verts=286
[CombatOverhaul] 自定义盔甲已蒙皮 ... vertices=286
```
第三人称截图 `C:\Users\Zero\Desktop\more_damage\armor_custom_render.png` 中胸甲已贴在角色上半身，不再飞到高处。注意：cube 必须写 `faces`（不能只写 `uv`），否则 `CubeMeshBuilder` 会报“没有可生成的网格面”。

新增纹理尺寸/UV 空间支持：模型 JSON 可写
```json
"texture_width": 64,
"texture_height": 32,
"uv_space": "pixel"
```
`uv_space=pixel` 时，自定义盔甲加载会把 `faces` 的像素 UV 除以纹理宽高，避免 64×32 盔甲纹理被当成 0..1 坐标而只采到边缘/无法覆盖模型。若 `faces` 已经是 0..1 归一化坐标，则写 `"uv_space":"normalized"` 或不写。

自动对齐：`SkinnedArmorRenderer.alignToSkeleton` 会按槽位取玩家 head/body/leg 权重顶点的 AABB，把盔甲网格中心平移到目标 AABB 中心。该逻辑只解决“整体偏移”，若模型自身 pivot/骨骼局部坐标差异很大，仍需要提供实际盔甲 JSON 复现。

**原版盔甲对齐修复**：`renderVanillaFallback` 原来额外做了 `scale(1, 32/24, 1)+translate(0,-24,0)`，而 `SkinnedPlayerRenderer` 的 pose 已经复制了原版 `scale(-1,-1,1)+translate(0,-1.501,0)`；两次变换叠加导致原版盔甲飞高。已删除这两步，HumanoidModel 和 player canonical 坐标现在一致。移除自定义 armor JSON 后，实机钻石胸甲回到角色身体上（截图 `vanilla_armor_align.png`）。

**盔甲按部位自动绑定（新）**：`SkinnedArmorRenderer.classifySourceParts` 对自定义盔甲的每个 `sourceBones` 组计算质心，找最近玩家 rig joint，分别映射；因此胸甲 body/左右袖可以落到 chest/shoulder_r/shoulder_l，不再整件绑一根骨。所有盔甲仍走同一 `bindToSkeleton`，组内按骨段柔化。尚未用多部件胸甲模型实机验证。

**damageoverhaul 新功能（2026-11-01）**：安装 jar `E:...DLC测试/mods/damageoverhaul-1.0.0.jar` 450,178 bytes 新增：
```text
PlayerRace / RaceConfigLoader / RaceSyncPacket / RaceSelectionScreen / RaceClientState
WeaponBlockHandler（stamina、isBlocking、setBlocking、resolveBlock、配置 damageReduction/staminaRatio）
StaminaClientState / StaminaHudOverlay / ModMessages$StaminaSyncPacket
WeaponBlockClientHandler / ShieldBlockHandler / ParCoolBridge
```
编译依赖 `combatoverhaul/libs/damageoverhaul-1.0.0.jar` 仍是旧 425,490 bytes，尚未包含这些 API；接战斗姿态前必须先更新该 jar。客户端 blocking 状态是否由 `BlockStatePacket` 维护还需确认，不能假设 `WeaponBlockHandler.isBlocking` 可直接在客户端动画层调用。

**2026-11-11 重复/死代码清理**：
```text
删除 client/bedrock/BedrockGeometryLoader.java
  - 只是 GeckoModelLoader.convert + BedrockGeometryAdapter 的空壳包装，全项目无引用

HumanoidMeshRenderer
  - 删除未使用的单 model 5/6 参数 render 重载；只保留 layared list 渲染

HumanoidPreviewScreen
  - 删除旧的 ParsedModel armorModel 代理预览、preview.fitView、syncArmorModelVisibility/showTree
  - 现在只保留：真 PlayerModel + 真 HumanoidArmorModel 外层/内层
```
代码重复审计结果（待后续重构，不一次性改）：
```text
ActionBindingScreen / WeaponGrantScreen
  - mouseClicked、refilter 等物品选择逻辑逐行相同

ModelEditorScreen / RigEditorScreen
  - drawLine、拖拽/循环块重复

ClientAnimHandler / ModelEditorScreen
  - 头部姿态/聚合片段重复

GeoImporter / AnimImporter
  - 文件导入/保存 boilerplate 重复

HumanoidPreviewScreen / SkinnedArmorRenderer
  - 盔甲槽可见性 + outer/inner 选择重复
```

**物品选择列表去重**：新增 `client/screen/ui/IdListState`，统一：
```text
allIds / filtered
filter 搜索
scroll 滚动与 clamp
indexAt 行命中
```
`ActionBindingScreen` 与 `WeaponGrantScreen` 已改用该状态，删除各自重复的 `refilter / clampScroll / 手写行索引`。两屏的差异逻辑（页签、writeAll、武器模型生成、保存回包）保持不变。构建 564,559 bytes。

**动画缺失回退原版**：标准人形查看器先调用 `humanoid.setupAnim(player, walkAnimation.position(), speed(), ...)` 建立原版基础姿态；自定义 action 只有实际包含该骨骼通道时才覆盖。没有行走/格挡片段时，骨架继续使用原版走路/待机，不会被清成 rest pose。实机模型仍可见，但需更新布局/盔甲几何。

**原版真盔甲几何（阶段 1）**：标准人形查看器改用 `HumanoidArmorModel` 的两个真实 baked layer：
```text
PLAYER_OUTER_ARMOR / PLAYER_SLIM_OUTER_ARMOR  -> diamond_layer_1（头/胸/靴）
PLAYER_INNER_ARMOR / PLAYER_SLIM_INNER_ARMOR  -> diamond_layer_2（护腿）
```
不再用 PlayerModel 冒充盔甲。`HumanoidMeshRenderer` 捕获真实 ModelPart 几何后绘制；四槽开关分别控制 outer/inner 的 part 可见性。实机截图 `humanoid_true_armor.png` 中钻石盔甲几何已套在角色头/胸/腿/脚位置。

**查看器相机与深度排序（新）**：标准人形查看器支持左键拖拽旋转 yaw/pitch、滚轮缩放；模型旋转中心按 ModelPart 的 1/16 坐标取 `y-0.5`，不再绕脚底。`HumanoidMeshRenderer` 统一捕获皮肤 + 外层盔甲 + 内层护腿到同一个四边形列表，按相机深度排序后再分批绘制，盔甲不再无条件盖在人物上。实机 `viewer_layered.png` / `viewer_rotated.png`。

**游戏内原版盔甲**：`SkinnedArmorRenderer.renderVanillaFallback` 之前错误地用 `HumanoidModel`（皮肤几何）当盔甲；已改为真实 `HumanoidArmorModel` + `PLAYER_OUTER_ARMOR` / `PLAYER_INNER_ARMOR` 层，`LEGS` 使用 inner layer_2，其他槽 outer layer_1。自定义盔甲仍走 `SkinnedArmorRenderer` 自动蒙皮路径。

**自定义盔甲 source_map 优先级修复（新）**：以前即使 JSON 写了 `source_map: armor_chest -> body`，随后 `classifySourceParts` 仍按“最近关节”覆盖，实际把胸甲绑到了 `f_102808_`（head），于是胸甲跟着头转。现在显式 `source_map` 非空时跳过最近点分类，保留作者语义，只对没有 source_map 的模型自动分类。日志验证：`[ArmorRender] source map kept {armor_chest=body}`。

**标准人形查看器（新）**：`client/screen/HumanoidPreviewScreen` + `/combatoverhaul humanoid`。直接实例化 `PlayerModel`（`HumanoidModel` 子类），从当前玩家动作表读动作池；`HumanoidMeshRenderer` 用 VertexConsumer 捕获 ModelPart 几何并按屏幕空间绘制，不再走 ParsedModel 代理。左侧动作列表可点击，右侧四个盔甲槽开关。当前盔甲仍用 PlayerModel 同 part + diamond 盔甲纹理做位置/尺寸代理，**尚未从真实 HumanoidModel 盔甲层提取 inflate 后的几何**；模型本体已可显示（`humanoid_preview6.png`），但尺寸/布局还需调大。

**跟随关节同切面（新）**：`BoneSplitter.syncFollowers` 不再把目标骨的比例直接套到外层跟随骨上；它读取目标骨的实际 `boundaries` 切面坐标，换算成跟随骨本地的切段长度，再调用 `splitInternal`。因此袖子/裤子的分段切面会和手臂/腿的目标切面一致，而不是“各自按相同比例”切在错位处。

**Rig 点编辑器（新）**：`client/screen/RigEditorScreen` + `/combatoverhaul rig`。左侧 18 个点，中间 x/y 俯视点图（线段连接父子），拖动点直接改 pivot；右侧精确 x/y/z 和 parent/semantic；保存到 `config/combatoverhaul/rigs/<ns>/<path>.json`。实机已打开并显示 18 点、父子线、选中 pelvis。

实机判定框线框（`-Dcombatoverhaul.hitboxTest=true`）：模型编辑器动作页显示橙色 12 边 AABB 和中心点，status=`测试：判定框线框已打开`，截图 `C:\Users\Zero\Desktop\more_damage\hitbox_overlay_test.png`。

**默认四肢关节 + 躯干三段**：新增 `client/model/DefaultLimbRig` 和 `/combatoverhaul defaultrig`。对原生 player/Alex 自动切：
```text
right_arm / left_arm  -> 2 段（肩、肘）
right_leg / left_leg  -> 2 段（髋、膝）
body                  -> 3 段（胸、腹、骨盆）
```
外层 sleeve/pants 由 BoneSplitter 同步切；切完自动蒙皮。实机 RigTest：`path=player_slim limbs=5 vertices=3172`。另新增 `/combatoverhaul demoactions`，生成 `demo_swing/walk/skin/idle/elbow/knee/torso`，没有美术动作也能验证关节。

**玩家 Y 位移修正**：`PlayerRenderer` HEAD 执行时原版 `LivingEntityRenderer` 的变换尚未执行；`SkinnedPlayerRenderer` 现在复制 `scale(-1,-1,1)` + `translate(0,-1.501,0)`，否则模型会沉到地下/只剩头。实机第三人称已能看到自定义玩家。

**头部注视 + 武器挂点 + 环境光**：`SkinnedPlayerRenderer.applyHeadLook` 用 `yHeadRot - yBodyRot` 的局部 yaw 和 `xRot` pitch 补 head 旋转（±75°/±60°）；pitch 用 `+=`。`DefaultLimbRig` 会在左右前臂手末端生成 `R_Weapon`/`L_Weapon` attach 骨（pivot y=6），`SkinnedPlayerRenderer` 优先用它们；没有时才退回 arm + 10px。玩家蒙皮/武器/盔甲统一使用原版 `render` 传入的 packed light，不再强制 FULL_BRIGHT，因此夜间会像原版模型一样变暗。武器矩阵只转换骨骼平移，不再把整个矩阵乘 1/16（否则武器被缩成小点）；不再额外放大，保持原物品模型尺寸，只做轻微外移和前方 -0.07 偏移。

**盔甲自动蒙皮**：`SkinnedArmorRenderer` 读取
`config/combatoverhaul/armor/<namespace>/<item_path>.json`。JSON 与实体模型同格式，先由 `CubeMeshBuilder` 生成连续网格，再用 `SkinWeightGenerator.bindToSkeleton` 绑定到当前玩家骨架（支持 `source_bones`/alias），因此肘、膝、躯干三段弯曲时盔甲跟着弯曲；有自己建模的盔甲也走同一套，不使用按部位硬编码的 HumanoidModel。没有该 JSON 的旧盔甲才回退原版 HumanoidModel。

---

## 2. 固定环境

```text
Minecraft 1.20.1
Forge 47.x
Java 17
Mod ID: combatoverhaul
主包: com.yourname.combatoverhaul
Gradle Wrapper: 工程内 gradlew.bat
```

构建：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.7.7-hotspot'
cd C:\Users\Zero\Desktop\more_damage\combatoverhaul
.\gradlew.bat build --offline
.\gradlew.bat coreCheck --offline
# 可选：真实模型只读 round-trip（不写回原文件）
.\gradlew.bat coreCheck --offline -PmodelFile="E:\.minecraft\versions\DLC测试\config\combatoverhaul\entities\combatoverhaul\skinned_player_er.json"
.\gradlew.bat coreCheck --offline -PmodelFile="E:\.minecraft\versions\DLC测试\config\combatoverhaul\entities\minecraft\player.json"
.\gradlew.bat coreCheck --offline -PmodelFile="E:\.minecraft\versions\DLC测试\config\combatoverhaul\entities\minecraft\player_slim.json"
```

`gradle.properties` 已加入 `systemProp.net.minecraftforge.gradle.check.certs=false`：ForgeGradle 配置期会访问 `libraries.minecraft.net` 做证书/连通性检查，离线或受限网络下可能长时间卡在 HTTPS 握手（不是编译失败）。该开关只关闭检查，不影响编译。

部署：

```text
E:\.minecraft\versions\DLC测试\mods\combatoverhaul-1.0.0.jar
C:\Users\Zero\Desktop\more_damage\prod_run\mods\combatoverhaul-1.0.0.jar
```

测试启动器：

```text
C:\Users\Zero\Documents\deepseek-harness\default-workspace\prod_launch.py
```

测试游戏目录：

```text
C:\Users\Zero\Desktop\more_damage\prod_run
```

**不要开多个客户端。** 多次自动截图验证出现过旧进程占用、新进程直接退出的情况。

---

## 3. 已实现且可工作的部分

### 3.1 模型解析与渲染

关键类：

```text
client/model/ParsedModel.java
client/model/ParsedBone.java
client/model/ParsedCube.java
client/bedrock/BedrockGeometry*.java
client/bedrock/BedrockRenderer.java
client/bedrock/BedrockPose.java
client/skinned/SkinnedMesh.java
client/skinned/SkinnedMeshRenderer.java
client/skinned/CubeMeshBuilder.java
client/skinned/SkinWeightGenerator.java
client/model/ModelRigJoints.java
rig/RigJoint.java
rig/RigJointSet.java
client/render/ModelPreviewRenderer.java
```

能力：

- JSON 模型解析（`format=2`）；
- bones / cubes / faces / UV / pivot / rotation_deg / scale；
- `rest_matrix`（9 floats，列主序）支持；
- 方块模型 BedrockRenderer；
- 连续网格 CPU LBS 蒙皮；
- `skinMatrix = animated * inverse(rest)`；
- 每顶点最多 4 骨权重；
- 权重解析归一化、NaN/负数/超 4 影响诊断；保存时非法权重直接拒绝；
- 骨骼未知字段与 `deform/attach/rest_matrix` 无损保存；缺失 `pivot` 不被伪造成显式 0；
- 网格 `vertices/faces/weights` 从内存重新写出，保存后不再丢失自动蒙皮结果；
- 按已有 bone 层级派生关节（rest pose 世界坐标）与骨段线：root 默认非变形、`attach` 挂点/非变形/零长度不画线；不插入骨骼、不改 pivot；
- 通用 cube→连续网格：任意骨骼下的 cube 面按约 1.5px 自适应细分，保留逐面 UV、face_verts、mirror、cube 自转；原生 player/Alex 无需外部脚本；
- 通用自动蒙皮：网格骨骼用骨段（父关节→本关节），cube 骨骼用世界 AABB；最近影响体 + 仅父/子相邻第二影响体，关节带 smoothstep，最多 2 影响；`deform=false/attach` 排除；人形外层 hat/jacket/sleeve/pants 不参与影响体，绑定父主骨；
- `source_bones`：cube 生成网格时记录每顶点来源骨骼，自动蒙皮优先跟随源骨骼/父骨；第二影响只允许在共享关节 4px 内，修复“手臂被 body 粘住拉成三角”；
- `source_bones` 可选写入 JSON（与 vertices 等长），保存重开后仍能正确重算；旧网格无 source 时会从 cube 重建；
- 原生 player/Alex 的扁平根在派生 skin 拓扑里挂到 body（标准 alias，不改 ParsedBone 树）；
- 自动蒙皮只改内存；编辑器“自动蒙皮”后点“保存模型”写回，保存路径会校验权重；
- 旧 canonical `pivot` rebase：`pivot_space` 缺失且有 `rest_matrix` 时，按父级全局 rest 线性逆转换到 `parent_rest`；保存写标记，避免重复迁移；
- 骨骼线使用派生拓扑；root 灰色、挂点橙色、普通关节绿色；
- 预览器实体纹理、UV、面直渲；
- 16px = 1 block，渲染时 /16；
- 全亮 lightmap 可选。

### 3.2 编辑器

关键类：

```text
client/screen/EntityBrowserScreen.java
client/screen/ModelEditorScreen.java
client/screen/HeldItemScreen.java
client/command/CombatOverhaulClientCommand.java
```

已具备：

- 实体/模型浏览器；
- 3D 模型视口；
- 骨骼树、立方体树；
- pivot / rotation / scale / parent 编辑；
- 动作时间轴、关键帧、segment；
- 骨骼遮罩/动画层基础编辑；
- 手持物品条目与视口预览；
- 地面网格；
- 相机 orbit/pan/zoom；
- 模型纹理半透明（35%）；
- 整棵骨骼树线框显示；按已有层级自动派生关节/骨线，状态栏显示“关节数 / 段数”；
- “分段关节”设置页：选 X/Y/Z 轴、2~8 段、每段百分比；原骨骼保留名称/动作通道，后续段成为子骨骼链；确认后切 cube、重建网格并蒙皮；
- 动作 Tab 的“自动蒙皮”按钮：网格模型直接生成权重；cube 模型（含原生 `minecraft:player`/`player_slim`）先生成连续网格再蒙皮；状态栏显示网格顶点/面/骨段/双骨/单骨/回退/警告数量；只改内存，需再点保存模型；
- 选中骨骼后的三轴万向节：把模型空间 X/Y/Z 三个圆投影到当前相机；按投影线段距离命中，拖动按屏幕角增量旋转，符号随相机自动修正；未选骨骼不显示，视口外裁剪；
- “绑定”工具（R 键第 4 项）：选中骨骼后左键刷选顶点，写入 `bind_bones` 手动主骨种子并立即重算权重；Shift+左键擦除；青色小点显示已绑定顶点，笔刷圆圈随鼠标显示；
- “跟随”按钮：父级输入框填目标骨名，当前骨骼及相对路径子树完全镜像目标；清空后点跟随=取消跟随；
- “蒙皮：关/开/必需”按钮：cube 模型默认关，显示方块几何；开则显示 skinnedMesh；纯 mesh 模型显示“必需”；
- 分段设置页底部固定“确认分段并蒙皮 / 取消”，面板随窗口缩放；Enter 也可确认；
- 视口右键旋转、中键屏幕平面平移、滚轮缩放。

编辑器命令：

```text
/combatoverhaul openplayer
/combatoverhaul open <model>
/combatoverhaul open <model> <action>
/combatoverhaul bedrock on|off
```

### 3.3 动作库

关键类：

```text
store/ActionStore.java
store/ActionLibrary.java
combat/ActionCache.java
```

当前测试配置：

```text
E:\.minecraft\versions\DLC测试\config\combatoverhaul\anim_library\
  manifest_er20.json
  er20\*.json   （54 个文件）
```

**重要历史 bug：** `prod_run\...\anim_library\er20` 曾经是一个同名**文件**而不是目录，导致 `[LocoEntry] actions=0`。现已修为目录并复制 54 个 action。以后同步动作库必须确认 `er20` 是目录。

### 3.4 数据闭环自检（2026-10-30）

新增：

```text
src/test/java/com/yourname/combatoverhaul/CoreDataCheck.java
```

覆盖：

- 骨骼 `deform/attach/rest_matrix` 与未知字段 round-trip；
- 缺失 `pivot` 不被写成显式 0；
- 重复权重合并、超和归一化；
- NaN 权重、负权重、缺失骨骼引用保存时拒绝；
- 自动关节：root 非变形、普通子骨变形、`attach` 挂点排除、零长度线跳过、父 pivot 累加；
- 自动蒙皮：合成双骨链的关节 50/50、远端单骨、root/attach 不参与、保存后重开仍存在；
- 真实 ER 模型：1704 顶点 / 20 骨段 / 607 双骨 / 1097 单骨，权重和=1、最多 2 影响、无 root/武器挂点权重、双骨必须父/子相邻、左右不串边；
- 原生 `minecraft:player`：cube→网格 2590 顶点 / 1770 面 / 6 影响体 / 132 双骨 / 2458 单骨；肩部存在 body/right_arm 混合；source_bones 保存/重开一致；
- 原生 `minecraft:player_slim`（Alex）：2538 顶点 / 1726 面 / 6 影响体 / 132 双骨 / 2406 单骨；肩部存在 body/right_arm 混合；
- 分段关节合成测试：选 Y 最长轴、切点 50%、上下段几何对接、子骨骼枢轴正确、切后自动蒙皮、rest pose 不变；
- 多段比例测试：2/3/5 比例（0.2/0.3/0.5）沿 Y 切 3 段，边界 0.4/4.0、子骨骼链 pivot 0.4/3.6、cube 重基正确、自动蒙皮后 rest 不变；
- 接缝柔化：合成手臂 224 顶点中 64 个切面顶点双骨、160 个单骨，第二影响只落在接缝；
- 原生 player 分段：`f_102811_` → `f_102811_2`，2670 顶点 / 8 影响体；子骨骼有权重，保存重开子骨骼和权重仍在；
- 原生 Alex 分段：2608 顶点 / 8 影响体；
- 双层皮肤：分段时同步切出右袖外层子骨 `f_103375_2`，它挂到 `f_102811_2`；下半段袖顶点权重到该外层子骨 >=0.5；
- 手动绑定：body 源顶点设置 `bind_bones=body` 后权重 >=0.99；`bind_bones` 保存重开后仍存在；
- 骨骼跟随：跟随者根骨复制目标 anim；目标切分后跟随者生成对应子骨且 pivot 一致；`follow` 保存重开仍在；
- 原生双层跟随：`f_103375_` 跟随 `f_102811_`，切臂后生成 `f_103375_2`，parent=`f_102811_2`，pivot 一致；
- rest pose 蒙皮前后位移 < 1e-5；旋转目标肢体后肢体顶点移动、无关躯干顶点不动；
- 旧 canonical pivot rebase 幂等（保存后重开 Head/L_Thigh 位置不变）；
- 可用 `-PmodelFile` 对真实 `skinned_player_er.json` 做只读 round-trip（23 关节 / 19 段线）；
- 2026-10-31 实机 `-Dcombatoverhaul.defaultRig=true`：`player_slim limbs=5 vertices=3172`，body 子链 `f_102810_2 -> f_102810_3`，动作表含 `demo_elbow/knee/torso`。

`build.gradle` 注册 `coreCheck`，并让 `check` 依赖它，因此 `gradlew build --offline` 会实际执行；`test` 任务没有可执行的 JUnit 测试（工程没有 JUnit 运行时），真正的自检入口是 `coreCheck`。

---

## 4. 当前玩家渲染状态（重要）

玩家骨骼渲染已重新接入，但做了严格回退：

```text
minecraft:player / player_slim 存在 skinnedMesh -> 走 SkinnedPlayerRenderer
没有模型或没有 skinnedMesh -> 不取消原版 PlayerRenderer
```

关键代码：

```java
// ClientAnimHandler.baseModelOf(Player)
String path = isSlimPlayer(player) ? "player_slim" : "player";
Entry pe = ActionCache.get("minecraft", path, false);
return pe != null && pe.model != null && pe.model.skinnedMesh != null
        ? pe.model : null;
```

`PlayerRendererMixin` 已重新加入 `combatoverhaul.mixins.json`，只在模型有 `skinnedMesh` 时 cancel 原版渲染。

因此：

```text
已保存且自动蒙皮的 player/player_slim -> 游戏内自定义骨段蒙皮
未保存/无 skinnedMesh -> 原版 PlayerRenderer
```

注意：

- 玩家模型必须先在编辑器里“自动蒙皮”并“保存模型”，游戏内才会替换；
- 当前 `SkinnedPlayerRenderer` 只做主体、手持和盔甲尝试，阴影/鞘翅/披风等原版层未完整迁移；
- 原生 player 没有 `R_Weapon/L_Weapon` 时，手持会退到 `right_arm/left_arm` 并沿手臂局部 +8px 移到手末端；位置仍是近似值；
- 这是重新启用后的第一版，实机仍需验证；
- 出现画面问题时可用 `-Dcombatoverhaul.bedrockRender=false` 或客户端指令关闭实验渲染，回退原版。

相关实验类：

```text
client/render/SkinnedPlayerRenderer.java
client/render/SkinnedArmorRenderer.java
mixin/client/PlayerRendererMixin.java
```

实机诊断指令：

```text
/combatoverhaul player       显示加载的 player/player_slim、骨骼数、蒙皮顶点/权重行、是否回退
/combatoverhaul reloadplayer 重载 minecraft:player / player_slim 缓存
/combatoverhaul skinplayer   为当前玩家模型生成 skinnedMesh 并写回 config
/combatoverhaul defaultrig    补左右肘/膝 + 躯干三段 + R_Weapon/L_Weapon，并生成关节 demo
/combatoverhaul demoactions  写入 demo_swing/walk/skin/idle/elbow/knee/torso
/combatoverhaul bedrock off  临时关闭玩家自定义渲染，回退原版
```

2026-10-31 实机验证（prod_run，player_slim）：

```text
/combatoverhaul player -> 骨骼=12 蒙皮顶点=2538 权重行=2538，会替换原版
F5 第三人称 -> 自定义蒙皮玩家可见
```

实机发现并修复：`SkinnedPlayerRenderer` 在 `PlayerRenderer` HEAD 处执行，必须复制原版
`scale(-1,-1,1)` 后的 `translate(0,-1.501,0)`；此前用 +1.5 导致模型整体移出画面/只剩头。
原地截图见：

```text
C:\Users\Zero\Desktop\more_damage\view_0.png
C:\Users\Zero\Desktop\more_damage\view_1.png
```

---

## 5. 数据与备份

### 5.1 当前玩家模型

```text
E:\.minecraft\versions\DLC测试\config\combatoverhaul\entities\combatoverhaul\skinned_player_er.json
426,273 bytes，2026-10-29 21:42
```

这是“可运行版的 ER-ish canonical 23 骨模型”，当前未被玩家渲染使用，但可供编辑器打开。

备份：

```text
skinned_player_er.before_epicfight.json
skinned_player_er.before_min.json
skinned_player_er.before_er_rebase.json
```

### 5.2 其他模型产物

```text
C:\Users\Zero\Desktop\more_damage\er_canonical_skeleton.json
C:\Users\Zero\Desktop\more_damage\skinned_player_ef.json
C:\Users\Zero\Desktop\more_damage\skinned_player_er.json
E:\.minecraft\...\skinned_player_min.json
```

状态：

- `skinned_player_ef.json`：EpicFight biped 转换产物；**拓扑/索引转换不正确，不要用**；
- `skinned_player_min.json`：12 变形骨 + 2 武器挂点；生成成功但未验证，未启用；
- `er_canonical_skeleton.json`：ER 23 骨参考；
- 旧 ER 动画导出保留。

### 5.3 工具

```text
tools/bvh2anim.py
tools/import_epicfight.py
tools/import_er_local.py
tools/make_er_skinned.py
tools/make_min_model.py
tools/convert_epicfight_model.py
tools/make_canonical_model.py
tools/auto_rig_preview.py
tools/draw_skeleton.py
```

不要假设这些工具全部仍适用于当前数据。尤其是：

- `convert_epicfight_model.py` 的 parts 数组解析是错误方向；
- `make_canonical_model.py` 的 ER rest/高度映射经过多轮修改，需重新审查；
- 2026-10-30 已修 `make_canonical_model.py`：pivot 改为父级 rest 局部坐标（`inv(parent_basis) @ (world_child - world_parent)`），输出 `pivot_space: "parent_rest"`。旧 canonical 文件仍由运行时 rebase 兼容；重新生成请用修复后的工具。

---

## 6. 已知缺陷与误导点

### 6.1 已修复

- `Spine.parent = RootRotY` 未映射到 `Pelvis`，导致躯干和腿分成两棵树；已修为 `RootRotY → Pelvis`；
- 玩家模型高度被压到 25px；已恢复 32px；
- 蒙皮模型因 `!mirrorModel` 条件被跳过；已改为蒙皮始终渲染，镜像只翻 X；
- 相机 `camZ=radius+120` 可能导致近平面裁剪；已固定 `camZ=6500` 并 disable depth test；
- 中键误旋转；已恢复中键只平移、右键旋转；
- 动作库 `er20` 路径是文件；已修为目录；
- 两腿之间长线是 `root→Pelvis`；骨骼预览已改为按派生关节拓扑过滤：root/挂点/非变形/零长度不画线；
- `ParsedBone.toJson` 重建骨骼丢 `deform/attach/rest_matrix` 与未知字段；已改为“原始 JSON 为底 + 已知字段覆盖”，`L_Weapon/R_Weapon` 的 `deform=false/attach=true` 可无损保存；
- `ParsedBone` 读取 `rest_matrix` 只取 3 个数导致永远不生效、保存时被删；已改为读取 9 个数；
- `ParsedModel.toJson` 不写 `vertices/faces/weights`；已从 `skinnedMesh` 重新写出，并校验骨骼引用、面索引、权重和；
- 缺失 `pivot` 被当成显式 0；已记录 `hasPivot`，缺失且未编辑时不伪造 pivot；
- 旧 canonical ER 模型的 `pivot` 是模型空间偏移、`rest_matrix` 却按父级局部旋转使用，导致 Head 关节跑到 y≈-17、左右腿 x 反号、自动蒙皮串左右；已加 `pivot_space` 检测与加载期 rebase（保存写 `parent_rest`）；
- 自动蒙皮未实现；已加 `CubeMeshBuilder` + `SkinWeightGenerator` + 编辑器按钮 + 离线验证；
- 原生 player/Alex 扫描树是扁平根，自动蒙皮没有父子关系；已在派生 skin 拓扑里用标准 alias 把 head/手臂/腿挂到 body，并用 cube AABB 代替身体中心线；
- 只支持 `skinnedMesh` 的判断已移除：cube-only 模型会先生成连续网格；
- 万向节原本是固定屏幕椭圆、未选骨骼也按视口 1/3 半径显示，看起来像装饰；已改为选中骨骼才显示、模型空间三圆按相机投影、按投影线段命中、拖动角度旋转，并裁剪在视口内；
- “添加关节”原本只插入空父节点、不切几何，选中后多一根空关节和巨大环，看起来像坏掉；已改为“分段关节”：真的切 cube、建子骨骼链、重建网格并自动蒙皮；
- 分段关节原本固定“最长轴 50/50”；已改为设置页，可选轴、2~8 段、每段百分比；
- 蒙皮原本只要存在 `skinnedMesh` 就强制走蒙皮；已改为预览开关，cube 模型默认显示方块；
- 玩家预览原本只镜像纹理/部分骨线，旋转没镜像，分段后线框和纹理左右运动相反；已移除局部镜像，统一坐标；
- 分段设置页 `JointSplitScreen` 固定 380x340，窗口较矮时确认/取消在屏幕外；已改为按窗口缩放并底部固定按钮，另加 Enter 确认；
- 白模创建页 8 行卦限输入固定行高，窗口较矮时创建/取消可能出屏；已按窗口压缩行高；
- 双层皮肤（sleeve/jacket/pants/hat）旧逻辑固定绑父主骨，主骨分段后下半段外层不跟随；现在分段时同步切外层 cube，并把外层第 2..N 段挂到对应主骨分段；权重侧也按位置选择父骨/分段子链；
- 自动蒙皮曾把原生手臂内侧顶点绑到 body（手臂/身体 AABB 重叠），导致手臂旋转时被拉成三角并粘在身体上；已加 `source_bones` 源 cube 优先 + 共享关节 4px 限制第二影响；
- 自动蒙皮无法猜出某些美术意图；已加“绑定”工具：选中骨骼后刷选顶点写 `bind_bones`，手动种子优先于 source/AABB，Shift+左键擦除；
- 构建配置期 ForgeGradle HTTPS 检查会卡住；已加 `systemProp.net.minecraftforge.gradle.check.certs=false`。

### 6.2 未解决

- 玩家真实骨骼替换未验证；
- 盔甲代理 `SkinnedArmorRenderer` 未装备验证，而且仍是原版 `HumanoidModel`，不是 ER 骨段蒙皮；
- 武器握持位置/角度未校准；
- run/sneak/dodge 没有可靠实机验证；
- 动作库中 ER/EpicFight 来源混合，动作 bone 名并不完全统一；
- 旧 12/16/23 骨代码只清理了一部分；
- `tools/convert_epicfight_model.py` 不能生成正确拓扑；
- “浏览器内渲染”若指 Web 预览，当前工程没有独立 Web 渲染器；当前只有 Minecraft GUI 编辑器预览；
- 自动蒙皮已实现并通过数据/数学自检（含原生 player/Alex），但**没有启动客户端做视觉验证**；cube 网格 UV/接缝/关节弯曲的实际观感仍需实机确认；
- `CubeMeshBuilder` 每个 cube 面独立生成顶点，没有焊接接缝；权重按位置一致，但顶点数/文件体积偏大；
- 原生 player/Alex 的外层 hat/jacket/sleeve/pants 与主 cube 重叠，参与影响体会让每顶点变成“主/外层”冗余双骨、挤掉真正的肩部 body/arm 混合；现在外层骨不参与影响体，网格顶点绑定到其父主骨（hat→head、jacket→body、sleeve→arm、pants→leg）；
- 非人形扁平根模型没有 body alias 时不会做 synthetic parent，可能只有单骨权重；
- `source_bones` 只在 CubeMeshBuilder 生成网格时写入；旧模型里已有的蒙皮网格没有该字段，自动蒙皮会从 cube 重建（会丢弃手工改过的 mesh/权重）；已有旧权重不会自动修正，需要再点一次“自动蒙皮”；
- 第二影响被限制在共享关节 4px 内；不同比例模型可能需要调整半径，目前是固定常量；
- 绑定笔刷按屏幕投影距离选点，会同时选到模型背面顶点；没有深度遮挡/框选/面选择/笔刷半径 UI；
- 绑定没有撤销；只能 Shift+左键擦除或重开模型；绑定只决定主骨，第二影响仍由 4px 关节规则生成；
- 分段生成的跟随子骨有显式 `follow=目标子骨` 映射；旧数据没有显式映射时，先按 `_2/_3` 分段序号匹配，再退回子骨索引；
- 分段关节现在只做接缝柔化：`applySplitSeamWeights` 在切面 `0.35px` 内设置 50/50，其余顶点保持单骨；旧模型没有 `rigid_joint` 时按 `arm__2 / f_102811_2` 命名兜底识别接缝；
- 如果以后要可调软硬，需要在设置页增加关节柔化参数；
- `follow` 只有在编辑器点“跟随”时才会把跟随者重挂到目标父级；直接手写 JSON 加 follow 不会自动改父级，世界矩阵不保证相等；
- 跟随会每帧复制目标基础姿态/动画，跟随者自身的手动旋转/关键帧会被覆盖；
- 目标分段会同步直接跟随者；跟随者的嵌套跟随者、以及目标新增的非分段子骨不会递归克隆；
- 蒙皮开关只影响预览，不影响保存的 `skinnedMesh`；重新打开 cube 模型默认仍是关，需再切“开”；
- 预览已移除玩家专用镜像；玩家模型在编辑器里的左右朝向现在与模型数据一致，但可能和游戏内镜像习惯不同；没有单独的“预览镜像”开关；
- 万向节使用模型全局 X/Y/Z 轴，不是骨骼局部轴；没有 local/global 切换，也没有实机拖拽验证；
- 分段关节只支持 cube 骨骼；设置页可选 X/Y/Z、2~8 段、每段百分比，但没有任意切点、预览和撤销；mesh-only 骨骼（无 cube）不支持；
- 分段后内层上下臂在切点被 1~2px 第二骨混合，旋转时出现模糊过渡带，而外层分段是硬段；现在普通分段只保留切面 0.35px 一圈柔化，其余保持单骨，内外层一致；
- 分段会把 face_verts 烘焙成 AABB origin/size（清空精确顶点），轴对齐方块模型正确；带 cube 自转/mirror/rest_matrix 的骨骼会拒绝；
- 外层 sleeve/pants/jacket/hat 只有在是主骨直接子级、cube 轴对齐、无自身旋转/缩放/rest_matrix 时才会同步切；嵌套外层或带自身变换的外层仍只靠权重跟随；
- 旧版“插入空关节”已经保存到模型里的空 `bone_13` 不会自动清理；需要手动删除或重开未修改的模型；新版不会再产生这种空关节；
- `HumanoidRig` 已同步 weights/source_bones 的骨骼名，但 held_items/action_zones 里的真实骨骼名尚未一起改名；
- `SkinnedMeshRenderer` 已改为逐面法线（蒙皮后顶点叉积 × pose 法线）；法线方向依赖 CubeMeshBuilder 的面绕序，实机若仍有暗面需要再核对绕序；
- 磁盘上的 `skinned_player_er.json` 仍是旧 pivot 格式；加载时内存 rebase，只有保存后才会写回 `pivot_space` 和修正后的 pivot。直接给 `GeoExporter` 等“绕过 ParsedModel”的代码读旧文件仍会得到旧坐标；
- `tools/make_canonical_model.py` 仍按模型空间偏移写 pivot；本轮修了运行时兼容，工具本身待同步（见第 5.3 节）；
- 缺失 `pivot` 的骨骼：骨线端点用包围盒回退位置，但关节十字仍画在骨骼原点；当前 `skinned_player_er` 所有骨骼都有 pivot，未触发该分支；
- `RigJointSet` 在显示骨骼线时按帧从模型派生；23 骨开销很小，但大骨架后续应缓存并在编辑 pivot 后失效。

### 6.3 验证证据

已实际编译（2026-11-13 16:56，完整 build）：

```text
BUILD SUCCESSFUL in 30s
> Task :coreCheck
CoreDataCheck PASS
> Task :test
```

已实际自检：

```text
coreCheck default:
  SplitSeamCheck two=64 one=160
  SplitJointCheck PASS child=arm__2 vertices=224 segments=2
  SplitMultiCheck PASS segments=3 vertices=288 influences=3
  CoreDataCheck PASS
coreCheck -PmodelFile=...skinned_player_er.json:
  RealModelCheck PASS vertices=1704 faces=992 weights=1704 joints=23 lines=19
  AutoSkinCheck PASS vertices=1704 segments=20 two=607 one=1097 sums=0.0
  L_Weapon deform=false/attach=true preserved
  rest pose 1704 顶点不变；R_Forearm 弯曲时手臂动、躯干不动
  pivot rebase 幂等（保存重开 Head/L_Thigh 位置不变）
coreCheck -PmodelFile=...minecraft\player.json:
  SplitJointCheck PASS child=arm__2 vertices=224 segments=2
  ManualBindCheck PASS entity=minecraft:player vertex=665 bone=f_102810_
  SplitNativeCheck PASS entity=minecraft:player bone=f_102811_ -> f_102811_2 vertices=2638 segments=7
  NativeModelAutoSkin PASS entity=minecraft:player cubes=1770 vertices=2590 segments=6 two=132 one=2458 shoulderBlend=true source_bones=true
coreCheck -PmodelFile=...minecraft\player_slim.json:
  SplitJointCheck PASS child=arm__2 vertices=224 segments=2
  SplitNativeCheck PASS entity=minecraft:player_slim bone=f_102811_ -> f_102811_2 vertices=2576 segments=7
  NativeModelAutoSkin PASS entity=minecraft:player_slim cubes=1726 vertices=2538 segments=6 two=132 one=2406 shoulderBlend=true source_bones=true
```

已实际截图（上一轮，未重新验证）：

```text
C:\Users\Zero\Desktop\more_damage\game_hierarchy.png
C:\Users\Zero\Desktop\more_damage\game_walk_live.png
```

日志曾确认：

```text
[Loco] want=Walk unarmed forward
[LocoEntry] entry=true actions=54
```

未做：

- Dedicated Server 启动测试；
- 多人同步测试；
- 盔甲装备实机测试；
- run/sneak/dodge 实机测试。

---

## 7. 架构经验（避免重复踩坑）

1. **模型、骨架、动作必须同源。**  
   混用 ER 骨架 + EpicFight 动作 + 自定义 rest 会产生反关节和 rest 错位。

2. **不要给每根骨随意设 rest 矩阵。**  
   如果动作是相对源 rest 的，就必须同时导入源 rest 或统一变换到目标轴。

3. **方块皮肤不等于可弯曲网格。**  
   关节处必须有足够权重过渡；否则只是刚性旋转。

4. **相机/裁剪和模型消失先分开排查。**  
   先确认模型是否真的进入渲染函数，再查近平面、深度、scissor、镜像分支。

5. **根骨可视化要单独处理。**  
   `root` 在脚底、`Pelvis` 在骨盆时，`root→Pelvis` 会被误看成拉长的躯干骨。`root` 应标记非变形，不画实体骨线。

6. **编辑器功能优先于玩家替换。**  
   用户最终要的是“导出模型、设关节、蒙皮、柔化、自由控制模型”，不是先把玩家动画接进游戏。

---

## 8. 下一步（按优先级）

### P0-a（已完成 2026-10-30）：模型/网格数据闭环

- `deform`/`attach`/`rest_matrix`/未知骨骼字段无损保存；
- 缺失 `pivot` 保留“缺失”语义；
- `vertices/faces/weights` 从内存重新写出；
- 权重归一化与非法数据拒绝；
- `coreCheck` 离线自检接入 `build`。

### P0-b：稳定编辑器预览

- 确认 MMB 只平移、RMB 只旋转；
- 确认半透明/骨骼线开关；
- 确认模型不再随机消失；
- 不再改玩家实体。

### P1（已完成 2026-10-30）：通用自动关节

已实现 `rig/RigJoint`、`rig/RigJointSet`、`client/model/ModelRigJoints`：

```text
读取已有 bone/part 层级（不改树）
关节 = 子骨 origin/pivot（rest pose 世界坐标）
骨线 = 父 origin → 子 origin
root = 非变形，不画长线
零长度连接跳过
无 pivot 才回退包围盒最近面中心
attach / 非变形不画线
```

真实 `skinned_player_er`：23 关节 / 19 段线。没有引入人形专用中心线。

### P1-b（已完成 2026-10-30）：分段关节

`client/model/BoneSplitter` + `JointSplitScreen`：

```text
点击“分段关节” -> 设置页
  选 X/Y/Z 轴
  选 2~8 段
  填每段百分比（总和 100%）
确认 -> 按比例切 cube
原骨骼保留名称/动作通道，拿第 1 段
后续段生成子骨骼链，枢轴在段起点
face_verts 烘焙为 AABB，UV 按比例裁切
清空旧 mesh -> CubeMeshBuilder 重建 -> SkinWeightGenerator 自动蒙皮
```

多段测试：0.2/0.3/0.5 沿 Y 切 3 段，边界 0.4/4.0，子骨骼 pivot 0.4/3.6，自动蒙皮 rest 不变。原生 player 默认 2 段：`f_102811_ -> f_102811_2`，2638 顶点 / 7 影响体；Alex：2576 顶点。

### P2（已完成 2026-10-30）：通用自动蒙皮

已实现 `client/skinned/SkinWeightGenerator` + 编辑器“自动蒙皮”：

```text
cube 骨骼用世界 AABB；网格骨骼用父→本关节骨段
优先 source_bones 对应的源骨骼/父骨
最近影响体 + 仅父/子相邻第二影响体
第二影响只允许在共享关节 4px 内
关节带 smoothstep（0.75~2.5px）
最多 2 影响，权重归一化
deform=false / attach 排除
保存路径写回 weights + source_bones
```

真实 `skinned_player_er`：20 骨段 / 607 双骨 / 1097 单骨；rest pose 1704 顶点位移 <1e-5；`R_Forearm` 弯曲时手臂动、躯干不动；双骨必须父/子相邻，左右不串边。

原生 `minecraft:player`：2590 顶点 / 1770 面 / 6 影响体 / 132 双骨 / 2458 单骨；`player_slim`（Alex）：2538 顶点 / 1726 面 / 6 影响体 / 132 双骨 / 2406 单骨；肩部均检测到 body/arm 混合；`source_bones` 保存重开一致。原生 cube 模型无需外部脚本，直接走编辑器“自动蒙皮”。

手动绑定：编辑器“绑定”工具刷选顶点写 `bind_bones`，自动蒙皮优先使用手动种子；`bind_bones` 随 JSON 保存。`ManualBindCheck` 验证 body 源顶点强制绑定 body 后权重 >=0.99，保存重开仍在。

蒙皮预览可关：cube 模型默认显示方块，工具栏“蒙皮”按钮切到 skinnedMesh；纯 mesh 模型没有 cube 可退，显示“蒙皮：必需”。玩家预览的局部 X 镜像已移除，纹理、骨线、选中框、万向节使用同一坐标。

默认四肢/躯干 rig：`DefaultLimbRig` 对 player/Alex 切左右臂 2 段、左右腿 2 段、body 3 段（外层袖/裤同步切），并在前臂末端建 `R_Weapon`/`L_Weapon` attach 骨；自动蒙皮后保存。`DemoActions` 提供 demo_swing/walk/skin/idle/elbow/knee/torso，实机 `defaultRig` 日志 `limbs=5 vertices=3172`，body 子链 `f_102810_2 -> f_102810_3`。

玩家头部注视：`applyHeadLook` 用 head 相对 body 的 yaw + 玩家 pitch 补 head 旋转；武器优先用 `R_Weapon/L_Weapon`，手末端位置由 attach 骨 pivot 决定，不再依赖 arm+8px 的近似。武器矩阵只改平移单位，不缩放整个矩阵；不做额外放大，保持原物品尺寸，只前移 -0.07。玩家渲染改为环境 packed light，不做自发光。

盔甲自定义模型路径：

```text
config/combatoverhaul/armor/<namespace>/<item_path>.json
```

例：`minecraft:diamond_chestplate` -> `config/combatoverhaul/armor/minecraft/diamond_chestplate.json`。
JSON 与实体模型同级（roots/cubes/texture/bone_aliases/source_bones），运行时生成网格后自动绑定玩家骨架；`source_bones` 指向玩家语义骨（head/body/right_arm/right_leg 等）即可跟随弯曲。没有该文件的旧盔甲回退 HumanoidModel。

骨骼跟随：`follow` 字段 + 编辑器“跟随”按钮；跟随者重挂到目标父级并复制目标基础姿态，渲染前 `syncFollow` 按子骨索引镜像目标子树动画。目标分段时 `BoneSplitter.syncFollowers` 同步切跟随者并复制子骨 rest。`FollowCheck` 验证 sleeve 跟随 arm：根骨 anim 同步、arm 切出 `arm__2` 后 sleeve 生成 `sleeve__2` 且 pivot 一致、follow 保存重开仍在。原生 `FollowNativeCheck` 验证右袖 `f_103375_` 跟随右臂，切臂后生成 `f_103375_2` 并挂到 `f_102811_2`；同时写入 `f_103375_2.follow=f_102811_2` 显式映射，避免子骨顺序变化后同步错段。双层皮肤优先用这条跟随路径。

接缝柔化：`BoneSplitter` 主骨/外层子骨带 `rigid_joint=true`/`soft_axis`；`applySplitSeamWeights` 只在切面 0.35px 内把上下段设为 50/50，其余顶点保持单骨。`SplitSeamCheck` 验证合成手臂 224 顶点中 64 个接缝顶点双骨、160 个单骨，柔化只落在接缝。`SkinnedMeshRenderer` 改为逐面法线，修复蒙皮整体偏暗。

双层皮肤：分段时同步切外层 sleeve/jacket/pants/hat 的 cube，外层第 2..N 段挂到对应主骨分段；权重侧再按位置选择父骨/分段子链。`SplitNativeCheck` 验证右袖外层子骨 `f_103375_2` 挂到 `f_102811_2`，且下半段袖顶点权重 >=0.5。

### P3（下一步）：导出/导入格式

建议：

```json
{
  "schema_version": 1,
  "rig": {"bones": []},
  "mesh": {"vertices": [], "faces": [], "uvs": []},
  "weights": [],
  "joints": [],
  "bone_lines": []
}
```

先实现纯 Java/JSON 层，不依赖 Minecraft 渲染类。

### P4：只在该闭环通过后再考虑

- 武器挂点；
- 盔甲绑定；
- 玩家运行时；
- 复杂战斗系统。

---

## 9. 交接时最重要的三句话

1. **玩家实机渲染已条件接回：有 skinnedMesh 的存档模型才会替换，没有则原版；不要用未保存模型判断。**
2. **动作库已经能读 54 条，但动作/骨架/模型三者来源仍不完全统一。**
3. **数据闭环、自动关节/骨线、多段比例分段、接缝柔化、逐面蒙皮法线、双层皮肤同步切割、可选蒙皮预览、源骨骼优先自动蒙皮、手动刷选绑定、完整子树骨骼跟随、设置页按钮可见性修复、玩家条件渲染回接、默认四肢+躯干三段 rig、内置 demo 动作、头部注视、武器 attach 骨、环境光、自定义盔甲模型自动蒙皮（ER + 原生 player/Alex）已完成；下一阶段：阴影/披风、无自定义 JSON 的旧盔甲自动代理、手持朝向微调、接缝柔化参数可调、绑定笔刷深度/撤销、rig/mesh 导出导入。**
