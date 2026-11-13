package com.yourname.combatoverhaul.client.screen;

import com.yourname.combatoverhaul.client.model.WeaponModelGenerator;
import com.yourname.combatoverhaul.client.screen.ui.IdListState;
import com.yourname.combatoverhaul.combat.ActionCache;
import com.yourname.combatoverhaul.store.ActionStore;
import com.yourname.combatoverhaul.store.ModelStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 武器授权编辑（武器模型动作）：把当前武器/白模模型的动作授权给若干武器物品——
 * 勾选写入动作的 weapons 绑定列表，被授权武器持有时触发该动作并获得其动画/判定箱
 * （ActionCache 按物品 id 反向合并）。
 *
 * <ul>
 *   <li>目标列表 = 已有模型配置的武器（config/combatoverhaul/weapons/，●标记）∪ 全部注册物品。</li>
 *   <li>授权时目标武器没有模型文件 → 自动从物品贴图生成薄板模型；失败标红（不阻塞授权）。</li>
 *   <li>「写入整套动作」开：勾选/取消同步到该模型的全部动作（批量给一整套动作上同一批武器）。</li>
 * </ul>
 *
 * 从 CombatPanelScreen 的「授权武器」按钮进入；关闭时保存并返回战斗编辑。
 */
public class WeaponGrantScreen extends Screen {
    private static final int ROW_H = 12;
    private static final int LIST_X = 10;
    private static final int LIST_TOP = 104;

    private final Screen combatParent;
    private final String modid;
    private final String path;
    private final int initialActionIndex;

    private final List<ActionStore.Action> actions = new ArrayList<>();
    private int actionIndex;
    private boolean loaded = false;

    private boolean writeAll = false;
    private Button btnWriteAll;
    private EditBox searchBox;
    private final IdListState list = new IdListState();
    private String status = "";

    /** 已有模型文件的武器 id（●标记） */
    private final Set<String> withModel = new HashSet<>();
    /** 授权时自动生成模型失败的物品 id（标红） */
    private final Set<String> failed = new HashSet<>();

    public WeaponGrantScreen(Screen combatParent, String modid, String path, int actionIndex) {
        super(Component.translatable("gui.combatoverhaul.grant_title", modid, path));
        this.combatParent = combatParent;
        this.modid = modid;
        this.path = path;
        this.initialActionIndex = actionIndex;
    }

    private ActionStore.Action action() {
        return actionIndex >= 0 && actionIndex < actions.size() ? actions.get(actionIndex) : null;
    }

    /** 本次勾选/取消作用到的动作集合（整套=全部动作；否则仅当前动作） */
    private List<ActionStore.Action> targets() {
        List<ActionStore.Action> out = new ArrayList<>();
        if (writeAll || actionIndex < 0) {
            out.addAll(actions);
        } else if (action() != null) {
            out.add(action());
        }
        return out;
    }

