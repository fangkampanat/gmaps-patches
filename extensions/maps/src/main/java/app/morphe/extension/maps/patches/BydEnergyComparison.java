package app.morphe.extension.maps.patches;

import android.app.Application;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

/** Worker-only observations. Nothing in this class is returned to the vehicle model or UI. */
final class BydEnergyComparison {
    private static final String TAG = "MorpheBydEnergy";
    private static final long MAX_SKEW_MS = 15000, MAX_FORECAST_AGE_MS = 30000;
    private static final long PART_BYTES = 2000000, SESSION_BYTES = 10000000, TOTAL_BYTES = 30000000;
    private static State state;

    private BydEnergyComparison() { }

    static void start(Application app, float nominalWh, float roadA, float roadB, float roadC) {
        try {
            state = new State(app);
            state.emit("start", "schema=1;build=dev18;comparison=provisional_remaining_forecast;"
                    + "stable_road_ids=unavailable;calibration_ready=false;bms_quantization_wh=100;"
                    + "odometer_quantization_m=100;max_sdk_skew_ms=" + MAX_SKEW_MS
                    + ";max_forecast_age_ms=" + MAX_FORECAST_AGE_MS + ";epoch_basis=diagnostic_inferred"
                    + ";forecast_values_read=worker;timestamp_basis=route_callback"
                    + ";nominal_wh=" + nominalWh + ";road_a=" + roadA + ";road_b=" + roadB + ";road_c=" + roadC);
        } catch (Throwable error) { failed(error); }
    }

    static void sample(BydEnergyReader.Sample sample, int energyWh, int capacityWh, String basis) {
        try { if (state != null) state.sample(new Sample(sample, energyWh, capacityWh, basis)); }
        catch (Throwable error) { failed(error); }
    }

    static void unavailable(String reason) {
        try {
            if (state == null) return;
            state.samples.clear(); state.lastSample = null; state.reset(reason);
        } catch (Throwable error) { failed(error); }
    }

    static void selection(boolean selected) {
        try { if (state != null && !selected) state.reset("native_deselected"); }
        catch (Throwable error) { failed(error); }
    }

    /** Returns this guided trip's summary row, or null when fewer than two paired forecasts were seen. */
    static String inactive() {
        try {
            if (state == null || !state.guided) return null;
            if (state.anchor != null && state.previous != null && state.anchor != state.previous)
                state.interval(state.previous, "last_observed_before_inactive");
            state.reset("navigation_inactive"); state.guided = false;
            return state.trip();
        } catch (Throwable error) { failed(error); return null; }
    }

    /** Pairs are the original segment length/Wh fields; no route identifiers or coordinates. */
    static void forecast(long callbackAt, int routeRef, int legs, long meters, long wh,
                         long[] pairs, int segments, boolean selected) {
        try {
            if (state != null) state.forecast(callbackAt, routeRef, legs, meters, wh, pairs, segments, selected);
        } catch (Throwable error) { failed(error); }
    }

    static void invalidForecast() {
        try { if (state != null) state.reset("forecast_unavailable"); }
        catch (Throwable error) { failed(error); }
    }

    // Same 50,000-segment ceiling as before, grown on demand instead of allocated whole per forecast.
    private static final int MAX_PAIR_VALUES = 100000;

    static long[] newSegments() {
        try { return state == null ? null : new long[1024]; }
        catch (Throwable error) { failed(error); return null; }
    }

    /** Returns the array holding the pair, which may be a grown copy of the argument. */
    static long[] segment(long[] pairs, int index, long meters, long wh) {
        if (pairs == null || index < 0 || index >= MAX_PAIR_VALUES / 2) return pairs;
        try {
            if (index * 2 + 1 >= pairs.length)
                pairs = Arrays.copyOf(pairs, Math.min(MAX_PAIR_VALUES, Math.max(pairs.length * 2, index * 2 + 2)));
            pairs[index * 2] = meters; pairs[index * 2 + 1] = wh;
        } catch (Throwable error) { failed(error); }
        return pairs;
    }

    /** Retained forecast points keep only their own segment pairs. */
    static long[] exact(long[] pairs, int segments) {
        if (pairs == null || segments < 0 || segments * 2 > pairs.length) return pairs;
        try { return segments * 2 == pairs.length ? pairs : Arrays.copyOf(pairs, segments * 2); }
        catch (Throwable error) { failed(error); return null; }
    }

    private static void failed(Throwable error) {
        // Diagnostics must never alter a poll, model publication, tracker update, or route callback.
        try { Log.w(TAG, "comparison disabled for this observation: " + error.getClass().getSimpleName()); }
        catch (Throwable ignored) { }
    }

