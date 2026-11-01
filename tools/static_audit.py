#!/usr/bin/env python3
"""Static integrity checks only; NOT a Paper compilation or Minecraft test."""
from pathlib import Path
from zipfile import ZipFile
import re, yaml, subprocess
root = Path(__file__).resolve().parents[1]
config = yaml.safe_load((root/'src/main/resources/config.yml').read_text())
plugin = yaml.safe_load((root/'src/main/resources/plugin.yml').read_text())
java = (root/'src/main/java/dev/riftpvp')
main = (java/'RiftPvpPlugin.java').read_text()
practice = (java/'PracticeFeatures.java').read_text()
kit = (java/'KitRoom.java').read_text()
staff = (java/'StaffTools.java').read_text()
tpa = (java/'TeleportRequests.java').read_text()
perks = (java/'LobbyPerks.java').read_text()
checks = {
  'all registered commands declared': all(name in plugin['commands'] for name in re.findall(r'"([a-z]+)"', main.split('for (String var5 : List.of(',1)[1].split(')) {',1)[0])),
  'tpa nonOP': plugin['permissions']['riftpvp.tpa']['default'] is True,
  'admin OP only': plugin['permissions']['riftpvp.admin']['default']=='op',
  'kit creation OP only': plugin['permissions']['riftpvp.kitcreate']['default']=='op',
  'VIP flight false by default': plugin['permissions']['riftpvp.vipflight']['default'] is False,
  'ranked Elo gaps configurable': config['ranked']['matchmaking']['maximum-elo-gap'] >= config['ranked']['matchmaking']['initial-elo-gap'],
  'ranked queue periodically rescanned': 'this.practice::retryRankedMatches' in main and 'void retryRankedMatches()' in practice,
  'casual matches never award ranked W/L': 'if (fight.competitive) {\n         rating(winner' in practice,
  'kit room caps default XP': 'private ItemStack[] defaultKitItems(String kit)' in kit,
  'Kit Room HTK End Stone': 'Material.END_STONE' in kit,
  'Endgame unlimited XP rule': '"endgame".equalsIgnoreCase(kit)' in (java/'KitManager.java').read_text(),
  'TPA request-bound actions': 'req.token().toString().equals(args[1])' in tpa,
  'VIP restricted to VIP role or node': 'hasVipLobbyFlight' in perks,
  'no VIP crafting grid bypass': 'InventoryType.CRAFTING) return' not in perks,
  'staff hierarchy protected': 'SetOfStaff.isAdministrator(targetRank)' in staff and 'level(actor) >= 2' in staff,
  'staff ban saved outside worlds': 'temporary-bans.yml' in staff,
  'Lobby kit disabled': '/kit cannot be used in the lobby' in main,
  'normal RTP dimension handling intact': 'void randomTeleport(Player' in main,
  'match recovery enabled': 'this.matchRecovery.recoverOnline()' in main,
}
for k,ok in checks.items():print(('PASS' if ok else 'FAIL')+' '+k)
assert all(checks.values()), 'Static audit failed'
hotbar = (java/'LobbyHotbar.java').read_text()
checks_vnext = {
  'base lobby items': all('new Shortcut("'+key+'"' in hotbar for key in ('queue','editor','rtp','leaderboard','settings')),
  'moderator and admin items': 'new Shortcut("staff", 15' in hotbar and 'new Shortcut("admin", 16' in hotbar,
  'admin runtime OP gate': 'if (shortcut.id().equals("admin")) return player.isOp();' in hotbar,
  'staff rank checked in runtime': 'plugin.staffTools().hasStaffAccess(player)' in hotbar,
  'no lobby item overwrites': 'existing == null || existing.getType().isAir()' in hotbar,
  'hotbar cleanup outside lobby': 'remove(player); return;' in hotbar,
  'moderator cannot be assigned plugin admin': '"owner", "founder", "moderator"' in (java/'RankManager.java').read_text(),
  'OP-only admin feature GUI': 'if (!player.isOp())' in (java/'AdminFeatures.java').read_text(),
  'lobby wind charges fully removed': 'supply(inventory, Material.WIND_CHARGE' not in perks and 'removeLegacyWindCharges(player)' in perks,
  'staff GUI validates holder': 'instanceof StaffHolder' in staff,
}
for name, ok in checks_vnext.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
assert all(checks_vnext.values()), 'Hotbar integrity checks failed'
lobby_reset = (java/'LobbyInventoryReset.java').read_text()
checks_lobby = {
  'auto clear enabled by default': config['lobby']['clear-inventory-on-entry'] is True,
  'join triggers lobby clear': 'onJoin(PlayerJoinEvent event)' in lobby_reset,
  'teleport arrival triggers lobby clear': '!plugin.inLobby(event.getFrom()) && plugin.inLobby(event.getTo())' in lobby_reset,
  'respawn arrival triggers lobby clear': 'onRespawn(PlayerRespawnEvent event)' in lobby_reset,
  'no outside-lobby clear': 'if (!player.isOnline() || !plugin.inLobby(player.getLocation())) return;' in lobby_reset,
  'clear offhand armor and cursor': all(x in lobby_reset for x in ('setArmorContents(new ItemStack[4])', 'setItemInOffHand(null)', 'setItemOnCursor(null)')),
  'restore lobby shortcut VIP visibility': all(x in lobby_reset for x in ('lobbyHotbar().refresh(player)', 'lobbyVisibility().tick()', 'lobbyPerks().tick()')),
  'explicit lobby command clears': 'this.lobbyInventoryReset.clearNow(var1)' in main,
  'recovery clears on arrival': 'plugin.lobbyInventoryReset().clearNow(player)' in (java/'MatchRecovery.java').read_text(),
  'lobby clear listener registered': 'this.staffTools, this.lobbyHotbar, this.lobbyInventoryReset,' in main,
}
for name,ok in checks_lobby.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
assert all(checks_lobby.values()), 'Lobby reset source integrity failed'
assert len(list(java.glob('*.java'))) >= 31, 'Original Stage 4/7 source files missing'
custom = (java/'CustomKits.java').read_text()
queue = (java/'MatchQueue.java').read_text()
cleaner = (java/'GroundEntityCleanup.java').read_text()
checks_latest = {
  'custom kit command registered': 'customkit' in plugin['commands'] and plugin['permissions']['riftpvp.customkit']['default'] is True and 'this.customKits.command(var6, var4)' in main,
  'custom kit six slots': 'slot < 1 || slot > 6' in custom,
  'custom kit does not trust arbitrary items': 'validateAndRebuild(source, capture(player))' in custom,
  'custom kit matched inventory for both': 'matchEquipment.put(a.getUniqueId()' in custom and 'matchEquipment.put(b.getUniqueId()' in custom,
  'custom duel validation': 'kit.matches("custom:[1-6]")' in practice and 'plugin.customKits().exists(kit)' in practice,
  'match equipment in normal pipeline': 'plugin.customKits().prepare(first, second, kit)' in queue and 'plugin.customKits().equip(var1, var4)' in queue,
  'custom snapshot cleaned after match': 'plugin.customKits().release(var1, var2)' in queue,
  'cleanup on by default': config['cleanup']['enabled'] is True and config['cleanup']['interval-seconds'] == 60,
  'cleanup all three dimension names': all(x in cleaner for x in ('"overworld", "nether", "end"', 'rtp.worlds.')),
  'cleanup only grounded entities': '!entity.isOnGround()' in cleaner,
  'cleanup loaded world only': 'Bukkit.getWorld(name)' in cleaner and 'world.getEntities()' in cleaner,
  'cleanup never removes players/NPC/decor': all(x in cleaner for x in ('entity instanceof Player', 'entity instanceof Mannequin', 'entity instanceof ArmorStand', 'entity instanceof Villager', 'entity instanceof Tameable')),
  'cleanup operator preview and execute': 'case "preview", "now"' in cleaner and 'sender instanceof Player player && !player.isOp()' in cleaner,
  'cleanup Admin GUI toggle': '"cleanup.enabled"' in (java/'AdminFeatures.java').read_text(),
  'cleanup scheduler wired': 'this.groundEntityCleanup.start()' in main and 'this.groundEntityCleanup.stop()' in main,
}
for name,ok in checks_latest.items(): print(('PASS' if ok else 'FAIL') + ' ' + name)
assert all(checks_latest.values()), 'Latest feature static source assertions failed'
print(f'PASS {len(checks) + len(checks_vnext) + len(checks_lobby) + len(checks_latest)} static assertions; {len(list(java.glob("*.java")))} Java files present')
