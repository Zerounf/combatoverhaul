package com.yourname.combatoverhaul.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scratch 式事件图：一个模型一张图，图内每个事件帽子下挂一条积木链。
 * 关键帧动画仍是独立 action，由 play 积木引用。
 */
public final class Graph {

    public static final int FORMAT_VERSION = 1;

    public String format = "combatoverhaul:graph/1";
    public final List<Script> scripts = new ArrayList<>();

    public static final class Script {
        public String event = "on_tick";
        /** on_signal 时匹配的信号名 */
        public String signal = "";
        public Block first;
    }

    public static final class Block {
        public String type = "";
        /** 标量参数：clip/signal/scope/ticks/count/text/... */
        public final Map<String, String> fields = new LinkedHashMap<>();
        public Block next;
        /** if/repeat 的主体 */
        public Block body;
        /** if 的否则分支 */
        public Block body2;
        public float x;
        public float y;

        public Block(String type) {
            this.type = type == null ? "" : type;
        }

        public String field(String key, String fallback) {
            String v = fields.get(key);
            return v == null ? fallback : v;
        }
    }

    public static Graph parse(JsonObject json) {
        Graph g = new Graph();
        if (json == null) {
            return g;
        }
        if (json.has("format") && json.get("format").isJsonPrimitive()) {
            g.format = json.get("format").getAsString();
        }
        if (json.has("scripts") && json.get("scripts").isJsonArray()) {
            for (var e : json.getAsJsonArray("scripts")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                Script s = new Script();
                if (o.has("event") && o.get("event").isJsonPrimitive()) {
                    s.event = o.get("event").getAsString();
                }
                if (o.has("signal") && o.get("signal").isJsonPrimitive()) {
                    s.signal = o.get("signal").getAsString();
                }
                if (o.has("first") && o.get("first").isJsonObject()) {
                    s.first = parseBlock(o.getAsJsonObject("first"));
                }
                g.scripts.add(s);
            }
        }
        return g;
    }

    private static Block parseBlock(JsonObject o) {
        if (o == null || !o.has("type")) {
            return null;
        }
        Block b = new Block(o.get("type").getAsString());
        if (o.has("fields") && o.get("fields").isJsonObject()) {
            for (var e : o.getAsJsonObject("fields").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    b.fields.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        if (o.has("x")) {
            b.x = o.get("x").getAsFloat();
        }
        if (o.has("y")) {
            b.y = o.get("y").getAsFloat();
        }
        if (o.has("next") && o.get("next").isJsonObject()) {
            b.next = parseBlock(o.getAsJsonObject("next"));
        }
        if (o.has("body") && o.get("body").isJsonObject()) {
            b.body = parseBlock(o.getAsJsonObject("body"));
        }
        if (o.has("body2") && o.get("body2").isJsonObject()) {
            b.body2 = parseBlock(o.getAsJsonObject("body2"));
        }
        return b;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("format", format);
        JsonArray arr = new JsonArray();
        for (Script s : scripts) {
            JsonObject o = new JsonObject();
            o.addProperty("event", s.event);
            if (s.signal != null && !s.signal.isBlank()) {
                o.addProperty("signal", s.signal);
            }
            if (s.first != null) {
                o.add("first", blockJson(s.first));
            }
            arr.add(o);
        }
        json.add("scripts", arr);
        return json;
    }

    private static JsonObject blockJson(Block b) {
        JsonObject o = new JsonObject();
        o.addProperty("type", b.type);
        if (!b.fields.isEmpty()) {
            JsonObject f = new JsonObject();
            for (var e : b.fields.entrySet()) {
                f.addProperty(e.getKey(), e.getValue());
            }
            o.add("fields", f);
        }
        o.addProperty("x", Math.round(b.x * 10f) / 10f);
        o.addProperty("y", Math.round(b.y * 10f) / 10f);
        if (b.next != null) {
            o.add("next", blockJson(b.next));
        }
        if (b.body != null) {
            o.add("body", blockJson(b.body));
        }
        if (b.body2 != null) {
            o.add("body2", blockJson(b.body2));
        }
        return o;
    }

    /** 删除一条链（编辑器用）；返回新头。 */
    public static Block remove(Block head, Block target) {
        if (head == null || target == null) {
            return head;
        }
        if (head == target) {
            return head.next;
        }
        Block prev = head;
        while (prev.next != null && prev.next != target) {
            prev = prev.next;
        }
        if (prev.next == target) {
            prev.next = target.next;
        }
        return head;
    }
}
