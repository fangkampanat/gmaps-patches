package app.morphe.extension.maps.patches;

import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Read-only dev.7 probes; captures existing calculations without invoking UI getters again. */
public final class BydNativeBatteryDiagnostics {
    private static final String PREFIX = "[DEBUG-native-dev22]";
    // Identical rows (ignoring per-call ids/timing) keep a first row, changes and a 30 s heartbeat.
    private static final long HEARTBEAT_MS = 30000;
    private static final Pattern VOLATILE = Pattern.compile(";(?:call|parent|start_elapsed_ms|start_wall_ms|duration_ms"
            + "|sdk_age_ms|model_age_ms|content_age_ms|model|current|previous"
            + "|(?:dynamic_|cached_|source_)?(?:arrival|display|info|ui))=[^;]*");
    private static final Pattern PERCENT_TOKEN = Pattern.compile("(?<![0-9])[0-9]{1,3}\\s*[%％]");
    private static final Pattern PERCENT_ONLY = Pattern.compile("\\s*-?[0-9]{1,3}\\s*[%％]\\s*");
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> fields = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> methods = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> indexMethods = new ConcurrentHashMap<>();
    private static final AtomicLong sequence = new AtomicLong();
    private static final ThreadLocal<Frame> arrival = new ThreadLocal<>();
    private static final ThreadLocal<Frame> calculation = new ThreadLocal<>();
    private static final ThreadLocal<Frame> leg = new ThreadLocal<>();
    private static final ThreadLocal<Frame> legacy = new ThreadLocal<>();
    private static final ThreadLocal<Frame> alternative = new ThreadLocal<>();
    private static final ThreadLocal<Frame> previewVisibility = new ThreadLocal<>();
    private static final ThreadLocal<Frame> phoneLeg = new ThreadLocal<>();
    private static final ThreadLocal<Frame> phoneText = new ThreadLocal<>();
    private static final ThreadLocal<Frame> phoneCompactText = new ThreadLocal<>();
    private static final ThreadLocal<Frame> phonePreviewText = new ThreadLocal<>();
    private static final Map<String, Stamp> seen = new LinkedHashMap<>();
    private static volatile String[][] schema;
    private BydNativeBatteryDiagnostics() { }

    /** Member names and presence-bit layouts are resolved and guarded against the inspected DEX. */
    public static void configure(String encoded) {
        try {
            String[] groups = encoded.split(";", -1);
            int[] counts = {12, 6, 6, 5, 3, 4, 3};
            if (groups.length != counts.length) throw new IllegalArgumentException("group count");
            String[][] resolved = new String[groups.length][];
            for (int i = 0; i < groups.length; i++) {
                resolved[i] = groups[i].split(",", -1);
                if (resolved[i].length != counts[i]) throw new IllegalArgumentException("member count");
                for (int j = 0; j < resolved[i].length; j++)
                    if (!resolved[i][j].matches(i == 6 && j != 1
                            ? "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"
                            : "[A-Za-z_$][A-Za-z0-9_$]*"))
                        throw new IllegalArgumentException("member name");
            }
            schema = resolved;
        } catch (Throwable error) {
            schema = null;
            Log.w("BydNativeBattery", PREFIX + " schema disabled: " + error.getClass().getSimpleName());
        }
    }

