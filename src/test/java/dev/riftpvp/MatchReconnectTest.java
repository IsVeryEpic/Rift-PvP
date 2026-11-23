package dev.riftpvp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MatchReconnectTest {
   @Test
   void onlyTreatsBothPlayersAsAbsentWhenBothAreActuallyOffline() {
      assertTrue(MatchReconnect.bothOffline(false, false));
      assertFalse(MatchReconnect.bothOffline(true, false));
      assertFalse(MatchReconnect.bothOffline(false, true));
      assertFalse(MatchReconnect.bothOffline(true, true));
   }
}
