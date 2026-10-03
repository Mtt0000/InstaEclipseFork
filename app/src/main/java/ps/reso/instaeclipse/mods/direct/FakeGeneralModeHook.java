package ps.reso.instaeclipse.mods.direct;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeHook {
    private static final String TAG_OVERLAY = "IE_FAKE_GENERAL_OVERLAY";
    private static final Set<View> watchedDecors = Collections.newSetFromMap(new WeakHashMap<>());

    // Using a boolean flag to track if we're supposed to show the overlay
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

            while (!stack.isEmpty()) {
                View v = stack.pop();

                // IG uses compose views now or complex wrappers for buttons.
                // We must check if the text is 'General' or 'Primary'
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

                // Identify the main list container (usually a RecyclerView in a FrameLayout)
                if (v instanceof androidx.recyclerview.widget.RecyclerView) {
                    if (v.getParent() instanceof ViewGroup) {
                        listContainer = (ViewGroup) v.getParent();
                    }
                }

                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
                }
            }

            if (listContainer != null) {
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
                        listContainer.setOnTouchListener((view, event) -> true);
                    } else {
                        overlay.setVisibility(View.GONE);
                        listContainer.setOnTouchListener(null);
                    }
                }

                if (generalBtn != null && primaryBtn != null) {
                    // Overwrite the click behavior. Note: standard click listeners might get overridden by IG.
                    // We hook onto touch events to act before click processing.
                    final View finalGeneral = generalBtn;
                    finalGeneral.setOnTouchListener((view, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            isFakeGeneralActive = true;
                            sweep(a);
                        }
                        return true; // We consume the touch
                    });

                    final View finalPrimary = primaryBtn;
                    finalPrimary.setOnTouchListener((view, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            isFakeGeneralActive = false;
                            view.performClick();
                            sweep(a);
                        }
                        return false;
                    });
                }
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
