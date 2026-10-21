package app.morphe.extension.maps.patches;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import java.util.Locale;
import org.json.JSONObject;

/** One vehicle-model state: atomic settings, bounded SDK learning and immutable published physics.
 * No SOC/Wh estimation, route request, Google forecast or UI percentage calculation lives here.
 */
final class BydVehicleModel {
    private static final String TAG = "MorpheBydModel";
    private static final int SCHEMA = 1;
    private static final double INITIAL_CONSUMPTION = 13.0;
    // Same-car/model empirical anchor: Google5758Wh /68.737km before the measured8.8kWh trip.
    // Scaling its road-load curve is an experimental normalization, NOT an exact server inverse.
    private static final double REFERENCE_CONSUMPTION = 5758.0 * 100.0 / 68737.0;
    private static final float AUTO_A = 160f, AUTO_B = 0f, AUTO_C = 0.35f;
    private static final double MIN_CONSUMPTION = 6.0, MAX_CONSUMPTION = 40.0;
    private static final long MAX_GAP_MS = 30000, MIN_WINDOW_MS = 300000, MAX_WINDOW_MS = 7200000;
    private static final long MIN_DISTANCE_M = 10000, MIN_ENERGY_WH = 1000;
    // At 50% SOC and above, integer SOC/100 Wh rounding keeps one Wh/SOC capacity within about 1%.
    private static final double MIN_CAPACITY_SOC = 50, CAPACITY_ALPHA = 0.1;
    private static SharedPreferences preferences;
    private static String identity;
    private static Settings defaults, configured;
    private static Model active, pending;
    private static long revision, acceptedWindows;
    private static double learnedConsumption = INITIAL_CONSUMPTION;
    private static double latestTotal = Double.NaN, latestRecent = Double.NaN;
    private static String learning = "Waiting for trip data";
    private static Point anchor, previous;
    private static double speedMetres, speedCoveredMs;
    // One car-average seed per process: a rounding boundary cannot republish the model every poll.
    private static boolean seedChecked;
    // Measured pack capacity used below 10% SOC instead of the entered value (Manual keeps its own).
    private static double measuredCapacity = Double.NaN, lastCapacitySoc = Double.NaN;
    private static long capacitySamples;
    private static int lastCapacityWh = -1;

    private BydVehicleModel() { }

    /** Automatic learns the consumption target, Fixed uses the entered one; both scale the same curve. */
    static final int AUTO = 0, FIXED = 1, MANUAL = 2;

    static final class Settings {
        final int mode;
        final float nominalWh, a, b, c;
        final double fixedConsumption;
        final long revision;
        Settings(int mode, float nominalWh, double fixedConsumption, float a, float b, float c, long revision) {
            this.mode = mode; this.nominalWh = nominalWh; this.fixedConsumption = fixedConsumption;
            this.a = a; this.b = b; this.c = c; this.revision = revision;
        }
    }

    static final class Model {
        final long revision;
        final int mode;
        final float nominalWh, a, b, c;
        final double consumption, scale;
        final String source;
        Model(long revision, Settings settings, double learned, String source) {
            this.revision = revision; mode = settings.mode; nominalWh = settings.nominalWh;
            consumption = mode == FIXED ? settings.fixedConsumption : learned; this.source = source;
            boolean scaled = mode != MANUAL;
            scale = scaled ? consumption / REFERENCE_CONSUMPTION : 1.0;
            a = scaled ? (float) (AUTO_A * scale) : settings.a;
            b = scaled ? (float) (AUTO_B * scale) : settings.b;
            c = scaled ? (float) (AUTO_C * scale) : settings.c;
        }
        String describe() {
            return "model_revision=" + revision + ";model_mode=" + modeName(mode)
                    + ";target_kwh_100km=" + consumption + ";scale=" + scale
                    + ";road_a=" + a + ";road_b=" + b + ";road_c=" + c
                    + ";model_source=" + source + ";normalization=empirical_not_exact_server_inverse";
        }
    }

