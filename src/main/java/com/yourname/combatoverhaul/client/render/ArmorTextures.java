package com.yourname.combatoverhaul.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;

/** 原版盔甲纹理路径。 */
public final class ArmorTextures {

    private ArmorTextures() {
    }

    public static ResourceLocation of(ArmorItem armor, EquipmentSlot slot) {
        String material = armor.getMaterial().getName();
        ResourceLocation asset = material.contains(":")
                ? new ResourceLocation(material)
                : new ResourceLocation("minecraft", material);
        String layer = slot == EquipmentSlot.LEGS ? "layer_2" : "layer_1";
        return new ResourceLocation(asset.getNamespace(),
                "textures/models/armor/" + asset.getPath() + "_" + layer + ".png");
    }
}
