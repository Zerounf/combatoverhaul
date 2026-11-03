package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.model.EntityModelScanner;
import com.yourname.combatoverhaul.client.screen.ui.EditorButton;
import com.yourname.combatoverhaul.client.screen.ui.EditorTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 编辑器主窗口：现代 IDE 布局。
 *
 * <pre>
 * Header: 标题 / 统计 / 搜索 / 重新扫描 / 3D / 关闭
 * Left: mod 列表
 * Center: 实体列表（搜索命中时跨 mod）
 * Right: 当前实体的详情与操作
 * Status: 扫描/操作状态
 * </pre>
 */
public class EntityBrowserScreen extends Screen {

    private static final int ROW = EditorTheme.ROW_H;
    private static final int LIST_TOP = 20;

    private static List<EntityModelScanner.ScanResult> CACHE = new ArrayList<>();
    private static boolean SCANNED = false;

    private final Map<String, List<EntityModelScanner.ScanResult>> groups = new LinkedHashMap<>();
    private final List<String> modIds = new ArrayList<>();
    private List<EntityModelScanner.ScanResult> currentEntities = new ArrayList<>();

    private EditBox searchBox;
    private String search = "";
    private String selectedMod = null;
    private int selectedEntity = -1;
    private int modScroll = 0;
    private int entityScroll = 0;
    private String status = "";
    private long lastEntityClick = 0;
    private int lastEntityClickIndex = -1;

    private int leftX, leftY, leftW, leftH;
    private int centerX, centerY, centerW, centerH;
    private int rightX, rightY, rightW, rightH;
    private int viewW, viewH;

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public EntityBrowserScreen() {
        super(Component.translatable("gui.combatoverhaul.title"));
    }

    @Override
    protected void init() {
        viewW = EditorTheme.visibleWidth(this.minecraft);
        viewH = EditorTheme.visibleHeight(this.minecraft);
        int top = EditorTheme.HEADER_H + EditorTheme.GAP;
        int bottom = viewH - EditorTheme.STATUS_H - 20;
        boolean compact = viewW < 600;
        leftX = compact ? 4 : EditorTheme.PAD;
        leftY = top;
        leftW = compact ? 72 : clamp((int) (viewW * 0.18f), 90, 160);
        leftH = bottom - top;

        rightW = compact ? 96 : clamp((int) (viewW * 0.24f), 120, 220);
        rightX = viewW - (compact ? 4 : EditorTheme.PAD) - rightW;
        rightY = top;
        rightH = leftH;

        centerX = leftX + leftW + EditorTheme.GAP;
        centerY = top;
        centerW = rightX - EditorTheme.GAP - centerX;
        centerH = leftH;

        int searchW = compact ? 72 : clamp((int) (viewW * 0.22f), 80, 160);
        searchBox = new EditBox(this.font, viewW - searchW - (compact ? 34 : 46), 5, searchW, 16,
                Component.translatable("gui.combatoverhaul.search"));
        searchBox.setMaxLength(64);
        searchBox.setValue(search);
        searchBox.setResponder(sv -> {
            search = sv.trim().toLowerCase(Locale.ROOT);
            refreshEntityList();
        });
        this.addRenderableWidget(searchBox);

        this.addRenderableWidget(new EditorButton(viewW - (compact ? 28 : 42), 5,
                compact ? 24 : 36, 16,
                Component.translatable("gui.combatoverhaul.close"), b -> onClose()));

        int actionY = viewH - EditorTheme.STATUS_H - 20;
        int bx = leftX;
        this.addRenderableWidget(new EditorButton(bx, actionY, compact ? 44 : 52, 16,
                Component.translatable("gui.combatoverhaul.rescan"), b -> rescan()));
        bx += compact ? 48 : 56;
        this.addRenderableWidget(new EditorButton(bx, actionY, compact ? 48 : 58, 16,
                Component.translatable("gui.combatoverhaul.edit3d"), b -> openEditor3D(), true));
        bx += compact ? 52 : 62;
        this.addRenderableWidget(new EditorButton(bx, actionY, compact ? 54 : 66, 16,
                Component.translatable("gui.combatoverhaul.overwrite"), b -> overwriteSelected()));
        bx += compact ? 58 : 70;
        this.addRenderableWidget(new EditorButton(bx, actionY, compact ? 48 : 58, 16,
                Component.translatable("gui.combatoverhaul.edit"), b -> openEditor()));
        bx += compact ? 52 : 62;
        this.addRenderableWidget(new EditorButton(bx, actionY, compact ? 56 : 70, 16,
                Component.translatable("gui.combatoverhaul.new_whitebox"), b -> createWhitebox()));

        if (!SCANNED) {
            status = Component.translatable("gui.combatoverhaul.scanning").getString();
            CACHE = EntityModelScanner.scanAndExportMissing();
            SCANNED = true;
            status = Component.translatable("gui.combatoverhaul.scan_done",
                    CACHE.size(), groupsCount(CACHE)).getString();
        }
        rebuildGroups();
        if (selectedMod == null && !modIds.isEmpty()) {
            selectedMod = modIds.get(0);
        }
        refreshEntityList();
    }

