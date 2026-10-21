package app.morphe.extension.maps.patches;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/** Patch-owned settings reached from a real Maps Settings row; all state belongs to BydVehicleModel. */
@SuppressWarnings("unused")
public final class BydVehicleSettings {
    private static final String TAG = "MorpheBydSettings";
    private static final String KEY = "morphe_byd_vehicle_battery";
    private static WeakReference<AlertDialog> active = new WeakReference<>(null);
    // Consumption takes up to two digits and one decimal place (6–40, e.g. 12.3), typed or pasted.
    private static final Pattern ONE_DECIMAL_TEXT = Pattern.compile("\\d{0,2}(\\.\\d?)?");
    private static final InputFilter ONE_DECIMAL = (source, start, end, dest, dstart, dend) -> {
        String next = dest.subSequence(0, dstart) + source.subSequence(start, end).toString()
                + dest.subSequence(dend, dest.length());
        return ONE_DECIMAL_TEXT.matcher(next).matches() ? null : "";
    };

    private BydVehicleSettings() { }

    /** The resolver supplies only names whose public APIs and register flows were proven in the APK. */
    public static void attach(Object fragment, String schema) {
        if (!bydRuntime() || fragment == null || schema == null) return;
        try {
            String[] d = schema.split("\\|", -1);
            if (d.length != 12 || !fragment.getClass().getName().equals(d[0]))
                throw new IllegalArgumentException("settings entry schema");
            Object screen = fragment.getClass().getMethod(d[1]).invoke(fragment);
            Object host = fragment.getClass().getMethod(d[2]).invoke(fragment);
            if (!(host instanceof Activity) || screen == null) return;
            Activity activity = (Activity) host;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            ClassLoader loader = fragment.getClass().getClassLoader();
            Class<?> preference = Class.forName("androidx.preference.Preference", false, loader);
            Class<?> group = Class.forName("androidx.preference.PreferenceGroup", false, loader);
            if (!group.isInstance(screen)) throw new IllegalArgumentException("settings screen type");
            Method find = group.getMethod(d[11], CharSequence.class);
            if (find.invoke(screen, KEY) != null) return;
            Context context = (Context) preference.getField(d[3]).get(screen);
            Object row = preference.getConstructor(Context.class).newInstance(context);
            preference.getMethod(d[4], String.class).invoke(row, KEY);
            preference.getMethod(d[5], CharSequence.class).invoke(row, "Vehicle and battery");
            preference.getMethod(d[6], CharSequence.class).invoke(row, "BYD energy model");
            Class<?> click = Class.forName(d[8], false, loader);
            WeakReference<Activity> owner = new WeakReference<>(activity);
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{click}, (proxy, method, args) -> {
                if (method.getName().equals(d[9]) && args != null && args.length == 1 && preference.isInstance(args[0])) {
                    Activity current = owner.get();
                    if (current != null) {
                        Context selected = current;
                        try {
                            Object value = preference.getField(d[3]).get(args[0]);
                            if (value instanceof Context) selected = (Context) value;
                        } catch (ReflectiveOperationException error) {
                            Log.w(TAG, "entry_theme_unavailable;dev=22", error);
                        }
                        open(current, selected);
                    }
                    return true;
                }
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                    if (method.getName().equals("toString")) return "Morphe BYD vehicle settings";
                }
                return false;
            });
            preference.getMethod(d[7], click).invoke(row, listener);
            group.getMethod(d[10], preference).invoke(screen, row);
            Log.i(TAG, "entry_attached;dev=22;screen=" + fragment.getClass().getName());
        } catch (Throwable error) {
            // A settings-entry failure must leave the original Maps screen usable.
            Log.w(TAG, "entry_unavailable;dev=22", error);
        }
    }

    public static void open(Activity activity) {
        open(activity, activity);
    }

    private static void open(Activity activity, Context mapsContext) {
        if (!bydRuntime() || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> open(activity, mapsContext));
            return;
        }
        try {
            AlertDialog prior = active.get();
            if (prior != null && prior.isShowing()) return;
            new Screen(activity, mapsContext == null ? activity : mapsContext).show();
        } catch (Throwable error) {
            Log.w(TAG, "screen_unavailable;dev=22", error);
            Toast.makeText(activity, "Couldn't open vehicle settings", Toast.LENGTH_LONG).show();
        }
    }

    private static final class Screen {
        final Activity activity;
        final Context context;
        final LinearLayout body, fixed, manual;
        final RadioGroup mode;
        final int autoId = View.generateViewId(), fixedId = View.generateViewId(), manualId = View.generateViewId();
        final EditText nominal, consumption, a, b, c;
        final TextView status;
        AlertDialog dialog;

        Screen(Activity owner, Context mapsContext) {
            activity = owner;
            // Follow the clicked Maps preference's theme, including an in-app override of system night mode.
            // Complete platform themes keep every widget/reset dialog independent of Maps' token resolver.
            context = dialogContext(owner, mapsContext);
            body = column(context);
            int padding = dp(context, 20);
            body.setPadding(padding, dp(context, 8), padding, dp(context, 12));
            status = addText(body, "", 14);
            status.setTextIsSelectable(true);
            mode = new RadioGroup(context);
            mode.setOrientation(RadioGroup.VERTICAL);
            RadioButton automatic = new RadioButton(context);
            automatic.setId(autoId);
            automatic.setText("Automatic");
            RadioButton steady = new RadioButton(context);
            steady.setId(fixedId);
            steady.setText("Fixed");
            RadioButton custom = new RadioButton(context);
            custom.setId(manualId);
            custom.setText("Manual A/B/C");
            mode.addView(automatic);
            mode.addView(steady);
            mode.addView(custom);
            body.addView(mode, matchWrap());
            fixed = column(context);
            consumption = number(fixed, "Consumption (kWh/100 km)", "6–40");
            consumption.setFilters(new InputFilter[]{ONE_DECIMAL});
            body.addView(fixed, matchWrap());
            manual = column(context);
            a = number(manual, "A (N)", "0–1000");
            b = number(manual, "B (N per m/s)", "0–20");
            c = number(manual, "C (N per (m/s)²)", "Above 0, up to 2");
            addText(manual, "F = A + B·v + C·v²", 13);
            // Automatic/Fixed measure the pack above 50% SOC; only Manual takes an entered capacity.
            nominal = number(manual, "Capacity below 10% SOC (Wh)", "5000–150000");
            body.addView(manual, matchWrap());
            addText(body, "Changes apply to the next route.", 13);
            mode.setOnCheckedChangeListener((group, checked) -> showFields(checked));
            load();
        }

        void showFields(int checked) {
            fixed.setVisibility(checked == fixedId ? View.VISIBLE : View.GONE);
            manual.setVisibility(checked == manualId ? View.VISIBLE : View.GONE);
        }

        void load() {
            BydVehicleModel.Settings values = BydVehicleModel.settings();
            nominal.setText(decimal(values.nominalWh));
            // Outside Fixed mode, switching to Fixed starts from the current learned value.
            double target = values.mode == BydVehicleModel.FIXED ? values.fixedConsumption : BydVehicleModel.learnedConsumption();
            consumption.setText(String.format(Locale.ROOT, "%.1f", target));
            a.setText(decimal(values.a));
            b.setText(decimal(values.b));
            c.setText(decimal(values.c));
            int checked = values.mode == BydVehicleModel.FIXED ? fixedId : values.mode == BydVehicleModel.MANUAL ? manualId : autoId;
            mode.check(checked);
            showFields(checked);
            status.setText(BydNativeBatteryPatch.vehicleStatus() + "\n" + BydVehicleModel.status());
        }

        void show() {
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(false);
            scroll.addView(body, matchWrap());
            dialog = new AlertDialog.Builder(context)
                .setTitle("Vehicle and battery")
                .setView(scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Close", null)
                .setNeutralButton("Reset", null)
                .create();
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> save());
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view -> confirmReset());
                if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            });
            dialog.setOnDismissListener(ignored -> {
                if (active.get() == dialog) active = new WeakReference<>(null);
            });
            active = new WeakReference<>(dialog);
            dialog.show();
            Log.i(TAG, "screen_open;dev=22");
        }

        void save() {
            try {
                int checked = mode.getCheckedRadioButtonId();
                int selected = checked == fixedId ? BydVehicleModel.FIXED
                        : checked == manualId ? BydVehicleModel.MANUAL : BydVehicleModel.AUTO;
                BydVehicleModel.Settings previous = BydVehicleModel.settings();
                // Hidden fields keep their saved values while another mode is selected.
                double target = selected == BydVehicleModel.FIXED ? value(consumption, 6, 40, false) : previous.fixedConsumption;
                boolean manualMode = selected == BydVehicleModel.MANUAL;
                float capacity = manualMode ? value(nominal, 5000, 150000, false) : previous.nominalWh;
                float roadA = manualMode ? value(a, 0, 1000, false) : previous.a;
                float roadB = manualMode ? value(b, 0, 20, false) : previous.b;
                float roadC = manualMode ? value(c, 0, 2, true) : previous.c;
                BydVehicleModel.saveSettings(selected, capacity, target, roadA, roadB, roadC);
                Toast.makeText(activity, "Saved", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                Log.i(TAG, "settings_saved;dev=22;mode=" + selected);
            } catch (InvalidInput ignored) {
                // The invalid field remains visible and focused; the dialog stays open.
            } catch (Throwable error) {
                Log.w(TAG, "save_rejected;dev=22", error);
                Toast.makeText(activity, "Couldn't save. Check the values.", Toast.LENGTH_LONG).show();
            }
        }

        void confirmReset() {
            new AlertDialog.Builder(context)
                .setTitle("Reset vehicle model?")
                .setMessage("Restores Automatic mode and the default values.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (ignored, which) -> {
                    try {
                        BydVehicleModel.resetSettings();
                        load();
                        Toast.makeText(activity, "Reset to defaults", Toast.LENGTH_SHORT).show();
                        Log.i(TAG, "settings_reset;dev=22");
                    } catch (Throwable error) {
                        Log.w(TAG, "reset_rejected;dev=22", error);
                        Toast.makeText(activity, "Couldn't reset", Toast.LENGTH_LONG).show();
                    }
                }).show();
        }
    }

    private static Context dialogContext(Activity owner, Context mapsContext) {
        Boolean dark = themedDark(mapsContext);
        String source = "preference_theme";
        if (dark == null && mapsContext != owner) {
            dark = themedDark(owner);
            source = "activity_theme";
        }
        if (dark == null) {
            dark = (mapsContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
            source = "configuration_fallback";
        }
        Log.i(TAG, "settings_theme;dev=22;dark=" + dark + ";source=" + source);
        return new ContextThemeWrapper(owner, dark
            ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    /** Maps' inspected GmmDayNightTheme inherits AppCompat's own isLightTheme attribute. */
    private static Boolean themedDark(Context context) {
        try {
            int attribute = context.getResources().getIdentifier("isLightTheme", "attr", context.getPackageName());
            TypedValue value = new TypedValue();
            if (attribute != 0 && context.getTheme().resolveAttribute(attribute, value, true)
                && value.type == TypedValue.TYPE_INT_BOOLEAN) return value.data == 0;
            // Named attributes may be absent in another BYD/Maps build; use the actual themed surface next.
            TypedArray colors = context.obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
            try {
                if (colors.hasValue(0)) {
                    int background = colors.getColor(0, Color.TRANSPARENT);
                    if (Color.alpha(background) == 255) return Color.luminance(background) < 0.5f;
                }
            } finally {
                colors.recycle();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "theme_attribute_unavailable;dev=22", error);
        }
        return null;
    }

    private static final class InvalidInput extends Exception { }

    private static float value(EditText field, float min, float max, boolean exclusiveMin) throws InvalidInput {
        float result;
        try { result = Float.parseFloat(field.getText().toString().trim()); }
        catch (RuntimeException error) { result = Float.NaN; }
        if (!Float.isFinite(result) || result < min || result > max || (exclusiveMin && result <= min)) {
            field.setError("Out of range");
            field.requestFocus();
            throw new InvalidInput();
        }
        field.setError(null);
        return result;
    }

    private static EditText number(LinearLayout parent, String label, String hint) {
        Context context = parent.getContext();
        TextView title = addText(parent, label, 14);
        EditText input = new EditText(context);
        input.setId(View.generateViewId());
        title.setLabelFor(input.getId());
        input.setContentDescription(label);
        input.setSingleLine(true);
        input.setTextSize(17);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(hint);
        parent.addView(input, matchWrap());
        return input;
    }

    private static TextView addText(LinearLayout parent, String text, int size) {
        TextView view = new TextView(parent.getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(0, dp(parent.getContext(), 6), 0, dp(parent.getContext(), 4));
        parent.addView(view, matchWrap());
        return view;
    }

    private static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context context, int size) { return Math.round(size * context.getResources().getDisplayMetrics().density); }
    private static String decimal(float value) { return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString(); }
    private static boolean bydRuntime() { return byd(Build.MANUFACTURER) || byd(Build.BRAND) || byd(Build.MODEL); }
    private static boolean byd(String value) { return value != null && value.regionMatches(true, 0, "BYD", 0, 3); }
}
