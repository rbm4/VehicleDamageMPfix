# Vehicle Damage MP Fix - A Project Zomboid B42.16.x / B42.17.x fix

Presented by [APOCALIPSE [BR]](https://apocalipse.cloud/) - A Brazilian Project Zomboid private server.

Fixes a vanilla multiplayer bug where vehicle collision damage is massively multiplied, destroying cars far faster than intended.

> **THIS IS A SERVER-SIDE JAVA MOD, SUBSCRIBING WON'T DO ANYTHING, YOU MUST FOLLOW THE INSTRUCTIONS BELOW**

## The Problem

In vanilla multiplayer, when a vehicle hits a zombie, animal, or player, the game applies vehicle damage through **two independent paths**:

**Path 1 — Lua command (`damageFromHitChr`):**
Every connected client that has the vehicle loaded independently runs Bullet physics. Each client detects the collision and sends a `damageFromHitChr` Lua command to the server. With N players nearby, the server receives N copies of the same damage. However, the game has a built-in client-side throttle (`VehiclePedestrianContactTracking`, 3.5s cooldown) that limits how often this command fires per zombie — so this path, while duplicated across clients, has *some* built-in protection.

**Path 2 — Java network packet (`VehicleHitField`):**
The driver's client also sends a `VehicleHitField` packet directly through the Java networking layer. This packet **bypasses Lua entirely** and applies damage via `addDamageFrontHitAChr` / `addDamageRearHitAChr` in the server-side `VehicleHitField.process()` method. The critical problem is: the driver's physics loop fires this packet **every single frame** while the vehicle's hitbox overlaps with the target. At 60 FPS, a single zombie hit produces ~3-5 packets (one per frame of overlap), and **there is zero deduplication or cooldown** on the server side. Every packet applies full damage.

The combined effect:

- Path 1 sends N×damage (one per connected client) — mitigated by client-side cooldown
- Path 2 sends 3-5×damage per hit (one per frame of overlap) — **no protection at all**
- Both paths stack on top of each other

This is why vehicles feel like paper in multiplayer — a single zombie hit that should deal 20-30 damage to the vehicle ends up dealing 100-200+.

## How we found this

Earlier versions of this mod tried to fix the problem purely through Lua — blocking duplicate `damageFromHitChr` commands, adding driver authorization, throttling, and scaling. None of it worked. Even when the Lua command was sending **zero damage**, vehicles were still being destroyed at the same rate. This proved that the real damage was coming from somewhere Lua couldn't touch.

Tracing the Java source revealed the second path: `VehicleHitField` packets sent directly from the driver's physics loop, processed entirely in Java, with no cooldown or deduplication — unlike the Lua path which has `VehiclePedestrianContactTracking`. The Lua path already had a throttle; the Java path simply didn't. The fix was to give it one.

## The Fix

This mod patches the vanilla `VehicleHitField.class` with a server-side classpath override. The patched class adds a deduplication cooldown to `VehicleHitField.process()` — the same kind of throttle that the Lua damage path already has, but was missing from the Java packet path.

What the patch does:

- When the server receives a `VehicleHitField` packet, it checks whether vehicle damage was already applied for the same **(vehicle, target)** pair within a 3.5-second window (matching the vanilla `VehiclePedestrianContactTracking` cooldown). If so, the vehicle damage is suppressed. If not, it applies normally and records the timestamp.
- Only **vehicle HP damage** is throttled. Character effects (zombie death, knockdown, stagger) are **not affected** — they pass through on every packet so zombies still die and ragdoll correctly.
- The dedup uses a static `HashMap<Long, Long>` keyed by a combination of the vehicle's network ID and the target's identity hash. Stale entries are cleaned up periodically (every 10 seconds).
- The cooldown is configurable at runtime via JVM property: `-Dpz.vehicle.hit.dedup.cooldown=3500` (milliseconds, default 3500). Set to 0 to disable dedup entirely.

The result: a vehicle takes **exactly 1x** damage per collision, matching singleplayer behavior. No Lua files are modified.

## Installation

This mod is a **Java classpath override** — it requires placing a patched `.class` file on your server. No Lua mod subscription is needed (though the Workshop page hosts the files and documentation).

The patched `VehicleHitField.class` must be placed in the correct folder relative to your `projectzomboid.jar`. Project Zomboid's classpath loads loose .class files **before** the JAR, so placing the patched file in the right directory overrides the vanilla class without modifying the JAR.

### Option A — Use the included build scripts

Inside this mod's folders, there's a `tools/` folder that contains two self-contained scripts that compile the patched source and deploy it automatically:

- **Windows:** `patchVehicleHitField.ps1` — Run in PowerShell. Will auto-download JDK 25 if needed. Default PZ path: `Z:\SteamLibrary\steamapps\common\ProjectZomboid`. Use `-PZDir "your\path"` to override.
- **Linux:** `patchVehicleHitField.sh` — Run with bash. Requires JDK 25 installed (`apt install openjdk-25-jdk-headless`). Default PZ path: `/opt/pzserver`. Edit `PZ_DIR` at the top to change.
- To revert: run the same script with `-Revert` (Windows) or `--revert` (Linux).

> **YOU MUST CONFIGURE YOUR ProjectZomboid folder path correctly for Windows or for Linux.**

### Option B — Manual deployment

If you don't have knowledge about scripts, you can get the `VehicleHitField.class` from `tools/out/classes/zombie/network/fields/hit/VehicleHitField.class` and place it manually:

- **Windows dedicated server:** Copy `VehicleHitField.class` to `<ProjectZomboid>/zombie/network/fields/hit/` (create the folders if they don't exist). This is the same directory that contains `projectzomboid.jar`, with the package path appended.
- **Linux dedicated server:** Copy to `<PZServer>/java/zombie/network/fields/hit/` (Linux servers use the `java/` subdirectory as classpath root).
- To revert: simply delete the `VehicleHitField.class` file and restart the server. The original class from the JAR will be used.

Restart the server after deploying.

## Technical Details

- **Server-side only** — no client-side files are modified, no Lua is changed
- **Zero performance overhead** — the dedup is a single HashMap lookup per incoming VehicleHitField packet
- **Safe** — all other network packets and game logic are left identical to vanilla
- **Original JAR untouched** — the classpath override is a standard JVM mechanism; deleting the .class file fully reverts to vanilla
- **No mod conflicts** — since no Lua files are overridden, this is compatible with any other mod

## Compatibility

- Build 42.16.x, 42.17.x
- Multiplayer only (does nothing in singleplayer)
- Safe to add or remove mid-save

With love from Brazil <3
