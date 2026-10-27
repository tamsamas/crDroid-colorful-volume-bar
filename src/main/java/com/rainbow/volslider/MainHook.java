package com.rainbow.volslider;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Multi-color gradient for crDroid's volume slider.
 *
 * How it works: crDroid's SliderTrack (VolumeDialogSliderTrackKt) builds its gradient with
 * Brush.verticalGradient(listOf(endColor, startColor)). We hook that call, but ONLY while we are
 * inside SliderTrack, and replace the 2-color list with the colors stored in the system setting
 * "volume_rainbow_colors" (comma separated hex, TOP to BOTTOM as seen on screen).
 *
 * Safety: every hook body is wrapped in try/catch. If anything goes wrong we leave the original
 * arguments untouched, so the worst case is "the normal 2-color gradient", never a SystemUI crash.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "RainbowVolume";
    private static final String SETTING_KEY = "volume_rainbow_colors";

    private static final String TRACK_CLASS =
            "com.android.systemui.volume.dialog.sliders.ui.compose.VolumeDialogSliderTrackKt";
    private static final String BRUSH_COMPANION = "androidx.compose.ui.graphics.Brush$Companion";
    private static final String COLOR_KT = "androidx.compose.ui.graphics.ColorKt";

    // Depth counter so nested SliderTrack / SliderTrack$default calls don't clear the flag early.
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[]{0};
        }
    };

    private static Method sBoxMethod;               // Color.box-impl(long) -> Color
    private static Constructor<?> sBoxConstructor;  // fallback: Color(long)
    private static Method sColorOfInt;              // ColorKt.Color(int) -> long
    private static boolean sLoggedOk = false;
    private static boolean sLoggedErr = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }
        try {
            hookTrack(lpparam.classLoader);
            hookBrush(lpparam.classLoader);
            try {
                Class<?> colorKt = XposedHelpers.findClass(COLOR_KT, lpparam.classLoader);
                sColorOfInt = colorKt.getDeclaredMethod("Color", int.class);
                sColorOfInt.setAccessible(true);
            } catch (Throwable ignored) {
                // fallback: pack the color ourselves (see toPackedColor)
            }
            XposedBridge.log(TAG + ": hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init failed, doing nothing: " + t);
        }
    }

    /** Marks the time window in which we are inside the VOLUME slider's SliderTrack. */
    private void hookTrack(ClassLoader cl) {
        Class<?> trackKt = XposedHelpers.findClass(TRACK_CLASS, cl);
        int count = 0;
        for (Method m : trackKt.getDeclaredMethods()) {
            // Real name is "SliderTrack-bX_ciNE" (jadx shows it as m2789SliderTrackbX_ciNE).
            if (m.getName().startsWith("SliderTrack")) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        DEPTH.get()[0]++;
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int[] d = DEPTH.get();
                        if (d[0] > 0) d[0]--;
                    }
                });
                count++;
            }
        }
        XposedBridge.log(TAG + ": hooked " + count + " SliderTrack method(s)");
    }

    /** Swaps the color list passed to Brush.verticalGradient while inside the volume SliderTrack. */
    private void hookBrush(ClassLoader cl) {
        Class<?> companion = XposedHelpers.findClass(BRUSH_COMPANION, cl);
        int count = 0;
        for (Method m : companion.getDeclaredMethods()) {
            if (m.getName().startsWith("verticalGradient-")) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (DEPTH.get()[0] <= 0) return; // not the volume slider -> leave alone
                        try {
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof List) {
                                    List<?> original = (List<?>) param.args[i];
                                    if (original.isEmpty() || original.get(0) == null) return;
                                    List<Object> custom = buildColorList(original.get(0).getClass());
                                    if (custom != null) {
                                        param.args[i] = custom;
                                        if (!sLoggedOk) {
                                            sLoggedOk = true;
                                            XposedBridge.log(TAG + ": applied " + custom.size() + " colors");
                                        }
                                    }
                                    return;
                                }
                            }
                        } catch (Throwable t) {
                            if (!sLoggedErr) {
                                sLoggedErr = true;
                                XposedBridge.log(TAG + ": brush hook error (ignored): " + t);
                            }
                        }
                    }
                });
                count++;
            }
        }
        XposedBridge.log(TAG + ": hooked " + count + " verticalGradient method(s)");
    }

    /** Reads the setting and builds a List of boxed androidx Color objects. Null = keep original. */
    private static List<Object> buildColorList(Class<?> colorClass) throws Throwable {
        // Same thing AndroidAppHelper.currentApplication() does internally.
        Object app = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        if (app == null) return null;
        ContentResolver cr = ((Context) app).getContentResolver();
        String raw = Settings.System.getString(cr, SETTING_KEY);
        if (raw == null || raw.trim().isEmpty()) return null;

        String[] parts = raw.split(",");
        if (parts.length < 2) return null;

        List<Object> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            String hex = part.trim().replace("#", "");
            long v = Long.parseLong(hex, 16);
            if (hex.length() <= 6) v |= 0xFF000000L; // no alpha given -> fully opaque
            out.add(boxColor(colorClass, toPackedColor((int) v)));
        }
        return out;
    }

    /** ARGB int -> Compose's packed Color long. */
    private static long toPackedColor(int argb) throws Throwable {
        if (sColorOfInt != null) {
            return (Long) sColorOfInt.invoke(null, argb);
        }
        // Compose sRGB: Color(color: Int) = Color(value = color.toULong() shl 32)
        return ((long) argb) << 32;
    }

    /** Wraps the packed long into a boxed androidx.compose.ui.graphics.Color. */
    private static Object boxColor(Class<?> colorClass, long packed) throws Throwable {
        if (sBoxMethod == null && sBoxConstructor == null) {
            // Name-agnostic lookup: static method taking one long and returning Color (= box-impl).
            for (Method m : colorClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) && p.length == 1
                        && p[0] == long.class && m.getReturnType() == colorClass) {
                    m.setAccessible(true);
                    sBoxMethod = m;
                    break;
                }
            }
            if (sBoxMethod == null) {
                sBoxConstructor = colorClass.getDeclaredConstructor(long.class);
                sBoxConstructor.setAccessible(true);
            }
        }
        if (sBoxMethod != null) return sBoxMethod.invoke(null, packed);
        return sBoxConstructor.newInstance(packed);
    }
}