    public static void onArrivalInput(Object route, int index, Object model) {
        if (!active()) return;
        try {
            Frame frame = new Frame("route=" + id(route) + ";index=" + index + ";" + routeState(route, index)
                    + ";model=" + id(model) + ";" + modelState(model)
                    + ";" + BydNativeBatteryPatch.diagnosticModelState(model));
            arrival.set(frame); emit("arrival_input", frame.describe(), false);
        } catch (Throwable error) { arrival.remove(); failure("arrival_input", error); }
    }
    public static void onArrivalResult(Object result) {
        Frame frame = arrival.get(); arrival.remove();
        if (!active()) return;
        try { emit("arrival_result", completed(frame) + ";" + arrivalState(result), false); }
        catch (Throwable error) { failure("arrival_result", error); }
    }
    public static void onCalculationInput(Object route, int currentWh, int capacityWh,
                                          boolean inaccurate, Object minimumSoc) {
        if (!active()) return;
        try {
            Frame parent = arrival.get();
            Frame frame = new Frame("parent=" + (parent == null ? "none" : parent.sequence)
                    + ";route=" + id(route) + ";current_wh=" + currentWh + ";capacity_wh=" + capacityWh
                    + ";inaccurate=" + inaccurate + ";minimum_soc=" + scalar(minimumSoc)
                    + ";" + routeState(route, -1));
            calculation.set(frame); emit("calculation_input", frame.describe(), false);
        } catch (Throwable error) { calculation.remove(); failure("calculation_input", error); }
    }
    public static void onCalculationResult(Object result) {
        Frame frame = calculation.get(); calculation.remove();
        if (!active()) return;
        try { emit("calculation_result", completed(frame) + ";" + arrivalState(result), false); }
        catch (Throwable error) { failure("calculation_result", error); }
    }
    public static void onLegInput(Object route, int index, boolean inaccurate, Object minimumSoc) {
        if (!active()) return;
        try {
            Frame parent = arrival.get();
            String family = "car_arrival";
            if (parent == null) { parent = phoneLeg.get(); family = "phone_leg"; }
            Frame frame = new Frame("parent=" + (parent == null ? "none" : parent.sequence)
                    + ";parent_family=" + (parent == null ? "none" : family)
                    + ";route=" + id(route) + ";index=" + index + ";inaccurate=" + inaccurate
                    + ";minimum_soc=" + scalar(minimumSoc) + ";" + routeState(route, index));
            leg.set(frame); emit("leg_input", frame.describe(), false);
        } catch (Throwable error) { leg.remove(); failure("leg_input", error); }
    }
    public static void onLegResult(Object result) {
        Frame frame = leg.get(); leg.remove();
        if (!active()) return;
        try { emit("leg_result", completed(frame) + ";" + arrivalState(result), false); }
        catch (Throwable error) { failure("leg_result", error); }
    }
    public static void onLegacySelectionInput(Object owner) {
        if (!active()) return;
        try {
            String[] f = schema[3]; Object stop = read(owner, f[0]);
            legacy.set(new Frame("owner=" + id(owner) + ";owner_type=" + type(owner)
                    + ";use_dynamic=" + scalar(read(owner, f[3]))
                    + ";dynamic_" + arrivalState(read(stop, f[1]))
                    + ";cached_" + arrivalState(read(owner, f[2]))));
        } catch (Throwable error) { legacy.remove(); failure("legacy_selection_input", error); }
    }
    public static void onLegacySelectionResult(Object display) {
        Frame frame = legacy.get(); legacy.remove();
        if (!active()) return;
        try { emit("legacy_selection", completed(frame) + ";" + displayState(display), true); }
        catch (Throwable error) { failure("legacy_selection", error); }
    }
    public static void onLegacyUi(Object state) {
        if (!active()) return;
        try { emit("legacy_ui", "ui=" + id(state) + ";visible=" + scalar(read(state, schema[3][4])), true); }
        catch (Throwable error) { failure("legacy_ui", error); }
    }
    public static void onAlternativeUiInput(Object owner) {
        if (!active()) return;
        try { alternative.set(new Frame("owner=" + id(owner) + ";owner_type=" + type(owner)
                + ";capability=" + scalar(read(owner, schema[4][0])))); }
        catch (Throwable error) { alternative.remove(); failure("alternative_input", error); }
    }
    /** The hook passes the result of the original delegated getter; it is never invoked a second time. */
    public static void onAlternativeUiState(Object state) {
        Frame frame = alternative.get();
        if (!active() || frame == null) return;
        try { frame.extra = ";" + arrivalState(read(read(state, schema[4][2]), schema[3][1])); }
        catch (Throwable error) { failure("alternative_state", error); }
    }
    public static void onAlternativeUiResult(boolean visible) {
        try {
            Frame frame = alternative.get(); alternative.remove();
            if (active()) emit("alternative_ui", completed(frame) + ";visible=" + visible, true);
        } catch (Throwable error) { alternative.remove(); failure("alternative_ui", error); }
    }
    public static void onDisplayInfo(Object sourceArrival, Object wrapper) {
        if (!active()) return;
        try { emit("display_info", "source_" + arrivalState(sourceArrival) + ";" + displayState(wrapper), true); }
        catch (Throwable error) { failure("display_info", error); }
    }
    public static void onPreviewArrival(Object result) {
        if (!active()) return;
        try { emit("preview_arrival", arrivalState(result), true); }
        catch (Throwable error) { failure("preview_arrival", error); }
    }
    public static void onPreviewInfo(Object info) {
        if (!active()) return;
        try { emit("preview_info", infoState(info), true); }
        catch (Throwable error) { failure("preview_info", error); }
    }
    public static void onPreviewVisibilityInput(Object owner) {
        try { if (active()) previewVisibility.set(new Frame("owner=" + id(owner) + ";owner_type=" + type(owner))); }
        catch (Throwable error) { previewVisibility.remove(); failure("preview_visibility_input", error); }
    }
    public static void onPreviewLayoutRestricted(boolean restricted) {
        try {
            Frame frame = previewVisibility.get();
            if (active() && frame != null) frame.extra += ";layout_restricted=" + restricted;
        } catch (Throwable error) { failure("preview_layout", error); }
    }
    public static void onPreviewSuppression(boolean suppressed) {
        try {
            Frame frame = previewVisibility.get();
            if (active() && frame != null) frame.extra += ";suppressed=" + suppressed;
        } catch (Throwable error) { failure("preview_suppression", error); }
    }
    public static void onPreviewVisibilityResult(boolean visible) {
        try {
            Frame frame = previewVisibility.get(); previewVisibility.remove();
            if (active()) emit("preview_visibility", completed(frame) + ";visible=" + visible, true);
        } catch (Throwable error) { previewVisibility.remove(); failure("preview_visibility", error); }
    }
    public static void onFuelText(Object text) {
        if (!active()) return;
        try {
            int length = text instanceof CharSequence ? ((CharSequence) text).length() : -1;
            emit("fuel_text_binding", "category=fuel_efficiency;has_text=" + (length > 0)
                    + ";text_length=" + length, true);
        } catch (Throwable error) { failure("fuel_text_binding", error); }
    }
    public static void onBatteryTextInfo(Object info) {
        if (!active()) return;
        try { emit("battery_text_binding", "category=battery_arrival;" + infoState(info), true); }
        catch (Throwable error) { failure("battery_text_binding", error); }
    }