    private static int groupsCount(List<EntityModelScanner.ScanResult> list) {
        return (int) list.stream().map(r -> r.modid).distinct().count();
    }

    private void rebuildGroups() {
        groups.clear();
        for (EntityModelScanner.ScanResult r : CACHE) {
            groups.computeIfAbsent(r.modid, k -> new ArrayList<>()).add(r);
        }
        modIds.clear();
        modIds.addAll(groups.keySet());
    }

    private void refreshEntityList() {
        currentEntities = new ArrayList<>();
        if (!search.isEmpty()) {
            for (List<EntityModelScanner.ScanResult> list : groups.values()) {
                for (EntityModelScanner.ScanResult r : list) {
                    if ((r.modid + ":" + r.path).toLowerCase(Locale.ROOT).contains(search)) {
                        currentEntities.add(r);
                    }
                }
            }
        } else if (selectedMod != null && groups.containsKey(selectedMod)) {
            currentEntities.addAll(groups.get(selectedMod));
        }
        selectedEntity = -1;
        entityScroll = 0;
    }

    private void rescan() {
        status = Component.translatable("gui.combatoverhaul.scanning").getString();
        CACHE = EntityModelScanner.scanAndExportMissing();
        rebuildGroups();
        refreshEntityList();
        status = Component.translatable("gui.combatoverhaul.scan_done",
                CACHE.size(), groupsCount(CACHE)).getString();
    }

    private EntityModelScanner.ScanResult selected() {
        return selectedEntity >= 0 && selectedEntity < currentEntities.size()
                ? currentEntities.get(selectedEntity) : null;
    }

    private void overwriteSelected() {
        EntityModelScanner.ScanResult r = selected();
        if (r == null) {
            status = Component.translatable("gui.combatoverhaul.select_first").getString();
            return;
        }
        status = EntityModelScanner.exportOverwrite(r)
                ? Component.translatable("gui.combatoverhaul.overwritten", r.modid + ":" + r.path).getString()
                : Component.translatable("gui.combatoverhaul.write_failed").getString();
    }

    private void openEditor() {
        EntityModelScanner.ScanResult r = selected();
        if (r == null) {
            status = Component.translatable("gui.combatoverhaul.select_first").getString();
            return;
        }
        this.minecraft.setScreen(new JsonEditorScreen(this, r.modid, r.path));
    }

    private void openEditor3D() {
        EntityModelScanner.ScanResult r = selected();
        if (r == null) {
            status = Component.translatable("gui.combatoverhaul.select_first").getString();
            return;
        }
        this.minecraft.setScreen(new ModelEditorScreen(this, r.modid, r.path, false));
    }

