package com.yourname.combatoverhaul.client.render;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.combatoverhaul.CombatOverhaulMod;
import com.yourname.combatoverhaul.client.anim.ClientAnimHandler;
import com.yourname.combatoverhaul.client.bedrock.BedrockPose;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.client.skinned.CubeMeshBuilder;
import com.yourname.combatoverhaul.client.skinned.SkinWeightGenerator;
import com.yourname.combatoverhaul.client.skinned.SkinnedMesh;
import com.yourname.combatoverhaul.client.skinned.SkinnedMeshRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家盔甲层。
 *
 * <p>优先读取 {@code config/combatoverhaul/armor/<namespace>/<path>.json}：
 * 该 JSON 与实体模型同格式，盔甲自己的 cube/骨骼会在运行时生成连续网格，
 * 再通过 {@link SkinWeightGenerator#bindToSkeleton} 自动绑定到当前玩家骨架，
 * 因此肘/膝/躯干分段弯曲时盔甲也一起弯曲，不是硬编码 HumanoidModel。
 *
 * <p>没有自定义盔甲模型时回退原版 HumanoidModel，只用于兼容旧盔甲。
 */
public final class SkinnedArmorRenderer {

    private static final Map<String, CustomArmor> CACHE = new ConcurrentHashMap<>();

    private SkinnedArmorRenderer() {
    }

    private static final class CustomArmor {
        ParsedModel model;
        SkinnedMesh mesh;
        ResourceLocation texture;
        ParsedModel boundSkeleton;
        boolean failed;
    }

    public static void render(Player player, PoseStack pose, MultiBufferSource buffers, int light,
                              BedrockPose poseData) {
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armor)) {
                continue;
            }
            if (tryRenderCustom(player, pose, buffers, light, poseData, stack, armor)) {
                continue;
            }
            renderVanillaFallback(player, pose, buffers, light, stack, armor);
        }
    }

    private static boolean tryRenderCustom(Player player, PoseStack pose, MultiBufferSource buffers,
                                           int light, BedrockPose poseData, ItemStack stack,
                                           ArmorItem armor) {
        ParsedModel skeleton = ClientAnimHandler.currentRenderModel();
        if (skeleton == null || skeleton.skinnedMesh == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String key = id.toString();
        CustomArmor entry = CACHE.get(key);
        if (entry == null) {
            entry = load(id, armor, skeleton);
            CACHE.put(key, entry);
        }
        if (entry.failed || entry.mesh == null) {
            return false;
        }
        // 玩家 rig 可能在运行中从 12 骨补成四肢/躯干分段；骨架对象变化时重新绑定。
        if (entry.boundSkeleton != skeleton) {
            SkinWeightGenerator.Result bind = SkinWeightGenerator.bindToSkeleton(entry.mesh, skeleton);
            if (!bind.ok) {
                CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 盔甲自动蒙皮失败 {}: {}", key, bind.error);
                entry.failed = true;
                return false;
            }
            entry.boundSkeleton = skeleton;
        }
        VertexConsumer vc = buffers.getBuffer(RenderType.armorCutoutNoCull(entry.texture));
        SkinnedMeshRenderer.render(vc, pose, skeleton, entry.mesh, poseData, light,
                OverlayTexture.NO_OVERLAY);
        return true;
    }

    /**
     * 自定义盔甲可能以自己的 pivot/原点建模。用“该槽位对应玩家骨段顶点的 AABB”
     * 把盔甲整体平移过去，避免盔甲围绕原点飞在角色上方或脚下。
     */
    private static void alignToSkeleton(com.yourname.combatoverhaul.client.skinned.SkinnedMesh armorMesh,
                                        ParsedModel skeleton, EquipmentSlot slot) {
        if (armorMesh == null || skeleton == null || skeleton.skinnedMesh == null
                || skeleton.skinnedMesh.vertices.isEmpty()) {
            return;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        String[] roots = switch (slot) {
            case HEAD -> new String[]{"head", "hat"};
            case CHEST -> new String[]{"body"};
            case LEGS -> new String[]{"right_leg", "left_leg"};
            case FEET -> new String[]{"right_leg", "left_leg"};
            default -> new String[0];
        };
        for (String semantic : roots) {
            com.yourname.combatoverhaul.client.model.ParsedBone b = skeleton.findBone(semantic);
            if (b != null) {
                collectBoneTree(b, names);
            }
        }
        if (names.isEmpty()) {
            return;
        }
        float[] target = boundsOfWeighted(skeleton.skinnedMesh, names);
        if (target == null) {
            return;
        }
        float[] own = armorMesh.bounds();
        float dx = (target[0] + target[3]) * 0.5f - (own[0] + own[3]) * 0.5f;
        float dy = (target[1] + target[4]) * 0.5f - (own[1] + own[4]) * 0.5f;
        float dz = (target[2] + target[5]) * 0.5f - (own[2] + own[5]) * 0.5f;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1.0e-4f) {
            armorMesh.translate(dx, dy, dz);
            CombatOverhaulMod.LOGGER.info("[ArmorRender] aligned {} offset=({},{},{})",
                    slot, dx, dy, dz);
        }
    }

    /**
     * 自定义盔甲可能用任意骨名。按“每个源骨组的质心”找最近玩家关节，
     * 这样胸甲的 body / 左右袖可以分别落到 chest / shoulder_r / shoulder_l，
     * 而不是整件胸甲被一根骨吸走。
     */
    private static void classifySourceParts(com.yourname.combatoverhaul.client.skinned.SkinnedMesh mesh,
                                            ParsedModel skeleton) {
        if (mesh == null || mesh.sourceBones.size() != mesh.vertices.size()) {
            return;
        }
        java.util.List<com.yourname.combatoverhaul.rig.RigJoint> joints =
                com.yourname.combatoverhaul.client.model.ModelRigJoints.from(skeleton).joints();
        if (joints.isEmpty()) {
            return;
        }
        java.util.Map<String, float[]> sums = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < mesh.vertices.size(); i++) {
            String src = mesh.sourceBones.get(i);
            if (src == null || src.isBlank()) {
                continue;
            }
            var v = mesh.vertices.get(i);
            float[] s = sums.computeIfAbsent(src, k -> new float[3]);
            s[0] += v.x;
            s[1] += v.y;
            s[2] += v.z;
            counts.merge(src, 1, Integer::sum);
        }
        java.util.Map<String, String> mapped = new java.util.LinkedHashMap<>();
        for (var e : sums.entrySet()) {
            int n = Math.max(1, counts.getOrDefault(e.getKey(), 1));
            float cx = e.getValue()[0] / n;
            float cy = e.getValue()[1] / n;
            float cz = e.getValue()[2] / n;
            com.yourname.combatoverhaul.rig.RigJoint best = null;
            double bestD = Double.MAX_VALUE;
            for (var j : joints) {
                double d = (j.x() - cx) * (j.x() - cx)
                        + (j.y() - cy) * (j.y() - cy)
                        + (j.z() - cz) * (j.z() - cz);
                if (d < bestD) {
                    bestD = d;
                    best = j;
                }
            }
            if (best != null) {
                mapped.put(e.getKey(), best.name());
            }
        }
        for (int i = 0; i < mesh.sourceBones.size(); i++) {
            String target = mapped.get(mesh.sourceBones.get(i));
            if (target != null) {
                mesh.sourceBones.set(i, target);
            }
        }
        CombatOverhaulMod.LOGGER.info("[ArmorRender] source parts {}", mapped);
    }

    private static void collectBoneTree(com.yourname.combatoverhaul.client.model.ParsedBone bone,
                                        java.util.Set<String> out) {
        out.add(bone.name);
        for (com.yourname.combatoverhaul.client.model.ParsedBone child : bone.children) {
            collectBoneTree(child, out);
        }
    }

    private static float[] boundsOfWeighted(com.yourname.combatoverhaul.client.skinned.SkinnedMesh mesh,
                                            java.util.Set<String> names) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (int i = 0; i < mesh.vertices.size(); i++) {
            boolean hit = false;
            if (i < mesh.weightBones.size()) {
                for (String n : mesh.weightBones.get(i)) {
                    if (n != null && names.contains(n)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) {
                continue;
            }
            any = true;
            var v = mesh.vertices.get(i);
            b[0] = Math.min(b[0], v.x);
            b[1] = Math.min(b[1], v.y);
            b[2] = Math.min(b[2], v.z);
            b[3] = Math.max(b[3], v.x);
            b[4] = Math.max(b[4], v.y);
            b[5] = Math.max(b[5], v.z);
        }
        return any ? b : null;
    }

    private static CustomArmor load(ResourceLocation id, ArmorItem armor, ParsedModel skeleton) {
        CustomArmor out = new CustomArmor();
        Path path = FMLPaths.CONFIGDIR.get().resolve("combatoverhaul")
                .resolve("armor").resolve(id.getNamespace())
                .resolve(id.getPath() + ".json");
        CombatOverhaulMod.LOGGER.info("[ArmorRender] load path={} exists={}",
                path, Files.isRegularFile(path));
        if (!Files.isRegularFile(path)) {
            CombatOverhaulMod.LOGGER.info("[ArmorRender] no custom armor file {}", path);
            out.failed = true;
            return out;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            CombatOverhaulMod.LOGGER.info("[ArmorRender] read {} chars", json.length());
            ParsedModel model = ParsedModel.parse(JsonParser.parseString(json).getAsJsonObject());
            CombatOverhaulMod.LOGGER.info("[ArmorRender] parsed bones={} roots={}",
                    model.bones.size(), model.roots.size());
            if (model.uvPixelSpace) {
                float su = 1f / Math.max(1f, model.textureWidth);
                float sv = 1f / Math.max(1f, model.textureHeight);
                for (com.yourname.combatoverhaul.client.model.ParsedBone bone : model.bones) {
                    for (com.yourname.combatoverhaul.client.model.ParsedCube cube : bone.cubes) {
                        cube.scaleUv(su, sv);
                    }
                }
            }
            // 自定义盔甲常用 helmet/chest/leg 命名；未显式写 source_map 时按名字映射到玩家语义骨，
            // 避免所有盔甲顶点被最近骨段吸到 head 而飞到头上去。
            if (model.sourceMap.isEmpty()) {
                for (com.yourname.combatoverhaul.client.model.ParsedBone bone : model.bones) {
                    String n = bone.name.toLowerCase(java.util.Locale.ROOT);
                    if (n.contains("helmet") || n.contains("head")) {
                        model.sourceMap.put(bone.name, "head");
                    } else if (n.contains("chest") || n.contains("body") || n.contains("torso")) {
                        model.sourceMap.put(bone.name, "body");
                    } else if (n.contains("leg") || n.contains("thigh")) {
                        model.sourceMap.put(bone.name, "right_leg");
                    } else if (n.contains("boot") || n.contains("foot")) {
                        model.sourceMap.put(bone.name, "right_leg");
                    } else if (n.contains("arm")) {
                        model.sourceMap.put(bone.name, "right_arm");
                    }
                }
            }
            CubeMeshBuilder.Result built = CubeMeshBuilder.build(model);
            CombatOverhaulMod.LOGGER.info("[ArmorRender] build ok={} mesh={} error={}",
                    built.ok, model.skinnedMesh, built.error);
            if (!built.ok || model.skinnedMesh == null) {
                CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 盔甲模型网格生成失败 {}: {}",
                        id, built.error);
                out.failed = true;
                return out;
            }
            if (!model.sourceMap.isEmpty() && model.skinnedMesh.sourceBones != null) {
                for (int i = 0; i < model.skinnedMesh.sourceBones.size(); i++) {
                    String mapped = model.sourceMap.get(model.skinnedMesh.sourceBones.get(i));
                    if (mapped != null && !mapped.isBlank()) {
                        model.skinnedMesh.sourceBones.set(i, mapped);
                    }
                }
            }
            alignToSkeleton(model.skinnedMesh, skeleton, armor.getEquipmentSlot());
            classifySourceParts(model.skinnedMesh, skeleton);
            SkinWeightGenerator.Result bind = SkinWeightGenerator.bindToSkeleton(
                    model.skinnedMesh, skeleton);
            CombatOverhaulMod.LOGGER.info("[ArmorRender] bind ok={} verts={} error={}",
                    bind.ok, bind.vertices, bind.error);
            if (!bind.ok) {
                CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 盔甲自动蒙皮失败 {}: {}",
                        id, bind.error);
                out.failed = true;
                return out;
            }
            out.model = model;
            out.mesh = model.skinnedMesh;
            out.boundSkeleton = skeleton;
            out.texture = textureOf(model, armor);
            CombatOverhaulMod.LOGGER.info("[CombatOverhaul] 自定义盔甲已蒙皮 {} vertices={}",
                    id, out.mesh.vertices.size());
            return out;
        } catch (RuntimeException e) {
            CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 盔甲模型读取失败 {}", id, e);
            out.failed = true;
            return out;
        } catch (java.io.IOException e) {
            CombatOverhaulMod.LOGGER.warn("[CombatOverhaul] 盔甲模型文件读取失败 {}", id, e);
            out.failed = true;
            return out;
        } catch (Throwable e) {
            CombatOverhaulMod.LOGGER.error("[CombatOverhaul] 盔甲模型致命错误 {}", id, e);
            out.failed = true;
            return out;
        }
    }

    private static ResourceLocation textureOf(ParsedModel model, ArmorItem armor) {
        if (model.textureSpec != null && !model.textureSpec.isBlank()) {
            try {
                return new ResourceLocation(model.textureSpec);
            } catch (RuntimeException ignored) {
            }
        }
        String material = armor.getMaterial().getName();
        ResourceLocation asset = material.contains(":")
                ? new ResourceLocation(material)
                : new ResourceLocation("minecraft", material);
        return new ResourceLocation(asset.getNamespace(),
                "textures/models/armor/" + asset.getPath() + "_layer_1.png");
    }

    private static void renderVanillaFallback(Player player, PoseStack pose,
                                              MultiBufferSource buffers, int light,
                                              ItemStack stack, ArmorItem armor) {
        pose.pushPose();
        // HumanoidModel 和 player 模型使用同一套 canonical 坐标：
        // 头 -8..0、身体 0..12、腿 12..24。pose 已经由 SkinnedPlayerRenderer
        // 复制了原版 scale(-1,-1,1)+translate(0,-1.501,0)，这里不能再额外缩放/平移。
        EquipmentSlot slot = armor.getEquipmentSlot();
        boolean inner = slot == EquipmentSlot.LEGS;
        boolean slim = ClientAnimHandler.isSlimPlayer(player);
        var layerLocation = inner
                ? (slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR)
                : (slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR);
        HumanoidModel<Player> model = new net.minecraft.client.model.HumanoidArmorModel<>(
                Minecraft.getInstance().getEntityModels().bakeLayer(layerLocation));
        ParsedModel pm = ClientAnimHandler.currentRenderModel();
        if (pm != null) {
            copyPart(pm, "head", model.head);
            copyPart(pm, "body", model.body);
            copyPart(pm, "right_arm", model.rightArm);
            copyPart(pm, "left_arm", model.leftArm);
            copyPart(pm, "right_leg", model.rightLeg);
            copyPart(pm, "left_leg", model.leftLeg);
        }
        model.setAllVisible(false);
        switch (slot) {
            case HEAD -> model.head.visible = true;
            case CHEST -> {
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
            }
            case LEGS -> {
                model.body.visible = true;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            case FEET -> {
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            default -> {
            }
        }
        String material = armor.getMaterial().getName();
        ResourceLocation asset = material.contains(":")
                ? new ResourceLocation(material)
                : new ResourceLocation("minecraft", material);
        String layer = slot == EquipmentSlot.LEGS ? "layer_2" : "layer_1";
        ResourceLocation texture = new ResourceLocation(asset.getNamespace(),
                "textures/models/armor/" + asset.getPath() + "_" + layer + ".png");
        VertexConsumer vc = buffers.getBuffer(RenderType.armorCutoutNoCull(texture));
        model.renderToBuffer(pose, vc, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
        pose.popPose();
    }

    private static void copyPart(ParsedModel model, String bone,
                                 net.minecraft.client.model.geom.ModelPart part) {
        ParsedModel skeleton = ClientAnimHandler.currentRenderModel();
        com.yourname.combatoverhaul.client.model.ParsedBone b =
                skeleton == null ? null : skeleton.findBone(bone);
        if (b == null) {
            return;
        }
        part.xRot = b.fx();
        part.yRot = b.fy();
        part.zRot = b.fz();
    }
}
