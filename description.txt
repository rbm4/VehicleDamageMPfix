[h1]Vehicle Damage MP Fix[/h1]
Fixes a vanilla multiplayer bug where vehicle collision damage is massively multiplied, destroying cars far faster than intended.

THIS IS A SERVER-SIDE JAVA MOD, SUBSCRIBING WON'T DO ANYTHING, YOU MUST FOLLOW THE INSTRUCTIONS BELOW

[h2]The Problem[/h2]
In vanilla multiplayer, when a vehicle hits a zombie, animal, or player, the game applies vehicle damage through [b]two independent paths[/b]:

[b]Path 1 — Lua command ([i]damageFromHitChr[/i]):[/b]
Every connected client that has the vehicle loaded independently runs Bullet physics. Each client detects the collision and sends a [i]damageFromHitChr[/i] Lua command to the server. With N players nearby, the server receives N copies of the same damage. However, the game has a built-in client-side throttle ([i]VehiclePedestrianContactTracking[/i], 3.5s cooldown) that limits how often this command fires per zombie — so this path, while duplicated across clients, has [i]some[/i] built-in protection.

[b]Path 2 — Java network packet ([i]VehicleHitField[/i]):[/b]
The driver's client also sends a [i]VehicleHitField[/i] packet directly through the Java networking layer. This packet [b]bypasses Lua entirely[/b] and applies damage via [i]addDamageFrontHitAChr[/i] / [i]addDamageRearHitAChr[/i] in the server-side [i]VehicleHitField.process()[/i] method. The critical problem is: the driver's physics loop fires this packet [b]every single frame[/b] while the vehicle's hitbox overlaps with the target. At 60 FPS, a single zombie hit produces ~3-5 packets (one per frame of overlap), and [b]there is zero deduplication or cooldown[/b] on the server side. Every packet applies full damage.

The combined effect:
[list]
[*] Path 1 sends N×damage (one per connected client) — mitigated by client-side cooldown
[*] Path 2 sends 3-5×damage per hit (one per frame of overlap) — [b]no protection at all[/b]
[*] Both paths stack on top of each other
[/list]

This is why vehicles feel like paper in multiplayer — a single zombie hit that should deal 20-30 damage to the vehicle ends up dealing 100-200+.

[h2]How we found this[/h2]
Earlier versions of this mod tried to fix the problem purely through Lua — blocking duplicate [i]damageFromHitChr[/i] commands, adding driver authorization, throttling, and scaling. None of it worked. Even when the Lua command was sending [b]zero damage[/b], vehicles were still being destroyed at the same rate. This proved that the real damage was coming from somewhere Lua couldn't touch.

Tracing the Java source revealed the second path: [i]VehicleHitField[/i] packets sent directly from the driver's physics loop, processed entirely in Java, with no cooldown or deduplication — unlike the Lua path which has [i]VehiclePedestrianContactTracking[/i]. The Lua path already had a throttle; the Java path simply didn't. The fix was to give it one.

[h2]The Fix[/h2]
This mod patches the vanilla [i]VehicleHitField.class[/i] with a server-side classpath override. The patched class adds a deduplication cooldown to [i]VehicleHitField.process()[/i] — the same kind of throttle that the Lua damage path already has, but was missing from the Java packet path.

What the patch does:
[list]
[*] When the server receives a [i]VehicleHitField[/i] packet, it checks whether vehicle damage was already applied for the same [b](vehicle, target)[/b] pair within a 3.5-second window (matching the vanilla [i]VehiclePedestrianContactTracking[/i] cooldown). If so, the vehicle damage is suppressed. If not, it applies normally and records the timestamp.
[*] Only [b]vehicle HP damage[/b] is throttled. Character effects (zombie death, knockdown, stagger) are [b]not affected[/b] — they pass through on every packet so zombies still die and ragdoll correctly.
[*] The dedup uses a static [i]HashMap<Long, Long>[/i] keyed by a combination of the vehicle's network ID and the target's identity hash. Stale entries are cleaned up periodically (every 10 seconds).
[*] The cooldown is configurable at runtime via JVM property: [i]-Dpz.vehicle.hit.dedup.cooldown=3500[/i] (milliseconds, default 3500). Set to 0 to disable dedup entirely.
[/list]

The result: a vehicle takes [b]exactly 1x[/b] damage per collision, matching singleplayer behavior. No Lua files are modified.

[h2]Installation[/h2]
This mod is a [b]Java classpath override[/b] — it requires placing a patched [i].class[/i] file on your server. No Lua mod subscription is needed (though the Workshop page hosts the files and documentation).

The patched [i]VehicleHitField.class[/i] must be placed in the correct folder relative to your [i]projectzomboid.jar[/i]. Project Zomboid's classpath loads loose .class files [b]before[/b] the JAR, so placing the patched file in the right directory overrides the vanilla class without modifying the JAR.

[b]Option A — Use the included build scripts:[/b]
Inside this mod's folders, there's a [i]tools/[/i] folder that contains two self-contained scripts that compile the patched source and deploy it automatically:
[list]
[*] [b]Windows:[/b] [i]patchVehicleHitField.ps1[/i] — Run in PowerShell. Will auto-download JDK 25 if needed. Default PZ path: [i]Z:\SteamLibrary\steamapps\common\ProjectZomboid[/i]. Use [i]-PZDir "your\path"[/i] to override.
[*] [b]Linux:[/b] [i]patchVehicleHitField.sh[/i] — Run with bash. Requires JDK 25 installed ([i]apt install openjdk-25-jdk-headless[/i]). Default PZ path: [i]/opt/pzserver[/i]. Edit [i]PZ_DIR[/i] at the top to change.
[*] To revert: run the same script with [i]-Revert[/i] (Windows) or [i]--revert[/i] (Linux).
[/list]

YOU MUST CONFIGURE YOUR ProjectZomboid folder path correctly for windows or for linux.

[b]Option B — Manual deployment:[/b]
If you don't have knowledge about scripts, you can get the [i]VehicleHitField.class[/i] from [i]tools/out/classes/zombie/network/fields/hit/VehicleHitField.class[/i] (after running a script once, or from a release) and place it manually:
[list]
[*] [b]Windows dedicated server:[/b] Copy [i]VehicleHitField.class[/i] to [i]<ProjectZomboid>/zombie/network/fields/hit/[/i] (create the folders if they don't exist). This is the same directory that contains [i]projectzomboid.jar[/i], with the package path appended.
[*] [b]Linux dedicated server:[/b] Copy to [i]<PZServer>/java/zombie/network/fields/hit/[/i] (Linux servers use the [i]java/[/i] subdirectory as classpath root).
[*] To revert: simply delete the [i]VehicleHitField.class[/i] file and restart the server. The original class from the JAR will be used.
[/list]

Restart the server after deploying.

[h2]Technical Details[/h2]
[list]
[*] [b]Server-side only[/b] — no client-side files are modified, no Lua is changed
[*] [b]Zero performance overhead[/b] — the dedup is a single HashMap lookup per incoming VehicleHitField packet
[*] [b]Safe[/b] — all other network packets and game logic are left identical to vanilla
[*] [b]Original JAR untouched[/b] — the classpath override is a standard JVM mechanism; deleting the .class file fully reverts to vanilla
[*] [b]No mod conflicts[/b] — since no Lua files are overridden, this is compatible with any other mod
[/list]

[h2]Compatibility[/h2]
[list]
[*] Build 42+
[*] Multiplayer only (does nothing in singleplayer)
[*] Safe to add or remove mid-save
[/list]

With love from Brazil <3