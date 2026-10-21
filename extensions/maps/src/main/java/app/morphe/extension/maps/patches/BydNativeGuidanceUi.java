package app.morphe.extension.maps.patches;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Original Google arrival DTO into the proven mobile duration binding before setText. */
public final class BydNativeGuidanceUi {
    private static final long FRESH_MS = 30000;
    // The guided UI callback runs about once per second with the same immutable route.
    private static final long RECOMPUTE_MS = 10000, LOG_HEARTBEAT_MS = 30000;
    // After Cancel Maps still animates the summary away; restoring at once flashed the plain ETA.
    private static final long EXIT_GRACE_MS = 1500;
    private static final Pattern LOG_VOLATILE = Pattern.compile("(?:age_ms)=[^;]*;?");
    private static final Object LOCK = new Object();
    private static volatile boolean configured;
    private static Class<?> summaryApi, mobileOwner, stateType, navigationType, selectorType,
            positionType, holderType, wrapperType, snapshotType, calculatorType, arrivalType, providerType;
    private static Field navigation, selector, routes, position, selectedIndex, wrapper, canonical,
            legBattery, mask, currentWh, capacityWh, dtoState, dtoAccessibility, dtoLevel, dtoDisplay;
    private static Method legCount, legAt, calculate, dtoText, dtoUnsupported, providerGet;
    private static Object missingState;
    private static volatile WeakReference<Object> calculator = new WeakReference<>(null);
    // Maps' scoped DI provider for the converter, stored at component setup, possibly before configure().
    private static volatile WeakReference<Object> provider = new WeakReference<>(null);
    // A provider that failed once is not asked again on every guided frame.
    private static volatile WeakReference<Object> failedProvider = new WeakReference<>(null);
    private static volatile Snapshot latest;
    // The snapshot whose clear already waits out the exit animation; guarded by LOCK.
    private static Snapshot exiting;
    // Last original-converter result for unchanged inputs; cleared with the snapshot.
    private static volatile Memo memo;
    private static long generation;
    private static Handler mainHandler;
    private static Runnable expiry;
    private static final Map<String, String> logSignatures = new HashMap<>();
    private static final Map<String, Long> logTimes = new HashMap<>();
    private static final Map<String, Integer> logSuppressed = new HashMap<>();

    private BydNativeGuidanceUi() { }