    /** The ordinary Maps screen uses a separate selector/formatter from gmm/car. */
    public static void onPhoneLegInput(Object route, int oneBasedDestination) {
        if (!active()) return;
        try {
            Frame frame = new Frame("family=phone;route=" + id(route) + ";one_based_destination=" + oneBasedDestination
                    + ";" + routeState(route, oneBasedDestination - 1));
            phoneLeg.set(frame); emit("phone_leg_input", frame.describe(), false);
        } catch (Throwable error) { phoneLeg.remove(); failure("phone_leg_input", error); }
    }
    public static void onPhoneLegResult(Object result) {
        Frame frame = phoneLeg.get(); phoneLeg.remove();
        if (!active()) return;
        try { emit("phone_leg_result", completed(frame) + ";" + arrivalState(result), false); }
        catch (Throwable error) { failure("phone_leg_result", error); }
    }
    public static void onPhoneTextInput(Object sourceArrival) {
        if (!active()) return;
        try { phoneText.set(new Frame("family=phone;format=full;" + arrivalState(sourceArrival))); }
        catch (Throwable error) { phoneText.remove(); failure("phone_text_input", error); }
    }
    public static void onPhoneTextResult(Object text) {
        Frame frame = phoneText.get(); phoneText.remove();
        if (!active()) return;
        try { emit("phone_text", completed(frame) + ";" + textState(text), true); }
        catch (Throwable error) { failure("phone_text_result", error); }
    }
    public static void onPhoneCompactTextInput(Object sourceArrival, boolean includeLabel) {
        if (!active()) return;
        try { phoneCompactText.set(new Frame("family=phone;format=compact;include_label=" + includeLabel
                + ";" + arrivalState(sourceArrival))); }
        catch (Throwable error) { phoneCompactText.remove(); failure("phone_compact_text_input", error); }
    }
    public static void onPhoneCompactTextResult(Object text) {
        Frame frame = phoneCompactText.get(); phoneCompactText.remove();
        if (!active()) return;
        try { emit("phone_compact_text", completed(frame) + ";" + textState(text), true); }
        catch (Throwable error) { failure("phone_compact_text_result", error); }
    }
    public static void onPhonePreviewTextInput(Object viewModel) {
        if (!active()) return;
        try { phonePreviewText.set(new Frame("family=phone;preview=" + id(viewModel) + ";preview_type=" + type(viewModel))); }
        catch (Throwable error) { phonePreviewText.remove(); failure("phone_preview_text_input", error); }
    }
    public static void onPhonePreviewEnabled(boolean enabled) { phonePreviewFlag("arrival_enabled", enabled); }
    public static void onPhonePreviewInitial(boolean initial) { phonePreviewFlag("initial_stop", initial); }
    public static void onPhonePreviewConsumption(boolean consumption) { phonePreviewFlag("consumption_mode", consumption); }
    private static void phonePreviewFlag(String name, boolean value) {
        try {
            Frame frame = phonePreviewText.get();
            if (active() && frame != null) frame.extra += ";" + name + "=" + value;
        } catch (Throwable error) { failure("phone_preview_flag", error); }
    }
    public static void onPhonePreviewTextResult(Object text) {
        Frame frame = phonePreviewText.get(); phonePreviewText.remove();
        if (!active()) return;
        try { emit("phone_preview_text", completed(frame) + ";" + textState(text), true); }
        catch (Throwable error) { failure("phone_preview_text_result", error); }
    }
    /** Observe the actual phone guidance state callback without querying the viewmodel again. */
    public static void onPhoneGuidanceState(Object owner, Object current, Object previous, boolean originalFlag) {
        if (!active()) return;
        try { emit("phone_guidance_state", "family=phone;owner=" + id(owner) + ";owner_type=" + type(owner)
                + ";current=" + id(current) + ";current_type=" + type(current)
                + ";previous=" + id(previous) + ";previous_type=" + type(previous)
                + ";original_flag=" + originalFlag, true); }
        catch (Throwable error) { failure("phone_guidance_state", error); }
    }
    /** Captures only a percent token and text presence, never the full formatted label. */
    private static String textState(Object text) {
        int length = text instanceof CharSequence ? ((CharSequence) text).length() : -1;
        String percent = "absent";
        if (length >= 0) {
            java.util.regex.Matcher token = PERCENT_TOKEN.matcher(text.toString());
            if (token.find()) percent = token.group().trim();
        }
        return "has_text=" + (length > 0) + ";text_length=" + length + ";percent_text=" + percent;
    }

