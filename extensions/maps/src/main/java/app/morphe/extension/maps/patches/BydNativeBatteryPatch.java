package app.morphe.extension.maps.patches;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** Opt-in experiment using Maps' own vemlite builder/provider. No vehicle setters or runtime ADB. */
@SuppressWarnings("unused")
public final class BydNativeBatteryPatch {
    private static final String TAG = "MorpheBydNative";
    private static final long POLL_MS = 10000, FRESH_MS = 30000;
    // Maps' navigation-state listener is not called again after guidance exits. End our own
    // guidance state from the guided-UI exit signal, or after the existing recent-guidance window.
    private static final long GUIDANCE_UI_ABSENT_MS = 10000, GUIDANCE_IDLE_MS = 180000;
    private static final long ROUTINE_HEARTBEAT_MS = 60000, LOG_LIMIT_BYTES = 10000000;
    private static final Pattern ROUTINE_VOLATILE = Pattern.compile(";(?:sdk_age_ms|model_age_ms|content_age_ms|previous_age_ms)=[^;]*");
    private static final ThreadLocal<Values> BUILD = new ThreadLocal<>();
    // Confined to one synchronous, inspected protobuf-copy method invocation.
    private static final ThreadLocal<Values> COPY = new ThreadLocal<>();
    // Captures only the synchronous initial provider query, never a prior publication.
    private enum InitialQueryState { NOT_QUERIED, ALLOWED, DENIED }
    private static final ThreadLocal<InitialQueryState> INITIAL_SELECTION = new ThreadLocal<>();
    private static final ThreadLocal<InitialQueryState> INITIAL_SELECTION_RESULT = new ThreadLocal<>();
    private static final ThreadLocal<Object> INITIAL_PROVIDERS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> INITIAL_NATIVE_QUERY = new ThreadLocal<>();
    // Why the unselected-aggregate query stopped; reported once with this initial context.
    private static final ThreadLocal<String> INITIAL_NATIVE_REASON = new ThreadLocal<>();
    private static final AtomicBoolean pending = new AtomicBoolean();
    private static final Object modelTransition = new Object();
    // Weak identity membership, rather than protobuf equality or strong message retention.
    private static final List<Owned> owned = new ArrayList<>();
    private static final List<Provider> providers = new ArrayList<>();
    private static final List<Preview> previews = new ArrayList<>();
    private static volatile boolean enabled, visible, guided;
    private static volatile Values available;
    private static volatile long progressAt, lastForecastAt, lastGateAt, navigationAbsentAt;
    private static Handler worker;
    private static Application app;
    private static BydEnergyReader reader;
    private static String[] providerSchema, forecastSchema;
    private static String[] modelSchema;
    private static Method modelGetter, startupGate, trackingGate;
    private static Method publicationClone;
    private static volatile Field nativeProviderModel;
    private static volatile Method nativeProviderUpdate;
    private static Method trackerSet, trackerInhibit, trackerRelease;
    private static String trackerExecutorField;
    private static final List<Tracker> trackers = new ArrayList<>();
    private static volatile Object publishedModel;
    private static volatile boolean nativeSelected;
    private static final AtomicBoolean startupReported = new AtomicBoolean(), trackingReported = new AtomicBoolean();
    private static WeakReference<Object> gateTarget = new WeakReference<>(null);
    private static float nominalWh, roadA, roadB, roadC;
    private static int failures;
    private static long lastUiGateAt;
    private static long lastInitialCacheAt;
    private static boolean lastInitialCacheReady;
    // Worker-only routine-row coalescing and batched daily CSV writes.
    private static final Map<String, Routine> routines = new HashMap<>();
    private static final StringBuilder logBuffer = new StringBuilder();
    private static final Runnable flushLog = BydNativeBatteryPatch::flushLog;
    private static boolean logFlushPosted;
    private static File logDirectory, logFile;
    private static String logDay;
    private static long logLength = -1, logDayEndsAt, logDirectoryRetryAt;
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> fields = new ConcurrentHashMap<>();

    private BydNativeBatteryPatch() { }

    /** Member names and physical priors are supplied by the guarded patch resolver. */
    public static void configure(String schema) {
        try {
            String[] sections = schema.split(";", -1);
            if (sections.length != 6) throw new IllegalArgumentException("schema sections");
            providerSchema = sections[0].split(",", -1);
            forecastSchema = sections[1].split(",", -1);
            String[] physics = sections[2].split(",", -1);
            modelSchema = sections[3].split(",", -1);
            String[] members = sections[4].split(",", -1);
            String[] tracker = sections[5].split(",", -1);
            if (providerSchema.length != 5 || forecastSchema.length != 7 || physics.length != 4
                    || modelSchema.length != 4 || members.length != 5 || tracker.length != 5)
                throw new IllegalArgumentException("schema fields");
            publicationClone = Class.forName(providerSchema[2]).getMethod(providerSchema[3],
                    Class.forName(providerSchema[4]), boolean.class);
            modelGetter = Class.forName(members[0]).getMethod(members[1]);
            Class<?> gates = Class.forName(members[2]);
            startupGate = gates.getMethod(members[3]); trackingGate = gates.getMethod(members[4]);
            Class<?> trackerApi = Class.forName(tracker[0]);
            trackerSet = trackerApi.getMethod(tracker[1], int.class);
            trackerInhibit = trackerApi.getMethod(tracker[2]); trackerRelease = trackerApi.getMethod(tracker[3]);
            trackerExecutorField = tracker[4];
            nominalWh = Float.parseFloat(physics[0]); roadA = Float.parseFloat(physics[1]);
            roadB = Float.parseFloat(physics[2]); roadC = Float.parseFloat(physics[3]);
            if (!(nominalWh >= 5000 && nominalWh <= 150000 && roadA >= 0 && roadA <= 1000
                    && roadB >= 0 && roadB <= 20 && roadC > 0 && roadC <= 2))
                throw new IllegalArgumentException("physical priors");
        } catch (Throwable error) { providerSchema = null; Log.w(TAG, "configuration rejected", error); }
    }