    private void createWhitebox() {
        this.minecraft.setScreen(new WhiteboxCreateScreen(this));
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        EditorTheme.appBackground(g, this.width, this.height);
        String subtitle = viewW >= 700
                ? CACHE.size() + " 实体 / " + modIds.size() + " 模块" : null;
        EditorTheme.header(g, this.font, viewW,
                viewW < 600 ? Component.literal("CombatOverhaul") : this.title, subtitle);

        EditorTheme.panel(g, leftX, leftY, leftW, leftH);
        EditorTheme.panel(g, centerX, centerY, centerW, centerH);
        EditorTheme.panel(g, rightX, rightY, rightW, rightH);
        EditorTheme.section(g, this.font, "模块", leftX, leftY, leftW);
        EditorTheme.section(g, this.font,
                search.isEmpty() ? "实体" : "搜索结果", centerX, centerY, centerW);
        EditorTheme.section(g, this.font, "详情", rightX, rightY, rightW);

        drawMods(g, mouseX, mouseY);
        drawEntities(g, mouseX, mouseY);
        drawDetails(g);

        String hint = "双击实体打开 3D 编辑器 · 滚轮滚动";
        EditorTheme.statusBar(g, this.font, viewW, viewH, status, hint);
        double sx = viewW / (double) Math.max(1, this.width);
        double sy = viewH / (double) Math.max(1, this.height);
        super.render(g, (int) (mouseX * sx), (int) (mouseY * sy), partialTick);
    }

    private void drawMods(GuiGraphics g, int mouseX, int mouseY) {
        int y0 = leftY + LIST_TOP;
        int h = leftH - LIST_TOP - 4;
        modScroll = clampScroll(modScroll, modIds.size() * ROW, h);
        g.enableScissor(leftX + 1, y0, leftX + leftW - 1, y0 + h);
        for (int i = 0; i < modIds.size(); i++) {
            int y = y0 + i * ROW - modScroll;
            if (y + ROW < y0 || y > y0 + h) continue;
            String id = modIds.get(i);
            boolean sel = id.equals(selectedMod);
            boolean hover = mouseX >= leftX && mouseX < leftX + leftW && mouseY >= y && mouseY < y + ROW;
            EditorTheme.row(g, this.font, leftX + 1, y, leftW - 2, ROW,
                    clip(id, leftW - 42) + "  " + groups.get(id).size(), sel, hover, EditorTheme.TEXT);
        }
        g.disableScissor();
    }

    private void drawEntities(GuiGraphics g, int mouseX, int mouseY) {
        int y0 = centerY + LIST_TOP;
        int h = centerH - LIST_TOP - 4;
        entityScroll = clampScroll(entityScroll, currentEntities.size() * ROW, h);
        g.enableScissor(centerX + 1, y0, centerX + centerW - 1, y0 + h);
        for (int i = 0; i < currentEntities.size(); i++) {
            int y = y0 + i * ROW - entityScroll;
            if (y + ROW < y0 || y > y0 + h) continue;
            EntityModelScanner.ScanResult r = currentEntities.get(i);
            boolean sel = i == selectedEntity;
            boolean hover = mouseX >= centerX && mouseX < centerX + centerW && mouseY >= y && mouseY < y + ROW;
            if (sel) {
                g.fill(centerX + 1, y, centerX + centerW - 1, y + ROW, EditorTheme.BG_ROW_SELECTED);
                g.fill(centerX + 1, y, centerX + 3, y + ROW, EditorTheme.ACCENT);
            } else if (hover) {
                g.fill(centerX + 1, y, centerX + centerW - 1, y + ROW, EditorTheme.BG_ROW_HOVER);
            }
            int dot = r.exported ? EditorTheme.OK : EditorTheme.TEXT_DIM;
            g.fill(centerX + 9, y + 8, centerX + 15, y + 14, dot);
            String tag = switch (r.source) {
                case "vanilla" -> "V";
                case "geckolib" -> "G";
                default -> "N";
            };
            int tagColor = switch (tag) {
                case "V" -> 0xFF7FD8FF;
                case "G" -> EditorTheme.WARN;
                default -> EditorTheme.TEXT_DIM;
            };
            g.drawString(this.font, tag, centerX + 22, y + 6, tagColor, false);
            String name = search.isEmpty() ? r.path : r.modid + ":" + r.path;
            g.drawString(this.font, clip(name, centerW - 44), centerX + 36, y + 6,
                    sel ? EditorTheme.TEXT : 0xFFB9C3D0, false);
        }
        g.disableScissor();
    }

