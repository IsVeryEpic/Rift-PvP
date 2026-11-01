from pathlib import Path
import re, yaml
root=Path(__file__).resolve().parents[1]
src=root/'src'/'main'/'java'/'dev'/'riftpvp'
code={file.stem:file.read_text() for file in src.glob('*.java')}
plugin=yaml.safe_load((root/'src'/'main'/'resources'/'plugin.yml').read_text())
config=yaml.safe_load((root/'src'/'main'/'resources'/'config.yml').read_text())
checks={
 'all original 31 Java source files included':len(code)>=31,
 'analytics hooks match start/finish': 'fightAnalytics().start(' in code['PracticeFeatures'] and 'fightAnalytics().finished(' in code['PracticeFeatures'],
 'damage and healing observed': 'fightAnalytics().hit(' in code['PracticeFeatures'] and 'fightAnalytics().healed(' in code['PracticeFeatures'],
 'streak aggregation persists': 'best-streak' in code['FightAnalytics'] and 'totals.save(file)' in code['FightAnalytics'],
 'match ledger stores statistics': 'recordStatistics(' in code['MatchLedger'] and 'statistics.' in code['MatchLedger'],
 'fightstats is public': plugin['permissions']['riftpvp.fightstats']['default'] is True,
 'queueprefs is public': plugin['permissions']['riftpvp.queueprefs']['default'] is True,
 'saved queue preferences': 'max-opponent-ping' in code['PlayerPreferences'] and 'settings.save(file)' in code['PlayerPreferences'],
 'ranked ping preference enforced': 'pingCompatible(one, two)' in code['PracticeFeatures'],
 'RTP queue ping preference enforced': 'pingCompatible(first, second)' in code['MatchQueue'],
 'custom queue ping preference enforced': 'pingCompatible(host, guest)' in code['CustomKits'],
 'pending queue retry': 'retryEligibleQueues' in code['RiftPvpPlugin'] and 'retryEligibleHosts' in code['RiftPvpPlugin'],
 'spectator navigation': 'equalsIgnoreCase("next")' in code['PracticeFeatures'] and 'equalsIgnoreCase("prev")' in code['PracticeFeatures'],
 'spectator restoration': 'unspectate(Player player)' in code['PracticeFeatures'] and 'player.teleportAsync(old.location)' in code['PracticeFeatures'],
 'discord disabled by default': config['discord']['enabled'] is False and config['discord']['webhook-url']=='',
 'discord async timeouts': 'runTaskAsynchronously' in code['DiscordBridge'] and 'setConnectTimeout' in code['DiscordBridge'] and 'setReadTimeout' in code['DiscordBridge'],
 'discord limited to official hosts': 'discord.com' in code['DiscordBridge'] and 'discordapp.com' in code['DiscordBridge'],
 'discord mentions disabled': 'allowed_mentions' in code['DiscordBridge'],
 'ranked integrity records repeated pairs': 'flag-after-matches' in code['RankedIntegrity'] and 'flagIntegrity(' in code['MatchLedger'],
 'ranked anti-boosting not punitive by default': config['ranked']['integrity']['suppress-repeat-elo'] is False,
 'moderator ban cap unchanged': 'Duration.ofDays(30)' in code['StaffTools'],
 'OP admin controls unchanged': 'if (!player.isOp())' in code['AdminFeatures'],
 'staff audit rotates and records': 'staff-audit.log' in code['StaffAudit'] and 'staff-audit.previous.log' in code['StaffAudit'],
 'kit changes audited': 'kit-delete' in code['KitCreator'] and 'kit-restore' in code['KitCreator'] and 'kit-create' in code['KitCreator'],
 'rank changes audited': 'rank-' in code['RiftPvpPlugin'] and 'staffAudit.log(' in code['RiftPvpPlugin'],
 'doctor reports health and privacy': 'void doctor(CommandSender sender)' in code['RiftPvpPlugin'] and 'Discord:' in code['RiftPvpPlugin'],
 'mob cleanup opt-in': config['cleanup']['grounded-mobs'] is False,
 'item drops age grace': config['cleanup']['minimum-item-age-seconds']>=30,
 'cleanup nearby player safety': config['cleanup']['player-protection-radius-blocks']>=24 and 'distanceSquared(entity.getLocation())' in code['GroundEntityCleanup'],
 'cleanup lobby safety': 'entity.getLocation().distanceSquared(lobby)' in code['GroundEntityCleanup'],
 'cleanup warning can be stopped': 'warningTask.cancel()' in code['GroundEntityCleanup'],
 'custom kit host modes': '"public", "party", "private"' in code['CustomKits'],
 'custom kit invite & direct join': 'verb.equals("invite")' in code['CustomKits'] and 'verb.equals("join")' in code['CustomKits'],
 'custom duel preserved': 'custom:[1-6]' in code['PracticeFeatures'],
 'lobby rejoin reset preserved': 'onJoin(PlayerJoinEvent event)' in code['LobbyInventoryReset'] and 'clearNow(player)' in code['LobbyInventoryReset'],
 'lobby survival boundaries preserved': 'plugin.inLobby(player.getLocation())' in code['LobbyInventoryReset'],
 'lobby queue cancellation': 'plugin.matchQueue().cancelForLobby(player);' in code['LobbyInventoryReset'],
 'commands registered and declared':True,
}
# The bootstrap command list uses declared quoted literals and excludes plugin aliases.
main=code['RiftPvpPlugin']
block=main.split('for (String var5 : List.of(',1)[1].split(')) {',1)[0]
registered=set(re.findall(r'"(\w+)"',block))
# Rift Menu has its own executor and is registered separately from the core command loop.
if 'this.getCommand("riftmenu")' in main and 'setExecutor(this.riftMenu)' in main:
 registered.add("riftmenu")
declared=set(plugin['commands'])
checks['commands registered and declared']=(registered==declared)
for key in ('riftpvp.admin','riftpvp.kitcreate'):
 checks[key+' stays OP']=plugin['permissions'][key]['default']=='op'
for key in ('riftpvp.heal','riftpvp.kiteditor','riftpvp.queue'):
 checks[key+' stays public']=plugin['permissions'][key]['default'] is True
for key in ('fightstats','queueprefs'):
 checks[key+' command permission declared']=plugin['commands'][key]['permission']==f'riftpvp.{key}'
for name,passed in checks.items():
 print(('PASS' if passed else 'FAIL')+' '+name)
print(f"{sum(checks.values())}/{len(checks)} Stage 7 static integration checks passed")
assert all(checks.values()), 'Source integration checks failed; not safe to package'