    static void request(String stage, Object model, boolean allowed) {
        request(stage, model, allowed, null);
    }
    static void request(String stage, Object model, boolean allowed, String queryState) {
        if (!active()) return;
        try { emit("request", "stage=" + stage + ";attach_allowed=" + allowed
                + (queryState == null ? "" : ";provider_clone_state=" + queryState) + ";model=" + id(model)
                + ";" + modelState(model) + ";" + BydNativeBatteryPatch.diagnosticModelState(model), false); }
        catch (Throwable error) { failure("request", error); }
    }
    static void start() {
        if (BydNativeBatteryPatch.diagnosticsEnabled())
            emit("init", "schema_ready=" + (schema != null) + ";version=1.3.1-dev.22;read_only=true", false);
    }
    static void publication(Object model) {
        if (!active()) return;
        try { emit("publication", "model=" + id(model) + ";" + modelState(model)
                + ";" + BydNativeBatteryPatch.diagnosticModelState(model), false); }
        catch (Throwable error) { failure("publication", error); }
    }
    static void guidance(Object route) {
        if (!active()) return;
        try {
            String note = "guidance=" + id(route) + ";guidance_type=" + type(route);
            // Guidance carries the wrapper, not the canonical route used by arrival converters.
            // Never dispatch a same-named method on that wrapper (its t() is a lazy constructor).
            if (!hasType(route, schema[6][0])) {
                emit("guidance_route", note + ";guidance_type_supported=false", true);
                return;
            }
            Object canonical = read(route, schema[6][1]);
            emit("guidance_route", note + ";guidance_type_supported=true;route=" + id(canonical)
                    + ";" + routeState(canonical, 0), true);
        }
        catch (Throwable error) { failure("guidance_route", error); }
    }
    private static boolean active() { return schema != null && BydNativeBatteryPatch.diagnosticsEnabled(); }

