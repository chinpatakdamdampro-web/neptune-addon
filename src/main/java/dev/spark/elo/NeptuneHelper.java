package dev.spark.elo;

import dev.lrxh.api.NeptuneAPIProvider;
import dev.lrxh.api.data.IGameData;
import dev.lrxh.api.data.IGlobalStats;
import dev.lrxh.api.data.IKitData;
import dev.lrxh.api.profile.IProfile;
import dev.lrxh.api.profile.IProfileService;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * All Neptune-touching code lives here.
 *
 * We depend only on Neptune's API jar at compile time (IProfile, IKitData, etc.).
 * The three things that are NOT in the API jar — KitData#updateDivision(),
 * GlobalStats#update(), and the static Profile#save(Profile) — are called via
 * reflection so we don't need the Plugin jar as a compile-time dependency.
 */
public class NeptuneHelper {

    private final Logger log;

    public NeptuneHelper(Logger log) {
        this.log = log;
    }

    // -------------------------------------------------------------------------
    // Profile loading
    // -------------------------------------------------------------------------

    /**
     * Returns the cached in-memory profile for an online player, or null if
     * Neptune has not loaded one.
     */
    public IProfile getCached(UUID uuid) {
        IProfileService svc = NeptuneAPIProvider.getAPI().getProfileService();
        return svc.getCachedProfile(uuid);
    }

    /**
     * Loads the profile from the database (or returns the cached one if already
     * loaded). Safe to call for offline players.
     */
    public CompletableFuture<IProfile> loadProfile(UUID uuid) {
        IProfileService svc = NeptuneAPIProvider.getAPI().getProfileService();
        return svc.getProfile(uuid);
    }

    // -------------------------------------------------------------------------
    // ELO mutation
    // -------------------------------------------------------------------------

    /**
     * Sets ELO to {@code amount} on every kit the profile has, then updates
     * per-kit divisions, recalculates GlobalStats, and triggers an async save.
     *
     * @return the name of the resulting (global) division, or "Unknown" if it
     *         cannot be determined.
     */
    public String applyElo(IProfile profile, int amount) {
        IGameData gameData = profile.getGameData();

        // 1. Set ELO on every kit and refresh its division.
        for (Map.Entry<?, IKitData> entry : gameData.getKitData().entrySet()) {
            IKitData kd = entry.getValue();
            kd.setElo(amount);          // IKitData.setElo — in API interface ✓
            reflectVoid(kd, "updateDivision"); // KitData#updateDivision — not in API
        }

        // 2. Recalculate GlobalStats (averages all kit ELOs → sets global division).
        IGlobalStats gs = gameData.getGlobalStats();
        reflectVoid(gs, "update");       // GlobalStats#update — not in API

        // 3. Persist asynchronously using Profile.save(Profile) (not in API).
        reflectSave(profile);

        // 4. Read back the global division name for the confirmation message.
        try {
            Object division = gs.getClass().getMethod("getDivision").invoke(gs);
            if (division != null) {
                return division.getClass().getMethod("getDisplayName").invoke(division).toString();
            }
        } catch (Exception ignored) { }
        return "Unknown";
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    /**
     * Calls a public no-arg void (or boolean) method on {@code target} by name.
     */
    private void reflectVoid(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            m.invoke(target);
        } catch (NoSuchMethodException e) {
            log.warning("[SparkElo] Method '" + methodName + "' not found on "
                    + target.getClass().getName() + " — Neptune version mismatch?");
        } catch (Exception e) {
            log.warning("[SparkElo] Reflection error calling '" + methodName + "': " + e.getMessage());
        }
    }

    /**
     * Calls the static Profile#save(Profile) method on the concrete profile object.
     * Signature: public static CompletableFuture<Void> save(Profile profile)
     */
    private void reflectSave(IProfile profile) {
        try {
            // The underlying object IS dev.lrxh.neptune.profile.impl.Profile at runtime.
            Class<?> profileClass = profile.getClass();
            Method save = profileClass.getMethod("save", profileClass);
            // save is static, so we invoke on null.
            save.invoke(null, profile);
        } catch (NoSuchMethodException e) {
            log.warning("[SparkElo] Profile.save() not found — Neptune version mismatch?");
        } catch (Exception e) {
            log.warning("[SparkElo] Could not save profile: " + e.getMessage());
        }
    }
}