    static synchronized void initialize(Context context, float nominalWh, float a, float b, float c) {
        if (active != null) return;
        validate(nominalWh, a, b, c);
        defaults = new Settings(AUTO, nominalWh, INITIAL_CONSUMPTION, a, b, c, 1);
        configured = defaults; revision = 1;
        identity = Build.MANUFACTURER + "/" + Build.MODEL + "/" + Build.DEVICE + "/" + Build.FINGERPRINT;
        String source = "measured_trip_13kwh_100km";
        try {
            preferences = context.getSharedPreferences("morphe_byd_vehicle_model", Context.MODE_PRIVATE);
            String saved = preferences.getString("state", null);
            if (saved != null) {
                JSONObject state = new JSONObject(saved);
                if (state.getInt("schema") == SCHEMA && identity.equals(state.getString("runtime"))) {
                    float n = (float) state.getDouble("nominal_wh"), x = (float) state.getDouble("a");
                    float y = (float) state.getDouble("b"), z = (float) state.getDouble("c");
                    validate(n, x, y, z);
                    double q = state.getDouble("learned_consumption");
                    long r = state.getLong("revision"), windows = state.getLong("accepted_windows");
                    if (!consumptionValid(q) || r < 1 || r > 1000000000L || windows < 0 || windows > 1000000)
                        throw new IllegalArgumentException("stored learning state");
                    // Older states have only "automatic"; a missing fixed value starts from the learned one.
                    int mode = state.has("mode") ? modeFrom(state.getString("mode"))
                            : state.getBoolean("automatic") ? AUTO : MANUAL;
                    double fixed = tenth(state.optDouble("fixed_consumption", q));
                    if (!consumptionValid(fixed)) throw new IllegalArgumentException("stored fixed consumption");
                    revision = r; acceptedWindows = windows; learnedConsumption = q;
                    // Older states have no measurement; an implausible one is dropped, not the whole state.
                    double measured = state.optDouble("measured_capacity_wh", Double.NaN);
                    if (capacityValid(measured)) {
                        measuredCapacity = measured; capacitySamples = Math.max(1, state.optLong("capacity_samples", 1));
                    }
                    configured = new Settings(mode, n, fixed, x, y, z, revision);
                    source = windows > 0 ? "persisted_sdk_learning" : "persisted_settings";
                }
            }
        } catch (Throwable error) {
            configured = defaults; learnedConsumption = INITIAL_CONSUMPTION; acceptedWindows = 0; revision = 1;
            Log.w(TAG, "state unavailable; measured-trip baseline retained", error);
        }
        active = new Model(++revision, configured, learnedConsumption, source);
        Log.i(TAG, "activated;" + active.describe());
    }

    static synchronized Settings settings() {
        if (configured == null) throw new IllegalStateException("vehicle model not initialized");
        return configured;
    }

    static synchronized double learnedConsumption() { return learnedConsumption; }

    static void saveSettings(int mode, float nominalWh, double fixedConsumption, float a, float b, float c) {
        validate(nominalWh, a, b, c);
        double fixed = tenth(fixedConsumption);
        if (mode != AUTO && mode != FIXED && mode != MANUAL) throw new IllegalArgumentException("mode");
        if (!consumptionValid(fixed)) throw new IllegalArgumentException("consumption must be 6–40 kWh/100 km");
        synchronized (BydVehicleModel.class) {
            settings();
            configured = new Settings(mode, nominalWh, fixed, a, b, c, ++revision);
            pending = new Model(++revision, configured, learnedConsumption, "saved_settings");
            resetWindow(); persist();
        }
        BydNativeBatteryPatch.onVehicleSettingsChanged();
    }

