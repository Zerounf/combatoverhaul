package com.yourname.combatoverhaul.rig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点状骨架：只描述关节位置、父子关系、语义点和挂点，不含任何几何。
 *
 * <p>模型通过 {@code "rig": "namespace:path"} 引用它，再用
 * {@code "rig_points": {"hand_r": "R_Weapon", ...}} 做一一绑定。
 * 点坐标使用模型空间（和 player 模型同样的 16px=1 block 坐标）。
 */
public final class Rig {

    public static final int FORMAT_VERSION = 1;

    public String id = "";
    public String note = "";
    public final List<Point> points = new ArrayList<>();
    public final Map<String, Point> byName = new LinkedHashMap<>();
    /** 挂点：socket 名 -> 点/偏移/旋转 */
    public final Map<String, Socket> sockets = new LinkedHashMap<>();

    public static final class Point {
        public String name = "";
        public String parent = "";
        public float[] pivot = {0, 0, 0};
        /** 语义名（head/right_arm/hand_r...），可为空 */
        public String semantic = "";
        /** true=只作为挂点，不参与蒙皮 */
        public boolean attach;
    }

    public static final class Socket {
        public String point = "";
        public float[] offset = {0, 0, 0};
        public float[] rotDeg = {0, 0, 0};
    }

    public static Rig parse(JsonObject json, String fallbackId) {
        Rig r = new Rig();
        r.id = fallbackId == null ? "" : fallbackId;
        if (json == null) {
            return r;
        }
        if (json.has("id") && json.get("id").isJsonPrimitive()) {
            r.id = json.get("id").getAsString();
        }
        if (json.has("note") && json.get("note").isJsonPrimitive()) {
            r.note = json.get("note").getAsString();
        }
        if (json.has("points") && json.get("points").isJsonArray()) {
            for (var e : json.getAsJsonArray("points")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                Point p = new Point();
                if (o.has("name") && o.get("name").isJsonPrimitive()) {
                    p.name = o.get("name").getAsString();
                }
                if (p.name.isBlank()) {
                    continue;
                }
                if (o.has("parent") && o.get("parent").isJsonPrimitive()) {
                    p.parent = o.get("parent").getAsString();
                }
                if (o.has("pivot") && o.get("pivot").isJsonArray()) {
                    p.pivot = read3(o.getAsJsonArray("pivot"), p.pivot);
                }
                if (o.has("semantic") && o.get("semantic").isJsonPrimitive()) {
                    p.semantic = o.get("semantic").getAsString();
                }
                if (o.has("attach") && o.get("attach").isJsonPrimitive()) {
                    p.attach = o.get("attach").getAsBoolean();
                }
                r.points.add(p);
                r.byName.put(p.name, p);
            }
        }
        if (json.has("sockets") && json.get("sockets").isJsonObject()) {
            for (var e : json.getAsJsonObject("sockets").entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getValue().getAsJsonObject();
                Socket s = new Socket();
                if (o.has("point") && o.get("point").isJsonPrimitive()) {
                    s.point = o.get("point").getAsString();
                }
                if (o.has("offset") && o.get("offset").isJsonArray()) {
                    s.offset = read3(o.getAsJsonArray("offset"), s.offset);
                }
                if (o.has("rot_deg") && o.get("rot_deg").isJsonArray()) {
                    s.rotDeg = read3(o.getAsJsonArray("rot_deg"), s.rotDeg);
                }
                if (!s.point.isBlank()) {
                    r.sockets.put(e.getKey(), s);
                }
            }
        }
        return r;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT_VERSION);
        if (!id.isBlank()) {
            json.addProperty("id", id);
        }
        if (!note.isBlank()) {
            json.addProperty("note", note);
        }
        JsonArray arr = new JsonArray();
        for (Point p : points) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.name);
            if (p.parent != null && !p.parent.isBlank()) {
                o.addProperty("parent", p.parent);
            }
            JsonArray pivot = new JsonArray();
            pivot.add(p.pivot[0]);
            pivot.add(p.pivot[1]);
            pivot.add(p.pivot[2]);
            o.add("pivot", pivot);
            if (p.semantic != null && !p.semantic.isBlank()) {
                o.addProperty("semantic", p.semantic);
            }
            if (p.attach) {
                o.addProperty("attach", true);
            }
            arr.add(o);
        }
        json.add("points", arr);
        if (!sockets.isEmpty()) {
            JsonObject sock = new JsonObject();
            for (var e : sockets.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("point", e.getValue().point);
                o.add("offset", vec(e.getValue().offset));
                o.add("rot_deg", vec(e.getValue().rotDeg));
                sock.add(e.getKey(), o);
            }
            json.add("sockets", sock);
        }
        return json;
    }

    public Point point(String name) {
        return name == null ? null : byName.get(name);
    }

    public Point bySemantic(String semantic) {
        if (semantic == null) {
            return null;
        }
        for (Point p : points) {
            if (semantic.equals(p.semantic)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 默认人形点骨架：玩家/Alex 的 16px 坐标，含肘、膝、手、脚和武器挂点。
     */
    public static Rig humanoid() {
        Rig r = new Rig();
        r.id = "combatoverhaul:humanoid";
        r.note = "默认人形点状骨架";
        add(r, "root", "", 0, 0, 0, "root");
        add(r, "pelvis", "root", 0, 12, 0, "pelvis");
        add(r, "spine", "pelvis", 0, 8, 0, "spine");
        add(r, "chest", "spine", 0, 4, 0, "chest");
        add(r, "neck", "chest", 0, 0, 0, "neck");
        add(r, "head", "neck", 0, -8, 0, "head");
        add(r, "shoulder_r", "chest", -5, 2, 0, "shoulder_r");
        add(r, "elbow_r", "shoulder_r", -5, 6, 0, "elbow_r");
        add(r, "hand_r", "elbow_r", -5, 12, 0, "hand_r");
        add(r, "shoulder_l", "chest", 5, 2, 0, "shoulder_l");
        add(r, "elbow_l", "shoulder_l", 5, 6, 0, "elbow_l");
        add(r, "hand_l", "elbow_l", 5, 12, 0, "hand_l");
        add(r, "hip_r", "pelvis", -1.9f, 12, 0, "hip_r");
        add(r, "knee_r", "hip_r", -1.9f, 18, 0, "knee_r");
        add(r, "foot_r", "knee_r", -1.9f, 24, 0, "foot_r");
        add(r, "hip_l", "pelvis", 1.9f, 12, 0, "hip_l");
        add(r, "knee_l", "hip_l", 1.9f, 18, 0, "knee_l");
        add(r, "foot_l", "knee_l", 1.9f, 24, 0, "foot_l");
        socket(r, "R_Weapon", "hand_r");
        socket(r, "L_Weapon", "hand_l");
        return r;
    }

    private static void add(Rig r, String name, String parent, float x, float y, float z,
                            String semantic) {
        Point p = new Point();
        p.name = name;
        p.parent = parent;
        p.pivot = new float[]{x, y, z};
        p.semantic = semantic;
        r.points.add(p);
        r.byName.put(name, p);
    }

    private static void socket(Rig r, String name, String point) {
        Socket s = new Socket();
        s.point = point;
        r.sockets.put(name, s);
    }

    private static void socketAt(Rig r, String name, String point, float x, float y, float z) {
        Socket s = new Socket();
        s.point = point;
        s.offset = new float[]{x, y, z};
        r.sockets.put(name, s);
    }

    private static float[] read3(JsonArray a, float[] fallback) {
        if (a == null || a.size() < 3) {
            return fallback;
        }
        try {
            return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static JsonArray vec(float[] v) {
        JsonArray a = new JsonArray();
        a.add(v[0]);
        a.add(v[1]);
        a.add(v[2]);
        return a;
    }
}
