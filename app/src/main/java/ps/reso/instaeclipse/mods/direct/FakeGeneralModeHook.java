package ps.reso.instaeclipse.mods.direct;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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

public class FakeGeneralModeHook {
    private static final String TAG_OVERLAY = "IE_FAKE_GENERAL_OVERLAY";
    private static final Set<View> watchedDecors = Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean isFakeGeneralActive = false;

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
            ViewGroup listContainer = null;
            androidx.recyclerview.widget.RecyclerView chatList = null;

            while (!stack.isEmpty()) {
                View v = stack.pop();

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

                if (isGeneral) generalBtn = findClickableParent(v);
                if (isPrimary) primaryBtn = findClickableParent(v);

                if (v instanceof androidx.recyclerview.widget.RecyclerView) {
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
                // Ensure overlay exists
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
                        // intercept touch in list container so you can't scroll the list underneath
                        overlay.setOnTouchListener((view, event) -> true);
                        chatList.setVisibility(View.INVISIBLE); // Explicitly hide real list
                    } else {
                        overlay.setVisibility(View.GONE);
                        overlay.setOnTouchListener(null);
                        chatList.setVisibility(View.VISIBLE);
                    }
                }

                if (generalBtn != null && primaryBtn != null) {
                    // Overwrite the click behavior completely by replacing touch listeners
                    final View finalGeneral = generalBtn;
                    finalGeneral.setOnTouchListener((view, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            isFakeGeneralActive = true;
                            // WE DO NOT CALL view.performClick() -> IG never knows we clicked it
                            sweep(a);
                            ModuleLog.line("(IE|FakeGeneral) General Hijacked.");
                        }
                        return true; // Consume touch entirely!
                    });

                    final View finalPrimary = primaryBtn;
                    finalPrimary.setOnTouchListener((view, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            isFakeGeneralActive = false;
                            // Temporarily remove our hook to let the real click pass through to Instagram
                            view.setOnTouchListener(null);
                            view.performClick();
                            sweep(a);
                            ModuleLog.line("(IE|FakeGeneral) Primary Clicked.");
                        }
                        return false;
                    });
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) sweep error: " + t);
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

    private static View createOverlay(Context ctx) {
        try {
            FrameLayout layout = new FrameLayout(ctx);
            layout.setTag(TAG_OVERLAY);
            layout.setBackgroundColor(Color.parseColor("#000000")); // Solid black background to cover chats
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