    private static final class State {
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        final String session = System.currentTimeMillis() + "-" + Process.myPid() + "-" + SystemClock.elapsedRealtime();
        final File directory;
        Sample lastSample;
        Point anchor, previous;
        // Whole guided trip; unlike anchor/previous it survives forecast revisions and resets.
        Point tripStart, tripLast;
        int tripReroutes;
        long epoch, written, retained;
        int part;
        boolean guided, stopped;
        File output;

        State(Application app) {
            File parent = app.getExternalFilesDir("byd-native-battery");
            directory = parent == null ? null : new File(parent, "energy-comparison");
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
                stopped = true; Log.w(TAG, "comparison storage unavailable"); return;
            }
            File[] files = directory.listFiles();
            if (files == null || files.length > 1000) {
                stopped = true; Log.w(TAG, "comparison storage inventory unavailable"); return;
            }
            for (File file : files) if (file.isFile() && file.getName().startsWith("comparison-dev18-")
                    && file.getName().endsWith(".csv")) retained += file.length();
        }

        void sample(Sample sample) {
            String discontinuity = null;
            if (lastSample != null) {
                if (sample.at <= lastSample.at || sample.at - lastSample.at > MAX_FORECAST_AGE_MS)
                    discontinuity = "sdk_gap_or_clock_order";
                else if (sample.energyWh > lastSample.energyWh)
                    discontinuity = "bms_energy_increase_charge_regen_or_quantization";
                else if (sample.soc > lastSample.soc)
                    discontinuity = "soc_increase_charge_or_quantization";
                else if (sample.mileage < lastSample.mileage) discontinuity = "odometer_decrease";
            }
            if (discontinuity != null) { reset(discontinuity); samples.clear(); }
            lastSample = sample; samples.addLast(sample);
            while (samples.size() > 8) samples.removeFirst();
            emit("sdk", "sdk_at_ms=" + sample.at + ";sdk_wall_ms=" + sample.wall + ";soc=" + sample.soc
                    + ";bms_wh=" + sample.energyWh + ";model_capacity_wh=" + sample.capacityWh + ";capacity_basis=" + sample.basis
                    + ";odometer_0_1km=" + sample.mileage + ";raw_counter=" + sample.raw);
        }

        void reset(String reason) {
            if (anchor == null && previous == null) return;
            emit("epoch_end", "reason=" + reason + ";not_arrival_confirmation=true");
            anchor = null; previous = null; epoch++;
        }

        void forecast(long callbackAt, int routeRef, int legs, long meters, long wh,
                      long[] pairs, int segments, boolean selected) {
            guided = true;
            long now = SystemClock.elapsedRealtime();
            if (!selected || now < callbackAt || now - callbackAt > MAX_FORECAST_AGE_MS
                    || meters <= 0 || segments <= 0 || pairs == null || segments > pairs.length / 2) {
                reset(!selected ? "forecast_native_unselected" : "forecast_stale_empty_or_invalid");
                emit("forecast_skip", "callback_ms=" + callbackAt + ";handled_ms=" + now
                        + ";native_selected=" + selected + ";meters=" + meters + ";google_remaining_wh=" + wh
                        + ";segments=" + segments); return;
            }
            Sample nearest = null;
            long skew = Long.MAX_VALUE;
            for (Sample sample : samples) {
                long distance = Math.abs(sample.at - callbackAt);
                if (distance < skew) { nearest = sample; skew = distance; }
            }
            if (nearest == null || skew > MAX_SKEW_MS) {
                reset("sdk_forecast_skew");
                emit("forecast_unpaired", "callback_ms=" + callbackAt + ";handled_ms=" + now
                        + ";nearest_sdk_skew_ms=" + (nearest == null ? -1 : skew)
                        + ";meters=" + meters + ";google_remaining_wh=" + wh); return;
            }
            Point point = new Point(callbackAt, now, routeRef, legs, meters, wh, pairs, segments, nearest);
            if (tripStart == null) tripStart = point;
            else if (meters > tripLast.meters + 200) tripReroutes++;
            tripLast = point;
            int tail = previous == null ? -1 : tail(previous, point);
            String revision = "none_detected_not_proven";
            if (previous != null) {
                if (callbackAt <= previous.at) revision = "callback_order";
                else if (callbackAt - previous.at > MAX_FORECAST_AGE_MS) revision = "forecast_gap";
                else if (legs != previous.legs) revision = "leg_count_changed";
                else if (meters > previous.meters + 200) revision = "remaining_distance_increase";
                else if (!suffix(previous, point, tail)) revision = "segment_values_revised_or_route_changed";
                else if (Math.abs(meters - previous.meters) <= 100 && Math.abs(wh - previous.wh) > 150)
                    revision = "stationary_forecast_revision_possible";
                if (!revision.equals("none_detected_not_proven")) reset(revision);
            }
            emit("forecast_pair", point.describe() + ";matching_tail_segments=" + tail
                    + ";revision=" + revision + ";stable_road_ids=unavailable;calibration_ready=false");
            if (anchor == null) anchor = point;
            previous = point;
            long odometerMeters = (long) (point.sample.mileage - anchor.sample.mileage) * 100;
            if (point.at - anchor.at >= 60000 && (odometerMeters >= 1000 || point.at - anchor.at >= 300000)
                    && anchor.sample.energyWh - point.sample.energyWh >= 200) {
                interval(point, "periodic"); anchor = point;
            }
        }

