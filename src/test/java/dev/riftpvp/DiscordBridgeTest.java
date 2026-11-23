package dev.riftpvp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiscordBridgeTest {
   @Test
   void escapesQuotesBackslashesAndStandardControls() {
      assertEquals("quote: \\\" backslash: \\\\ line: \\n tab: \\t",
         DiscordBridge.escapeJson("quote: \" backslash: \\ line: \n tab: \t"));
   }

   @Test
   void escapesEveryRemainingJsonControlCharacter() {
      StringBuilder input = new StringBuilder();
      StringBuilder expected = new StringBuilder();

      for (char value = 0; value < 0x20; value++) {
         input.append(value);
         switch (value) {
            case '\b' -> expected.append("\\b");
            case '\f' -> expected.append("\\f");
            case '\n' -> expected.append("\\n");
            case '\r' -> expected.append("\\r");
            case '\t' -> expected.append("\\t");
            default -> {
               expected.append("\\u00");
               expected.append(Character.forDigit((value >> 4) & 0xF, 16));
               expected.append(Character.forDigit(value & 0xF, 16));
            }
         }
      }

      assertEquals(expected.toString(), DiscordBridge.escapeJson(input.toString()));
   }

   @Test
   void leavesUnicodeAndOrdinaryTextUnchanged() {
      String message = "RiftPvP — Português — 日本語 — ✦";
      assertEquals(message, DiscordBridge.escapeJson(message));
   }
}
