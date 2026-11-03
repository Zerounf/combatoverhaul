package com.yourname.combatoverhaul.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.store.ModelStore;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 武器模型自动生成：从物品注册名对应的物品贴图生成一张「薄板」武器模型
 * （正面/背面 = 不透明像素包围盒区域，四个侧面 = 边缘像素拉伸，与原版
 * ItemModelGenerator 的手持观感一致），写入 weapons/&lt;ns&gt;/&lt;path&gt;.json。
 *
 * <p>用途：给某武器授权动作时若该武器还没有模型文件，先自动生成一份基础模型，
 * 让动作/判定箱（自动几何）有几何可用；生成的文件可继续在 3D 编辑器里手改。
 *
 * <p>生成失败的情形（无物品模型 JSON / 贴图缺失 / 全透明贴图）由调用方标红提示。
 */
public final class WeaponModelGenerator {

    /** 薄板厚度（模型单位/像素，1-2px 的经典物品薄板观感） */
    private static final int PLATE_THICKNESS = 2;

    private WeaponModelGenerator() {
    }

    /**
     * 确保某武器有模型文件：已有则原样保留（true）；没有则从物品贴图生成（true）；
     * 生成失败返回 false。
     *
     * @param diag 输出失败原因说明（供界面提示），可为 null
     */
    public static boolean ensureModel(String itemId, String[] diag) {
        String[] nsp = itemId.split(":", 2);
        if (nsp.length != 2 || nsp[0].isEmpty() || nsp[1].isEmpty()) {
            setDiag(diag, "bad item id: " + itemId);
            return false;
        }
        String ns = nsp[0];
        String path = nsp[1];
        if (ModelStore.weaponExists(ns, path)) {
            return true; // 已有模型（含手动调过的），绝不覆盖
        }
        JsonObject model = generate(ns, path, diag);
        if (model == null) {
            return false;
        }
        boolean ok = ModelStore.writeWeapon(ns, path, model.toString());
        if (!ok) {
            setDiag(diag, "write failed: " + ns + "/" + path);
        }
        return ok;
    }