    private void drawDetails(GuiGraphics g) {
        int x = rightX + 10;
        int y = rightY + 28;
        EntityModelScanner.ScanResult r = selected();
        if (r == null) {
            g.drawString(this.font, "未选择实体", x, y, EditorTheme.TEXT_MUTED, false);
            g.drawString(this.font, "从中间列表选择一个实体", x, y + 14, EditorTheme.TEXT_DIM, false);
            return;
        }
        g.drawString(this.font, clip(r.modid + ":" + r.path, rightW - 20), x, y, EditorTheme.TEXT, false);
        g.drawString(this.font, "source: " + r.source, x, y + 18, EditorTheme.TEXT_MUTED, false);
        g.drawString(this.font, "exported: " + (r.exported ? "yes" : "no"), x, y + 32,
                r.exported ? EditorTheme.OK : EditorTheme.TEXT_DIM, false);
        g.drawString(this.font, "file:", x, y + 50, EditorTheme.TEXT_MUTED, false);
        g.drawString(this.font, clip("config/combatoverhaul/entities/" + r.modid + "/" + r.path + ".json",
                rightW - 20), x, y + 62, EditorTheme.TEXT_DIM, false);
        g.drawString(this.font, "操作", x, y + 86, EditorTheme.TEXT_MUTED, false);
    }

    private void drawBottomActions(GuiGraphics g) {
        int y = this.height - EditorTheme.STATUS_H - 26;
        // 底部操作条在 state bar 上方；按钮由 init 添加
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        // 完全自绘，不叠原版菜单背景
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double sx = viewW / (double) Math.max(1, this.width);
        double sy = viewH / (double) Math.max(1, this.height);
        double mx = mouseX * sx;
        double my = mouseY * sy;
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        int y0 = leftY + LIST_TOP;
        if (mx >= leftX && mx < leftX + leftW && my >= y0 && my < leftY + leftH) {
            int idx = (int) ((my - y0 + modScroll) / ROW);
            if (idx >= 0 && idx < modIds.size()) {
                selectedMod = modIds.get(idx);
                refreshEntityList();
            }
            return true;
        }
        int ey0 = centerY + LIST_TOP;
        if (mx >= centerX && mx < centerX + centerW && my >= ey0 && my < centerY + centerH) {
            int idx = (int) ((my - ey0 + entityScroll) / ROW);
            if (idx >= 0 && idx < currentEntities.size()) {
                selectedEntity = idx;
                long now = System.currentTimeMillis();
                if (now - lastEntityClick < 300 && lastEntityClickIndex == idx) {
                    lastEntityClick = 0;
                    lastEntityClickIndex = -1;
                    openEditor3D();
                } else {
                    lastEntityClick = now;
                    lastEntityClickIndex = idx;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double sx = viewW / (double) Math.max(1, this.width);
        double sy = viewH / (double) Math.max(1, this.height);
        double mx = mouseX * sx;
        double my = mouseY * sy;
        int y0 = leftY + LIST_TOP;
        if (mx >= leftX && mx < leftX + leftW && my >= y0 && my < leftY + leftH) {
            modScroll = clampScroll(modScroll - (int) delta * ROW, modIds.size() * ROW, leftH - LIST_TOP - 4);
            return true;
        }
        int ey0 = centerY + LIST_TOP;
        if (mx >= centerX && mx < centerX + centerW && my >= ey0 && my < centerY + centerH) {
            entityScroll = clampScroll(entityScroll - (int) delta * ROW,
                    currentEntities.size() * ROW, centerH - LIST_TOP - 4);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private static int clampScroll(int value, int content, int viewport) {
        int max = Math.max(0, content - viewport);
        return Math.max(0, Math.min(max, value));
    }

    private String clip(String s, int width) {
        return width <= 0 ? s : this.font.plainSubstrByWidth(s, width);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }
}