    private boolean checked(String id) {
        for (ActionStore.Action a : targets()) {
            if (a.weapons.contains(id)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void init() {
        if (!loaded) {
            loaded = true;
            actions.addAll(ActionStore.load(modid, path, true));
            actionIndex = actions.isEmpty() ? -1
                    : Math.max(0, Math.min(initialActionIndex, actions.size() - 1));
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

        // 写入整套动作开关
        btnWriteAll = Button.builder(Component.empty(), b -> {
                    writeAll = !writeAll;
                    refreshWriteAll();
                }).bounds(10, 46, 110, 16).build();
        this.addRenderableWidget(btnWriteAll);
        refreshWriteAll();

        // 搜索 + 批量
        searchBox = new EditBox(this.font, 10, 84, 180, 14,
                Component.translatable("gui.combatoverhaul.grant_search"));
        searchBox.setMaxLength(64);
        searchBox.setValue(list.filter);
        searchBox.setResponder(s -> {
            list.setFilter(s);
            list.setVisibleRows(visibleRows());
        });
        this.addRenderableWidget(searchBox);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.check_filtered"),
                b -> batchSet(true)).bounds(196, 83, 88, 16).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.combatoverhaul.uncheck_filtered"),
                b -> batchSet(false)).bounds(288, 83, 88, 16).build());
    }

    /** 选项 = config 武器目录已有模型（排除 whitebox 工具目录）+ 全部注册物品 */
    private void rebuildIds() {
        withModel.clear();
        List<String> ids = new ArrayList<>();
        try {
            Path root = ModelStore.weaponsRoot();
            if (Files.isDirectory(root)) {
                try (var modStream = Files.list(root)) {
                    for (Path dir : modStream.filter(Files::isDirectory).toList()) {
                        String ns = dir.getFileName().toString();
                        if (ns.equals("whitebox")) {
                            continue; // 白模是动作载体，不作为授权目标
                        }
                        try (var fileStream = Files.list(dir)) {
                            for (Path f : fileStream.toList()) {
                                String n = f.getFileName().toString();
                                if (!n.endsWith(".json") || n.endsWith(".actions.json")) {
                                    continue;
                                }
                                String id = ns + ":" + n.substring(0, n.length() - 5);
                                if (withModel.add(id)) {
                                    ids.add(id);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            String s = id.toString();
            if (withModel.add(s)) {
                ids.add(s);
            }
        }
        list.setVisibleRows(visibleRows());
        list.setAll(ids);
    }

    private void refilter() {
        list.setVisibleRows(visibleRows());
        list.refilter();
    }

    private void refreshWriteAll() {
        if (btnWriteAll != null) {
            btnWriteAll.setMessage(Component.translatable(writeAll
                    ? "gui.combatoverhaul.grant_write_all_on" : "gui.combatoverhaul.grant_write_all"));
        }
    }

    private void cycleAction(int d) {
        if (actions.isEmpty()) {
            return;
        }
        actionIndex = Math.floorMod(actionIndex + d, actions.size());
        list.scroll = 0;
        refilter();
    }

    private void toggle(String id) {
        List<ActionStore.Action> ts = targets();
        if (ts.isEmpty()) {
            status = Component.translatable("gui.combatoverhaul.no_action").getString();
            return;
        }
        if (checked(id)) {
            for (ActionStore.Action a : ts) {
                a.weapons.remove(id);
            }
            return;
        }
        for (ActionStore.Action a : ts) {
            if (!a.weapons.contains(id)) {
                a.weapons.add(id);
            }
        }
        // 授权目标无模型 → 自动从物品贴图生成薄板模型（失败标红，不阻塞授权）
        String[] nsp = id.split(":", 2);
        if (nsp.length == 2 && !ModelStore.weaponExists(nsp[0], nsp[1]) && !failed.contains(id)) {
            String[] diag = new String[1];
            if (WeaponModelGenerator.ensureModel(id, diag)) {
                withModel.add(id);
                status = Component.translatable("gui.combatoverhaul.grant_generated", id).getString();
            } else {
                failed.add(id);
                status = Component.translatable("gui.combatoverhaul.grant_generate_failed",
                        id + " (" + diag[0] + ")").getString();
            }
        }
    }

    private void batchSet(boolean check) {
        List<ActionStore.Action> ts = targets();
        if (ts.isEmpty()) {
            status = Component.translatable("gui.combatoverhaul.no_action").getString();
            return;
        }
        int generated = 0;
        int genFailed = 0;
        for (String id : list.filtered) {
            if (check) {
                if (checked(id)) {
                    continue;
                }
                for (ActionStore.Action a : ts) {
                    if (!a.weapons.contains(id)) {
                        a.weapons.add(id);
                    }
                }
                String[] nsp = id.split(":", 2);
                if (nsp.length == 2 && !ModelStore.weaponExists(nsp[0], nsp[1])) {
                    String[] diag = new String[1];
                    if (WeaponModelGenerator.ensureModel(id, diag)) {
                        withModel.add(id);
                        generated++;
                    } else {
                        failed.add(id);
                        genFailed++;
                    }
                }
            } else {
                for (ActionStore.Action a : ts) {
                    a.weapons.remove(id);
                }
            }
        }
        if (check && generated + genFailed > 0) {
            status = Component.translatable("gui.combatoverhaul.grant_batch_done",
                    generated, genFailed).getString();
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.height - LIST_TOP - 30) / ROW_H);
    }

    private boolean saveAndInvalidate() {
        if (ActionStore.save(modid, path, true, actions)) {
            ActionCache.invalidate(modid, path, true);
            status = Component.translatable("gui.combatoverhaul.binding_saved").getString();
            return true;
        }
        status = Component.translatable("gui.combatoverhaul.save_fail").getString();
        return false;
    }

    private void close() {
        if (!saveAndInvalidate()) {
            return; // 写失败时留在本界面，避免未保存的授权被下一次加载覆盖
        }
        Minecraft.getInstance().setScreen(
                new CombatPanelScreen(combatParent, modid, path, true, actionIndex));
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
                : Component.translatable("gui.combatoverhaul.combat_action", a.name), 76, 28, 0xA0A0FF);
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.grant_count",
                checkedCount(), list.filtered.size()), 196, 28, 0x909090);

        // 图例
        g.drawString(this.font, Component.translatable("gui.combatoverhaul.grant_legend"),
                10, 70, 0x909090);

        // 列表
        int rows = visibleRows();
        for (int i = 0; i < rows && list.scroll + i < list.filtered.size(); i++) {
            String id = list.filtered.get(list.scroll + i);
            boolean ck = checked(id);
            boolean bad = failed.contains(id);
            boolean has = withModel.contains(id);
            int y = LIST_TOP + i * ROW_H;
            if (mouseX >= LIST_X && mouseX <= this.width - 10 && mouseY >= y && mouseY < y + ROW_H) {
                g.fill(LIST_X, y, this.width - 10, y + ROW_H, 0x33FFFFFF);
            }
            String mark = has ? "§a●" : "§8○";
            int color = bad ? 0xFF6060 : ck ? 0x80FF80 : 0xC0C0C0;
            g.drawString(this.font, (ck ? "§a[✓] §f" : "§7[ ] ") + mark + " " + id,
                    LIST_X + 2, y + 2, color);
        }
        if (list.filtered.isEmpty()) {
            g.drawString(this.font, "§7-", LIST_X + 2, LIST_TOP + 2, 0x808080);
        }

        g.drawString(this.font, Component.translatable("gui.combatoverhaul.grant_hint"),
                10, this.height - 26, 0x909090);
        if (!status.isEmpty()) {
            g.drawString(this.font, status, 10, this.height - 14, 0x80FF80);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 当前授权目标集合中已勾选的武器数 */
    private int checkedCount() {
        Set<String> set = new HashSet<>();
        for (ActionStore.Action act : targets()) {
            set.addAll(act.weapons);
        }
        return set.size();
    }
}
