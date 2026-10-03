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
    private static final String TAG_GHOST_GENERAL = "IE_GHOST_GENERAL";
    private static final String TAG_GHOST_PRIMARY = "IE_GHOST_PRIMARY";
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

                // Do not re-process our own ghost views
                if (TAG_GHOST_GENERAL.equals(v.getTag())) isGeneral = false;
                if (TAG_GHOST_PRIMARY.equals(v.getTag())) isPrimary = false;

                if (isGeneral) generalBtn = findClickableParent(v);
                if (isPrimary) primaryBtn = findClickableParent(v);

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
                        listContainer.setOnTouchListener((view, event) -> true);
                    } else {
                        overlay.setVisibility(View.GONE);
                        listContainer.setOnTouchListener(null);
                    }
                }

                // Inject transparent ghost buttons directly over the real ones in their FrameLayout parents
                if (generalBtn != null && generalBtn.getParent() instanceof ViewGroup) {
                    ViewGroup parent = (ViewGroup) generalBtn.getParent();
                    if (parent.findViewWithTag(TAG_GHOST_GENERAL) == null) {
                        View ghost = new View(a);
                        ghost.setTag(TAG_GHOST_GENERAL);
                        ghost.setBackgroundColor(Color.TRANSPARENT);
                        ghost.setClickable(true);

                        final View finalGeneral = generalBtn;
                        ghost.setOnClickListener(view -> {
                            isFakeGeneralActive = true;
                            // Update selection visually
                            if (finalGeneral instanceof ViewGroup) {
                                for(int i=0; i<((ViewGroup)finalGeneral).getChildCount(); i++) {
                                     ((ViewGroup)finalGeneral).getChildAt(i).setSelected(true);
                                }
                            }
                            sweep(a);
                        });

                        ViewGroup.LayoutParams lp = generalBtn.getLayoutParams();
                        parent.addView(ghost, parent.indexOfChild(generalBtn) + 1, lp);

                        // Force real button to ignore clicks
                        generalBtn.setClickable(false);
                        generalBtn.setOnTouchListener((view, event) -> true);
                        ModuleLog.line("(IE|FakeGeneral) Ghost General button injected.");
                    }
                }

                if (primaryBtn != null && primaryBtn.getParent() instanceof ViewGroup) {
                    ViewGroup parent = (ViewGroup) primaryBtn.getParent();
                    if (parent.findViewWithTag(TAG_GHOST_PRIMARY) == null) {
                        View ghost = new View(a);
                        ghost.setTag(TAG_GHOST_PRIMARY);
                        ghost.setBackgroundColor(Color.TRANSPARENT);
                        ghost.setClickable(true);

                        final View finalPrimary = primaryBtn;
                        ghost.setOnClickListener(view -> {
                            isFakeGeneralActive = false;

                            // Re-enable clicks temporarily to trigger real logic
                            finalPrimary.setClickable(true);
                            finalPrimary.setOnTouchListener(null);
                            finalPrimary.performClick();

                            // Immediately disable again
                            finalPrimary.setClickable(false);
                            finalPrimary.setOnTouchListener((v, e) -> true);

                            sweep(a);
                        });

                        ViewGroup.LayoutParams lp = primaryBtn.getLayoutParams();
                        parent.addView(ghost, parent.indexOfChild(primaryBtn) + 1, lp);

                        // Force real button to ignore clicks
                        primaryBtn.setClickable(false);
                        primaryBtn.setOnTouchListener((view, event) -> true);
                        ModuleLog.line("(IE|FakeGeneral) Ghost Primary button injected.");
                    }
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
        return view; // fallback
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
