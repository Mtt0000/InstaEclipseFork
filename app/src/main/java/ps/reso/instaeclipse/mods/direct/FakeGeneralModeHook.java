package ps.reso.instaeclipse.mods.direct;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.content.res.Resources;
import android.content.Context;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeHook {
    private static final String TAG_OVERLAY = "IE_FAKE_GENERAL_OVERLAY";
    private static final Set<View> watchedDecors = Collections.newSetFromMap(new WeakHashMap<>());

    // Store state
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

                // Find "General" and "Primary" buttons by text/content description
                CharSequence desc = v.getContentDescription();
                if (desc != null) {
                    if (desc.toString().equalsIgnoreCase("General")) {
                        generalBtn = v;
                    } else if (desc.toString().equalsIgnoreCase("Primary")) {
                        primaryBtn = v;
                    }
                }

                // Identify the main list container (usually a RecyclerView in a FrameLayout)
                if (v instanceof androidx.recyclerview.widget.RecyclerView) {
                    // Try to grab the parent of the RecyclerView which is usually the content area
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
                        // Make sure it's on top
                        overlay.bringToFront();
                    } else {
                        overlay.setVisibility(View.GONE);
                    }
                }

                if (generalBtn != null && primaryBtn != null) {
                    // Hijack clicks by adding our own touch listener that intercepts the event completely
                    generalBtn.setOnTouchListener((view, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            isFakeGeneralActive = true;
                            sweep(a); // re-apply overlay
                            ModuleLog.line("(IE|FakeGeneral) Fake general activated.");
                        }
                        return true; // Consume event! Don't let IG see it.
                    });

                    primaryBtn.setOnTouchListener((view, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            isFakeGeneralActive = false;
                            // Trigger original click logic
                            view.performClick();
                            sweep(a);
                            ModuleLog.line("(IE|FakeGeneral) Primary activated, overlay hidden.");
                        }
                        return false; // let IG handle it normally
                    });
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
            layout.setBackgroundColor(Color.parseColor("#000000")); // Solid black background to cover chats
            layout.setClickable(true); // Intercept touches behind it

            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

            // Try to load the image we copied
            try {
                Context myContext = ctx.createPackageContext("ps.reso.instaeclipse", 0);
                int resId = myContext.getResources().getIdentifier("fake_general_empty", "drawable", "ps.reso.instaeclipse");
                if (resId != 0) {
                    Drawable d = myContext.getResources().getDrawable(resId, null);
                    iv.setImageDrawable(d);
                }
            } catch (Exception e) {
                 ModuleLog.line("(IE|FakeGeneral) Error loading image: " + e);
            }

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            layout.addView(iv, lp);

            layout.setVisibility(View.GONE);
            return layout;
        } catch (Exception e) {
            ModuleLog.line("(IE|FakeGeneral) Error creating overlay: " + e);
            return null;
        }
    }
}
