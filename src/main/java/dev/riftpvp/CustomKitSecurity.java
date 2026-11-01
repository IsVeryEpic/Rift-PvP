package dev.riftpvp;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Trusted-template-only loadouts; reject nested inventory/NBT and unsafe enchant overrides. */
final class CustomKitSecurity {
    private CustomKitSecurity() {}
    static boolean allowed(RiftPvpPlugin plugin, ItemStack stack) {
        if (stack == null) return true;
        Material material = stack.getType();
        if (material.isAir() || stack.getAmount() < 1 || stack.getAmount() > stack.getMaxStackSize()) return false;
        String name = material.name();
        if (name.endsWith("_SHULKER_BOX") || name.equals("BUNDLE")
            || name.equals("CHEST_MINECART") || name.equals("HOPPER_MINECART")
            || name.equals("CHEST") || name.equals("TRAPPED_CHEST") || name.equals("BARREL")
            || name.equals("HOPPER") || name.equals("DISPENSER") || name.equals("DROPPER")) return false;
        if (plugin.getConfig().getStringList("custom-kits.forbidden-materials").contains(name)) return false;
        if (!stack.hasItemMeta()) return true;
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BlockStateMeta || meta instanceof BundleMeta) return false;
        if (!plugin.getConfig().getBoolean("custom-kits.allow-persistent-custom-data", false)
            && !meta.getPersistentDataContainer().isEmpty()) return false;
        if (!plugin.getConfig().getBoolean("custom-kits.allow-unsafe-enchants", false)) {
            for (var entry : stack.getEnchantments().entrySet())
                if (!levelAllowed(entry.getKey(),entry.getValue())) return false;
            if (meta instanceof EnchantmentStorageMeta book)
                for (var entry : book.getStoredEnchants().entrySet())
                    if (!levelAllowed(entry.getKey(), entry.getValue())) return false;
        }
        return true;
    }
    private static boolean levelAllowed(Enchantment enchant, int level) {
        return level >= 1 && level <= enchant.getMaxLevel();
    }
}
