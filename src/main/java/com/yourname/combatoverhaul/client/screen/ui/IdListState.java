package com.yourname.combatoverhaul.client.screen.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 共用的“可搜索 id 列表”状态。只负责数据、过滤和滚动，
 * 不负责渲染或 Action 保存，避免两个选择界面继续复制这套逻辑。
 */
public final class IdListState {

    public static final int ROW_H = 12;

    public final List<String> all = new ArrayList<>();
    public final List<String> filtered = new ArrayList<>();
    public String filter = "";
    public int scroll;
    private int visibleRows = 1;

    public void setAll(Collection<String> ids) {
        all.clear();
        all.addAll(ids);
        all.sort(Comparator.naturalOrder());
        refilter();
    }

    public void setVisibleRows(int rows) {
        visibleRows = Math.max(1, rows);
        clamp(visibleRows);
    }

    public void refilter() {
        filtered.clear();
        String f = filter == null ? "" : filter.trim().toLowerCase();
        for (String id : all) {
            if (f.isEmpty() || id.toLowerCase().contains(f)) {
                filtered.add(id);
            }
        }
        clamp(visibleRows);
    }

    public void setFilter(String value) {
        filter = value == null ? "" : value;
        scroll = 0;
        refilter();
    }

    public void clamp(int rows) {
        scroll = Math.max(0, Math.min(scroll,
                Math.max(0, filtered.size() - Math.max(1, rows))));
    }

    public void scrollBy(double delta, int rows) {
        scroll -= (int) (delta * 3);
        clamp(rows);
    }

    public int indexAt(double mouseY, int listTop) {
        return scroll + (int) ((mouseY - listTop) / ROW_H);
    }
}