    static void resetSettings() {
        synchronized (BydVehicleModel.class) {
            settings();
            double start = carAverage();
            learnedConsumption = finite(start) ? start : INITIAL_CONSUMPTION; acceptedWindows = 0;
            configured = new Settings(AUTO, defaults.nominalWh, learnedConsumption,
                    defaults.a, defaults.b, defaults.c, ++revision);
            pending = new Model(++revision, configured, learnedConsumption,
                    finite(start) ? "reset_car_average" : "reset_measured_trip_baseline");
            learning = "Waiting for trip data"; resetWindow(); persist();
        }
        BydNativeBatteryPatch.onVehicleSettingsChanged();
    }

    static synchronized Model current() { return active; }

    /** Caller chooses a route/settings transition. SDK polling never activates a learned revision. */
    static synchronized Model activatePending() {
        if (pending != null) {
            active = pending; pending = null;
            Log.i(TAG, "activated;" + active.describe());
        }
        return active;
    }

    static synchronized String status() {
        if (active == null) return "Vehicle data not ready";
        // Learning runs only in Automatic mode, so Fixed and Manual have no learning line.
        String origin = acceptedWindows > 0 ? " · learned from " + acceptedWindows + " trip segments"
                : learnedConsumption == carAverage() ? " · from car average" : "";
        String text = configured.mode == AUTO
                ? String.format(Locale.ROOT, "Automatic · %.1f kWh/100 km", learnedConsumption) + origin + "\n" + learning
                : configured.mode == FIXED
                ? String.format(Locale.ROOT, "Fixed · %.1f kWh/100 km", configured.fixedConsumption)
                : "Manual A/B/C";
        text += configured.mode == MANUAL
                ? String.format(Locale.ROOT, "\nBattery below 10%%: %.1f kWh entered", configured.nominalWh / 1000.0)
                : finite(measuredCapacity)
                ? String.format(Locale.ROOT, "\nBattery: %.1f kWh measured", measuredCapacity / 1000.0)
                : "\nBattery: measuring (needs 50% SOC or more)";
        if (pending != null) text += "\nSaved changes apply when navigation ends";
        if (finite(latestTotal) && finite(latestRecent))
            text += String.format(Locale.ROOT, "\nCar average: %.1f kWh/100 km total, %.1f recent", latestTotal, latestRecent);
        else if (finite(latestTotal)) text += String.format(Locale.ROOT, "\nCar average: %.1f kWh/100 km total", latestTotal);
        else if (finite(latestRecent)) text += String.format(Locale.ROOT, "\nCar average: %.1f kWh/100 km recent", latestRecent);
        return text;
    }

