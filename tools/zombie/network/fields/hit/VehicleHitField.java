// Patched VehicleHitField.java - Server-side vehicle damage deduplication
// Prevents per-frame packet spam from applying vehicle damage multiple times
// for a single collision event. Character damage (zombie death/knockdown) is
// NOT throttled - only the vehicle HP damage is deduplicated.
//
// Cooldown is configurable via JVM property:
//   -Dpz.vehicle.hit.dedup.cooldown=3500  (ms, default 3500)
//
// Original: zombie.network.fields.hit.VehicleHitField (Build 42)
package zombie.network.fields.hit;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.IMovable;
import zombie.network.fields.INetworkPacketField;
import zombie.vehicles.BaseVehicle;

public class VehicleHitField extends Hit implements IMovable, INetworkPacketField {
    @JSONField
    public int vehicleDamage;
    @JSONField
    public float vehicleSpeed;
    @JSONField
    public boolean isVehicleHitFromBehind;
    @JSONField
    public boolean isTargetHitFromBehind;
    @JSONField
    public boolean isStaggerBack;
    @JSONField
    public boolean isKnockedDown;

    // --- Dedup state (server-side only) ---
    private static final long DEDUP_COOLDOWN_MS =
        Long.getLong("pz.vehicle.hit.dedup.cooldown", 3500L);
    private static final HashMap<Long, Long> recentVehicleDamage = new HashMap<>();
    private static long lastCleanupTime = 0L;
    private static final long CLEANUP_INTERVAL_MS = 10_000L;

    /**
     * Build a dedup key combining vehicle ID and target identity.
     * Upper 32 bits: vehicle network ID (truncated to int).
     * Lower 32 bits: System.identityHashCode of the target character.
     */
    private static long makeDedupKey(BaseVehicle vehicle, IsoGameCharacter target) {
        return ((long)(vehicle.getId() & 0xFFFF) << 32)
             | (System.identityHashCode(target) & 0xFFFFFFFFL);
    }

    /**
     * Returns true if vehicle damage for this (vehicle, target) pair should be
     * suppressed because a hit was already applied within the cooldown window.
     * If not suppressed, records the current timestamp for future checks.
     */
    private static boolean isVehicleDamageThrottled(BaseVehicle vehicle, IsoGameCharacter target) {
        if (DEDUP_COOLDOWN_MS <= 0L) {
            return false;   // dedup disabled
        }
        long now = System.currentTimeMillis();
        long key = makeDedupKey(vehicle, target);
        Long lastHit = recentVehicleDamage.get(key);
        if (lastHit != null && (now - lastHit) < DEDUP_COOLDOWN_MS) {
            return true;    // within cooldown - suppress
        }
        recentVehicleDamage.put(key, now);

        // Periodic cleanup of stale entries
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            lastCleanupTime = now;
            Iterator<Map.Entry<Long, Long>> it = recentVehicleDamage.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > DEDUP_COOLDOWN_MS * 2) {
                    it.remove();
                }
            }
        }
        return false;
    }
    // --- End dedup state ---

    public void set(
        boolean ignore,
        float damage,
        float hitForce,
        float hitDirectionX,
        float hitDirectionY,
        int vehicleDamage,
        float vehicleSpeed,
        boolean isVehicleHitFromBehind,
        boolean isTargetHitFromBehind,
        boolean isStaggerBack,
        boolean isKnockedDown
    ) {
        this.set(damage, hitForce, hitDirectionX, hitDirectionY);
        this.vehicleDamage = vehicleDamage;
        this.vehicleSpeed = vehicleSpeed;
        this.isVehicleHitFromBehind = isVehicleHitFromBehind;
        this.isTargetHitFromBehind = isTargetHitFromBehind;
        this.isStaggerBack = isStaggerBack;
        this.isKnockedDown = isKnockedDown;
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.vehicleDamage = b.getInt();
        this.vehicleSpeed = b.getFloat();
        this.isVehicleHitFromBehind = b.getBoolean();
        this.isTargetHitFromBehind = b.getBoolean();
        this.isStaggerBack = b.getBoolean();
        this.isKnockedDown = b.getBoolean();
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        b.putInt(this.vehicleDamage);
        b.putFloat(this.vehicleSpeed);
        b.putBoolean(this.isVehicleHitFromBehind);
        b.putBoolean(this.isTargetHitFromBehind);
        b.putBoolean(this.isStaggerBack);
        b.putBoolean(this.isKnockedDown);
    }

    public void process(IsoGameCharacter wielder, IsoGameCharacter target, BaseVehicle vehicle) {
        this.process(wielder, target);
        if (GameServer.server) {
            // --- PATCHED: vehicle damage dedup ---
            if (this.vehicleDamage != 0 && !isVehicleDamageThrottled(vehicle, target)) {
                if (this.isVehicleHitFromBehind) {
                    vehicle.addDamageFrontHitAChr(this.vehicleDamage);
                } else {
                    vehicle.addDamageRearHitAChr(this.vehicleDamage);
                }

                vehicle.transmitBlood();
            }
            // --- END PATCHED ---

            // Character damage is NOT throttled - zombie death/knockdown must apply
            if (target instanceof IsoAnimal isoAnimal) {
                isoAnimal.setHealth(0.0F);
            } else if (target instanceof IsoZombie isoZombie) {
                isoZombie.applyDamageFromVehicleHit(this.vehicleSpeed, this.damage);
                isoZombie.setKnockedDown(this.isKnockedDown);
                isoZombie.setStaggerBack(this.isStaggerBack);
            } else if (target instanceof IsoPlayer isoPlayer) {
                isoPlayer.applyDamageFromVehicleHit(this.vehicleSpeed, this.damage);
                isoPlayer.setKnockedDown(this.isKnockedDown);
            }
        } else if (GameClient.client && target instanceof IsoPlayer) {
            target.getActionContext().reportEvent("washit");
            target.setVariable("hitpvp", false);
        }
    }

    @Override
    public float getSpeed() {
        return this.vehicleSpeed;
    }

    @Override
    public boolean isVehicle() {
        return true;
    }
}
