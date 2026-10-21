package app.morphe.extension.maps.patches;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.CharacterStyle;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One committed ETA binding with an inline battery icon. No view or text observers. */
final class BydGuidanceEtaText {
    private static final long FRESH_MS = 30000;
    private static final int MAX_SP = 24, MIN_SP = 16, EMERGENCY_SP = 14;
    // A paper fit with ~2 px to spare still wrapped the icon onto a hidden second line in split screen.
    private static final int SLACK_DP = 4, SLACK_STEP_DP = 2, MAX_EXTRA_SLACK_DP = 16;
    private static final float MIN_SCALE = .90f, FILL_TINT = .35f;
    private static final String GAP = "[\\s\\p{Zs}]*";
    private static final String NUMBER = "(\\p{Nd}+(?:[,.\\u066c]\\p{Nd}+)*)";
    private static final Pattern DURATION = Pattern.compile("\\A" + GAP
            + "(?:" + NUMBER + GAP + "(ชั่วโมง|ชม\\.?|hours?\\.?|hrs?\\.?|h)" + GAP + ")?"
            + "(?:" + NUMBER + GAP + "(นาที|น\\.?|minutes?\\.?|mins?\\.?|m)" + GAP + ")?\\z",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Map<TextView, SavedStyle> styled = new WeakHashMap<>();
    private static Field callbackField;
    private static Method callbackWidth;
    private static Class<?> durationType;

    private BydGuidanceEtaText() { }

    /** The original pgl callback supplies the safe width, independent of the last layout. */
    static void configure(Class<?> textType, String fieldName, String methodName)
            throws ReflectiveOperationException {
        Field field = textType.getDeclaredField(fieldName);
        Class<?> callback = field.getType();
        Method method = callback.getMethod(methodName);
        if (!TextView.class.isAssignableFrom(textType) || Modifier.isStatic(field.getModifiers())
                || !callback.isInterface() || callback.getDeclaredMethods().length != 1
                || method.getParameterTypes().length != 0 || method.getReturnType() != int.class
                || Modifier.isStatic(method.getModifiers()))
            throw new IllegalArgumentException("ETA width callback");
        field.setAccessible(true);
        durationType = textType; callbackField = field; callbackWidth = method;
    }

    /** Text and level come from the same original Google arrival DTO. */
    static CharSequence decorate(CharSequence original, String percent, String accessibility,
            long elapsed, int displayPercent) {
        Owned previous = owned(original);
        if (previous != null) original = previous.original;
        if (original == null || original.length() == 0 || original.length() > 512
                || percent == null || percent.trim().isEmpty() || percent.length() > 64
                || displayPercent < 0 || displayPercent > 100
                || original.toString().indexOf('\n') >= 0 || original.toString().indexOf('\r') >= 0
                || percent.indexOf('\n') >= 0 || percent.indexOf('\r') >= 0) return original;
        CharSequence saved = immutable(original);
        CharSequence compact = compactDuration(saved);
        if (compact == null) {
            BydNativeGuidanceUi.layoutLog("slot=eta;unsupported=duration_format");
            return original;
        }
        return compose(new Owned(saved, compact, percent, accessibility, elapsed, displayPercent));
    }

    /** Return an exact fitted width spec, or zero to run the entire original pgl measure. */
    static int measure(TextView view, int incomingWidthSpec) {
        Owned marker = owned(view.getText());
        if (marker == null) { restore(view); return 0; }
        long age = SystemClock.elapsedRealtime() - marker.elapsed;
        if (age < 0 || age > FRESH_MS || durationType == null || !durationType.isInstance(view)) {
            restore(view); return 0;
        }
        try {
            Object callback = callbackField.get(view);
            if (callback == null) return unsupported(view, "no_callback", 0);
            int available = ((Number) callbackWidth.invoke(callback)).intValue();
            int mode = View.MeasureSpec.getMode(incomingWidthSpec);
            int incoming = View.MeasureSpec.getSize(incomingWidthSpec);
            if (mode != View.MeasureSpec.UNSPECIFIED) available = Math.min(available, incoming);
            int padding = view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
            SavedStyle saved;
            synchronized (styled) {
                saved = styled.get(view);
                if (saved == null) { saved = new SavedStyle(view); styled.put(view, saved); }
            }
            float density = view.getResources().getDisplayMetrics().scaledDensity;
            // The last real layout of our own text: if its first line stops before the end, the icon
            // wrapped onto a line maxLines hides. Widen the slack until the whole text is on line one.
            Layout rendered = view.getLayout();
            CharSequence shown = rendered == null ? null : rendered.getText();
            int renderedEnd = shown == null || rendered.getLineCount() < 1 ? -1 : rendered.getLineEnd(0);
            boolean wrapped = renderedEnd >= 0 && renderedEnd < shown.length() && owned(shown) != null;
            // A layout not built yet logs as one_line too, so routine rows keep one signature and coalesce.
            String renderedState = wrapped ? "wrapped_" + renderedEnd + "/" + shown.length() : "one_line";
            if (wrapped && saved.extraSlack < Math.round(MAX_EXTRA_SLACK_DP * density)) {
                saved.extraSlack += Math.max(1, Math.round(SLACK_STEP_DP * density)); saved.fit = null;
            }
            int slack = Math.max(2, Math.round(SLACK_DP * density)) + saved.extraSlack;
            int room = available - padding - slack;
            if (available <= 0 || available == Integer.MAX_VALUE || room < 1)
                return unsupported(view, "no_width", available);
            // pgl's native max lives separately from its current paint size.
            // The latter may still be a constructor default on first measure.
            float maximum = MAX_SP * density;
            float minimum = MIN_SP * density;
            TextPaint paint = new TextPaint(view.getPaint());
            paint.setTextScaleX(saved.scale);
            float size, scale, desired;
            String fallback;
            Fit fit = saved.fit;
            if (fit != null && fit.matches(marker, room, density, saved.scale, paint)) {
                // Same text, level, width and paint: Maps' re-measure reuses the proven fit.
                size = fit.size; scale = fit.scale; desired = fit.desired; fallback = fit.fallback;
                applyStyle(view, size, scale);
                saved.accessibility(view, marker);
            } else {
                CharSequence text = compose(marker);
                size = fit(text, paint, minimum, maximum, room);
                fallback = "none";
                if (size < 0) {
                    minimum = Math.min(maximum, EMERGENCY_SP * density);
                    size = fit(text, paint, minimum, maximum, room);
                    fallback = "small";
                }
                scale = saved.scale;
                if (size < 0) {
                    size = minimum;
                    paint.setTextSize(size);
                    paint.setTextScaleX(saved.scale * MIN_SCALE);
                    float width = Layout.getDesiredWidth(text, paint);
                    if (!Float.isFinite(width) || width <= 0 || width > room)
                        return unsupported(view, "does_not_fit", available);
                    float low = MIN_SCALE, high = 1f;
                    for (int step = 0; step < 12; step++) {
                        float middle = (low + high) / 2f;
                        paint.setTextScaleX(saved.scale * middle);
                        if (Layout.getDesiredWidth(text, paint) <= room) low = middle; else high = middle;
                    }
                    scale = saved.scale * low;
                    paint.setTextScaleX(scale);
                    fallback = "condensed";
                }
                // All text and size changes occur in the same native measure, before drawing.
                applyStyle(view, size, scale);
                if (!samePresentation(view.getText(), text)) view.setText(text);
                saved.accessibility(view, marker);
                Typeface typeface = paint.getTypeface();
                float letterSpacing = paint.getLetterSpacing();
                int flags = paint.getFlags();
                paint.setTextSize(size); paint.setTextScaleX(scale);
                desired = Layout.getDesiredWidth(text, paint);
                if (!Float.isFinite(desired) || desired > room + .5f) {
                    saved.fit = null;
                    return unsupported(view, "final_width", available);
                }
                saved.fit = new Fit(marker, room, density, saved.scale, typeface, letterSpacing, flags,
                        size, scale, desired, fallback);
            }
            int width = mode == View.MeasureSpec.EXACTLY ? available
                    : Math.min(available, (int) Math.ceil(desired) + padding + 2);
            BydNativeGuidanceUi.layoutLog("slot=eta;view=" + Integer.toHexString(System.identityHashCode(view))
                    + ";width_budget=" + available + ";slack_px=" + slack + ";rendered=" + renderedState
                    + ";desired_px=" + Math.round(desired)
                    + ";font_sp=" + (Math.round(size / density * 10) / 10f) + ";scale_x=" + scale
                    + ";fallback=" + fallback + ";eta_units=h_m;inline_icon=true;single_line=true;structural_mutations=0");
            return View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        } catch (Throwable error) {
            restore(view);
            BydNativeGuidanceUi.layoutLog("slot=eta;error=" + error.getClass().getSimpleName());
            return 0;
        }
    }

    private static void applyStyle(TextView view, float size, float scale) {
        if (view.getAutoSizeTextType() != TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
        if (view.getMaxLines() != 1) view.setMaxLines(1);
        if (view.getEllipsize() != null) view.setEllipsize(null);
        if (Math.abs(view.getTextSize() - size) > .01f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        if (Math.abs(view.getTextScaleX() - scale) > .0001f) view.setTextScaleX(scale);
    }

    static boolean hasStyled() {
        synchronized (styled) { return !styled.isEmpty(); }
    }

    private static int unsupported(TextView view, String reason, int width) {
        restore(view);
        BydNativeGuidanceUi.layoutLog("slot=eta;unsupported=" + reason + ";width_budget=" + width);
        return 0;
    }

    /** Restore only owned text and the style that this helper actually changed. */
    static void restore(TextView view) {
        Owned marker = owned(view.getText());
        SavedStyle saved;
        synchronized (styled) { saved = styled.remove(view); }
        if (marker != null) view.setText(marker.original);
        if (saved != null) saved.restore(view);
    }

    static void clear() {
        ArrayList<TextView> views;
        synchronized (styled) { views = new ArrayList<>(styled.keySet()); }
        for (TextView view : views) if (view != null) restore(view);
    }

    private static float fit(CharSequence text, TextPaint paint, float minimum, float maximum, int room) {
        paint.setTextSize(minimum);
        float required = Layout.getDesiredWidth(text, paint);
        if (!Float.isFinite(required) || required > room) return -1;
        paint.setTextSize(maximum);
        if (Layout.getDesiredWidth(text, paint) <= room) return maximum;
        float low = minimum, high = maximum;
        for (int step = 0; step < 12; step++) {
            float middle = (low + high) / 2f;
            paint.setTextSize(middle);
            if (Layout.getDesiredWidth(text, paint) <= room) low = middle; else high = middle;
        }
        return low;
    }

    private static CharSequence compose(Owned marker) {
        SpannableStringBuilder value = new SpannableStringBuilder(marker.compact);
        // Size is deliberately owned here; every other original text/span is retained.
        for (CharacterStyle span : value.getSpans(0, value.length(), CharacterStyle.class)) {
            CharacterStyle underlying = span.getUnderlying();
            if (underlying instanceof AbsoluteSizeSpan || underlying instanceof RelativeSizeSpan)
                value.removeSpan(span);
        }
        value.append('\u2002');
        int icon = value.length(); value.append('\ufffc');
        value.setSpan(new BatterySpan(marker.displayPercent), icon, icon + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        value.setSpan(marker, 0, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return new SpannedString(value);
    }

    /** Rewrite only known unit labels/spacing; never parse, round or recompute duration digits. */
    private static CharSequence compactDuration(CharSequence original) {
        Matcher match = DURATION.matcher(original);
        if (!match.matches() || match.start(1) < 0 && match.start(3) < 0) return null;
        boolean hours = match.start(1) >= 0, minutes = match.start(3) >= 0;
        SpannableStringBuilder text = new SpannableStringBuilder(original);
        // Work backwards so every offset still refers to the original formatted result.
        if (minutes) {
            text.delete(match.end(4), text.length());
            text.replace(match.start(4), match.end(4), "m");
            text.delete(match.end(3), match.start(4));
        }
        if (hours) {
            if (minutes) text.replace(match.end(2), match.start(3), " ");
            else text.delete(match.end(2), text.length());
            text.replace(match.start(2), match.end(2), "h");
            text.delete(match.end(1), match.start(2));
            text.delete(0, match.start(1));
        } else text.delete(0, match.start(3));
        return new SpannedString(text);
    }

    private static CharSequence immutable(CharSequence value) {
        return value instanceof Spanned ? new SpannedString(value) : value.toString();
    }

    private static Owned owned(CharSequence value) {
        if (!(value instanceof Spanned)) return null;
        Spanned text = (Spanned) value;
        Owned[] owners = text.getSpans(0, text.length(), Owned.class);
        return owners.length == 1 && text.getSpanStart(owners[0]) == 0
                && text.getSpanEnd(owners[0]) == text.length() ? owners[0] : null;
    }

    private static boolean samePresentation(CharSequence before, CharSequence after) {
        Owned old = owned(before), next = owned(after);
        return old != null && old == next && TextUtils.equals(before, after);
    }

    private static final class Owned {
        final CharSequence original, compact;
        final String percent, accessibility;
        final long elapsed;
        final int displayPercent;
        Owned(CharSequence original, CharSequence compact, String percent, String accessibility,
                long elapsed, int displayPercent) {
            this.original = original; this.compact = compact;
            this.percent = percent; this.accessibility = accessibility; this.elapsed = elapsed;
            this.displayPercent = displayPercent;
        }
    }

    /** One successful fit for the same compact text, level, room and measuring paint. */
    private static final class Fit {
        final String compact;
        final int displayPercent, room, flags;
        final float density, baseScale, letterSpacing, size, scale, desired;
        final Typeface typeface;
        final String fallback;
        Fit(Owned marker, int room, float density, float baseScale, Typeface typeface, float letterSpacing, int flags,
            float size, float scale, float desired, String fallback) {
            compact = marker.compact.toString(); displayPercent = marker.displayPercent;
            this.room = room; this.density = density; this.baseScale = baseScale; this.typeface = typeface;
            this.letterSpacing = letterSpacing; this.flags = flags;
            this.size = size; this.scale = scale; this.desired = desired; this.fallback = fallback;
        }
        boolean matches(Owned marker, int room, float density, float baseScale, Paint paint) {
            return this.room == room && displayPercent == marker.displayPercent
                    && Float.compare(this.density, density) == 0 && Float.compare(this.baseScale, baseScale) == 0
                    && typeface == paint.getTypeface() && Float.compare(letterSpacing, paint.getLetterSpacing()) == 0
                    && flags == paint.getFlags() && compact.contentEquals(marker.compact);
        }
    }

    private static final class SavedStyle {
        final float size, scale;
        final int maxLines, maxHeight, autoType, autoMin, autoMax, autoStep;
        final int[] autoSizes;
        final TextUtils.TruncateAt ellipsize;
        CharSequence nativeDescription, appliedDescription;
        Fit fit;
        // Pixels added after a real layout still wrapped; kept for this view until it is restored.
        int extraSlack;
        SavedStyle(TextView view) {
            size = view.getTextSize(); scale = view.getTextScaleX();
            maxLines = view.getMaxLines(); maxHeight = view.getMaxHeight(); ellipsize = view.getEllipsize();
            autoType = view.getAutoSizeTextType(); autoMin = view.getAutoSizeMinTextSize();
            autoMax = view.getAutoSizeMaxTextSize(); autoStep = view.getAutoSizeStepGranularity();
            autoSizes = view.getAutoSizeTextAvailableSizes(); nativeDescription = view.getContentDescription();
        }
        void accessibility(TextView view, Owned marker) {
            CharSequence current = view.getContentDescription();
            if (!TextUtils.equals(current, appliedDescription)) nativeDescription = current;
            CharSequence base = nativeDescription == null || nativeDescription.length() == 0
                    ? marker.original : nativeDescription;
            String battery = marker.accessibility == null || marker.accessibility.isEmpty()
                    ? marker.percent : marker.accessibility;
            appliedDescription = base + ", " + battery;
            if (!TextUtils.equals(current, appliedDescription)) view.setContentDescription(appliedDescription);
        }
        void restore(TextView view) {
            if (TextUtils.equals(view.getContentDescription(), appliedDescription))
                view.setContentDescription(nativeDescription);
            if (maxLines >= 0) view.setMaxLines(maxLines); else if (maxHeight >= 0) view.setMaxHeight(maxHeight);
            view.setEllipsize(ellipsize);
            view.setTextScaleX(scale);
            view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            if (autoType == TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM) {
                if (autoMin > 0 && autoMax > autoMin && autoStep > 0)
                    view.setAutoSizeTextTypeUniformWithConfiguration(autoMin, autoMax, autoStep, TypedValue.COMPLEX_UNIT_PX);
                else if (autoSizes != null && autoSizes.length > 0)
                    view.setAutoSizeTextTypeUniformWithPresetSizes(autoSizes, TypedValue.COMPLEX_UNIT_PX);
                else view.setAutoSizeTextTypeWithDefaults(autoType);
            }
        }
    }

    /** One number-bearing symbol follows the ETA font and original Google arrival level. */
    private static final class BatterySpan extends ImageSpan {
        BatterySpan(int displayPercent) { super(new BatteryDrawable(displayPercent), ImageSpan.ALIGN_BASELINE); }
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            Paint.FontMetricsInt original = paint.getFontMetricsInt();
            int height = Math.max(1, Math.round((original.descent - original.ascent) * .90f));
            int width = ((BatteryDrawable) getDrawable()).configure(paint, height);
            getDrawable().setBounds(0, 0, width, height);
            if (metrics != null) { metrics.top = original.top; metrics.ascent = original.ascent;
                metrics.descent = original.descent; metrics.bottom = original.bottom; metrics.leading = original.leading; }
            return width;
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) {
            BatteryDrawable battery = (BatteryDrawable) getDrawable();
            battery.color = paint.getColor();
            float center = y + (paint.ascent() + paint.descent()) / 2f;
            int save = canvas.save();
            canvas.translate(x, center - battery.getBounds().height() / 2f);
            battery.draw(canvas); canvas.restoreToCount(save);
        }
    }

    private static final class BatteryDrawable extends Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect digitBounds = new Rect();
        private final int displayPercent;
        private final String label;
        int color;
        private int alpha = 255;
        BatteryDrawable(int displayPercent) {
            this.displayPercent = displayPercent;
            label = Integer.toString(displayPercent);
        }
        int configure(Paint parent, int height) {
            number.setTypeface(Typeface.create(parent.getTypeface(), Typeface.BOLD));
            number.setTextAlign(Paint.Align.CENTER);
            number.setTextScaleX(parent.getTextScaleX());
            number.setTextSize(parent.getTextSize() * .78f);
            number.getTextBounds("0123456789", 0, 10, digitBounds);
            float line = Math.max(1f, height * .08f), gap = Math.max(1f, height * .04f);
            float innerHeight = Math.max(1f, height - 2f * (line + gap));
            if (digitBounds.height() > innerHeight) {
                number.setTextSize(number.getTextSize() * innerHeight / digitBounds.height());
                number.getTextBounds("0123456789", 0, 10, digitBounds);
            }
            float digitWidth = 0;
            for (int digit = 0; digit < 10; digit++)
                digitWidth = Math.max(digitWidth, number.measureText("0123456789", digit, digit + 1));
            // Reserve three widest digits at every level; 9/10/99/100 never move the ETA.
            float body = Math.max(height * 1.65f, 3f * digitWidth + 2f * (line + gap + height * .08f));
            return Math.max(1, (int) Math.ceil(body + height * .14f));
        }
        @Override public void draw(Canvas canvas) {
            float width = getBounds().width(), height = getBounds().height();
            float line = Math.max(1f, height * .08f), inset = line / 2f;
            float bodyRight = width - height * .14f;
            int foregroundAlpha = Color.alpha(color) * alpha / 255;
            stroke.setColor(color); stroke.setAlpha(foregroundAlpha); stroke.setStrokeWidth(line);
            stroke.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(inset, inset, bodyRight - inset, height - inset,
                    line, line, stroke);
            stroke.setStyle(Paint.Style.FILL);
            float gap = Math.max(1f, height * .04f), innerInset = line + gap;
            float innerWidth = bodyRight - 2f * innerInset;
            float fillRight = innerInset + innerWidth * displayPercent / 100f;
            canvas.drawRect(bodyRight, height * .31f, width, height * .69f, stroke);
            // The level is a faint tint behind the number, so the digits keep one solid colour at
            // every level instead of changing colour where the fill ends.
            if (displayPercent > 0 && innerWidth > 0 && height > 2f * innerInset) {
                stroke.setAlpha(Math.round(foregroundAlpha * FILL_TINT));
                canvas.drawRect(innerInset, innerInset, fillRight, height - innerInset, stroke);
                stroke.setAlpha(foregroundAlpha);
            }
            float centerX = bodyRight / 2f;
            float baseline = height / 2f - (digitBounds.top + digitBounds.bottom) / 2f;
            number.setColor(color); number.setAlpha(foregroundAlpha);
            canvas.drawText(label, centerX, baseline, number);
        }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter value) {
            stroke.setColorFilter(value); number.setColorFilter(value); invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
