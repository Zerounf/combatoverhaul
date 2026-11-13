package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.screen.ui.IdListState;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作绑定编辑：为当前动作勾选 weapons（绑定武器物品）与 entities（授权生物）。
 * 从 CombatPanelScreen 的「绑定」按钮进入；返回时自动保存并回到战斗编辑。
 *
 * <ul>
 *   <li>点击列表行切换勾选；搜索框过滤；「勾选/清除筛选结果」批量操作。</li>
 *   <li>weapons 为空的动作 = 徒手/通用兜底；entities 页签仅实体模型动作显示。</li>
 * </ul>
 */
public class ActionBindingScreen extends Screen {
    private static final int ROW_H = 12;
    private static final int LIST_X = 10;
    private static final int LIST_TOP = 88;

    private final Screen returnParent; // CombatPanelScreen 的 parent（用于重建战斗编辑界面）
    private final String modid;
    private final String path;
    private final boolean weapon;

    private final List<ActionStore.Action> actions = new ArrayList<>();
    private int actionIndex;
    private boolean loaded = false;

    private int tab = 0; // 0=武器 1=生物
    private EditBox searchBox;
    private final IdListState list = new IdListState();
    private String status = "";

    public ActionBindingScreen(Screen returnParent, String modid, String path, boolean weapon, int actionIndex) {
        super(Component.translatable("gui.combatoverhaul.binding_title", modid, path));
        this.returnParent = returnParent;
        this.modid = modid;
        this.path = path;
        this.weapon = weapon;
        this.actionIndex = Math.max(0, actionIndex);
    }

    private ActionStore.Action action() {
        return actionIndex >= 0 && actionIndex < actions.size() ? actions.get(actionIndex) : null;
    }

    /** 当前页签对应的绑定列表 */
    private List<String> bindingList() {
        ActionStore.Action a = action();
        if (a == null) {
            return null;
        }
        return tab == 0 ? a.weapons : a.entities;
    }

    @Override
    protected void init() {
        if (!loaded) {
            loaded = true;
            actions.addAll(ActionStore.load(modid, path, weapon));
            if (actions.isEmpty()) {
                actionIndex = -1;
            } else {
                actionIndex = Math.min(actionIndex, actions.size() - 1);
            }
            rebuildIds();
            refilter();
        }

        int y = 24;
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleAction(-1))
                .bounds(34, y, 16, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleAction(1))
                .bounds(52, y, 16, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.back"), b -> close())
                .bounds(this.width - 86, y, 76, 16).build());

        // 页签
        int ty = 46;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.tab_weapons"), b -> switchTab(0))
                .bounds(10, ty, 70, 16).build());
        if (!weapon) {
            this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.tab_entities"), b -> switchTab(1))
                    .bounds(84, ty, 70, 16).build());
        }

        // 搜索 + 批量
        searchBox = new EditBox(this.font, 10, 68, 180, 14,
                Component.translatable("gui.combatoverhaul.binding_search"));
        searchBox.setMaxLength(64);
        searchBox.setValue(list.filter);
        searchBox.setResponder(s -> {
            list.setFilter(s);
            list.setVisibleRows(visibleRows());
        });
        this.addRenderableWidget(searchBox);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.check_filtered"),
                b -> batchSet(true)).bounds(196, 67, 88, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.uncheck_filtered"),
                b -> batchSet(false)).bounds(288, 67, 88, 16).build());
    }

    private void rebuildIds() {
        List<String> ids = new ArrayList<>();
        var registry = tab == 0 ? BuiltInRegistries.ITEM : BuiltInRegistries.ENTITY_TYPE;
        for (ResourceLocation id : registry.keySet()) {
            ids.add(id.toString());
        }
        list.setVisibleRows(visibleRows());
        list.setAll(ids);
    }

    private void refilter() {
        list.setVisibleRows(visibleRows());
        list.refilter();
    }

    private void switchTab(int t) {
        if (t == tab) {
            return;
        }
        tab = t;
        list.scroll = 0;
        rebuildIds();
    }

    private void cycleAction(int d) {
        if (actions.isEmpty()) {
            return;
        }
        actionIndex = Math.floorMod(actionIndex + d, actions.size());
        list.scroll = 0;
        refilter();
    }

    private void batchSet(boolean check) {
        List<String> bound = bindingList();
        if (bound == null) {
            return;
        }
        for (String id : list.filtered) {
            if (check && !bound.contains(id)) {
                bound.add(id);
            } else if (!check) {
                bound.remove(id);
            }
        }
    }

    private void toggle(String id) {
        List<String> bound = bindingList();
        if (bound == null) {
            return;
        }
        if (!bound.remove(id)) {
            bound.add(id);
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.height - LIST_TOP - 30) / ROW_H);
    }

    private boolean saveAndInvalidate() {
        if (ActionStore.save(modid, path, weapon, actions)) {
            ActionCache.invalidate(modid, path, weapon);
            status = Component.translatable("gui.combatoverhaul.binding_saved").getString();
            return true;
        }
        status = Component.translatable("gui.combatoverhaul.save_fail").getString();
        return false;
    }

    private void close() {
        if (!saveAndInvalidate()) {
            return; // 写失败时留在本界面，避免未保存的绑定被下一次加载覆盖
        }
        Minecraft.getInstance().setScreen(new CombatPanelScreen(returnParent, modid, path, weapon, actionIndex));
    }

    @Override
    public void onClose() {
        close();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        list.scrollBy(delta, visibleRows());
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= LIST_X && mouseX <= this.width - 10 && mouseY >= LIST_TOP) {
            int idx = list.indexAt(mouseY, LIST_TOP);
            if (idx >= 0 && idx < list.filtered.size() && idx < list.scroll + visibleRows()) {
                toggle(list.filtered.get(idx));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        ActionStore.Action a = action();
        g.drawString(this.font, a == null ? Component.translatable("gui.combatoverhaul.no_action")
                : Component.translatable("gui.combatoverhaul.binding_action", a.name), 76, 28, 0xA0A0FF);

        // 当前页签高亮 + 已选统计
        List<String> bound = bindingList();
        String tabName = Component.translatable(tab == 0
                ? "gui.combatoverhaul.tab_weapons" : "gui.combatoverhaul.tab_entities").getString();
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.binding_count",
                bound == null ? 0 : bound.size(), list.filtered.size()), 382, 71, 0x909090);
        g.drawString(this.font, "§8" + tabName, 162, 50, 0xFFFFFF);

        // 列表
        int rows = visibleRows();
        for (int i = 0; i < rows && list.scroll + i < list.filtered.size(); i++) {
            String id = list.filtered.get(list.scroll + i);
            boolean checked = bound != null && bound.contains(id);
            int y = LIST_TOP + i * ROW_H;
            if (mouseX >= LIST_X && mouseX <= this.width - 10 && mouseY >= y && mouseY < y + ROW_H) {
                g.fill(LIST_X, y, this.width - 10, y + ROW_H, 0x33FFFFFF);
            }
            g.drawString(this.font, (checked ? "§a[✓] §f" : "§7[ ] ") + id, LIST_X + 2, y + 2,
                    checked ? 0x80FF80 : 0xC0C0C0);
        }
        if (list.filtered.isEmpty()) {
            g.drawString(this.font, "§7-", LIST_X + 2, LIST_TOP + 2, 0x808080);
        }

        g.drawString(this.font, Component.translatable("gui.combatoverhaul.binding_hint"),
                10, this.height - 26, 0x909090);
        if (!status.isEmpty()) {
            g.drawString(this.font, status, 10, this.height - 14, 0x80FF80);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