    private static String modelState(Object model) throws Exception {
        String[] f = schema[1]; Object battery = read(model, f[0]);
        Object current = read(battery, f[2]), capacity = read(battery, f[3]);
        return "has_model=" + (model != null) + ";battery_mask=" + scalar(read(battery, f[1]))
                + ";current_mask=" + scalar(read(current, f[4])) + ";capacity_mask=" + scalar(read(capacity, f[4]))
                + ";current_wh=" + scalar(read(current, f[5])) + ";capacity_wh=" + scalar(read(capacity, f[5]));
    }
    private static String routeState(Object route, int index) throws Exception {
        String[] f = schema[0]; if (route == null) return "route_missing=true";
        String note = "route_type=" + type(route);
        if (!type(route).equals(schema[6][2])) return note + ";route_type_supported=false";
        note += ";route_type_supported=true";
        int count = -1;
        try {
            count = number(method(route.getClass(), f[0]).invoke(route));
            note += ";legs=" + count;
        } catch (Throwable error) { note += ";leg_count_error=" + errorTypes(error); }
        if (index >= 0 && index < count && count <= 1000) {
            try {
                Object leg = indexMethod(route.getClass(), f[1]).invoke(route, index);
                Object battery = read(leg, f[2]);
                note += ";leg_mask=" + scalar(read(battery, f[3])) + ";leg_current_wh=" + scalar(read(battery, f[4]))
                        + ";leg_capacity_wh=" + scalar(read(battery, f[5])) + ";leg_negative_wh=" + scalar(read(battery, f[6]));
            } catch (Throwable error) { note += ";leg_snapshot_error=" + errorTypes(error); }
        } else note += ";leg_inspected=false";
        try {
            Object summary = method(route.getClass(), f[7]).invoke(route), energy = read(summary, f[9]);
            note += ";summary_mask=" + scalar(read(summary, f[8])) + ";energy_mask=" + scalar(read(energy, f[10]))
                    + ";summary_energy_wh=" + scalar(read(energy, f[11]));
        } catch (Throwable error) { note += ";summary_snapshot_error=" + errorTypes(error); }
        return note;
    }
    private static String arrivalState(Object result) throws Exception {
        String[] f = schema[2]; Object level = read(result, f[1]);
        return "arrival=" + id(result) + ";state=" + scalar(read(result, f[0]))
                + ";inaccurate=" + scalar(read(result, f[2])) + ";arrival_wh=" + scalar(read(level, f[3]))
                + ";arrival_capacity_wh=" + scalar(read(level, f[4])) + ";arrival_percent=" + scalar(read(level, f[5]));
    }
    private static String displayState(Object wrapper) throws Exception {
        return "display=" + id(wrapper) + ";" + infoState(read(wrapper, schema[5][0]));
    }
    private static String infoState(Object info) throws Exception {
        String[] f = schema[5]; Object text = read(info, f[2]); String percent = "absent";
        if (text instanceof CharSequence) {
            String value = text.toString();
            percent = PERCENT_ONLY.matcher(value).matches() ? value.trim() : "non_percent_text";
        }
        return "info=" + id(info) + ";info_state=" + scalar(read(info, f[1]))
                + ";percent_text=" + percent + ";info_inaccurate=" + scalar(read(info, f[3]));
    }
    private static Object read(Object target, String name) throws Exception {
        if (target == null) return null;
        Class<?> type = target.getClass();
        ConcurrentHashMap<String, Field> byName = members(fields, type);
        Field field = byName.get(name);
        if (field == null) {
            try { field = type.getField(name); }
            catch (NoSuchFieldException error) { field = type.getDeclaredField(name); field.setAccessible(true); }
            byName.put(name, field);
        }
        return field.get(target);
    }
    private static Method method(Class<?> type, String name) throws Exception {
        ConcurrentHashMap<String, Method> byName = members(methods, type);
        Method found = byName.get(name);
        if (found == null) { found = type.getMethod(name); byName.put(name, found); }
        return found;
    }
    private static Method indexMethod(Class<?> type, String name) throws Exception {
        ConcurrentHashMap<String, Method> byName = members(indexMethods, type);
        Method found = byName.get(name);
        if (found == null) { found = type.getMethod(name, int.class); byName.put(name, found); }
        return found;
    }
    private static <T> ConcurrentHashMap<String, T> members(ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, T>> cache,
                                                           Class<?> type) {
        ConcurrentHashMap<String, T> byName = cache.get(type);
        if (byName != null) return byName;
        byName = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, T> raced = cache.putIfAbsent(type, byName);
        return raced == null ? byName : raced;
    }
    private static int number(Object value) { return value instanceof Number ? ((Number) value).intValue() : -1; }
    private static String id(Object object) { return object == null ? "null" : Integer.toHexString(System.identityHashCode(object)); }
    private static String type(Object object) { return object == null ? "null" : object.getClass().getName(); }
    private static boolean hasType(Object object, String expected) {
        Class<?> current = object == null ? null : object.getClass();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getSuperclass())
            if (current.getName().equals(expected)) return true;
        return false;
    }
    private static String scalar(Object value) {
        if (value == null) return "missing";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Enum<?>) return ((Enum<?>) value).name();
        return "unsupported_type";
    }
    private static String completed(Frame frame) {
        return frame == null ? "call=missing" : frame.describe() + ";duration_ms=" + (SystemClock.elapsedRealtime() - frame.elapsed);
    }
    private static void failure(String stage, Throwable error) {
        if (active()) emit("error", "stage=" + stage + ";types=" + errorTypes(error), true);
    }
    /** Types only: Throwable messages and route toString can contain private route data. */
    private static String errorTypes(Throwable error) {
        StringBuilder types = new StringBuilder();
        for (int depth = 0; error != null && depth < 4; depth++) {
            if (depth > 0) types.append('>');
            types.append(error.getClass().getSimpleName());
            Throwable cause = error.getCause();
            if (cause == error) break;
            error = cause;
        }
        return types.toString();
    }
    private static void emit(String kind, String note, boolean coalesce) {
        try { emitSnapshot(kind, note, coalesce); }
        catch (Throwable ignored) { /* A diagnostic failure must never change Maps' return path. */ }
    }
    /** Every row type coalesces; the former per-call rows (coalesce=false) repeated unchanged each second. */
    private static void emitSnapshot(String kind, String note, boolean coalesce) {
        long elapsed = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        String context = BydNativeBatteryPatch.diagnosticContext();
        String signature = VOLATILE.matcher(";" + context + ";" + note).replaceAll("");
        // A shared key preserves fuel -> battery -> fuel transitions; request stages coalesce separately.
        String key = kind.endsWith("_text_binding") ? "text_binding" : kind;
        if (note.startsWith("stage=")) {
            int end = note.indexOf(';');
            key += ":" + (end < 0 ? note : note.substring(0, end));
        }
        int suppressed;
        synchronized (seen) {
            Stamp previous = seen.get(key);
            if (previous != null && previous.signature.equals(signature) && elapsed - previous.elapsed >= 0
                    && elapsed - previous.elapsed < HEARTBEAT_MS) { previous.suppressed++; return; }
            suppressed = previous == null ? 0 : previous.suppressed;
            seen.remove(key);
            seen.put(key, new Stamp(signature, elapsed));
            if (seen.size() > 64) seen.remove(seen.keySet().iterator().next());
        }
        BydNativeBatteryPatch.diagnosticEvent("diag22_" + kind, PREFIX + ";call_elapsed_ms=" + elapsed
                + ";call_wall_ms=" + wall + ";thread_id=" + Thread.currentThread().getId()
                + ";" + context + ";" + note + (suppressed > 0 ? ";suppressed=" + suppressed : ""));
    }
    private static final class Frame {
        final long sequence = BydNativeBatteryDiagnostics.sequence.incrementAndGet();
        final long elapsed = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        final String input; String extra = "";
        Frame(String input) { this.input = input; }
        String describe() { return "call=" + sequence + ";start_elapsed_ms=" + elapsed + ";start_wall_ms=" + wall + ";" + input + extra; }
    }
    private static final class Stamp {
        final String signature; final long elapsed;
        int suppressed;
        Stamp(String signature, long elapsed) { this.signature = signature; this.elapsed = elapsed; }
    }
}