    /** Diagnostic route | mobile/calculator/provider | original DTO | ETA width | enabled. */
    public static void configure(String encoded) {
        clear("configuration"); configured = false;
        calculator = new WeakReference<>(null);
        try {
            String[] sections = encoded.split("\\|", -1);
            if (sections.length != 5 || !(sections[4].equals("true") || sections[4].equals("false")))
                throw new IllegalArgumentException("UI sections");
            if (sections[4].equals("false")) return;
            String[] routeGroups = sections[0].split(";", -1), uiGroups = sections[1].split(";", -1),
                    dtoGroups = sections[2].split(";", -1);
            if (routeGroups.length != 7 || uiGroups.length != 3 || dtoGroups.length != 3)
                throw new IllegalArgumentException("UI groups");
            String[] r = members(routeGroups[0], 12), w = members(routeGroups[6], 3),
                    u = members(uiGroups[0], 13), p = members(uiGroups[1], 3), q = members(uiGroups[2], 2),
                    d = members(dtoGroups[0], 12), z = members(dtoGroups[2], 3),
                    e = members(sections[3], 3);
            members(dtoGroups[1], 11);
            summaryApi = type(u[0]); mobileOwner = type(u[1]); stateType = type(u[2]);
            navigationType = type(u[4]); selectorType = type(u[6]); positionType = type(u[9]); holderType = type(u[11]);
            wrapperType = type(w[0]); snapshotType = type(w[2]); calculatorType = type(p[0]); arrivalType = type(p[2]);
            navigation = publicField(stateType, u[3]); selector = publicField(navigationType, u[5]);
            routes = publicField(selectorType, u[7]); position = publicField(selectorType, u[8]);
            selectedIndex = publicField(positionType, u[10]); wrapper = publicField(holderType, u[12]);
            canonical = publicField(wrapperType, w[1]);
            legCount = snapshotType.getMethod(r[0]); legAt = snapshotType.getMethod(r[1], int.class);
            legBattery = publicField(legAt.getReturnType(), r[2]);
            Class<?> battery = legBattery.getType();
            mask = publicField(battery, r[3]); currentWh = publicField(battery, r[4]); capacityWh = publicField(battery, r[5]);
            calculate = calculatorType.getMethod(p[1], snapshotType, int.class);
            providerType = type(q[0]); providerGet = providerType.getMethod(q[1]);
            if (arrivalType != type(d[2])) throw new IllegalArgumentException("arrival DTO mismatch");
            dtoText = arrivalType.getMethod(d[3]); dtoUnsupported = arrivalType.getMethod(d[4]);
            dtoState = publicField(arrivalType, d[6]); dtoAccessibility = publicField(arrivalType, d[7]);
            dtoLevel = publicField(arrivalType, d[10]); dtoDisplay = publicField(dtoLevel.getType(), d[11]);
            missingState = dtoState.get(arrivalType.getField(z[2]).get(null));
            if (!(missingState instanceof Enum) || !((Enum<?>) missingState).name().equals("NO_BATTERY_INFORMATION"))
                throw new IllegalArgumentException("missing battery state");
            if (!summaryApi.isInterface() || !summaryApi.isAssignableFrom(mobileOwner)
                    || canonical.getType() != snapshotType || !Modifier.isFinal(snapshotType.getModifiers())
                    || legCount.getReturnType() != int.class || selectedIndex.getType() != int.class
                    || mask.getType() != int.class || currentWh.getType() != int.class || capacityWh.getType() != int.class
                    || calculate.getReturnType() != arrivalType || Modifier.isStatic(calculate.getModifiers())
                    || dtoText.getReturnType() != String.class || dtoUnsupported.getReturnType() != boolean.class
                    || dtoAccessibility.getType() != String.class || !dtoState.getType().isEnum()
                    || dtoDisplay.getType() != int.class || dtoLevel.getType().isPrimitive()
                    || !providerType.isInterface() || providerGet.getReturnType() != Object.class
                    || providerGet.getParameterTypes().length != 0)
                throw new IllegalArgumentException("UI types");
            BydGuidanceEtaText.configure(type(e[0]), e[1], e[2]);
            configured = true;
        } catch (Throwable error) {
            Log.w("MorpheBydNative", "ETA configuration rejected: " + errorTypes(error));
        }
    }

    /** Observe the original converter without constructing a replacement calculator. */
    public static void registerCalculator(Object source) {
        if (!configured || !BydNativeBatteryPatch.guidanceUiReady()) return;
        if (calculatorType.isInstance(source)) calculator = new WeakReference<>(source);
    }

    /** Maps' DI component stores the converter's scoped provider; keep it to ask later. */
    public static void registerCalculatorProvider(Object source) {
        if (source != null) provider = new WeakReference<>(source);
    }

    /**
     * Search -> Start reaches guidance before the preview has requested the lazily created converter.
     * Ask Maps' own scoped provider for it: the same instance the preview would get, never a replacement.
     */
    private static Object providedCalculator() {
        Object owner = provider.get();
        if (!providerType.isInstance(owner) || owner == failedProvider.get()) return null;
        try {
            Object value = providerGet.invoke(owner);
            if (!calculatorType.isInstance(value)) {
                failedProvider = new WeakReference<>(owner); log("provider_error", "reason=unexpected_type"); return null;
            }
            calculator = new WeakReference<>(value);
            log("provider", "source=maps_scoped_provider");
            return value;
        } catch (Throwable error) {
            failedProvider = new WeakReference<>(owner); log("provider_error", errorTypes(error)); return null;
        }
    }