    /** Required BMS/odometer observations learn only a slow average-use adjustment, not A/C separately. */
    static synchronized void observe(BydEnergyReader.Sample sample, int energyWh) {
        latestTotal = sample.totalConsumptionKwhPer100Km;
        latestRecent = sample.recentConsumptionKwhPer100Km;
        observeCapacity(sample.soc, energyWh);
        if (configured == null || configured.mode != AUTO) { resetWindow(); return; }
        seedFromCarAverage();
        // Preserve battery reads when optional Instrument access fails, but never learn using unknown distance units.
        if (sample.mileageUnit != 1) {
            learning = "Waiting for the car's distance unit"; resetWindow(); return;
        }
        Point point = new Point(sample, energyWh);
        if (previous == null) { startWindow(point); return; }
        long dt = point.at - previous.at, stepM = (long) (point.mileage - previous.mileage) * 100;
        String invalid = null;
        if (dt <= 0 || dt > MAX_GAP_MS) invalid = "sdk_gap_or_order";
        else if (point.wh > previous.wh || point.soc > previous.soc)
            invalid = "energy_soc_increase_charge_regen_or_estimation";
        else if (stepM < 0 || stepM > 282.0 / 3.6 * dt / 1000.0 + 200) invalid = "odometer_discontinuity";
        if (invalid != null) {
            Log.i(TAG, "learning_window_reset;reason=" + invalid);
            learning = "Waiting for continuous data"; startWindow(point); return;
        }
        if (finite(previous.speed) && finite(point.speed)) {
            speedMetres += (previous.speed + point.speed) / 2.0 / 3.6 * dt / 1000.0;
            speedCoveredMs += dt;
        }
        previous = point;
        long duration = point.at - anchor.at, metres = (long) (point.mileage - anchor.mileage) * 100;
        long wh = (long) anchor.wh - point.wh;
        if (duration > MAX_WINDOW_MS || metres > 100000) {
            learning = "Waiting for a new trip segment"; startWindow(point); return;
        }
        if (duration < MIN_WINDOW_MS || metres < MIN_DISTANCE_M || wh < MIN_ENERGY_WH) return;
        double measured = wh * 100.0 / metres;
        boolean speedCovered = speedCoveredMs >= duration * 0.8;
        boolean speedConsistent = !speedCovered || Math.abs(speedMetres - metres) <= Math.max(300, metres * 0.20);
        boolean sdkCorroborates = !finite(latestTotal) || (measured >= latestTotal * 0.5 && measured <= latestTotal * 2.0);
        if (!consumptionValid(measured) || !speedConsistent || !sdkCorroborates) {
            Log.i(TAG, "learning_window_rejected;metres=" + metres + ";wh=" + wh
                    + ";measured_kwh_100km=" + measured + ";speed_check=" + speedConsistent
                    + ";sdk_corroborates=" + sdkCorroborates);
            learning = "Kept current value, waiting for more data"; startWindow(point); return;
        }
        double before = learnedConsumption;
        // This is consumption-adaptive normalization, not an independent speed/efficiency fit.
        double next = before + 0.20 * (measured - before);
        learnedConsumption = Math.max(MIN_CONSUMPTION, Math.min(MAX_CONSUMPTION,
                Math.max(before * 0.95, Math.min(before * 1.05, next))));
        acceptedWindows++;
        if (Math.abs(learnedConsumption - active.consumption) >= 0.10 || pending != null)
            pending = new Model(++revision, configured, learnedConsumption, "sdk_bms_odometer_mean");
        learning = "Updated from measured energy and distance";
        persist();
        Log.i(TAG, "learning_window_accepted;windows=" + acceptedWindows + ";metres=" + metres
                + ";wh=" + wh + ";measured_kwh_100km=" + measured + ";before=" + before
                + ";pending_target=" + learnedConsumption + ";speed_coverage=" + (speedCoveredMs / duration)
                + ";speed_metres=" + speedMetres + ";speed_shape_calibrated=false;"
                + (pending == null ? "effective_unchanged" : pending.describe()));
        startWindow(point);
    }

    /** Smooths one capacity per new high-SOC BMS observation; repeated identical polls are ignored. */
    private static void observeCapacity(double soc, int energyWh) {
        if (!(soc >= MIN_CAPACITY_SOC && soc <= 100) || energyWh <= 0) return;
        if (soc == lastCapacitySoc && energyWh == lastCapacityWh) return;
        lastCapacitySoc = soc; lastCapacityWh = energyWh;
        double capacity = energyWh * 100.0 / soc;
        if (!capacityValid(capacity)) return;
        measuredCapacity = finite(measuredCapacity) ? measuredCapacity + CAPACITY_ALPHA * (capacity - measuredCapacity) : capacity;
        capacitySamples++;
        persist();
    }

    /** Capacity for SOC below 10%: Manual uses its entered value; otherwise the measurement, else the default. */
    static synchronized double lowSocCapacity(Model model) {
        return model.mode != MANUAL && finite(measuredCapacity) ? measuredCapacity : model.nominalWh;
    }
    static synchronized String lowSocCapacityBasis(Model model) {
        return model.mode == MANUAL ? "manual_capacity" : finite(measuredCapacity) ? "measured_capacity" : "nominal_fallback";
    }

