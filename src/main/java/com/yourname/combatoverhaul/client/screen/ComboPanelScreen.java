package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.combat.ComboStore;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 连段编辑：左侧连段链列表（新建/删除/重命名），右侧当前链的动作节点树
 * （每行一段：动作名 [输入] 取消窗口），底部为选中节点的字段编辑。
 *
 * <p>节点树语义：根 = 起手动作（由武器绑定/徒手/技能键触发进入）；
 * 子节点 = {下一段动作, 输入类型, 取消窗口[起,止]tick}——父动作播放中窗口内按下
 * 对应输入 → 取消并接续。保存至模型 JSON 同目录的 &lt;模型名&gt;.combos.json。
 */
public class ComboPanelScreen extends Screen {
    private static final int ROW_H = 12;
    private static final int LIST_TOP = 44;

    private final Screen parent;
    private final String modid;
    private final String path;
    private final boolean weapon;

    /** 动作名候选（循环选择与保存校验用） */
    private final List<ActionStore.Action> actions = new ArrayList<>();
    private ComboStore.File file;
    private int chainIndex = -1;
    private int nodeIndex = -1;
    private int chainScroll = 0;
    private int nodeScroll = 0;
    private String status = "";

    private EditBox nameBox;
    private EditBox actionBox;
    private EditBox winStartBox;
    private EditBox winEndBox;
    private Button btnInput;

    private int listX1 = 190;
    private int treeX0 = 200;

    public ComboPanelScreen(Screen parent, String modid, String path, boolean weapon) {
        super(Component.translatable("gui.combatoverhaul.combo_title", modid, path));
        this.parent = parent;
        this.modid = modid;
        this.path = path;
        this.weapon = weapon;
    }

    // ------------------------------------------------------------------ 数据

    private ComboStore.Chain chain() {
        return file != null && chainIndex >= 0 && chainIndex < file.chains.size()
                ? file.chains.get(chainIndex) : null;
    }

    private ComboStore.Node node() {
        ComboStore.Chain c = chain();
        return c != null && nodeIndex >= 0 && nodeIndex < c.nodes.size() ? c.nodes.get(nodeIndex) : null;
    }

    private boolean isRoot(ComboStore.Node n) {
        return n == null || n.parent < 0;
    }