    /** Follow the same next-stop leg as Maps' native ETA and use its original converter. */
    public static void captureState(Object owner, Object current) {
        if (!configured || !BydNativeBatteryPatch.guidanceUiReady()) { clear("not_ready"); return; }
        if (!mobileOwner.isInstance(owner)) return;
        try {
            if (!stateType.isInstance(current)) { clear("no_state"); return; }
            Object nav = navigation.get(current);
            if (!navigationType.isInstance(nav)) {
                BydNativeBatteryPatch.onNavigationUiAbsent();
                clearAfterExit("no_navigation"); return;
            }
            Object selection = selector.get(nav);
            if (!selectorType.isInstance(selection)) { clear("no_selection"); return; }
            Object pos = position.get(selection), list = routes.get(selection);
            if (!positionType.isInstance(pos) || !(list instanceof List)) { clear("no_route_list"); return; }
            List<?> routeList = (List<?>) list;
            int selected = selectedIndex.getInt(pos);
            if (selected < 0 || selected >= routeList.size() || routeList.size() > 1000) { clear("bad_route_index"); return; }
            Object holder = routeList.get(selected);
            if (!holderType.isInstance(holder)) { clear("bad_route_holder"); return; }
            Object outer = wrapper.get(holder);
            if (!wrapperType.isInstance(outer)) { clear("bad_wrapper"); return; }
            Object route = canonical.get(outer);
            if (route == null || route.getClass() != snapshotType) { clear("bad_snapshot"); return; }
            int count = ((Number) legCount.invoke(route)).intValue();
            if (count <= 0 || count > 1000) { clear("no_legs"); return; }
            // Patch-time guards prove native ETA item1 maps to the remaining route's leg0.
            Object battery = legBattery.get(legAt.invoke(route, 0));
            if (battery == null) { clear("no_arrival"); return; }
            int bits = mask.getInt(battery), wh = currentWh.getInt(battery), cap = capacityWh.getInt(battery);
            // Preserve the previous feature's bounds; negative/underflow estimates remain out of scope.
            if ((bits & 4) == 0 || (bits & 16) == 0 || (bits & 512) != 0
                    || cap < 5000 || cap > 150000 || wh < 0 || wh > cap) { clear("unsupported_arrival"); return; }
            Object source = calculator.get();
            if (!calculatorType.isInstance(source)) source = providedCalculator();
            if (!calculatorType.isInstance(source)) {
                log("unavailable", "reason=no_calculator"); clear("no_calculator"); return;
            }
            long now = SystemClock.elapsedRealtime();
            Memo previous = memo;
            String percentText, accessibility;
            int displayPercent;
            if (previous != null && previous.matches(owner, route, source, bits, wh, cap)
                    && now - previous.at >= 0 && now - previous.at < RECOMPUTE_MS) {
                // Same immutable route/leg and converter: reuse its DTO result, re-run it periodically.
                percentText = previous.percentText; accessibility = previous.accessibility;
                displayPercent = previous.displayPercent;
            } else {
                // The original wrapper subtracts one and chooses its own intermediate/final threshold.
                // Keep Maps' calibration, localized text and accessibility for the same ETA target.
                Object dto = calculate.invoke(source, route, 1);
                if (!arrivalType.isInstance(dto) || dtoState.get(dto) == missingState
                        || (Boolean) dtoUnsupported.invoke(dto)) { clear("no_native_information"); return; }
                Object level = dtoLevel.get(dto);
                if (level == null) { clear("no_native_level"); return; }
                displayPercent = dtoDisplay.getInt(level);
                if (displayPercent < 0 || displayPercent > 100) { clear("unsupported_native_level"); return; }
                percentText = (String) dtoText.invoke(dto); accessibility = (String) dtoAccessibility.get(dto);
                memo = new Memo(owner, route, source, bits, wh, cap, percentText, accessibility, displayPercent, now);
            }
            Snapshot snapshot = new Snapshot(owner, now, Integer.toHexString(System.identityHashCode(route)), 0,
                    percentText, accessibility, displayPercent);
            long epoch;
            synchronized (LOCK) { latest = snapshot; epoch = ++generation; }
            scheduleExpiry(snapshot, epoch);
            log("capture", "route=" + snapshot.route + ";destination=" + snapshot.destination
                    + ";legs=" + count + ";one_based_destination=1;target=native_eta_next_stop"
                    + ";arrival_wh=" + wh + ";capacity_wh=" + cap + ";display_percent=" + displayPercent + ";native_dto=true");
        } catch (Throwable error) { clear("snapshot_error"); log("snapshot_error", errorTypes(error)); }
    }

