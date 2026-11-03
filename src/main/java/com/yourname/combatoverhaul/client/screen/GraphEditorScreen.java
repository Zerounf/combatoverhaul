package com.yourname.combatoverhaul.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.combatoverhaul.graph.Graph;
import com.yourname.combatoverhaul.graph.GraphStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全屏 Scratch 式事件图工作区。
 *
 * <p>左侧积木栏，中间事件脚本列，右侧参数面板。点击积木追加到选中脚本末尾；
 * 选中 if/repeat 时追加到它的主体。保存为 config/combatoverhaul/graphs/ns/path.json。
 */
public final class GraphEditorScreen extends Screen {

    private int paletteW = 170;
    private int propW = 190;
    private static final int TOP = 34;
    private static final int BLOCK_H = 24;
    private static final int GAP = 4;

    private final String namespace;
    private final String path;
    private Graph graph;
    private int selectedScript;
    private Graph.Block selectedBlock;
    private final List<EditBox> propBoxes = new ArrayList<>();
    private final Map<String, EditBox> propByField = new LinkedHashMap<>();
    private String status = "";
    private Graph.Block draggingBlock;
    private String dragNewType;
    private int dragScript = -1;
    private double dragX;
    private double dragY;
    private double dragStartX;
    private double dragStartY;

    private static final String[] EVENTS = {
            "on_spawn", "on_tick", "on_attack_start", "on_hit", "on_hurt", "on_death",
            "on_animation_end", "on_signal", "on_item_use", "on_jump", "on_land"
    };

    private static final String[][] PALETTE = {
            {"play", "动画：播放片段", "clip"},
            {"wait", "控制：等待", "ticks"},
            {"broadcast", "广播：发送信号", "signal"},
            {"if", "控制：如果", "condition"},
            {"repeat", "控制：重复", "count"},
            {"hitbox", "战斗：判定框启停", "name"},
            {"damage", "战斗：造成伤害", "amount"},
            {"heal", "实体：治疗", "amount"},
            {"velocity", "实体：设置速度", "y"},
            {"sound", "表现：播放音效", "sound"},
            {"particle", "表现：粒子（客户端）", "effect"},
            {"say", "表现：聊天栏消息", "text"},
    };

    public GraphEditorScreen(String namespace, String path) {
        super(Component.literal("事件积木图"));
        this.namespace = namespace;
        this.path = path;
        this.graph = GraphStore.load(namespace, path);
    }