    /** 节点深度（根=0；防御性限制防环；坏文件里的越界 parent 不能把编辑器打崩） */
    private int depthOf(ComboStore.Chain c, int idx) {
        int d = 0;
        int cur = idx;
        while (cur >= 0 && cur < c.nodes.size() && d < 64) {
            cur = c.nodes.get(cur).parent;
            if (cur >= 0) {
                d++;
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ 构建

    @Override
    protected void init() {
        if (file == null) {
            file = new ComboStore.File();
            String content = ComboStore.read(modid, path, weapon);
            if (content != null) {
                try {
                    file = ComboStore.parse(content);
                } catch (RuntimeException ignored) {
                }
            }
            actions.addAll(ActionStore.load(modid, path, weapon));
            chainIndex = file.chains.isEmpty() ? -1 : 0;
        }

        int y = 24;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.combo_new"), b -> newChain())
                .bounds(10, y, 64, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.combo_del"), b -> delChain())
                .bounds(78, y, 64, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.combo_add_child"), b -> addNode())
                .bounds(treeX0, y, 76, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.combo_del_node"), b -> delNode())
                .bounds(treeX0 + 80, y, 76, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.combo_save"), b -> save())
                .bounds(this.width - 168, y, 78, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.back"), b -> back())
                .bounds(this.width - 86, y, 76, 16).build());

        // 底部字段：行0 = 链名 | 节点动作；行1 = （非根）输入切换 + 取消窗口
        int f0 = this.height - 72;
        int f1 = this.height - 54;
        nameBox = new EditBox(this.font, 44, f0, 140, 14, Component.literal(""));
        nameBox.setMaxLength(64);
        this.addRenderableWidget(nameBox);
        actionBox = new EditBox(this.font, 238, f0, 110, 14, Component.literal(""));
        actionBox.setMaxLength(64);
        this.addRenderableWidget(actionBox);
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleAction(-1))
                .bounds(350, f0, 14, 14).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleAction(1))
                .bounds(366, f0, 14, 14).build());
        btnInput = this.addRenderableWidget(Button.builder(Component.literal(""), b -> toggleInput())
                .bounds(treeX0, f1, 70, 14).build());
        winStartBox = new EditBox(this.font, 316, f1, 40, 14, Component.literal(""));
        this.addRenderableWidget(winStartBox);
        winEndBox = new EditBox(this.font, 366, f1, 40, 14, Component.literal(""));
        this.addRenderableWidget(winEndBox);

        syncFields();
    }

    /** 字段 ← 状态（重建/选择变化后调用） */
    private void syncFields() {
        ComboStore.Chain c = chain();
        ComboStore.Node n = node();
        nameBox.setValue(c != null ? c.name : "");
        nameBox.setEditable(c != null);
        actionBox.setValue(n != null ? n.action : "");
        actionBox.setEditable(n != null);
        boolean leaf = n != null && !isRoot(n);
        btnInput.active = leaf;
        btnInput.setMessage(Component.translatable(leaf && "skill".equals(n.input)
                ? "gui.combatoverhaul.combo_input_skill" : "gui.combatoverhaul.combo_input_attack"));
        winStartBox.setValue(leaf ? trimNum(n.windowStart) : "");
        winEndBox.setValue(leaf ? trimNum(n.windowEnd) : "");
        winStartBox.setEditable(leaf);
        winEndBox.setEditable(leaf);
    }

    private static String trimNum(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    /** 字段 → 状态（选择变化/保存前调用；数值非法提示且不写入该字段） */
    private void collect() {
        ComboStore.Chain c = chain();
        if (c != null) {
            c.name = nameBox.getValue().trim();
        }
        ComboStore.Node n = node();
        if (n != null) {
            n.action = actionBox.getValue().trim();
            if (!isRoot(n)) {
                try {
                    n.windowStart = Float.parseFloat(winStartBox.getValue().trim());
                } catch (RuntimeException e) {
                    status = Component.translatable("gui.combatoverhaul.box_invalid").getString();
                }
                try {
                    n.windowEnd = Float.parseFloat(winEndBox.getValue().trim());
                } catch (RuntimeException e) {
                    status = Component.translatable("gui.combatoverhaul.box_invalid").getString();
                }
            }
        }
    }

    // ------------------------------------------------------------------ 节点操作

    private void newChain() {
        collect();
        ComboStore.Chain c = new ComboStore.Chain();
        c.name = Component.translatable("gui.combatoverhaul.combo_new").getString() + (file.chains.size() + 1);
        file.chains.add(c);
        chainIndex = file.chains.size() - 1;
        nodeIndex = -1;
        syncFields();
    }

    private void delChain() {
        if (chain() == null) {
            status = Component.translatable("gui.combatoverhaul.combo_need_chain").getString();
            return;
        }
        collect();
        file.chains.remove(chainIndex);
        chainIndex = file.chains.isEmpty() ? -1 : Math.min(chainIndex, file.chains.size() - 1);
        nodeIndex = -1;
        syncFields();
    }

    /** 添加下一段：空链先建根（动作名取首个动作），否则在选中节点下挂子节点 */
    private void addNode() {
        ComboStore.Chain c = chain();
        if (c == null) {
            status = Component.translatable("gui.combatoverhaul.combo_need_chain").getString();
            return;
        }
        collect();
        if (c.nodes.isEmpty()) {
            c.nodes.add(new ComboStore.Node(actions.isEmpty() ? "" : actions.get(0).name, -1, "attack", 0, 0));
            nodeIndex = 0;
        } else {
            if (node() == null) {
                status = Component.translatable("gui.combatoverhaul.combo_need_node").getString();
                return;
            }
            ComboStore.Node p = c.nodes.get(nodeIndex);
            // 默认窗口 = 父动作末段 2tick（可中断后摇的常用起点），动作名默认沿用父段
            float end = 2;
            for (ActionStore.Action a : actions) {
                if (a.name.equals(p.action)) {
                    end = Math.max(1, a.lengthTicks);
                    break;
                }
            }
            c.nodes.add(new ComboStore.Node(p.action, nodeIndex, "attack", Math.max(0, end - 2), end));
            nodeIndex = c.nodes.size() - 1;
        }
        syncFields();
    }

    /** 删除节点：子节点上提挂到被删节点的父级（根被删则子节点成为新根） */
    private void delNode() {
        ComboStore.Chain c = chain();
        if (c == null) {
            status = Component.translatable("gui.combatoverhaul.combo_need_chain").getString();
            return;
        }
        if (node() == null) {
            status = Component.translatable("gui.combatoverhaul.combo_need_node").getString();
            return;
        }
        collect();
        int rm = nodeIndex;
        int up = c.nodes.get(rm).parent;
        for (ComboStore.Node n : c.nodes) {
            if (n.parent == rm) {
                n.parent = up;
            }
        }
        c.nodes.remove(rm);
        for (ComboStore.Node n : c.nodes) {
            if (n.parent > rm) {
                n.parent--;
            }
        }
        nodeIndex = c.nodes.isEmpty() ? -1 : Math.min(nodeIndex, c.nodes.size() - 1);
        syncFields();
    }

    /** 动作名在动作列表中循环取值 */
    private void cycleAction(int dir) {
        ComboStore.Node n = node();
        if (n == null) {
            status = Component.translatable("gui.combatoverhaul.combo_need_node").getString();
            return;
        }
        collect();
        if (actions.isEmpty()) {
            return;
        }
        int idx = -1;
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).name.equals(n.action)) {
                idx = i;
                break;
            }
        }
        idx = idx < 0 ? 0 : (idx + dir + actions.size()) % actions.size();
        n.action = actions.get(idx).name;
        syncFields();
    }

    private void toggleInput() {
        ComboStore.Node n = node();
        if (n == null || isRoot(n)) {
            return;
        }
        collect();
        n.input = "skill".equals(n.input) ? "attack" : "skill";
        syncFields();
    }

    private boolean save() {
        collect();
        int unknown = 0;
        for (ComboStore.Chain c : file.chains) {
            for (ComboStore.Node n : c.nodes) {
                if (!n.action.isEmpty() && actions.stream().noneMatch(a -> a.name.equals(n.action))) {
                    unknown++;
                }
            }
        }
        if (ComboStore.write(modid, path, weapon, file)) {
            ActionCache.invalidate(modid, path, weapon);
            com.yourname.combatoverhaul.combat.ComboStore.invalidate(modid, path, weapon);
            status = Component.translatable(unknown == 0
                    ? "gui.combatoverhaul.combo_saved" : "gui.combatoverhaul.combo_saved_unknown", unknown).getString();
            return true;
        }
        status = Component.translatable("gui.combatoverhaul.save_fail").getString();
        return false;
    }

    private void back() {
        collect();
        if (!save()) {
            return; // 写失败时留在本界面，避免未保存的连段被下一次加载覆盖
        }
        this.minecraft.setScreen(new ModelEditorScreen(parent, modid, path, weapon));
    }

    @Override
    public void onClose() {
        back();
    }

    // ------------------------------------------------------------------ 鼠标

    private int listBottom() {
        return this.height - 80;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < listX1) {
            int visible = Math.max(1, (listBottom() - LIST_TOP - 2) / ROW_H);
            int max = Math.max(0, file.chains.size() - visible);
            chainScroll = Math.max(0, Math.min(max, chainScroll - (int) (delta * 3)));
        } else {
            ComboStore.Chain c = chain();
            int total = c == null ? 0 : c.nodes.size();
            int visible = Math.max(1, (listBottom() - LIST_TOP - 2) / ROW_H);
            int max = Math.max(0, total - visible);
            nodeScroll = Math.max(0, Math.min(max, nodeScroll - (int) (delta * 3)));
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= LIST_TOP && mouseY <= listBottom()) {
            int row = ((int) mouseY - LIST_TOP - 2) / ROW_H;
            if (mouseX >= 10 && mouseX <= listX1) {
                int idx = row + chainScroll;
                if (idx >= 0 && idx < file.chains.size()) {
                    collect();
                    chainIndex = idx;
                    nodeIndex = -1;
                    nodeScroll = 0;
                    syncFields();
                    return true;
                }
            } else if (mouseX >= treeX0) {
                ComboStore.Chain c = chain();
                int idx = row + nodeScroll;
                if (c != null && idx >= 0 && idx < c.nodes.size()) {
                    collect();
                    nodeIndex = idx;
                    syncFields();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        int bottom = listBottom();

        // 左：连段链列表
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.combo_chains", file.chains.size()),
                10, 32, 0x909090);
        g.enableScissor(0, LIST_TOP, listX1, bottom);
        for (int i = 0; i < file.chains.size(); i++) {
            int y = LIST_TOP + 2 + (i - chainScroll) * ROW_H;
            if (y < LIST_TOP - ROW_H || y > bottom) {
                continue;
            }
            ComboStore.Chain c = file.chains.get(i);
            int color = i == chainIndex ? 0xFFFF60 : 0xFFFFFF;
            g.drawString(this.font, c.name + " (" + c.nodes.size() + ")", 12, y, color);
        }
        g.disableScissor();

        // 右：当前链节点树
        ComboStore.Chain c = chain();
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.combo_nodes",
                c == null ? 0 : c.nodes.size()), treeX0, 32, 0x909090);
        g.enableScissor(treeX0, LIST_TOP, this.width, bottom);
        if (c != null) {
            for (int i = 0; i < c.nodes.size(); i++) {
                int y = LIST_TOP + 2 + (i - nodeScroll) * ROW_H;
                if (y < LIST_TOP - ROW_H || y > bottom) {
                    continue;
                }
                ComboStore.Node n = c.nodes.get(i);
                String indent = "  ".repeat(depthOf(c, i));
                String text;
                int color = i == nodeIndex ? 0xFFFF60 : 0xFFFFFF;
                if (n.action.isEmpty() || actions.stream().noneMatch(a -> a.name.equals(n.action))) {
                    color = i == nodeIndex ? 0xFF6060 : 0xE07070; // 动作名不存在 → 红
                }
                if (n.parent < 0) {
                    text = indent + "▶ " + n.action + "  ("
                            + Component.translatable("gui.combatoverhaul.combo_entry").getString() + ")";
                } else {
                    String in = Component.translatable("skill".equals(n.input)
                            ? "gui.combatoverhaul.combo_input_short_skill"
                            : "gui.combatoverhaul.combo_input_short_attack").getString();
                    text = indent + "└ " + n.action + "  [" + in + "] "
                            + trimNum(n.windowStart) + "~" + trimNum(n.windowEnd) + "t";
                }
                g.drawString(this.font, text, treeX0 + 2, y, color);
            }
        }
        g.disableScissor();

        // 底部字段标签 + 起手段提示
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.combo_chain_name"),
                10, this.height - 69, 0x909090);
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.combo_action"),
                treeX0, this.height - 69, 0x909090);
        ComboStore.Node n = node();
        if (n != null && !isRoot(n)) {
            g.drawString(this.font, Component.translatable("gui.combatoverhaul.combo_window"),
                    274, this.height - 51, 0x909090);
            g.drawString(this.font, "~", 358, this.height - 51, 0x909090);
        } else {
            g.drawString(this.font, Component.translatable("gui.combatoverhaul.combo_root_hint"),
                    treeX0, this.height - 51, 0x70A070);
        }

        if (!status.isEmpty()) {
            g.drawString(this.font, status, 10, this.height - 14, 0x80FF80);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