    /** Proven dev.9 seam: return final text before Maps performs its ordinary binding. */
    public static CharSequence decorate(Object owner, CharSequence original) {
        if (!configured || original == null) return original;
        Snapshot snapshot = latest;
        if (!summaryApi.isInstance(owner) || !valid(snapshot) || snapshot.owner.get() != owner) return original;
        try {
            CharSequence result = BydGuidanceEtaText.decorate(original, snapshot.percentText,
                    snapshot.accessibility, snapshot.elapsed, snapshot.displayPercent);
            log("binding", "route=" + snapshot.route + ";destination=" + snapshot.destination
                    + ";target=native_eta_next_stop;display_percent=" + snapshot.displayPercent
                    + ";native_dto=true;icon_inline=true;icon_level_native=true");
            return result;
        } catch (Throwable error) { log("binding_error", errorTypes(error)); return original; }
    }

    /** The runtime marker selects only the owned ETA; every other pgl stays original. */
    public static int etaWidth(View view, int widthSpec) {
        if (!(view instanceof TextView)) return 0;
        TextView text = (TextView) view;
        try {
            if (!configured || !BydNativeBatteryPatch.guidanceUiReady()) {
                BydGuidanceEtaText.restore(text); return 0;
            }
            return BydGuidanceEtaText.measure(text, widthSpec);
        } catch (Throwable error) { log("measure_error", errorTypes(error)); return 0; }
    }

