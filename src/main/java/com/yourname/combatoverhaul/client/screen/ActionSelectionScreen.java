package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.screen.ui.IdListState;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 两个 Action 勾选界面的公共骨架：动作切换、搜索、列表滚动、
 * 勾选/取消批量按钮、保存返回。
 *
 * <p>子类只负责：选项列表、行绘制、勾选语义和额外的页签/开关。
 */
public abstract class ActionSelectionScreen extends Screen {

    protected static final int ROW_H = IdListState.ROW_H;
    protected static final int LIST_X = 10;

    protected final Screen returnParent;
    protected final String modid;
    protected final String path;
    protected final boolean weapon;
    protected final int listTop;
    protected final int searchY;

    protected final List<ActionStore.Action> actions = new ArrayList<>();
    protected int actionIndex;
    protected boolean loaded;
    protected final IdListState list = new IdListState();
    protected String status = "";
    protected EditBox searchBox;

    protected ActionSelectionScreen(Screen returnParent, String modid, String path,
                                    boolean weapon, int actionIndex, int listTop, int searchY,
                                    Component title) {
        super(title);
        this.returnParent = returnParent;
        this.modid = modid;
        this.path = path;
        this.weapon = weapon;
        this.actionIndex = actionIndex;
        this.listTop = listTop;
        this.searchY = searchY;
    }

    protected abstract void rebuildIds();

    protected abstract void toggle(String id);

    protected abstract void batchSet(boolean check);

    protected abstract void addExtraWidgets();

    protected abstract String actionLabel();

    protected abstract String hintKey();

    protected String searchKey() {
        return "gui.combatoverhaul.binding_search";
    }

    protected abstract void drawRow(GuiGraphics g, String id, int y, int mouseX, int mouseY);

    protected abstract void renderExtra(GuiGraphics g, int mouseX, int mouseY);

    protected abstract Screen backScreen();

    protected ActionStore.Action action() {
        return actionIndex >= 0 && actionIndex < actions.size() ? actions.get(actionIndex) : null;
    }

    protected int visibleRows() {
        return Math.max(1, (height - listTop - 30) / ROW_H);
    }

    @Override
    protected void init() {
        if (!loaded) {
            loaded = true;
            actions.addAll(ActionStore.load(modid, path, weapon));
            actionIndex = actions.isEmpty() ? -1
                    : Math.min(Math.max(0, actionIndex), actions.size() - 1);
            rebuildIds();
        }

        int y = 24;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleAction(-1))
                .bounds(34, y, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleAction(1))
                .bounds(52, y, 16, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.back"), b -> close())
                .bounds(width - 86, y, 76, 16).build());

        addExtraWidgets();

        searchBox = new EditBox(font, 10, searchY, 180, 14,
                Component.translatable(searchKey()));
        searchBox.setMaxLength(64);
        searchBox.setValue(list.filter);
        searchBox.setResponder(s -> {
            list.setFilter(s);
            list.setVisibleRows(visibleRows());
        });
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.check_filtered"),
                b -> batchSet(true)).bounds(196, searchY - 1, 88, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.uncheck_filtered"),
                b -> batchSet(false)).bounds(288, searchY - 1, 88, 16).build());
    }

    protected void refilter() {
        list.setVisibleRows(visibleRows());
        list.refilter();
    }

    protected void cycleAction(int delta) {
        if (actions.isEmpty()) {
            return;
        }
        actionIndex = Math.floorMod(actionIndex + delta, actions.size());
        list.scroll = 0;
        refilter();
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

    protected void close() {
        if (!saveAndInvalidate()) {
            return;
        }
        Minecraft.getInstance().setScreen(backScreen());
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
        if (mouseX >= LIST_X && mouseX <= width - 10 && mouseY >= listTop) {
            int idx = list.indexAt(mouseY, listTop);
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
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);

        ActionStore.Action a = action();
        g.drawString(font, a == null ? Component.translatable("gui.combatoverhaul.no_action")
                : Component.translatable(actionLabel(), a.name), 76, 28, 0xA0A0FF);

        renderExtra(g, mouseX, mouseY);

        int rows = visibleRows();
        for (int i = 0; i < rows && list.scroll + i < list.filtered.size(); i++) {
            drawRow(g, list.filtered.get(list.scroll + i), listTop + i * ROW_H, mouseX, mouseY);
        }
        if (list.filtered.isEmpty()) {
            g.drawString(font, "§7-", LIST_X + 2, listTop + 2, 0x808080);
        }

        g.drawString(font, Component.translatable(hintKey()), 10, height - 26, 0x909090);
        if (!status.isEmpty()) {
            g.drawString(font, status, 10, height - 14, 0x80FF80);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    protected void drawStandardRow(GuiGraphics g, String id, int y, boolean checked,
                                   int mouseX, int mouseY) {
        if (mouseX >= LIST_X && mouseX <= width - 10 && mouseY >= y && mouseY < y + ROW_H) {
            g.fill(LIST_X, y, width - 10, y + ROW_H, 0x33FFFFFF);
        }
        g.drawString(font, (checked ? "§a[✓] §f" : "§7[ ] ") + id, LIST_X + 2, y + 2,
                checked ? 0x80FF80 : 0xC0C0C0);
    }
}
