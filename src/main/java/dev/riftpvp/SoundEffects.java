package dev.riftpvp;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

final class SoundEffects {
   private final RiftPvpPlugin plugin;
   private final Set<String> invalidCuesLogged = new HashSet<>();

   SoundEffects(RiftPvpPlugin var1) {
      this.plugin = var1;
   }

   void play(Player var1, String var2) {
      this.play(var1, var2, 1.0F);
   }

   void play(Player var1, String var2, float var3) {
      if (var1 != null && var1.isOnline() && this.plugin.getConfig().getBoolean("sounds.enabled", true)
            && (this.plugin.playerPreferences() == null || this.plugin.playerPreferences().allows(var1, "sounds"))) {
         String var4 = "sounds." + var2 + ".";
         String var5 = this.plugin.getConfig().getString(var4 + "name", defaultSound(var2));
         if (var5 != null && !var5.isBlank() && !var5.equalsIgnoreCase("none")) {
            try {
               Sound var6 = Sound.valueOf(var5.trim().toUpperCase(Locale.ROOT));
               float var7 = (float)Math.max(0.0, Math.min(4.0, this.plugin.getConfig().getDouble(var4 + "volume", 0.7)));
               float var8 = (float)Math.max(0.5, Math.min(2.0, this.plugin.getConfig().getDouble(var4 + "pitch", 1.0) * var3));
               var1.playSound(var1.getLocation(), var6, SoundCategory.PLAYERS, var7, var8);
            } catch (IllegalArgumentException var9) {
               if (this.invalidCuesLogged.add(var2 + "=" + var5)) {
                  this.plugin.getLogger().warning("Invalid sound for " + var2 + ": " + var5);
               }
            }
         }
      }
   }

   private static String defaultSound(String var0) {
      return switch (var0) {
         case "queue-join", "queue-leave", "npc-click" -> "UI_BUTTON_CLICK";
         case "match-found", "kill" -> "ENTITY_PLAYER_LEVELUP";
         case "countdown" -> "BLOCK_NOTE_BLOCK_PLING";
         case "match-start" -> "ENTITY_PLAYER_ATTACK_CRIT";
         case "teleport" -> "ENTITY_ENDERMAN_TELEPORT";
         case "kit" -> "ENTITY_ITEM_PICKUP";
         case "heal" -> "BLOCK_BEACON_POWER_SELECT";
         case "combat-start" -> "BLOCK_NOTE_BLOCK_BASS";
         case "death" -> "ENTITY_PLAYER_HURT";
         default -> "UI_BUTTON_CLICK";
      };
   }
}