        void interval(Point point, String reason) {
            Point start = anchor;
            if (start == null || start == point) return;
            long actual = (long) start.sample.energyWh - point.sample.energyWh;
            long google = start.wh - point.wh;
            long road = start.meters - point.meters;
            long odometer = (long) (point.sample.mileage - start.sample.mileage) * 100;
            int tail = tail(start, point);
            emit("interval", "reason=" + reason + ";basis=provisional_remaining_delta;calibration_ready=false"
                    + ";stable_road_ids=unavailable;forecast_revision_possible=true;charge_state=unavailable"
                    + ";from_callback_ms=" + start.at + ";to_callback_ms=" + point.at
                    + ";from_sdk_ms=" + start.sample.at + ";to_sdk_ms=" + point.sample.at
                    + ";from_sdk_offset_ms=" + (start.sample.at - start.at)
                    + ";to_sdk_offset_ms=" + (point.sample.at - point.at)
                    + ";elapsed_ms=" + (point.at - start.at) + ";sdk_elapsed_ms=" + (point.sample.at - start.sample.at)
                    + ";from_soc=" + start.sample.soc + ";to_soc=" + point.sample.soc
                    + ";from_bms_wh=" + start.sample.energyWh + ";to_bms_wh=" + point.sample.energyWh
                    + ";actual_net_discharge_wh=" + actual + ";google_remaining_delta_wh=" + google
                    + ";residual_wh=" + (actual - google) + ";remaining_distance_delta_m=" + road
                    + ";odometer_delta_m=" + odometer + ";distance_gap_m=" + (odometer - road)
                    + ";sdk_order_valid=" + (point.sample.at > start.sample.at)
                    + ";distance_agrees_within_500m=" + (road > 0 && odometer > 0 && Math.abs(odometer - road) <= 500)
                    + ";actual_delta_at_least_200wh=" + (actual >= 200)
                    + ";from_model_capacity_wh=" + start.sample.capacityWh + ";to_model_capacity_wh=" + point.sample.capacityWh
                    + ";from_capacity_basis=" + start.sample.basis + ";to_capacity_basis=" + point.sample.basis
                    + ";matching_tail_segments=" + tail + ";suffix_compatible=" + suffix(start, point, tail)
                    + ";bms_delta_quantization_bound_wh=100;odometer_delta_quantization_bound_m=100"
                    + ";actual_net_kwh_per_100km=" + (odometer > 0 ? number(actual * 100.0 / odometer) : "na")
                    + ";average_sdk_speed_kmh=" + (point.sample.at > start.sample.at
                            ? number(odometer * 3600.0 / (point.sample.at - start.sample.at)) : "na")
                    + ";google_remaining_delta_kwh_per_100km=" + (road > 0 ? number(google * 100.0 / road) : "na")
                    + ";net_to_remaining_delta_ratio=" + (google > 0 ? number(actual * 1.0 / google) : "na"));
        }

        /** BMS discharge over the whole trip against the drop in Google's remaining forecast; then clears the trip. */
        String trip() {
            Point start = tripStart, end = tripLast;
            int reroutes = tripReroutes;
            tripStart = null; tripLast = null; tripReroutes = 0;
            if (start == null || end == start) return null;
            long actual = (long) start.sample.energyWh - end.sample.energyWh;
            long google = start.wh - end.wh;
            long road = start.meters - end.meters;
            long odometer = (long) (end.sample.mileage - start.sample.mileage) * 100;
            long sdkMs = end.sample.at - start.sample.at;
            int capacity = start.sample.capacityWh;
            String note = "duration_s=" + (end.at - start.at) / 1000 + ";odometer_m=" + odometer + ";route_m=" + road
                    + ";remaining_end_m=" + end.meters + ";reroutes=" + reroutes
                    + ";start_soc=" + start.sample.soc + ";end_soc=" + end.sample.soc
                    + ";start_bms_wh=" + start.sample.energyWh + ";end_bms_wh=" + end.sample.energyWh
                    + ";actual_wh=" + actual + ";google_start_wh=" + start.wh + ";google_end_wh=" + end.wh
                    + ";google_used_wh=" + google
                    + ";actual_to_google=" + (google > 0 ? number(actual * 1.0 / google) : "na")
                    + ";actual_kwh_100km=" + (odometer > 0 ? number(actual * 100.0 / odometer) : "na")
                    + ";google_kwh_100km=" + (road > 0 ? number(google * 100.0 / road) : "na")
                    + ";average_speed_kmh=" + (sdkMs > 0 ? number(odometer * 3600.0 / sdkMs) : "na")
                    + ";start_capacity_wh=" + capacity + ";capacity_basis=" + start.sample.basis
                    + ";planned_end_percent=" + (capacity > 0 ? number((start.sample.energyWh - google) * 100.0 / capacity) : "na")
                    + ";bms_quantization_wh=100;odometer_quantization_m=100";
            emit("trip", note);
            return note;
        }