    @Override
    protected void init() {
        clearWidgets();
        paletteW = Math.min(190, Math.max(136, width / 4));
        propW = Math.min(210, Math.max(150, width / 4));
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> save())
                .bounds(width - propW + 4, height - 28, (propW - 12) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(width - propW + 8 + (propW - 12) / 2, height - 28,
                        (propW - 12) / 2, 20).build());
        int eventX = paletteW + 6;
        int eventW = Math.max(54, (width - paletteW - propW - 18) / 3);
        addRenderableWidget(Button.builder(Component.literal("+ tick"), b -> addScript("on_tick"))
                .bounds(eventX, 8, eventW, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+ hit"), b -> addScript("on_hit"))
                .bounds(eventX + eventW + 4, 8, eventW, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+ signal"), b -> addScript("on_signal"))
                .bounds(eventX + (eventW + 4) * 2, 8, eventW, 18).build());

        String eventLabel = graph.scripts.isEmpty() ? "事件: 无"
                : "事件: " + graph.scripts.get(Math.max(0, Math.min(selectedScript, graph.scripts.size() - 1))).event;
        addRenderableWidget(Button.builder(Component.literal(eventLabel), b -> cycleEvent())
                .bounds(eventX, 30, Math.min(180, Math.max(100, eventW * 2)), 18).build());

        int btnW = (propW - 12) / 2;
        addRenderableWidget(Button.builder(Component.literal("删除选中"), b -> removeSelected())
                .bounds(width - propW + 4, height - 54, propW - 8, 20).build());
        addRenderableWidget(Button.builder(Component.literal("上移"), b -> moveSelected(-1))
                .bounds(width - propW + 4, height - 80, btnW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下移"), b -> moveSelected(1))
                .bounds(width - propW + 8 + btnW, height - 80, btnW, 20).build());
        rebuildProperties();
    }

    private void addScript(String event) {
        Graph.Script s = new Graph.Script();
        s.event = event;
        if ("on_signal".equals(event)) {
            s.signal = "pulse";
        }
        graph.scripts.add(s);
        selectedScript = graph.scripts.size() - 1;
        selectedBlock = null;
        init();
    }

    private void cycleEvent() {
        if (graph.scripts.isEmpty()) {
            addScript("on_tick");
            return;
        }
        int idx = Math.max(0, Math.min(selectedScript, graph.scripts.size() - 1));
        Graph.Script s = graph.scripts.get(idx);
        int at = 0;
        for (int i = 0; i < EVENTS.length; i++) {
            if (EVENTS[i].equals(s.event)) {
                at = i;
                break;
            }
        }
        s.event = EVENTS[(at + 1) % EVENTS.length];
        if ("on_signal".equals(s.event) && (s.signal == null || s.signal.isBlank())) {
            s.signal = "pulse";
        }
        init();
    }

    private void addBlock(String type) {
        if (graph.scripts.isEmpty()) {
            addScript("on_tick");
        }
        Graph.Script s = graph.scripts.get(Math.max(0, Math.min(selectedScript, graph.scripts.size() - 1)));
        Graph.Block b = new Graph.Block(type);
        defaults(b);
        if (selectedBlock != null && ("if".equals(selectedBlock.type) || "repeat".equals(selectedBlock.type))) {
            if (selectedBlock.body == null) {
                selectedBlock.body = b;
            } else {
                append(selectedBlock.body, b);
            }
        } else if (s.first == null) {
            s.first = b;
        } else {
            append(s.first, b);
        }
        selectedBlock = b;
        rebuildProperties();
    }

    private static void defaults(Graph.Block b) {
        switch (b.type) {
            case "play" -> b.fields.put("clip", "demo_swing");
            case "wait" -> b.fields.put("ticks", "5");
            case "broadcast" -> {
                b.fields.put("signal", "pulse");
                b.fields.put("scope", "self");
                b.fields.put("radius", "16");
            }
            case "if" -> b.fields.put("condition", "true");
            case "repeat" -> b.fields.put("count", "3");
            case "hitbox" -> {
                b.fields.put("name", "blade");
                b.fields.put("enabled", "true");
            }
            case "damage" -> {
                b.fields.put("amount", "4");
                b.fields.put("target", "nearest");
                b.fields.put("radius", "8");
            }
            case "heal" -> b.fields.put("amount", "4");
            case "velocity" -> {
                b.fields.put("x", "0");
                b.fields.put("y", "0.5");
                b.fields.put("z", "0");
            }
            case "sound" -> b.fields.put("sound", "minecraft:entity.player.attack.sweep");
            case "particle" -> {
                b.fields.put("effect", "minecraft:crit");
                b.fields.put("count", "8");
                b.fields.put("speed", "0.2");
            }
            case "say" -> b.fields.put("text", "hello");
            default -> {
            }
        }
    }

    private static void append(Graph.Block head, Graph.Block b) {
        Graph.Block cur = head;
        while (cur.next != null) {
            cur = cur.next;
        }
        cur.next = b;
    }

    private void removeSelected() {
        if (selectedBlock == null || graph.scripts.isEmpty()) {
            return;
        }
        Graph.Script s = graph.scripts.get(selectedScript);
        s.first = Graph.remove(s.first, selectedBlock);
        // 主体中的块
        for (Graph.Block b : allBlocks(s.first)) {
            if (b.body != null) {
                b.body = Graph.remove(b.body, selectedBlock);
            }
            if (b.body2 != null) {
                b.body2 = Graph.remove(b.body2, selectedBlock);
            }
        }
        selectedBlock = null;
        rebuildProperties();
    }

    private void moveSelected(int dir) {
        if (selectedBlock == null || graph.scripts.isEmpty()) {
            return;
        }
        Graph.Script s = graph.scripts.get(selectedScript);
        List<Graph.Block> chain = chainOf(s.first);
        int i = chain.indexOf(selectedBlock);
        if (i < 0 || i + dir < 0 || i + dir >= chain.size()) {
            return;
        }
        Graph.Block a = chain.get(i);
        Graph.Block b = chain.get(i + dir);
        chain.set(i, b);
        chain.set(i + dir, a);
        Graph.Block head = chain.get(0);
        for (int k = 0; k + 1 < chain.size(); k++) {
            chain.get(k).next = chain.get(k + 1);
        }
        chain.get(chain.size() - 1).next = null;
        s.first = head;
        rebuildProperties();
    }

    private static List<Graph.Block> chainOf(Graph.Block head) {
        List<Graph.Block> list = new ArrayList<>();
        for (Graph.Block b = head; b != null; b = b.next) {
            list.add(b);
        }
        return list;
    }

    private static List<Graph.Block> allBlocks(Graph.Block head) {
        List<Graph.Block> out = new ArrayList<>();
        for (Graph.Block b = head; b != null; b = b.next) {
            out.add(b);
            if (b.body != null) {
                out.addAll(allBlocks(b.body));
            }
            if (b.body2 != null) {
                out.addAll(allBlocks(b.body2));
            }
        }
        return out;
    }

    private void rebuildProperties() {
        for (EditBox e : propBoxes) {
            removeWidget(e);
        }
        propBoxes.clear();
        propByField.clear();
        if (selectedBlock == null) {
            return;
        }
        int y = 92;
        List<String> keys = new ArrayList<>(selectedBlock.fields.keySet());
        if (keys.isEmpty()) {
            keys.add("type");
        }
        for (String key : keys) {
            EditBox box = new EditBox(font, width - propW + 8, y + 11, propW - 16, 18,
                    Component.literal(key));
            box.setMaxLength(256);
            box.setValue(selectedBlock.fields.getOrDefault(key, ""));
            box.setResponder(v -> selectedBlock.fields.put(key, v));
            addRenderableWidget(box);
            propBoxes.add(box);
            propByField.put(key, box);
            y += 32;
        }
    }

    private void save() {
        status = GraphStore.save(namespace, path, graph) ? "已保存" : "保存失败";
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(0, 0, width, height, 0xEE17191F);
        g.fill(0, 0, paletteW, height, 0xFF20242C);
        g.fill(width - propW, 0, width, height, 0xFF20242C);
        g.drawString(font, "事件积木图  " + namespace + ":" + path, 6, 6, 0xFFFFFFFF, false);
        int py0 = TOP;
        for (String[] def : PALETTE) {
            boolean hover = mouseX >= 4 && mouseX <= paletteW - 4
                    && mouseY >= py0 && mouseY <= py0 + 20;
            g.fill(4, py0, paletteW - 4, py0 + 20, hover ? 0xFF3A4353 : 0xFF2C313B);
            g.fill(4, py0, 8, py0 + 20, categoryColor(def[0]));
            g.drawString(font, def[1], 12, py0 + 6, 0xFFE0E4EA, false);
            py0 += 22;
        }
        g.drawString(font, "拖到右侧事件列，或单击追加", 6, height - 16, 0xFF7F8794, false);
        g.drawString(font, "事件脚本", paletteW + 8, 30, 0xFFB8C0D0, false);
        g.drawString(font, "参数", width - propW + 8, 76, 0xFFB8C0D0, false);
        if (!status.isBlank()) {
            g.drawString(font, status, paletteW + 8, 30, 0xFF80FF80, false);
        }

        int baseX = paletteW + 8;
        int colW = Math.min(230, Math.max(170, (width - paletteW - propW - 16) / Math.max(1, graph.scripts.size())));
        for (int i = 0; i < graph.scripts.size(); i++) {
            Graph.Script s = graph.scripts.get(i);
            int x = baseX + i * colW;
            if (x + colW - 8 > width - propW) {
                break;
            }
            boolean sel = i == selectedScript;
            g.fill(x, 44, x + colW - 8, height - 100, sel ? 0x332F6FFF : 0x2220242C);
            g.drawString(font, s.event + (s.signal == null || s.signal.isBlank() ? "" : " / " + s.signal),
                    x + 6, 50, sel ? 0xFFFFFFFF : 0xFFAAB0BC, false);
            int y = 70;
            y = drawChain(g, s.first, x + 6, y, 0, 0xFF5A7FCF, colW - 20);
            if (s.first == null) {
                g.drawString(font, "空：点左侧积木追加", x + 10, y, 0xFF777F8C, false);
            }
        }
        // 参数名标签
        int py = 92;
        for (String key : propByField.keySet()) {
            g.drawString(font, key, width - propW + 8, py, 0xFF9FA8B8, false);
            py += 32;
        }
        if ((draggingBlock != null || dragNewType != null)
                && (Math.abs(mouseX - dragStartX) > 4 || Math.abs(mouseY - dragStartY) > 4)) {
            String ghost = draggingBlock != null ? label(draggingBlock)
                    : dragNewType == null ? "" : dragNewType;
            g.fill((int) dragX - 90, (int) dragY - 10, (int) dragX + 90, (int) dragY + 14,
                    0xFFFFB84D);
            g.drawString(font, ghost, (int) dragX - 82, (int) dragY - 4,
                    0xFF101010, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private static int categoryColor(String type) {
        return switch (type) {
            case "play", "wait", "if", "repeat" -> 0xFF5A7FCF;
            case "broadcast" -> 0xFFD08A3A;
            case "hitbox", "damage", "heal" -> 0xFFCF5A6A;
            case "velocity" -> 0xFF5ACF9A;
            case "sound", "say" -> 0xFF9A7ACF;
            default -> 0xFF8A929E;
        };
    }

    private int drawChain(GuiGraphics g, Graph.Block head, int x, int y, int depth, int color,
                          int width) {
        int curY = y;
        for (Graph.Block b = head; b != null; b = b.next) {
            int w = width - depth * 12;
            boolean sel = b == selectedBlock;
            g.fill(x, curY, x + w, curY + BLOCK_H, sel ? 0xFFFFB84D : color);
            g.fill(x + 2, curY + 2, x + w - 2, curY + BLOCK_H - 2, 0xFF2B303A);
            g.drawString(font, label(b), x + 8, curY + 8, 0xFFFFFFFF, false);
            curY += BLOCK_H + GAP;
            if (b.body != null) {
                curY = drawChain(g, b.body, x + 14, curY, depth + 1, 0xFF7A5FCF, width - 14);
                curY += 2;
            }
            if (b.body2 != null) {
                g.drawString(font, "否则", x + 14, curY, 0xFFAAAAAA, false);
                curY += 12;
                curY = drawChain(g, b.body2, x + 14, curY, depth + 1, 0xFF7A5FCF, width - 14);
            }
        }
        return curY;
    }

    private String label(Graph.Block b) {
        String extra = b.fields.getOrDefault("clip", b.fields.getOrDefault("signal", ""));
        return switch (b.type) {
            case "play" -> "播放动画: " + extra;
            case "wait" -> "等待 " + b.field("ticks", "1") + " tick";
            case "broadcast" -> "广播 " + b.field("signal", "") + " [" + b.field("scope", "self") + "]";
            case "if" -> "如果 " + b.field("condition", "true");
            case "repeat" -> "重复 " + b.field("count", "1") + " 次";
            case "hitbox" -> "判定框 " + b.field("name", "") + " " + b.field("enabled", "true");
            case "damage" -> "伤害 " + b.field("amount", "1");
            case "heal" -> "治疗 " + b.field("amount", "1");
            case "velocity" -> "速度";
            case "sound" -> "音效";
            case "particle" -> "粒子: " + b.field("effect", "");
            case "say" -> "消息: " + b.field("text", "");
            default -> b.type;
        };
    }

    private int columnWidth() {
        return Math.min(230, Math.max(170, (width - paletteW - propW - 16)
                / Math.max(1, graph.scripts.size())));
    }

    private int scriptAt(double mx) {
        int baseX = paletteW + 8;
        int colW = columnWidth();
        for (int i = 0; i < graph.scripts.size(); i++) {
            int x = baseX + i * colW;
            if (mx >= x && mx <= x + colW - 8) {
                return i;
            }
        }
        return -1;
    }

    private static void relink(List<Graph.Block> chain) {
        for (int i = 0; i + 1 < chain.size(); i++) {
            chain.get(i).next = chain.get(i + 1);
        }
        if (!chain.isEmpty()) {
            chain.get(chain.size() - 1).next = null;
        }
    }

    private static final class BodySlot {
        Graph.Block container;
        Graph.Block bodyHead;
        int index;
    }

    private static int chainHeight(Graph.Block head) {
        int h = 0;
        for (Graph.Block b = head; b != null; b = b.next) {
            h += BLOCK_H + GAP;
            if (b.body != null) {
                h += chainHeight(b.body);
            }
            if (b.body2 != null) {
                h += 12 + chainHeight(b.body2);
            }
        }
        return h;
    }

    private static BodySlot findBodySlot(Graph.Script s, int x, int colW, int depth,
                                         double mx, double my) {
        int y = 70 + depth * 0;
        for (Graph.Block b = s.first; b != null; b = b.next) {
            int w = colW - 20 - depth * 12;
            int bodyTop = y + BLOCK_H + GAP;
            if (("if".equals(b.type) || "repeat".equals(b.type))
                    && mx >= x + 14 && mx <= x + w && my >= bodyTop) {
                int bodyH = Math.max(BLOCK_H + GAP, chainHeight(b.body));
                if (my <= bodyTop + bodyH) {
                    BodySlot slot = new BodySlot();
                    slot.container = b;
                    slot.bodyHead = b.body;
                    int idx = 0;
                    int yy = bodyTop;
                    for (Graph.Block cur = b.body; cur != null; cur = cur.next) {
                        if (my < yy + BLOCK_H / 2) {
                            break;
                        }
                        idx++;
                        yy += BLOCK_H + GAP;
                    }
                    slot.index = idx;
                    return slot;
                }
            }
            y += BLOCK_H + GAP;
            if (b.body != null) {
                y += chainHeight(b.body);
            }
            if (b.body2 != null) {
                y += 12 + chainHeight(b.body2);
            }
        }
        return null;
    }

    private static void insertIntoBody(BodySlot slot, Graph.Block block) {
        List<Graph.Block> chain = chainOf(slot.bodyHead);
        block.next = null;
        chain.add(Math.max(0, Math.min(slot.index, chain.size())), block);
        relink(chain);
        slot.container.body = chain.isEmpty() ? null : chain.get(0);
    }

    private void dropNew(String type, double mx, double my) {
        if (Math.abs(mx - dragStartX) < 4 && Math.abs(my - dragStartY) < 4) {
            addBlock(type);
            return;
        }
        int target = scriptAt(mx);
        if (target < 0) {
            return;
        }
        Graph.Block b = new Graph.Block(type);
        defaults(b);
        Graph.Script s = graph.scripts.get(target);
        int colW = columnWidth();
        int x = paletteW + 8 + target * colW;
        BodySlot slot = findBodySlot(s, x, colW, 0, mx, my);
        if (slot != null) {
            insertIntoBody(slot, b);
            selectedScript = target;
            selectedBlock = b;
            rebuildProperties();
            return;
        }
        List<Graph.Block> chain = chainOf(s.first);
        int index = 0;
        int y = 70;
        for (Graph.Block cur : chain) {
            if (my < y + BLOCK_H / 2) {
                break;
            }
            index++;
            y += BLOCK_H + GAP;
        }
        chain.add(Math.max(0, Math.min(index, chain.size())), b);
        relink(chain);
        s.first = chain.isEmpty() ? null : chain.get(0);
        selectedScript = target;
        selectedBlock = b;
        rebuildProperties();
    }

    private void dropDragged(double mx, double my) {
        Graph.Block block = draggingBlock;
        int source = dragScript;
        draggingBlock = null;
        if (block == null || source < 0 || source >= graph.scripts.size()) {
            return;
        }
        if (Math.abs(mx - dragStartX) < 4 && Math.abs(my - dragStartY) < 4) {
            return;
        }
        int target = scriptAt(mx);
        if (target < 0) {
            target = source;
        }
        List<Graph.Block> sourceChain = chainOf(graph.scripts.get(source).first);
        int from = sourceChain.indexOf(block);
        if (from < 0) {
            return; // 嵌套块暂不拖出主体
        }
        int colW = columnWidth();
        int xcol = paletteW + 8 + target * colW;
        BodySlot slot = findBodySlot(graph.scripts.get(target), xcol, colW, 0, mx, my);
        if (slot != null) {
            sourceChain.remove(block);
            relink(sourceChain);
            graph.scripts.get(source).first = sourceChain.isEmpty() ? null : sourceChain.get(0);
            insertIntoBody(slot, block);
            selectedScript = target;
            selectedBlock = block;
            rebuildProperties();
            return;
        }
        int index = 0;
        int y = 70;
        List<Graph.Block> targetChain = chainOf(graph.scripts.get(target).first);
        for (Graph.Block b : targetChain) {
            if (b == block) {
                continue;
            }
            if (my < y + BLOCK_H / 2) {
                break;
            }
            index++;
            y += BLOCK_H + GAP;
        }
        // 从源链移除，再按目标链重插
        sourceChain.remove(block);
        relink(sourceChain);
        graph.scripts.get(source).first = sourceChain.isEmpty() ? null : sourceChain.get(0);
        if (target == source) {
            targetChain = sourceChain;
        } else {
            targetChain = chainOf(graph.scripts.get(target).first);
        }
        index = Math.max(0, Math.min(index, targetChain.size()));
        block.next = null;
        targetChain.add(index, block);
        relink(targetChain);
        graph.scripts.get(target).first = targetChain.isEmpty() ? null : targetChain.get(0);
        selectedScript = target;
        selectedBlock = block;
        rebuildProperties();
    }

    private int paletteIndex(double my) {
        int y = TOP;
        for (int i = 0; i < PALETTE.length; i++) {
            if (my >= y && my <= y + 20) {
                return i;
            }
            y += 22;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (mx < paletteW && my >= TOP) {
            int idx = paletteIndex(my);
            if (idx >= 0) {
                dragNewType = PALETTE[idx][0];
                dragStartX = mx;
                dragStartY = my;
                dragX = mx;
                dragY = my;
                return true;
            }
            return super.mouseClicked(mx, my, button);
        }
        if (mx < paletteW || mx > width - propW) {
            return super.mouseClicked(mx, my, button);
        }
        int baseX = paletteW + 8;
        int colW = columnWidth();
        for (int i = 0; i < graph.scripts.size(); i++) {
            int x = baseX + i * colW;
            if (mx >= x && mx <= x + colW - 8 && my >= 44) {
                selectedScript = i;
                Graph.Block hit = hit(graph.scripts.get(i).first, x + 6, 70, 0,
                        colW - 20, mx, my);
                if (hit != null) {
                    selectedBlock = hit;
                    draggingBlock = hit;
                    dragScript = i;
                    dragStartX = mx;
                    dragStartY = my;
                    dragX = mx;
                    dragY = my;
                }
                rebuildProperties();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingBlock != null || dragNewType != null) {
            dragX = mx;
            dragY = my;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragNewType != null) {
            String type = dragNewType;
            dragNewType = null;
            dropNew(type, mx, my);
            return true;
        }
        if (draggingBlock != null) {
            dropDragged(mx, my);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private Graph.Block hit(Graph.Block head, int x, int y, int depth, int width,
                            double mx, double my) {
        int curY = y;
        for (Graph.Block b = head; b != null; b = b.next) {
            int w = width - depth * 12;
            if (mx >= x && mx <= x + w && my >= curY && my <= curY + BLOCK_H) {
                return b;
            }
            curY += BLOCK_H + GAP;
            if (b.body != null) {
                Graph.Block nested = hit(b.body, x + 14, curY, depth + 1, width - 14, mx, my);
                if (nested != null) {
                    return nested;
                }
                curY = chainBottom(b.body, curY);
            }
            if (b.body2 != null) {
                curY += 12;
                Graph.Block nested = hit(b.body2, x + 14, curY, depth + 1, width - 14, mx, my);
                if (nested != null) {
                    return nested;
                }
                curY = chainBottom(b.body2, curY);
            }
        }
        return null;
    }

    private static int chainBottom(Graph.Block head, int y) {
        int cur = y;
        for (Graph.Block b = head; b != null; b = b.next) {
            cur += BLOCK_H + GAP;
            if (b.body != null) {
                cur = chainBottom(b.body, cur);
            }
            if (b.body2 != null) {
                cur += 12;
                cur = chainBottom(b.body2, cur);
            }
        }
        return cur;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
