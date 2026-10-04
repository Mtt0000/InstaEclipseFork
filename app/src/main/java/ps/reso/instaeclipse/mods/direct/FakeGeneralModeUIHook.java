package ps.reso.instaeclipse.mods.direct;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeUIHook {
    private static final String TAG_OVERLAY = "IE_FAKE_GENERAL_OVERLAY";
    private static final Set<View> watchedDecors = Collections.newSetFromMap(new WeakHashMap<>());

    public static boolean isFakeGeneralActive = false;
    private static Activity currentActivity = null;

    public static void initZygote() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "performClick", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!FeatureFlags.isFakeGeneralModeEnabled) return;
                    View v = (View) param.thisObject;
                    handleViewInteraction(v);
                }
            });

            XposedHelpers.findAndHookMethod(View.class, "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!FeatureFlags.isFakeGeneralModeEnabled) return;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        View v = (View) param.thisObject;
                        handleViewInteraction(v);
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) Error hooking view clicks: " + t);
        }
    }

    private static void handleViewInteraction(View v) {
        boolean isGeneral = false;
        boolean isPrimary = false;

        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            if (text != null) {
                if (text.toString().equalsIgnoreCase("General")) isGeneral = true;
                if (text.toString().equalsIgnoreCase("Primary")) isPrimary = true;
            }
        }
        CharSequence desc = v.getContentDescription();
        if (desc != null) {
            if (desc.toString().equalsIgnoreCase("General")) isGeneral = true;
            if (desc.toString().equalsIgnoreCase("Primary")) isPrimary = true;
        }

        if (isGeneral && !isFakeGeneralActive) {
            isFakeGeneralActive = true;
            ModuleLog.line("(IE|FakeGeneral) General Tab Clicked. Activating Fake Mode.");
            if (currentActivity != null) sweep(currentActivity);
        } else if (isPrimary && isFakeGeneralActive) {
            isFakeGeneralActive = false;
            ModuleLog.line("(IE|FakeGeneral) Primary Tab Clicked. Deactivating Fake Mode.");
            if (currentActivity != null) sweep(currentActivity);
        }
    }

    public static void watchActivity(final Activity a) {
        if (a == null) return;
        currentActivity = a;
        if (!FeatureFlags.isFakeGeneralModeEnabled) return;
        try {
            sweep(a);
            final View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null || !watchedDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (FeatureFlags.isFakeGeneralModeEnabled) sweep(a);
            });
        } catch (Throwable ignored) {}
    }

    public static void sweep(Activity a) {
        if (a == null || !FeatureFlags.isFakeGeneralModeEnabled) return;
        try {
            View root = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (!(root instanceof ViewGroup)) return;

            ArrayDeque<View> stack = new ArrayDeque<>();
            stack.push(root);

            ViewGroup listContainer = null;
            androidx.recyclerview.widget.RecyclerView chatList = null;

            while (!stack.isEmpty()) {
                View v = stack.pop();

                if (v instanceof androidx.recyclerview.widget.RecyclerView) {
                    // Check if it's the direct inbox list (heuristics: it's a large recyclerview usually in a framelayout)
                    if (v.getParent() instanceof ViewGroup) {
                        listContainer = (ViewGroup) v.getParent();
                        chatList = (androidx.recyclerview.widget.RecyclerView) v;
                    }
                }

                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
                }
            }

            if (listContainer != null && chatList != null) {
                View overlay = listContainer.findViewWithTag(TAG_OVERLAY);
                if (overlay == null) {
                    overlay = createOverlay(a);
                    if (overlay != null) {
                        listContainer.addView(overlay, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    }
                }

                if (overlay != null) {
                    if (isFakeGeneralActive) {
                        overlay.setVisibility(View.VISIBLE);
                        overlay.bringToFront();
                        // HIDE the actual list so it can't be interacted with
                        chatList.setVisibility(View.GONE);
                    } else {
                        overlay.setVisibility(View.GONE);
                        // Show the actual list
                        chatList.setVisibility(View.VISIBLE);
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) sweep error: " + t);
        }
    }

    private static View createOverlay(Context ctx) {
        try {
            FrameLayout layout = new FrameLayout(ctx);
            layout.setTag(TAG_OVERLAY);
            layout.setBackgroundColor(Color.parseColor("#000000")); // Solid black background
            layout.setClickable(true);

            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

            try {
                Context myContext = ctx.createPackageContext("ps.reso.instaeclipse", 0);
                @SuppressLint("DiscouragedApi") int resId = myContext.getResources().getIdentifier("fake_general_empty", "drawable", "ps.reso.instaeclipse");
                if (resId != 0) {
                    @SuppressLint("UseCompatLoadingForDrawables") Drawable d = myContext.getResources().getDrawable(resId, null);
                    iv.setImageDrawable(d);
                }
            } catch (Exception ignored) { }

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            layout.addView(iv, lp);

            layout.setVisibility(View.GONE);
            return layout;
        } catch (Exception e) {
            return null;
        }
    }
}