    /** 生成武器模型 JSON；失败返回 null */
    private static JsonObject generate(String ns, String path, String[] diag) {
        try {
            ResourceLocation texRl = resolveItemTexture(ns, path, diag);
            if (texRl == null) {
                return null;
            }
            ResourceLocation pngRl = pngOf(texRl);
            Resource res = Minecraft.getInstance().getResourceManager()
                    .getResource(pngRl).orElse(null);
            if (res == null) {
                setDiag(diag, "texture file missing: " + pngRl);
                return null;
            }
            int[] bbox = new int[4]; // {x0, y0, x1, y1}（含端点）
            try (NativeImage img = NativeImage.read(res.open())) {
                if (!scanOpaqueBounds(img, bbox)) {
                    setDiag(diag, "texture fully transparent: " + pngRl);
                    return null;
                }
                return buildModelJson(texRl, img.getWidth(), img.getHeight(), bbox);
            }
        } catch (Exception ex) {
            CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 武器模型自动生成失败: {}:{}", ns, path, ex);
            setDiag(diag, ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
            return null;
        }
    }

    /**
     * 解析物品模型 JSON 的贴图：读 assets/&lt;ns&gt;/models/item/&lt;path&gt;.json，
     * 取 textures.layer0（无则取第一个贴图槽），缺失时沿 parent 链向上找
     * （builtin/* 终止）。返回贴图 RL（形如 minecraft:item/diamond_sword）。
     */
    private static ResourceLocation resolveItemTexture(String ns, String path, String[] diag) {
        String curNs = ns;
        String curPath = path;
        for (int depth = 0; depth < 16; depth++) {
            ResourceLocation modelRl = new ResourceLocation(curNs, "models/item/" + curPath + ".json");
            Resource res = Minecraft.getInstance().getResourceManager().getResource(modelRl).orElse(null);
            if (res == null) {
                setDiag(diag, "item model missing: " + modelRl);
                return null;
            }
            JsonObject json;
            try (var in = res.open()) {
                json = JsonParser.parseString(new String(in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception ex) {
                setDiag(diag, "item model parse fail: " + modelRl);
                return null;
            }
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                JsonObject texs = json.getAsJsonObject("textures");
                String tex = null;
                if (texs.has("layer0") && texs.get("layer0").isJsonPrimitive()) {
                    tex = texs.get("layer0").getAsString();
                } else if (!texs.entrySet().isEmpty()) {
                    // 非标准槽位（all/texture 等）兜底取第一个
                    tex = texs.entrySet().iterator().next().getValue().getAsString();
                }
                if (tex != null && !tex.isEmpty()) {
                    return tex.contains(":")
                            ? new ResourceLocation(tex)
                            : new ResourceLocation(curNs, tex);
                }
            }
            // 本层无贴图 → 沿 parent 链继续（builtin/* 无贴图可找）
            if (!json.has("parent") || !json.get("parent").isJsonPrimitive()) {
                setDiag(diag, "no layer0 in parent chain of " + ns + ":" + path);
                return null;
            }
            String parent = json.get("parent").getAsString();
            if (parent.startsWith("builtin/")) {
                setDiag(diag, "builtin parent has no texture: " + parent);
                return null;
            }
            if (parent.contains(":")) {
                String[] pp = parent.split(":", 2);
                curNs = pp[0];
                curPath = pp[1];
            } else {
                curPath = parent;
            }
        }
        setDiag(diag, "parent chain too deep: " + ns + ":" + path);
        return null;
    }

    /** 贴图 RL → PNG 文件 RL（textures/ 前缀 + .png 后缀；已带则原样） */
    private static ResourceLocation pngOf(ResourceLocation texRl) {
        String p = texRl.getPath();
        if (p.startsWith("textures/") && p.endsWith(".png")) {
            return texRl;
        }
        if (p.endsWith(".png")) {
            return new ResourceLocation(texRl.getNamespace(), "textures/" + p);
        }
        return new ResourceLocation(texRl.getNamespace(), "textures/" + p + ".png");
    }

    /** 扫描不透明（alpha>0）像素包围盒；全透明返回 false */
    private static boolean scanOpaqueBounds(NativeImage img, int[] bbox) {
        int w = img.getWidth();
        int h = img.getHeight();
        int x0 = -1, y0 = -1, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((img.getPixelRGBA(x, y) >>> 24) & 0xFF) > 0) {
                    if (x0 < 0) {
                        x0 = x;
                        y0 = y;
                        x1 = x;
                        y1 = y;
                    } else {
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                    }
                }
            }
        }
        if (x0 < 0) {
            return false;
        }
        bbox[0] = x0;
        bbox[1] = y0;
        bbox[2] = x1;
        bbox[3] = y1;
        return true;
    }

    /**
     * 组装模型 JSON：单根骨骼 root（枢轴 0,0,0），一个薄板立方体——
     * 包围盒宽高为板面（X×Y），厚 2px（Z）；X 居中、Z 居中，贴图底边贴在 y=0
     * （模型空间 +Y 朝下，手持位置=原点）。正面 UV=包围盒像素区，侧面=边缘拉伸。
     */
    private static JsonObject buildModelJson(ResourceLocation texRl, int texW, int texH, int[] bbox) {
        int pw = bbox[2] - bbox[0] + 1;
        int ph = bbox[3] - bbox[1] + 1;
        int t = PLATE_THICKNESS;

        ParsedCube cube = new ParsedCube(-pw / 2f, -ph, -t / 2f, pw, ph, t);
        float u0 = bbox[0] / (float) texW;
        float v0 = bbox[1] / (float) texH;
        float u1 = (bbox[2] + 1) / (float) texW;
        float v1 = (bbox[3] + 1) / (float) texH;
        cube.fillFacesFromSpan(u0, v0, u1, v1, 1f / texW, 1f / texH);

        JsonObject json = new JsonObject();
        json.addProperty("format", ModelStore.FORMAT_VERSION);
        json.addProperty("note", "generated from item texture " + texRl);
        json.addProperty("texture", pngRlString(texRl));
        JsonObject root = new JsonObject();
        root.add("pivot", ParsedCube.arr(0, 0, 0));
        com.google.gson.JsonArray cubes = new com.google.gson.JsonArray();
        cubes.add(cube.toJson());
        root.add("cubes", cubes);
        JsonObject roots = new JsonObject();
        roots.add("root", root);
        json.add("roots", roots);
        return json;
    }

    /** 贴图 RL 的完整 PNG 路径字符串（写入模型 texture 字段，与编辑器解析一致） */
    private static String pngRlString(ResourceLocation texRl) {
        ResourceLocation png = pngOf(texRl);
        return png.getNamespace() + ":" + png.getPath();
    }

    private static void setDiag(String[] diag, String msg) {
        if (diag != null && diag.length > 0) {
            diag[0] = msg;
        }
    }
}