    public static void onApplicationCreate(Application application) {
        try {
            if (providerSchema == null || !isByd() || enabled) return;
            if (Build.VERSION.SDK_INT >= 28 &&
                    !application.getPackageName().equals(Application.getProcessName())) return;
            Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            app = application;
            BydVehicleModel.initialize(application, nominalWh, roadA, roadB, roadC);
            HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            thread.start(); worker = new Handler(thread.getLooper()); reader = new BydEnergyReader();
            enabled = true;
            BydNativeBatteryDiagnostics.start();
            worker.post(() -> {
                BydVehicleModel.Model model = BydVehicleModel.current();
                BydEnergyComparison.start(application, model.nominalWh, model.a, model.b, model.c);
                event("vehicle_model", model.describe());
            });
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(Activity a) { if (isMaps(a)) { visible = true; schedule(); } }
                public void onActivityPaused(Activity a) { if (isMaps(a)) visible = false; }
                public void onActivityCreated(Activity a, Bundle s) { }
                public void onActivityStarted(Activity a) { }
                public void onActivityStopped(Activity a) { }
                public void onActivitySaveInstanceState(Activity a, Bundle s) { }
                public void onActivityDestroyed(Activity a) { }
            });
            worker.post(() -> event("init", "native_experiment;BYD_scoped_tracker_gates;road_load_priors="
                    + roadA + "/" + roadB + "/" + roadC));
        } catch (Throwable error) { enabled = false; Log.w(TAG, "native experiment disabled", error); }
    }

    /** Injected after the existing provider constructor completes. */
    public static void registerProvider(Object target) {
        if (!enabled || target == null) return;
        try {
            Provider provider = new Provider(target,
                    target.getClass().getMethod(providerSchema[0], float.class),
                    target.getClass().getField(providerSchema[1]));
            // Publish only the inspected native type/member, not the worker's provider list.
            nativeProviderUpdate = provider.update;
            nativeProviderModel = provider.model;
            // The constructor consumer can query Maps before a queued publication runs.
            // Reuse only an already fresh immutable sample; SDK reads stay on the worker.
            Values seed = active() ? freshAvailable() : null;
            boolean seeded = seed != null && publishProvider(provider, seed);
            worker.post(() -> {
                providers.removeIf(entry -> entry.target.get() == null || entry.target.get() == target);
                providers.add(provider);
                event("provider", "registered;count=" + providers.size());
                // A delayed poll must not postpone publication of an already fresh SDK sample.
                Values values = active() ? freshAvailable() : null;
                if (values != null && (!seeded || values != seed)) publishProvider(provider, values);
                schedule();
            });
        } catch (Throwable error) { Log.w(TAG, "provider registration failed", error); }
    }

    /** After Maps' only external native-model write and republish: its demo-setting collector. */
    public static void onNativeModelCleared(Object target) {
        if (!enabled || target == null) return;
        Field field = nativeProviderModel;
        Method update = nativeProviderUpdate;
        String outcome;
        try {
            if (field == null || update == null || target.getClass() != field.getDeclaringClass()) outcome = "unregistered";
            // Re-seed only a cleared slot; never replace a model Maps itself wrote.
            else if (field.get(target) != null) outcome = "not_cleared";
            else {
                // Same rule as registration: reuse an already fresh sample; SDK reads stay on the worker.
                Values seed = active() ? freshAvailable() : null;
                if (seed == null) outcome = active() ? "no_fresh_sample" : "inactive";
                else outcome = publishProvider(new Provider(target, update, field), seed) ? "reseeded" : "reseed_unconfirmed";
            }
        } catch (Throwable error) { outcome = "error:" + BydEnergyReader.describe(error); }
        postRoutine("native_model_clear", "native_model_clear", "source=external_writer;outcome=" + outcome
                + ";thread_id=" + Thread.currentThread().getId());
    }

    // BUILD is confined to our synchronous factory invocation on its caller thread.
    // Other demo calls are unchanged, including concurrent calls on another thread.
    public static boolean isBuilding() { return BUILD.get() != null; }
    public static int energyWh(int original) { Values v = BUILD.get(); return v == null ? original : v.energyWh; }
    public static int capacityWh(int original) { Values v = BUILD.get(); return v == null ? original : v.capacityWh; }
    public static float capacityFloat(float original) { Values v = BUILD.get(); return v == null ? original : v.capacityWh; }
    public static double capacityDouble(double original) { Values v = BUILD.get(); return v == null ? original : v.capacityWh; }
    public static float rolling(float original) { Values v = BUILD.get(); return v == null ? original : v.model.a; }
    public static float linear(float original) { Values v = BUILD.get(); return v == null ? original : v.model.b; }
    public static float drag(float original) { Values v = BUILD.get(); return v == null ? original : v.model.c; }

    /** Called before the factory publishes; the collector may immediately clone the message. */
    public static void onBuiltModel(Object model) {
        Values values = BUILD.get();
        if (values != null && model != null) {
            remember(model, values);
            postRoutine("factory_model", "factory_model", modelDescription(model));
        }
    }
    /** Runs inside our factory call, before the envelope enters Maps' stream. */
    public static void onProviderEnvelope(Object envelope) {
        Values values = BUILD.get();
        if (values == null || envelope == null) return;
        try {
            // The injected copy hooks preserve sample ownership on this immutable clone.
            Object model = publicationClone.invoke(null, envelope, true);
            if (ownedValues(model) != values || freshValues(model) == null)
                throw new IllegalStateException("fresh factory clone ownership");
            postRoutine("factory_cache", "factory_cache", "source=publication_envelope;" + modelDescription(model));
        } catch (Throwable error) { postEvent("factory_cache_error", BydEnergyReader.describe(error)); }
        finally { COPY.remove(); }
    }
    public static void onCopyInput(Object model) {
        Values values = ownedValues(model);
        if (values == null) COPY.remove(); else COPY.set(values);
    }
    public static void onCopyResult(Object model) {
        Values values = COPY.get(); COPY.remove();
        if (model != null && values != null) remember(model, values);
    }
    /** Cache the actual published provider clone, including Maps' provider metadata. */
    public static void onPublishedModel(Object model) {
        onCopyResult(model);
        synchronized (owned) {
            Values values = freshValues(model), previous = ownedValues(publishedModel);
            if (values != null && (previous == null || newer(values, previous) || values == previous)) publishedModel = model;
        }
        BydNativeBatteryDiagnostics.publication(model);
    }
    private static void remember(Object model, Values values) {
        synchronized (owned) {
            owned.removeIf(entry -> entry.model.get() == null || entry.model.get() == model);
            owned.add(new Owned(model, values));
        }
    }
    // Every successful poll publishes a new sample; Settings-only copies preserve its SDK time.
    private static Values freshValues(Object model) {
        Values values = ownedValues(model);
        Values sdk = freshAvailable();
        return values != null && sdk != null && values.model.revision == sdk.model.revision
                && SystemClock.elapsedRealtime() - values.confirmed <= FRESH_MS ? values : null;
    }
    private static Values freshAvailable() {
        Values values = available;
        BydVehicleModel.Model model = BydVehicleModel.current();
        return enabled && values != null && model != null && values.model.revision == model.revision
                && SystemClock.elapsedRealtime() - values.confirmed <= FRESH_MS ? values : null;
    }

    private static boolean newer(Values next, Values previous) {
        return next.model.revision > previous.model.revision
                || (next.model.revision == previous.model.revision && next.elapsed > previous.elapsed);
    }

    static String vehicleStatus() {
        Values values = freshAvailable();
        return values == null ? "No live battery data"
                : String.format(Locale.ROOT, "SOC %.0f%% · %.1f kWh", values.soc, values.energyWh / 1000.0);
    }

    static void onVehicleSettingsChanged() {
        if (enabled && worker != null) worker.post(BydNativeBatteryPatch::activateVehicleModel);
    }

    /** Worker only: stage learning during a trip, then publish one coherent revision for the next route. */
    private static void activateVehicleModel() {
        synchronized (modelTransition) {
            if (guided) return;
            BydVehicleModel.Model before = BydVehicleModel.current();
            BydVehicleModel.Model model = BydVehicleModel.activatePending();
            if (model == null || model == before) return;
            Values previous = available;
            available = null; publishedModel = null;
            BydEnergyComparison.unavailable("vehicle_model_revision_changed");
            if (previous != null && SystemClock.elapsedRealtime() - previous.confirmed <= FRESH_MS && active()
                    && (previous.soc >= 10 || previous.energyWh <= BydVehicleModel.lowSocCapacity(model) + 500)) {
                // Reuse one fresh BMS observation. No SDK call or route request comes from Settings.
                int capacity = previous.soc >= 10 ? previous.capacityWh
                        : Math.max(previous.energyWh, (int) Math.round(BydVehicleModel.lowSocCapacity(model)));
                Values values = new Values(previous.elapsed, previous.confirmed, previous.soc, previous.energyWh,
                        capacity, previous.basis, model);
                available = values; publish(values);
            } else publish(null);
            event("vehicle_model", model.describe());
        }
    }

    public static void registerPreview(Object preview, Object provider) {
        if (!enabled || preview == null || provider == null) return;
        synchronized (previews) {
            previews.removeIf(entry -> entry.target.get() == null || entry.target.get() == preview);
            previews.add(new Preview(preview, provider));
        }
        postEvent("preview", "registered");
    }
    /** -1 preserves Maps' original/manual behavior when this native model is not selected/fresh. */
    public static int previewBatteryPercent(Object target) {
        if (!enabled) return -1;
        Preview preview = null;
        synchronized (previews) {
            for (Preview entry : previews) if (entry.target.get() == target) { preview = entry; break; }
        }
        if (preview == null) return -1;
        try {
            Object model = newerOwnedModel(modelGetter.invoke(preview.provider));
            Values values = freshValues(model);
            if (values == null) return -1;
            int percent = Math.max(0, Math.min(100, (int) Math.round(values.soc)));
            if (preview.lastSample != values.elapsed) {
                preview.lastSample = values.elapsed;
                postEvent("preview_soc", "percent=" + percent + ";" + values.describe());
            }
            return percent;
        } catch (Throwable error) {
            postEvent("preview_error", BydEnergyReader.describe(error)); return -1;
        }
    }
    /** Keep the exact BMS Wh, including the low-SOC nominal-capacity case and integer rounding. */
    public static int previewEnergyWh(Object model, int computed) {
        Values values = freshValues(model);
        if (values == null) return computed;
        postRoutine("preview_energy", "preview_energy", "maps_computed_wh=" + computed + ";bms_wh=" + values.energyWh);
        return values.energyWh;
    }

    public static void registerGates(Object target) {
        if (!enabled || target == null) return;
        worker.post(() -> { gateTarget = new WeakReference<>(target); event("ui_gates", "registered"); logUiGates(true); });
    }
    public static void onTrackerCreated() { postEvent("tracker", "created"); }
    public static void onTrackerStart(boolean value) { postEvent("tracker_start", "enabled=" + value); }
    /** Preserve remote/manual behavior outside this opt-in BYD main-process runtime. */
    public static boolean allowTrackerStartup(boolean original) {
        if (enabled && startupReported.compareAndSet(false, true))
            postEvent("native_gate", "stage=startup;original=" + original + ";effective=true");
        return original || enabled;
    }
    public static boolean allowTrackerTracking(boolean original) {
        if (enabled && trackingReported.compareAndSet(false, true))
            postEvent("native_gate", "stage=tracking;original=" + original + ";effective=true");
        return original || enabled;
    }
    public static void registerTracker(Object target) {
        if (!enabled || target == null) return;
        try {
            Executor executor = (Executor) target.getClass().getField(trackerExecutorField).get(target);
            Tracker tracker = new Tracker(target, executor);
            worker.post(() -> { trackers.add(tracker); event("tracker", "registered"); syncTrackers(); });
        } catch (Throwable error) { postEvent("tracker_error", BydEnergyReader.describe(error)); }
    }
    /** Each bridge owns exactly one inhibitor; all calls run on Maps' tracker executor. */
    private static void syncTrackers() {
        Iterator<Tracker> iterator = trackers.iterator();
        while (iterator.hasNext()) {
            Tracker tracker = iterator.next();
            if (tracker.target.get() == null) { iterator.remove(); continue; }
            if (!tracker.pending.compareAndSet(false, true)) continue;
            try { tracker.executor.execute(() -> updateTracker(tracker)); }
            catch (Throwable error) {
                tracker.pending.set(false); event("tracker_error", BydEnergyReader.describe(error));
            }
        }
    }
    private static void updateTracker(Tracker tracker) {
        try {
            Object target = tracker.target.get();
            if (target == null) return;
            synchronized (tracker) {
                Values values = guided && nativeSelected ? freshAvailable() : null;
                if (values == null) {
                    releaseTracker(tracker, target);
                    return;
                }
                try {
                    if (!tracker.inhibited) { trackerInhibit.invoke(target); tracker.inhibited = true; }
                    // Let Maps interpolate consumption between BMS energy changes.
                    if (tracker.lastWh != values.energyWh) {
                        trackerSet.invoke(target, values.energyWh); tracker.lastWh = values.energyWh;
                        postEvent("tracker_energy", "energy_wh=" + values.energyWh + ";soc=" + values.soc
                                + ";sdk_age_ms=" + (SystemClock.elapsedRealtime() - values.confirmed));
                    }
                } catch (Throwable error) {
                    releaseTracker(tracker, target); throw error;
                }
            }
        } catch (Throwable error) { postEvent("tracker_error", BydEnergyReader.describe(error)); }
        finally { tracker.pending.set(false); }
    }
    private static void releaseTracker(Tracker tracker, Object target) throws Exception {
        if (!tracker.inhibited) return;
        trackerRelease.invoke(target); tracker.inhibited = false; tracker.lastWh = -1;
        postEvent("tracker_release", "bridge_inhibitor_released");
    }
    /** Worker readback also works if the original startup gate prevented listener registration. */
    private static void logUiGates(boolean force) {
        Object target = gateTarget.get();
        long now = SystemClock.elapsedRealtime();
        if (target == null || (!force && now - lastUiGateAt < POLL_MS)) return;
        lastUiGateAt = now;
        try {
            routine("ui_gates", "ui_gates", "startup=" + startupGate.invoke(target) + ";tracking="
                    + trackingGate.invoke(target) + ";guided=" + guided);
        } catch (Throwable error) { event("gate_error", BydEnergyReader.describe(error)); }
    }
    private static void postEvent(String kind, String note) {
        if (enabled && worker != null) worker.post(() -> event(kind, note));
    }
    /** Repeated rows keep their first occurrence, changes and a heartbeat with a suppressed count. */
    private static void postRoutine(String key, String kind, String note) {
        if (enabled && worker != null) worker.post(() -> routine(key, kind, note));
    }

    static boolean diagnosticsEnabled() { return enabled && worker != null; }
    // Mobile's first binding precedes the separate core GUIDING callback. Its typed
    // current-state route and summary owner provide the UI proof; require fresh BYD selection.
    static boolean guidanceUiReady() {
        return enabled && nativeSelected && freshAvailable() != null && active();
    }
    static void diagnosticEvent(String kind, String note) { postEvent(kind, note); }
    static String diagnosticModelState(Object model) { return modelDescription(model); }
    static String diagnosticContext() {
        Values sample = available;
        return "guided=" + guided + ";visible=" + visible + ";native_selected=" + nativeSelected
                + ";sdk_age_ms=" + (sample == null ? -1 : SystemClock.elapsedRealtime() - sample.confirmed);
    }
    /** Main-thread guided-UI callback saw no navigation; the worker ends a stale guidance state. */
    static void onNavigationUiAbsent() {
        if (enabled && guided) navigationAbsentAt = SystemClock.elapsedRealtime();
    }

    public static void beginInitialModelSelection() {
        INITIAL_SELECTION_RESULT.remove();
        INITIAL_PROVIDERS.remove(); INITIAL_NATIVE_QUERY.remove(); INITIAL_NATIVE_REASON.remove();
        INITIAL_SELECTION.set(InitialQueryState.NOT_QUERIED);
    }
    /** Captures this aggregate's actual ordered list before its receiver register is reused. */
    public static void captureInitialProviders(Object candidates) {
        INITIAL_PROVIDERS.remove();
        InitialQueryState state = INITIAL_SELECTION.get();
        // Claim at most one aggregate per begin, including after an original getter throws.
        // null=unclaimed, false=list captured, true=native getter invoked.
        if (enabled && state == InitialQueryState.NOT_QUERIED
                && INITIAL_NATIVE_QUERY.get() == null && candidates instanceof List<?>) {
            INITIAL_NATIVE_QUERY.set(false);
            INITIAL_PROVIDERS.set(candidates);
        } else if (enabled && state != null && INITIAL_NATIVE_REASON.get() == null) {
            // Keep the first aggregate's reason; a later aggregate cannot rearm the claim.
            INITIAL_NATIVE_REASON.set(state != InitialQueryState.NOT_QUERIED ? "capture_after_" + state
                    : candidates instanceof List<?> ? "capture_claimed" : "capture_not_list");
        }
    }
    /** Called only from the inspected aggregate's all-streams-null return, never its selected arm. */
    public static Object queryUnselectedInitialProvider() {
        Object captured = INITIAL_PROVIDERS.get(); INITIAL_PROVIDERS.remove();
        InitialQueryState state = INITIAL_SELECTION.get();
        // Other aggregate callers have no initial selection and keep the original null silently.
        if (!enabled || state == null) return null;
        String skip = state != InitialQueryState.NOT_QUERIED ? "state_" + state
                : !(captured instanceof List<?>) ? "no_capture" : !active() ? "inactive"
                : freshAvailable() == null ? "sdk_stale" : null;
        Field field = nativeProviderModel;
        if (skip == null && (field == null || modelGetter == null)) skip = "member_unresolved";
        if (skip != null) return initialQuerySkip(skip);
        try {
            Object candidate = null;
            for (Object item : (List<?>) captured) {
                if (item == null || item.getClass() != field.getDeclaringClass()) continue;
                // Ambiguous native membership stays unresolved.
                if (candidate != null) return initialQuerySkip("native_member_ambiguous");
                candidate = item;
            }
            if (candidate == null) return initialQuerySkip("native_member_missing");
            Object model = field.get(candidate);
            if (freshValues(model) == null) return initialQuerySkip(model == null ? "native_model_null"
                    : ownedValues(model) == null ? "native_model_foreign" : "native_model_stale");
            INITIAL_NATIVE_QUERY.set(true);
            INITIAL_NATIVE_REASON.set("getter_invoked");
            // No seed, SDK read or forced Boolean: Maps reads its real envelope/gate/age.
            Object result = modelGetter.invoke(candidate);
            boolean accepted = INITIAL_SELECTION.get() == InitialQueryState.ALLOWED
                    && active() && freshValues(result) != null;
            INITIAL_NATIVE_REASON.set(accepted ? "accepted" : "rejected_" + INITIAL_SELECTION.get());
            postRoutine("initial_native_query", "initial_native_query", "source=unselected_aggregate;provider_clone_state="
                    + INITIAL_SELECTION.get() + ";accepted=" + accepted + ";" + modelDescription(result));
            return accepted ? result : null;
        } catch (Throwable error) {
            INITIAL_NATIVE_REASON.set("error");
            postEvent("initial_native_query_error", BydEnergyReader.describe(error));
            return null;
        }
    }
    private static Object initialQuerySkip(String reason) {
        INITIAL_NATIVE_REASON.set(reason);
        return null;
    }
    public static void recordInitialModelSelection(boolean allowed) {
        if (INITIAL_SELECTION.get() != null)
            INITIAL_SELECTION.set(allowed ? InitialQueryState.ALLOWED : InitialQueryState.DENIED);
    }
    public static boolean finishInitialModelSelection() {
        InitialQueryState state = INITIAL_SELECTION.get();
        INITIAL_SELECTION.remove();
        INITIAL_PROVIDERS.remove();
        if (state == null) INITIAL_SELECTION_RESULT.remove();
        else INITIAL_SELECTION_RESULT.set(state);
        return state == InitialQueryState.ALLOWED;
    }

    /** Initial request only: its allowed branch may precede reactive provider delivery. */
    public static Object filterInitialRequestModel(Object model, boolean allowed) {
        // Preserve the original false-gate path, including its native selection state.
        if (!allowed) return model;
        if (model != null) return filterRequestModel(model);
        // The startup query's null/invalid result must not be replaced by the cache.
        if (Boolean.TRUE.equals(INITIAL_NATIVE_QUERY.get())) return null;
        if (!enabled) return null;
        Values sample = freshAvailable();
        Object cached = publishedModel;
        Values values = ownedValues(cached);
        boolean ready = enabled && active() && sample != null && freshValues(cached) != null;
        long now = SystemClock.elapsedRealtime();
        logInitialCache(ready, sample == null ? -1 : now - sample.confirmed,
                values == null ? -1 : now - values.confirmed);
        selectNative(ready);
        return ready ? cached : null;
    }

    private static void logInitialCache(boolean ready, long sdkAge, long modelAge) {
        long now = SystemClock.elapsedRealtime();
        synchronized (BydNativeBatteryPatch.class) {
            if (ready == lastInitialCacheReady && now - lastInitialCacheAt < POLL_MS) return;
            lastInitialCacheReady = ready; lastInitialCacheAt = now;
        }
        postEvent("initial_cache", "source=owned_publication_cache;selected_model=null;ready=" + ready
                + ";sdk_age_ms=" + sdkAge + ";model_age_ms=" + modelAge);
    }

    /** Replace only our cached model with a fresh published clone before the null guard. */
    public static Object filterRequestModel(Object model) {
        if (model == null) { selectNative(false); return null; }
        Values values = ownedValues(model);
        if (values == null) { selectNative(false); return model; }
        Object current = newerOwnedModel(model);
        Values currentValues = freshValues(current);
        if (currentValues != null && newer(currentValues, values)) {
            selectNative(true);
            postRoutine("request_refresh", "request_refresh", "previous_age_ms="
                    + (SystemClock.elapsedRealtime() - values.elapsed) + ";" + modelDescription(current));
            return current;
        }
        if (freshValues(model) == null) {
            selectNative(false);
            postRoutine("stale_request", "stale_request", "native_model_removed;" + modelDescription(model));
            return null;
        }
        selectNative(true);
        return model;
    }
    private static Object newerOwnedModel(Object model) {
        Values previous = ownedValues(model);
        if (previous == null) return model;
        Object current = publishedModel;
        Values values = freshValues(current);
        return values != null && newer(values, previous) ? current : model;
    }
    /** Keep preview capacity/current Wh from the same sample before Maps rebuilds it. */
    public static Object refreshPreviewModel(Object model) { return newerOwnedModel(model); }
    private static void selectNative(boolean value) {
        if (nativeSelected == value) return;
        nativeSelected = value;
        if (!value) BydNativeGuidanceUi.clear("native_deselected");
        if (enabled && worker != null) worker.post(() -> {
            event("native_selection", "selected=" + nativeSelected); syncTrackers();
            BydEnergyComparison.selection(value);
        });
    }

    /** Actual route-request field-write path; does not send a request by itself. */
    public static void onRequestModel(Object model) {
        requestModel("navigation_writer", model);
    }
    public static void onInitialRequestModel(Object model) { requestModel("initial_writer", model); }
    public static void onPreviewRequestModel(Object model) { requestModel("preview_final", model); }
    public static void onInitialRequestContext(Object model, boolean allowed) {
        InitialQueryState state = INITIAL_SELECTION_RESULT.get();
        String reason = INITIAL_NATIVE_REASON.get();
        INITIAL_SELECTION_RESULT.remove();
        INITIAL_NATIVE_QUERY.remove();
        INITIAL_NATIVE_REASON.remove();
        if (!enabled) return;
        // NOT_QUERIED means no clone entry was observed, not an explicit false gate.
        String queryState = state == null ? "UNKNOWN" : state.name();
        BydNativeBatteryDiagnostics.request("initial_context", model, allowed, queryState);
        // not_reached: a selected provider answered, or the unselected-aggregate query never ran.
        postRoutine("initial_context", "initial_context", "attach_allowed=" + allowed
                + ";provider_clone_state=" + queryState + ";native_query=" + (reason == null ? "not_reached" : reason)
                + ";has_model=" + (model != null) + ";" + modelDescription(model));
    }
    private static void requestModel(String stage, Object model) {
        if (!enabled || model == null) return;
        BydNativeBatteryDiagnostics.request(stage, model, true);
        // Snapshot the actual immutable protobuf fields at the write, not merely SDK expectations.
        postRoutine("request_model:" + stage, "request_model", "stage=" + stage + ";" + modelDescription(model));
    }
    private static String modelDescription(Object model) {
        if (model == null) return "owned=false";
        Values values = ownedValues(model);
        long now = SystemClock.elapsedRealtime();
        Values sdk = available;
        // model_age_ms is the freshness basis; content_age_ms is when this exact content first appeared.
        String source = "sdk_age_ms=" + (sdk == null ? -1 : now - sdk.confirmed) + ";" +
                (values == null ? "owned=false" : "owned=true;fresh=" + (freshValues(model) != null)
                        + ";model_age_ms=" + (now - values.confirmed) + ";content_age_ms=" + (now - values.elapsed)
                        + ";" + values.describe());
        try {
            Object battery = field(model, modelSchema[0]);
            Object current = battery == null ? null : field(battery, modelSchema[1]);
            Object capacity = battery == null ? null : field(battery, modelSchema[2]);
            return source + ";model_energy_wh=" + (current == null ? "unset" : field(current, modelSchema[3]))
                    + ";model_capacity_wh=" + (capacity == null ? "unset" : field(capacity, modelSchema[3]));
        } catch (Throwable error) { return source + ";model_read_error=" + BydEnergyReader.describe(error); }
    }

    public static void onTrackerGate(boolean value) {
        if (!enabled) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastGateAt < POLL_MS) return;
        lastGateAt = now;
        worker.post(() -> event("tracker_gate", "enabled=" + value));
    }

    /** Runs independently of the native gate, only in Maps' own GUIDING state. */
    public static void onRoute(Object route) {
        if (!enabled) return;
        boolean starting;
        long callbackAt = SystemClock.elapsedRealtime();
        // Progress time and guided state change together, so a stale-guidance check cannot interleave.
        synchronized (modelTransition) { starting = !guided; guided = true; progressAt = callbackAt; }
        schedule();
        BydNativeBatteryDiagnostics.guidance(route);
        if (starting) worker.post(BydNativeBatteryPatch::syncTrackers);
        if (route == null || callbackAt - lastForecastAt < POLL_MS) return;
        lastForecastAt = callbackAt;
        worker.post(() -> forecast(route, callbackAt));
    }
    public static void onInactive() {
        BydNativeGuidanceUi.clear("navigation_inactive");
        if (!enabled) return;
        boolean ending;
        synchronized (modelTransition) { ending = guided; guided = false; }
        // Repeated non-guiding callbacks have nothing left to release or activate.
        if (ending) { worker.post(() -> finishGuidance("navigation_inactive")); schedule(); }
    }
    /** Worker only: Maps may not report a non-guiding state after guidance exits. */
    private static void checkGuidanceEnd() {
        long now = SystemClock.elapsedRealtime(), idle;
        String reason;
        synchronized (modelTransition) {
            if (!guided) return;
            idle = now - progressAt;
            if (navigationAbsentAt > progressAt && idle >= GUIDANCE_UI_ABSENT_MS) reason = "navigation_ui_absent";
            else if (idle >= GUIDANCE_IDLE_MS) reason = "progress_timeout";
            else return;
            guided = false;
        }
        BydNativeGuidanceUi.clear("guidance_end");
        finishGuidance(reason + ";progress_age_ms=" + idle);
    }
    /** Worker only: the same between-route transition for a reported or inferred guidance end. */
    private static void finishGuidance(String reason) {
        syncTrackers();
        String trip = BydEnergyComparison.inactive();
        BydVehicleModel.Model model = BydVehicleModel.current();
        if (trip != null) event("trip_summary", trip + (model == null ? ""
                : ";model_revision=" + model.revision + ";target_kwh_100km=" + model.consumption));
        activateVehicleModel();
        event("guidance_end", "reason=" + reason);
    }

    private static void schedule() {
        if (enabled && pending.compareAndSet(false, true)) worker.post(BydNativeBatteryPatch::poll);
    }
    private static void poll() {
        pending.set(false);
        if (!enabled) return;
        synchronized (previews) { previews.removeIf(entry -> entry.target.get() == null); }
        checkGuidanceEnd();
        boolean active = active();
        try {
            if (!active) {
                available = null; publishedModel = null; selectNative(false); publish(null);
                for (Provider provider : providers) provider.retained = null;
                syncTrackers(); BydEnergyComparison.unavailable("poll_inactive");
                BydVehicleModel.unavailable("poll_inactive"); return;
            }
            if (!reader.ready()) event("reader", reader.init(app));
            BydEnergyReader.Sample sample = reader.read();
            if (sample == null) {
                available = null; publishedModel = null; BydNativeGuidanceUi.clear("sdk_unavailable");
                publish(null); routine("sample", "sample", "unavailable");
                BydEnergyComparison.unavailable("sdk_unavailable");
                BydVehicleModel.unavailable("sdk_unavailable");
            }
            else {
                int current = (int) Math.round(sample.energy * 1000);
                BydVehicleModel.Model model = BydVehicleModel.current();
                // BYD reports whole SOC percents and Maps floors arrival percents. Centring the pair inside its
                // percent makes Maps show the dashboard value at arrival instead of one less.
                boolean centred = sample.soc >= 10 && sample.soc < 100 && sample.soc == Math.rint(sample.soc);
                double capacity = sample.soc >= 10 ? current * 100.0 / (centred ? sample.soc + 0.5 : sample.soc)
                        : BydVehicleModel.lowSocCapacity(model);
                if (!(capacity >= 5000 && capacity <= 150000 && current <= capacity + 500))
                    throw new IllegalArgumentException("inconsistent SOC/energy");
                int capacityWh = Math.max(current, (int) Math.round(capacity));
                String basis = sample.soc < 10 ? BydVehicleModel.lowSocCapacityBasis(model)
                        : centred ? "SOC_midpoint_energy_pair" : "single_SOC_energy_pair";
                // A new envelope resets Maps' measurement-age stopwatch even when SOC/Wh are unchanged.
                Values values = new Values(sample.elapsed, sample.elapsed, sample.soc, current, capacityWh, basis, model);
                available = values; publish(values);
                routine("sample", "sample", values.describe()); failures = 0;
                BydEnergyComparison.sample(sample, current, values.capacityWh, values.basis);
                routine("sdk_telemetry", "sdk_telemetry", "total_kwh_100km=" + sample.totalConsumptionKwhPer100Km
                        + ";recent_kwh_100km=" + sample.recentConsumptionKwhPer100Km
                        + ";speed_kmh=" + sample.speedKmh + ";status=" + sample.telemetryStatus);
                // Optional learning cannot invalidate the real SOC/Wh publication.
                try { BydVehicleModel.observe(sample, current); }
                catch (Throwable error) { BydVehicleModel.unavailable("learning_error"); event("learning_error", BydEnergyReader.describe(error)); }
            }
        } catch (Throwable error) {
            available = null; publishedModel = null; BydNativeGuidanceUi.clear("sdk_error");
            publish(null); event("error", BydEnergyReader.describe(error));
            BydEnergyComparison.unavailable("sdk_error");
            BydVehicleModel.unavailable("sdk_error");
            if (++failures >= 20) { enabled = false; event("disabled", "20_failures"); }
        }
        syncTrackers();
        routine("tracker_state", "tracker_state", "guided=" + guided + ";native_selected=" + nativeSelected
                + ";fresh_sdk=" + (freshAvailable() != null) + ";count=" + trackers.size());
        if (enabled) logUiGates(false);
        if (enabled && pending.compareAndSet(false, true))
            worker.postDelayed(BydNativeBatteryPatch::poll, POLL_MS);
    }

    private static void publish(Values values) {
        Iterator<Provider> iterator = providers.iterator();
        int lost = 0;
        while (iterator.hasNext()) {
            Provider provider = iterator.next(); Object target = provider.target.get();
            if (target == null) { iterator.remove(); lost++; continue; }
            if (active()) provider.retained = target;
            publishProvider(provider, values);
        }
        routine("provider_state", "provider_state", providerState(lost));
    }
    private static String providerState(int lost) {
        return "alive=" + providers.size() + ";lost=" + lost + ";active=" + active()
                + ";fresh_sdk=" + (freshAvailable() != null);
    }
    private static boolean publishProvider(Provider provider, Values values) {
        Object target = provider.target.get();
        if (target == null) return false;
        try {
            if (values != null) BUILD.set(values);
            provider.update.invoke(target, values == null ? -1f : (float) values.soc);
            Object message = provider.model.get(target);
            if (values != null && (message == null || ownedValues(message) != values))
                throw new IllegalStateException("factory did not build current sample");
            // A successful seed also needs the owned envelope clone used by initial fallback.
            return values == null || freshValues(publishedModel) == values;
        } catch (Throwable error) { postEvent("publish_error", BydEnergyReader.describe(error)); return false; }
        finally { BUILD.remove(); }
    }
    private static boolean active() {
        return visible || (guided && SystemClock.elapsedRealtime() - progressAt < GUIDANCE_IDLE_MS);
    }
    private static Values ownedValues(Object message) {
        if (message == null) return null;
        synchronized (owned) {
            for (Owned entry : owned) if (entry.model.get() == message) return entry.values;
        }
        return null;
    }

    private static void forecast(Object route, long callbackAt) {
        try {
            Object legs = field(route, forecastSchema[0]);
            if (legs == null) {
                event("forecast", "legs_missing"); BydEnergyComparison.invalidForecast(); return;
            }
            int count = ((Number) legs.getClass().getMethod(forecastSchema[1]).invoke(legs)).intValue();
            if (count < 0 || count > 1000) throw new IllegalArgumentException("leg count");
            Method at = legs.getClass().getMethod(forecastSchema[2], int.class);
            int segments = 0; long meters = 0, wh = 0;
            long[] comparisonSegments = BydEnergyComparison.newSegments();
            for (int index = 0; index < count; index++) {
                Object energy = field(at.invoke(legs, index), forecastSchema[3]);
                if (energy == null) continue;
                Object entries = field(energy, forecastSchema[4]);
                if (!(entries instanceof Iterable)) continue;
                for (Object entry : (Iterable<?>) entries) {
                    if (++segments > 50000) throw new IllegalArgumentException("segment count");
                    long beforeMeters = meters, beforeWh = wh;
                    meters += ((Number) field(entry, forecastSchema[5])).longValue();
                    wh += ((Number) field(entry, forecastSchema[6])).longValue();
                    comparisonSegments = BydEnergyComparison.segment(comparisonSegments, segments - 1,
                            meters - beforeMeters, wh - beforeWh);
                }
            }
            event("forecast", "legs=" + count + ";segments=" + segments + ";meters=" + meters + ";energy_wh=" + wh);
            BydEnergyComparison.forecast(callbackAt, System.identityHashCode(route), count, meters, wh,
                    BydEnergyComparison.exact(comparisonSegments, segments), segments, nativeSelected);
        } catch (Throwable error) {
            event("forecast_error", BydEnergyReader.describe(error)); BydEnergyComparison.invalidForecast();
        }
    }
    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        ConcurrentHashMap<String, Field> byName = fields.get(type);
        if (byName == null) {
            byName = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Field> raced = fields.putIfAbsent(type, byName);
            if (raced != null) byName = raced;
        }
        Field field = byName.get(name);
        if (field == null) { field = type.getField(name); byName.put(name, field); }
        return field.get(target);
    }

    /** Worker only: coalesce one row stream by key, preserving changes and a heartbeat count. */
    private static void routine(String key, String kind, String note) {
        long now = SystemClock.elapsedRealtime();
        String signature = ROUTINE_VOLATILE.matcher(";" + note).replaceAll("");
        Routine last = routines.get(key);
        if (last != null && last.signature.equals(signature) && now - last.at >= 0 && now - last.at < ROUTINE_HEARTBEAT_MS) {
            last.suppressed++; return;
        }
        int suppressed = last == null ? 0 : last.suppressed;
        routines.put(key, new Routine(signature, now));
        event(kind, suppressed > 0 ? note + ";suppressed=" + suppressed : note);
    }

    /** Worker only. Never logs coordinates, VIN, accounts, or full request protobufs. */
    private static void event(String kind, String note) {
        String safe = note.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
        Log.i(TAG, kind + " " + safe);
        long wall = System.currentTimeMillis();
        // Rows from one worker turn share one append, and each stays in its own day's file.
        if (wall >= logDayEndsAt) { flushLog(); openLogDay(wall); }
        logBuffer.append(wall).append(',').append(SystemClock.elapsedRealtime()).append(',').append(kind)
                .append(",\"").append(safe).append("\"\n");
        if (logBuffer.length() >= 65536) flushLog();
        else if (!logFlushPosted && worker != null) { logFlushPosted = true; worker.post(flushLog); }
    }
    private static void openLogDay(long wall) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(wall);
        logDay = String.format(Locale.US, "%04d%02d%02d", day.get(Calendar.YEAR),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        day.add(Calendar.DAY_OF_MONTH, 1);
        logDayEndsAt = day.getTimeInMillis();
        logFile = null; logLength = -1;
    }
    /** Worker only: same daily file, header and 10 MB stop rule, checked once per batch. */
    private static void flushLog() {
        logFlushPosted = false;
        if (logBuffer.length() == 0) return;
        String rows = logBuffer.toString();
        logBuffer.setLength(0);
        try {
            if (logDirectory == null) {
                long now = SystemClock.elapsedRealtime();
                if (now < logDirectoryRetryAt) return;
                logDirectory = app.getExternalFilesDir("byd-native-battery");
                if (logDirectory == null) { logDirectoryRetryAt = now + 60000; return; }
            }
            if (logFile == null) {
                logFile = new File(logDirectory, "native-" + logDay + ".csv");
                logLength = logFile.exists() ? logFile.length() : -1;
            }
            if (logLength > LOG_LIMIT_BYTES) return;
            byte[] bytes = (logLength < 0 ? "wall_ms,elapsed_ms,kind,note\n" + rows : rows).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream stream = new FileOutputStream(logFile, true)) { stream.write(bytes); }
            logLength = Math.max(0, logLength) + bytes.length;
        } catch (Throwable error) { logFile = null; Log.w(TAG, "log write failed", error); }
    }
    private static boolean isMaps(Activity a) { return "com.google.android.maps.MapsActivity".equals(a.getClass().getName()); }
    private static boolean isByd() { return startsByd(Build.MANUFACTURER) || startsByd(Build.BRAND) || startsByd(Build.MODEL); }
    private static boolean startsByd(String s) { return s != null && s.regionMatches(true, 0, "BYD", 0, 3); }
    private static final class Provider {
        final WeakReference<Object> target; final Method update; final Field model;
        // Maps can retain only this provider's stream. Hold the factory while active,
        // then drop this reference after clearing the model on an inactive poll.
        Object retained;
        Provider(Object target, Method update, Field model) {
            this.target = new WeakReference<>(target); this.retained = target; this.update = update; this.model = model;
        }
    }
    private static final class Owned {
        final WeakReference<Object> model; final Values values;
        Owned(Object model, Values values) { this.model = new WeakReference<>(model); this.values = values; }
    }
    private static final class Preview {
        final WeakReference<Object> target;
        // Keep the provider alive while its preview exists: Maps retains only its stream.
        final Object provider;
        volatile long lastSample;
        Preview(Object target, Object provider) {
            this.target = new WeakReference<>(target); this.provider = provider;
        }
    }
    private static final class Tracker {
        final WeakReference<Object> target;
        final Executor executor;
        final AtomicBoolean pending = new AtomicBoolean();
        boolean inhibited;
        int lastWh = -1;
        Tracker(Object target, Executor executor) { this.target = new WeakReference<>(target); this.executor = executor; }
    }
    private static final class Values {
        // SDK timestamps remain fixed for this publication, including Settings-only copies.
        final long elapsed; final double soc; final int energyWh, capacityWh; final String basis;
        final BydVehicleModel.Model model;
        final long confirmed;
        Values(long elapsed, long confirmed, double soc, int energyWh, int capacityWh, String basis,
               BydVehicleModel.Model model) {
            this.elapsed = elapsed; this.confirmed = confirmed; this.soc = soc; this.energyWh = energyWh;
            this.capacityWh = capacityWh; this.basis = basis; this.model = model;
        }
        String describe() { return "soc=" + soc + ";energy_wh=" + energyWh + ";capacity_wh=" + capacityWh + ";capacity_basis=" + basis + ";not_SOH;" + model.describe(); }
    }
    private static final class Routine {
        final String signature; final long at;
        int suppressed;
        Routine(String signature, long at) { this.signature = signature; this.at = at; }
    }
}
