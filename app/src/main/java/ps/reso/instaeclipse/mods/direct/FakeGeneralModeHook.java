package ps.reso.instaeclipse.mods.direct;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeHook {

    // UI state tracking
    private static final Set<View> watchedDecors = Collections.newSetFromMap(new WeakHashMap<>());
    public static boolean isGeneralTabActive = false; // Tracks which tab the user is actually looking at

    // Data layer anchor
    private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";

    // --- 1. DATA LAYER: Intercept thread list load ---
    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.isFakeGeneralModeEnabled) return;

                Object r = param.getResult();
                if (!(r instanceof java.util.List<?> list) || list.isEmpty()) return;

                // We solely rely on our UI tracking state to decide whether to hide threads
                if (isGeneralTabActive) {
                    ModuleLog.line("(IE|FakeGeneralData) General Tab is active in UI, forcing empty list.");
                    param.setResult(new java.util.ArrayList<>());
                }
            }
        };

        try {
            int hooks = 0;
            java.util.List<MethodData> anchorMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)));

            if (!anchorMethods.isEmpty()) {
                String targetClassName = anchorMethods.get(0).getClassName();
                Class<?> clazz = classLoader.loadClass(targetClassName);
                for (Method m : clazz.getDeclaredMethods()) {
                    if (java.util.List.class.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length > 0) {
                        XposedBridge.hookMethod(m, filter);
                        hooks++;
                    }
                }
            }
            if (hooks > 0) ModuleLog.line("(IE|FakeGeneralData) ✅ Hooked thread store.");
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneralData) ❌ " + t.getMessage());
        }
    }

    // --- 2. UI LAYER: Track which tab is active ---
    public static void watchActivity(final Activity a) {
        if (a == null || !FeatureFlags.isFakeGeneralModeEnabled) return;
        try {
            sweep(a);
            final View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null || !watchedDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (FeatureFlags.isFakeGeneralModeEnabled) sweep(a);
            });
        } catch (Throwable ignored) {}
    }

    private static void sweep(Activity a) {
        try {
            View root = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (!(root instanceof ViewGroup)) return;

            ArrayDeque<View> stack = new ArrayDeque<>();
            stack.push(root);

            View generalBtn = null;
            View primaryBtn = null;

            while (!stack.isEmpty()) {
                View v = stack.pop();

                boolean isGeneral = false;
                boolean isPrimary = false;

                if (v instanceof TextView) {
                    CharSequence text = ((TextView) v).getText();
                    if (text != null) {
                        if (text.toString().equalsIgnoreCase("General")) isGeneral = true;
                        if (text.toString().equalsIgnoreCase("Primary") || text.toString().equalsIgnoreCase("Requests")) isPrimary = true;
                    }
                }

                CharSequence desc = v.getContentDescription();
                if (desc != null) {
                    if (desc.toString().equalsIgnoreCase("General")) isGeneral = true;
                    if (desc.toString().equalsIgnoreCase("Primary") || desc.toString().equalsIgnoreCase("Requests")) isPrimary = true;
                }

                if (isGeneral) generalBtn = findClickableParent(v);
                if (isPrimary) primaryBtn = findClickableParent(v);

                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
                }
            }

            // We intercept touches BEFORE IG processes the click to update our state flag immediately.
            // We DO NOT consume the event, we let it pass to IG so it loads the tab normally.
            if (generalBtn != null && generalBtn.getTag() == null) {
                generalBtn.setTag("HOOKED");
                generalBtn.setOnTouchListener((view, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        isGeneralTabActive = true;
                        ModuleLog.line("(IE|FakeGeneral) UI detected switch to General Tab.");
                    }
                    return false; // Let IG handle the actual click
                });
            }

            if (primaryBtn != null && primaryBtn.getTag() == null) {
                primaryBtn.setTag("HOOKED");
                primaryBtn.setOnTouchListener((view, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        isGeneralTabActive = false;
                        ModuleLog.line("(IE|FakeGeneral) UI detected switch to Primary/Requests Tab.");
                    }
                    return false; // Let IG handle the actual click
                });
            }

        } catch (Throwable t) {
            // silent fail
        }
    }

    private static View findClickableParent(View view) {
        View current = view;
        while (current != null) {
            if (current.isClickable()) return current;
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }
        return view;
    }
}