    private static boolean valid(Snapshot snapshot) {
        if (snapshot == null || snapshot.owner.get() == null || !BydNativeBatteryPatch.guidanceUiReady()) return false;
        long age = SystemClock.elapsedRealtime() - snapshot.elapsed;
        return age >= 0 && age <= FRESH_MS;
    }
    private static Handler handler() {
        synchronized (LOCK) {
            if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
            return mainHandler;
        }
    }
    private static void scheduleExpiry(Snapshot snapshot, long epoch) {
        Handler main = handler();
        synchronized (LOCK) {
            if (generation != epoch || latest != snapshot) return;
            if (expiry != null) main.removeCallbacks(expiry);
            expiry = () -> clear(snapshot, epoch, "frame_expired");
            main.postDelayed(expiry, FRESH_MS + 1);
        }
    }
    /**
     * Navigation UI gone (Cancel): keep the last frame through Maps' exit animation, then clear as usual.
     * Repeated callbacks keep the first deadline; a new capture replaces the snapshot and cancels it.
     */
    private static void clearAfterExit(String reason) {
        Handler main = handler();
        synchronized (LOCK) {
            Snapshot snapshot = latest;
            if (snapshot != null) {
                if (exiting == snapshot) return;
                long epoch = generation;
                exiting = snapshot;
                if (expiry != null) main.removeCallbacks(expiry);
                expiry = () -> clear(snapshot, epoch, reason);
                main.postDelayed(expiry, EXIT_GRACE_MS);
                return;
            }
        }
        clear(reason);
    }
    static void clear(String reason) { clear(null, -1, reason); }
    private static void clear(Snapshot expected, long expectedEpoch, String reason) {
        Snapshot previous; long epoch;
        synchronized (LOCK) {
            if (expectedEpoch >= 0 && (generation != expectedEpoch || latest != expected)) return;
            memo = null;
            // Repeated not-ready/inactive callbacks: nothing published, scheduled or still styled.
            if (latest == null && expiry == null && !BydGuidanceEtaText.hasStyled()) return;
            previous = latest; latest = null; epoch = ++generation;
            if (mainHandler != null && expiry != null) mainHandler.removeCallbacks(expiry);
            expiry = null;
        }
        Runnable restore = () -> {
            synchronized (LOCK) {
                if (generation == epoch && latest == null) BydGuidanceEtaText.clear();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) restore.run(); else handler().post(restore);
        if (previous != null) log("clear", "reason=" + reason);
    }

    private static String[] members(String encoded, int count) {
        String[] values = encoded.split(",", -1);
        if (values.length != count) throw new IllegalArgumentException("UI members");
        for (String value : values) if (!value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
            throw new IllegalArgumentException("UI member name");
        return values;
    }
    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, BydNativeGuidanceUi.class.getClassLoader());
    }
    private static Field publicField(Class<?> owner, String name) throws Exception {
        Field field = owner.getField(name);
        if (Modifier.isStatic(field.getModifiers())) throw new IllegalArgumentException("static UI field");
        return field;
    }
    private static String errorTypes(Throwable error) {
        StringBuilder value = new StringBuilder();
        for (int depth = 0; error != null && depth < 4; depth++) {
            if (depth > 0) value.append('>'); value.append(error.getClass().getSimpleName());
            Throwable cause = error.getCause(); if (cause == error) break; error = cause;
        }
        return value.toString();
    }
    /** Identical rows are counted, so suppressed= also measures capture/binding/layout frequency. */
    private static void log(String kind, String note) {
        long now = SystemClock.elapsedRealtime(); String signature = kind + ";" + LOG_VOLATILE.matcher(note).replaceAll("");
        int suppressed;
        synchronized (BydNativeGuidanceUi.class) {
            Long previous = logTimes.get(kind);
            Integer count = logSuppressed.get(kind);
            if (signature.equals(logSignatures.get(kind)) && previous != null && now - previous >= 0
                    && now - previous < LOG_HEARTBEAT_MS) {
                logSuppressed.put(kind, count == null ? 1 : count + 1); return;
            }
            suppressed = count == null ? 0 : count;
            logSignatures.put(kind, signature); logTimes.put(kind, now); logSuppressed.put(kind, 0);
        }
        BydNativeBatteryPatch.diagnosticEvent("guidance_ui_" + kind, "[DEBUG-native-dev22];call_elapsed_ms=" + now
                + ";call_wall_ms=" + System.currentTimeMillis() + ";" + note
                + (suppressed > 0 ? ";suppressed=" + suppressed : ""));
    }

    static void layoutLog(String note) { log("layout", note); }

    private static final class Memo {
        final Object owner, route, calculator;
        final int bits, wh, cap, displayPercent;
        final String percentText, accessibility;
        final long at;
        Memo(Object owner, Object route, Object calculator, int bits, int wh, int cap,
             String percentText, String accessibility, int displayPercent, long at) {
            this.owner = owner; this.route = route; this.calculator = calculator; this.bits = bits; this.wh = wh;
            this.cap = cap; this.percentText = percentText; this.accessibility = accessibility;
            this.displayPercent = displayPercent; this.at = at;
        }
        boolean matches(Object owner, Object route, Object calculator, int bits, int wh, int cap) {
            return this.owner == owner && this.route == route && this.calculator == calculator
                    && this.bits == bits && this.wh == wh && this.cap == cap;
        }
    }

    private static final class Snapshot {
        final WeakReference<Object> owner;
        final long elapsed;
        final String route, percentText, accessibility;
        final int destination, displayPercent;
        Snapshot(Object owner, long elapsed, String route, int destination, String percentText, String accessibility, int displayPercent) {
            this.owner = new WeakReference<>(owner); this.elapsed = elapsed; this.route = route;
            this.destination = destination; this.percentText = percentText; this.accessibility = accessibility;
            this.displayPercent = displayPercent;
        }
    }
}