        void emit(String kind, String note) {
            String safe = note.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
            String row = System.currentTimeMillis() + "," + SystemClock.elapsedRealtime() + "," + session
                    + "," + epoch + "," + kind + ",\"" + safe + "\"\n";
            Log.i(TAG, "comparison " + kind + " epoch=" + epoch + ";" + safe);
            if (stopped) return;
            try {
                byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
                byte[] header = "wall_ms,elapsed_ms,session,route_epoch,kind,note\n".getBytes(StandardCharsets.UTF_8);
                boolean fresh = output == null || output.length() + bytes.length > PART_BYTES;
                long required = bytes.length + (fresh ? header.length : 0);
                if (written + required > SESSION_BYTES || retained + required > TOTAL_BYTES) {
                    stopped = true; Log.w(TAG, "comparison CSV budget reached; existing logs preserved"); return;
                }
                if (fresh) {
                    output = new File(directory, "comparison-dev18-" + session + "-" + (++part) + ".csv");
                    if (!output.createNewFile()) {
                        stopped = true; Log.w(TAG, "comparison CSV already exists; preserved without appending"); return;
                    }
                }
                try (FileOutputStream stream = new FileOutputStream(output, true)) {
                    if (fresh) stream.write(header);
                    stream.write(bytes);
                }
                written += required; retained += required;
            } catch (Throwable error) {
                stopped = true; Log.w(TAG, "comparison CSV unavailable: " + error.getClass().getSimpleName());
            }
        }
    }

    /** Numeric pairs are only a continuity hint; equal pairs are not stable road identities. */
    private static int tail(Point before, Point after) {
        int count = 0;
        while (count < before.segments && count < after.segments) {
            int a = (before.segments - count - 1) * 2, b = (after.segments - count - 1) * 2;
            if (before.pairs[a] != after.pairs[b] || before.pairs[a + 1] != after.pairs[b + 1]) break;
            count++;
        }
        return count;
    }
    private static boolean suffix(Point before, Point after, int tail) {
        return after.segments <= before.segments && tail >= Math.max(1, after.segments - 1);
    }
    private static String number(double value) { return String.format(Locale.US, "%.3f", value); }

    private static final class Sample {
        final long at, wall;
        final double soc;
        final int energyWh, capacityWh, mileage, raw;
        final String basis;
        Sample(BydEnergyReader.Sample sample, int energyWh, int capacityWh, String basis) {
            at = sample.elapsed; wall = sample.wall; soc = sample.soc; mileage = sample.mileage; raw = sample.raw;
            this.energyWh = energyWh; this.capacityWh = capacityWh; this.basis = basis;
        }
    }
    private static final class Point {
        final long at, handledAt, meters, wh;
        final int routeRef, legs, segments;
        final long[] pairs;
        final Sample sample;
        Point(long at, long handledAt, int routeRef, int legs, long meters, long wh, long[] pairs, int segments, Sample sample) {
            this.at = at; this.handledAt = handledAt; this.routeRef = routeRef; this.legs = legs;
            this.meters = meters; this.wh = wh; this.segments = segments; this.pairs = pairs; this.sample = sample;
        }
        String describe() {
            long hash = 0xcbf29ce484222325L;
            for (int i = 0; i < segments * 2; i++) hash = (hash ^ pairs[i]) * 0x100000001b3L;
            return "callback_ms=" + at + ";handled_ms=" + handledAt + ";callback_queue_ms=" + (handledAt - at)
                    + ";route_object_ref=" + Integer.toHexString(routeRef) + ";legs=" + legs + ";segments=" + segments
                    + ";segment_values_hash=" + Long.toHexString(hash) + ";remaining_m=" + meters + ";google_remaining_wh=" + wh
                    + ";sdk_at_ms=" + sample.at + ";sdk_offset_ms=" + (sample.at - at) + ";soc=" + sample.soc
                    + ";bms_wh=" + sample.energyWh + ";model_capacity_wh=" + sample.capacityWh + ";capacity_basis=" + sample.basis
                    + ";odometer_0_1km=" + sample.mileage;
        }
    }
}
