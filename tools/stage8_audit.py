"""Static source/config integration checks. Never substitutes for Java 25 compilation/live tests."""
from pathlib import Path
import yaml,re
root=Path(__file__).resolve().parents[1]
src=root/'src/main/java/dev/riftpvp'
code={x.stem:x.read_text() for x in src.glob('*.java')}
plugin=yaml.safe_load((root/'src/main/resources/plugin.yml').read_text())
config=yaml.safe_load((root/'src/main/resources/config.yml').read_text())
main=code['RiftPvpPlugin']
registered=set(re.findall(r'"(\w+)"',main.split('for (String var5 : List.of(',1)[1].split(')) {',1)[0]))
# The main Rift Menu is registered by its dedicated executor.
if 'this.getCommand("riftmenu")' in main and 'setExecutor(this.riftMenu)' in main:
 registered.add("riftmenu")
declared=set(plugin['commands'])
C={
 'all legacy and six new Java classes':len(code)>=37 and all(n in code for n in ('DataBackups','CustomKitSecurity','MatchReconnect','KitModes','FirstJoinTutorial','MaintenanceControl')),
 'plugin command registration complete':registered==declared,
 'backups enabled with bounds':config['backups']['enabled'] and config['backups']['max-size-mb']>=4 and config['backups']['keep']>=2,
 'backups restrict files to plugin data folder':'root.relativize(file)' in code['DataBackups'] and 'relative.startsWith("backups")' in code['DataBackups'],
 'backups protect against symlinks':'Files.isSymbolicLink' in code['DataBackups'],
 'backups have archive size and entry caps':'too many archive entries' in code['DataBackups'] and 'expanded backup exceeds size limit' in code['DataBackups'],
 'backup schedule and startup snapshot':'snapshotAsync(null,"startup")' in code['DataBackups'] and '"scheduled"' in code['DataBackups'],
 'backup manual command and audit':'snapshotAsync(sender,"manual")' in code['DataBackups'] and 'snapshot("pre-restore")' in code['DataBackups'],
 'restore requires console and zero players':'sender instanceof org.bukkit.entity.Player' in code['DataBackups'] and '!Bukkit.getOnlinePlayers().isEmpty()' in code['DataBackups'],
 'restore requires maintenance':'!plugin.maintenance().isActive()' in code['DataBackups'],
 'restore staged rather than hot replacing':'pending-restore.zip' in code['DataBackups'] and 'applyPendingRestoreAtStartup' in main,
 'restore applied before manager initialization':main.index('applyPendingRestoreAtStartup()')<main.index('this.saveDefaultConfig()'),
 'restore failure disables plugin':'PENDING BACKUP RESTORE FAILED' in main and 'disablePlugin(this)' in main,
 'custom load revalidated':'ItemStack[] verified = validateAndRebuild(source, result)' in code['CustomKits'],
 'custom capture checks source equipped kit':'equippedKitId(player)' in code['CustomKits'],
 'custom snapshot same for both players':'matchEquipment.put(a.getUniqueId(), duplicate(items))' in code['CustomKits'] and 'matchEquipment.put(b.getUniqueId(), duplicate(items))' in code['CustomKits'],
 'custom enchant bounds':'enchant.getMaxLevel()' in code['CustomKitSecurity'],
 'custom nested containers rejected':'BlockStateMeta' in code['CustomKitSecurity'] and 'BundleMeta' in code['CustomKitSecurity'],
 'custom PDC preserved if server trusted':config['custom-kits']['allow-persistent-custom-data'] is True and 'comparable(wanted).isSimilar(comparable(allowed))' in code['CustomKits'],
 'custom forbidden material block':'_SHULKER_BOX' in code['CustomKitSecurity'],
 'custom inputs checked before kit saving':'CustomKitSecurity.allowed(plugin, wanted)' in code['CustomKits'],
 'grace configurable and bounded':config['reconnect']['grace-seconds']==25 and 'Math.min(90' in code['MatchReconnect'],
 'grace existing fight only':'hasActiveFight(id)' in code['MatchReconnect'] and 'isInActiveFight(id)' in code['MatchReconnect'],
 'disconnects pause with safe grace and recovery':'onDisconnect(var1.getPlayer())' in code['MatchQueue'] and 'matchReconnect().pending(id)' in code['PracticeFeatures'] and 'recoverSoon(remaining)' in code['MatchReconnect'] and config['reconnect']['enabled'] is True,
 'paused fight blocks damage and items':'void damage(EntityDamageEvent' in code['MatchReconnect'] and 'void inventory(InventoryClickEvent' in code['MatchReconnect'] and 'void drop(' in code['MatchReconnect'],
 'paused world change protected':'!plugin.matchReconnect().frozen(var2)' in code['MatchQueue'],
 'grace on join bypasses crash recovery':'!plugin.matchReconnect().pending(player.getUniqueId())' in code['MatchRecovery'],
 'reconnect timeout awards offline forfeit':'forfeitDisconnected(player)' in code['MatchReconnect'],
 'reconnect teleport errors not punished':'plugin.practice().expireMatch(id)' in code['MatchReconnect'] and 'recoverOnline()' in code['MatchReconnect'],
 'ranks unaffected':plugin['permissions']['riftpvp.admin']['default']=='op' and plugin['permissions']['riftpvp.heal']['default'] is True and plugin['permissions']['riftpvp.kitcreate']['default']=='op',
 'kit mode permission checked in code':'plugin.ranks().canManageKits(player)' in code['KitModes'],
 'kit mode defaults true':'getBoolean("kits." + kit + ".modes." + mode, true)' in code['KitModes'],
 'kit mode cancels waiting but not current fights':'removeKitQueue(kit)' in code['KitModes'] and 'removeRankedKitQueue(kit)' in code['KitModes'] and 'removeHostsForSource(kit)' in code['KitModes'],
 'ranked mode checked':'kitModes().check(player, kit, "ranked")' in code['PracticeFeatures'] and 'kitModes().allowed(kit, "ranked")' in code['PracticeFeatures'],
 'duel mode checked':'kitModes().check(player, kit, "duel")' in code['PracticeFeatures'],
 'queue mode checked':'kitModes().check(var1, var2, "queue")' in code['MatchQueue'],
 'custom mode checked':'kitModes().allowed(saved.source, "custom")' in code['CustomKits'],
 'tutorial one-time persisted':'tutorial-seen.yml' in code['FirstJoinTutorial'] and 'greeted.add(player.getUniqueId())' in code['FirstJoinTutorial'],
 'tutorial replay command':'this.firstJoinTutorial.show(var6)' in main,
 'tutorial uses clickable links':'ClickEvent.runCommand' in code['FirstJoinTutorial'],
 'tutorial not forced GUI':'openInventory(' not in code['FirstJoinTutorial'],
 'maintenance off by default':config['maintenance']['enabled'] is False,
 'maintenance on command OP gate':'this.maintenance.command(var1, var2)' in main and 'else if (!this.ranks.isAdmin(var1))' in main,
 'maintenance blocks new match selection':'maintenance().allowNewMatch(null)' in code['MatchQueue'],
 'maintenance leaves current fights running':'clearWaitingQueues()' in code['MaintenanceControl'] and 'clearRankedWaiting()' in code['MaintenanceControl'] and 'clearWaitingOnly()' in code['MaintenanceControl'],
 'maintenance persistent switch':'saveConfig()' in code['MaintenanceControl'] and 'void reload()' in code['MaintenanceControl'],
 'maintenance grants staff login exception':'PlayerLoginEvent' in code['MaintenanceControl'] and 'canManageKits(event.getPlayer())' in code['MaintenanceControl'],
 'maintenance hot restore blocks joins':'AsyncPlayerPreLoginEvent' in code['MaintenanceControl'] and 'restartRequired()' in code['MaintenanceControl'],
 'existing lobby safety remains':'lobbyInventoryReset.clearNow(var1)' in main and 'void clearNow(Player player)' in code['LobbyInventoryReset'],
 'current PVP modes retained':all(n in plugin['commands'] for n in ('rtpq','duel','ranked','customkit','kitdelete','kitrestore','kitsettings','heal')),
}
for name,valid in C.items(): print(('PASS' if valid else 'FAIL')+' '+name)
print(f'{sum(C.values())}/{len(C)} Stage 8 static integration assertions passed')
assert all(C.values())
