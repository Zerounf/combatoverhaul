package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.store.ActionStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作绑定编辑：为当前动作勾选 weapons（绑定武器物品）与 entities（授权生物）。
 * 从 CombatPanelScreen 的「绑定」按钮进入；返回时自动保存并回到战斗编辑。
 */
public class ActionBindingScreen extends ActionSelectionScreen {

    private int tab = 0; // 0=武器 1=生物

    public ActionBindingScreen(Screen returnParent, String modid, String path,
                               boolean weapon, int actionIndex) {
        super(returnParent, modid, path, weapon, actionIndex, 88, 68,
                Component.translatable("gui.combatoverhaul.binding_title", modid, path));
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
    protected void rebuildIds() {
        List<String> ids = new ArrayList<>();
        var registry = tab == 0 ? BuiltInRegistries.ITEM : BuiltInRegistries.ENTITY_TYPE;
        for (ResourceLocation id : registry.keySet()) {
            ids.add(id.toString());
        }
        list.setVisibleRows(visibleRows());
        list.setAll(ids);
    }

    @Override
    protected void toggle(String id) {
        List<String> bound = bindingList();
        if (bound == null) {
            return;
        }
        if (!bound.remove(id)) {
            bound.add(id);
        }
    }

    @Override
    protected void batchSet(boolean check) {
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

    @Override
    protected void addExtraWidgets() {
        int ty = 46;
        addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.tab_weapons"),
                b -> switchTab(0)).bounds(10, ty, 70, 16).build());
        if (!weapon) {
            addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.tab_entities"),
                    b -> switchTab(1)).bounds(84, ty, 70, 16).build());
        }
    }

    private void switchTab(int t) {
        if (t == tab) {
            return;
        }
        tab = t;
        list.scroll = 0;
        rebuildIds();
    }

    @Override
    protected String actionLabel() {
        return "gui.combatoverhaul.binding_action";
    }

    @Override
    protected String hintKey() {
        return "gui.combatoverhaul.binding_hint";
    }

    @Override
    protected void drawRow(GuiGraphics g, String id, int y, int mouseX, int mouseY) {
        List<String> bound = bindingList();
        drawStandardRow(g, id, y, bound != null && bound.contains(id), mouseX, mouseY);
    }

    @Override
    protected void renderExtra(GuiGraphics g, int mouseX, int mouseY) {
        List<String> bound = bindingList();
        String tabName = Component.translatable(tab == 0
                ? "gui.combatoverhaul.tab_weapons" : "gui.combatoverhaul.tab_entities").getString();
        g.drawString(font, Component.translatable("gui.combatoverhaul.binding_count",
                bound == null ? 0 : bound.size(), list.filtered.size()), 382, 71, 0x909090);
        g.drawString(font, "§8" + tabName, 162, 50, 0xFFFFFF);
    }

    @Override
    protected Screen backScreen() {
        return new CombatPanelScreen(returnParent, modid, path, weapon, actionIndex);
    }
}