    /**
     * Until one trip segment is learned, Automatic starts from the car's own total average instead of
     * the fixed measured-trip value. The revision activates only outside guidance, like saved settings.
     */
    private static void seedFromCarAverage() {
        double start = carAverage();
        if (seedChecked || acceptedWindows > 0 || !finite(start)) return;
        seedChecked = true;
        if (Math.abs(start - learnedConsumption) < 0.05) return;
        double before = learnedConsumption;
        learnedConsumption = start;
        pending = new Model(++revision, configured, learnedConsumption, "car_average_seed");
        learning = "Started from the car's average; learning from trips";
        persist();
        Log.i(TAG, "car_average_seed;before=" + before + ";" + pending.describe());
        BydNativeBatteryPatch.onVehicleSettingsChanged();
    }

    /** The car's reported total average, to one decimal, or NaN when unavailable or outside 6–40. */
    private static double carAverage() {
        double value = tenth(latestTotal);
        return consumptionValid(value) ? value : Double.NaN;
    }

    static synchronized void unavailable(String reason) {
        if (anchor != null) Log.i(TAG, "learning_window_reset;reason=" + reason);
        resetWindow();
    }

    private static void startWindow(Point point) {
        anchor = previous = point; speedMetres = speedCoveredMs = 0;
    }
    private static void resetWindow() {
        anchor = previous = null; speedMetres = speedCoveredMs = 0;
    }
    private static boolean finite(double number) { return !Double.isNaN(number) && !Double.isInfinite(number); }
    private static boolean capacityValid(double wh) { return finite(wh) && wh >= 5000 && wh <= 150000; }
    private static boolean consumptionValid(double q) { return finite(q) && q >= MIN_CONSUMPTION && q <= MAX_CONSUMPTION; }
    /** Fixed consumption keeps one decimal place, matching the settings input. */
    private static double tenth(double value) { return finite(value) ? Math.round(value * 10.0) / 10.0 : value; }
    private static String modeName(int mode) { return mode == FIXED ? "fixed" : mode == MANUAL ? "manual" : "auto"; }
    private static int modeFrom(String name) {
        switch (name) {
            case "auto": return AUTO;
            case "fixed": return FIXED;
            case "manual": return MANUAL;
            default: throw new IllegalArgumentException("stored mode");
        }
    }

    private static void validate(float nominalWh, float a, float b, float c) {
        if (!finite(nominalWh) || nominalWh < 5000 || nominalWh > 150000)
            throw new IllegalArgumentException("capacity must be 5000–150000 Wh");
        if (!finite(a) || a < 0 || a > 1000 || !finite(b) || b < 0 || b > 20 || !finite(c) || c <= 0 || c > 2)
            throw new IllegalArgumentException("A must be 0–1000, B 0–20, C above 0 up to 2");
    }

    private static void persist() {
        if (preferences == null) return;
        try {
            JSONObject state = new JSONObject();
            state.put("schema", SCHEMA); state.put("runtime", identity); state.put("revision", revision);
            // "automatic" stays for older builds, which read Fixed as Automatic.
            state.put("mode", modeName(configured.mode)); state.put("automatic", configured.mode != MANUAL);
            state.put("fixed_consumption", configured.fixedConsumption); state.put("nominal_wh", configured.nominalWh);
            state.put("a", configured.a); state.put("b", configured.b); state.put("c", configured.c);
            state.put("learned_consumption", learnedConsumption); state.put("accepted_windows", acceptedWindows);
            // JSON forbids NaN: an unmeasured capacity is simply absent.
            if (finite(measuredCapacity)) { state.put("measured_capacity_wh", measuredCapacity); state.put("capacity_samples", capacitySamples); }
            // One editor/string publishes the whole revision; no per-field preference races.
            preferences.edit().putString("state", state.toString()).apply();
        } catch (Throwable error) { Log.w(TAG, "model state was not saved", error); }
    }

    private static final class Point {
        final long at;
        final int wh, mileage;
        final double soc, speed;
        Point(BydEnergyReader.Sample sample, int wh) {
            at = sample.elapsed; this.wh = wh; mileage = sample.mileage;
            soc = sample.soc; speed = sample.speedKmh;
        }
    }
}
