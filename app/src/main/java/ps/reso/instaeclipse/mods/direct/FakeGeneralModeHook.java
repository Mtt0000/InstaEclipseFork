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

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeHook {

    private static final String TAG_OVERLAY = "IE_FAKE_GENERAL_OVERLAY";
    private static final String TAG_GHOST_GENERAL = "IE_GHOST_GENERAL";
    private static final String TAG_GHOST_PRIMARY = "IE_GHOST_PRIMARY";
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
            View decorView = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (!(decorView instanceof ViewGroup)) return;
            ViewGroup decor = (ViewGroup) decorView;

            ArrayDeque<View> stack = new ArrayDeque<>();
            stack.push(decor);

            View generalBtn = null;
            View primaryBtn = null;
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

                if (TAG_GHOST_GENERAL.equals(v.getTag())) isGeneral = false;
                if (TAG_GHOST_PRIMARY.equals(v.getTag())) isPrimary = false;

                if (isGeneral) generalBtn = findClickableParent(v);
                if (isPrimary) primaryBtn = findClickableParent(v);

                if (v instanceof androidx.recyclerview.widget.RecyclerView) {
                    chatList = (androidx.recyclerview.widget.RecyclerView) v;
                }

                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
                }
            }

            if (chatList != null && chatList.getParent() instanceof ViewGroup) {
                ViewGroup listContainer = (ViewGroup) chatList.getParent();

                View overlay = decor.findViewWithTag(TAG_OVERLAY);
                if (overlay == null) {
                    overlay = createOverlay(a);
                    if (overlay != null) {
                        decor.addView(overlay);
                    }
                }

                if (overlay != null) {
                    // Update overlay position to match listContainer
                    int[] listLoc = new int[2];
                    listContainer.getLocationInWindow(listLoc);
                    FrameLayout.LayoutParams overlayLp = (FrameLayout.LayoutParams) overlay.getLayoutParams();
                    if (overlayLp.leftMargin != listLoc[0] || overlayLp.topMargin != listLoc[1] ||
                        overlayLp.width != listContainer.getWidth() || overlayLp.height != listContainer.getHeight()) {

                        overlayLp.leftMargin = listLoc[0];
                        overlayLp.topMargin = listLoc[1];
                        overlayLp.width = listContainer.getWidth();
                        overlayLp.height = listContainer.getHeight();
                        overlay.setLayoutParams(overlayLp);
                    }

                    if (isFakeGeneralActive) {
                        overlay.setVisibility(View.VISIBLE);
                        overlay.bringToFront();
                        chatList.setVisibility(View.INVISIBLE);
                    } else {
                        overlay.setVisibility(View.GONE);
                        chatList.setVisibility(View.VISIBLE);
                    }
                }
            }

            // Inject transparent ghost buttons directly over the real ones in the DecorView
            if (generalBtn != null && generalBtn.getWidth() > 0 && generalBtn.getHeight() > 0) {
                int[] loc = new int[2];
                generalBtn.getLocationInWindow(loc);

                View ghost = decor.findViewWithTag(TAG_GHOST_GENERAL);
                if (ghost == null) {
                    ghost = new View(a);
                    ghost.setTag(TAG_GHOST_GENERAL);
                    ghost.setBackgroundColor(Color.TRANSPARENT);
                    ghost.setClickable(true);

                    final View finalGeneral = generalBtn;
                    ghost.setOnClickListener(view -> {
                        isFakeGeneralActive = true;
                        // Select visually
                        finalGeneral.performClick();
                        sweep(a);
                        ModuleLog.line("(IE|FakeGeneral) Fake General activated via ghost click.");
                    });
                    decor.addView(ghost);
                }

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ghost.getLayoutParams();
                if (lp.leftMargin != loc[0] || lp.topMargin != loc[1] || lp.width != generalBtn.getWidth() || lp.height != generalBtn.getHeight()) {
                    lp.leftMargin = loc[0];
                    lp.topMargin = loc[1];
                    lp.width = generalBtn.getWidth();
                    lp.height = generalBtn.getHeight();
                    ghost.setLayoutParams(lp);
                }
                ghost.bringToFront();
            }

            if (primaryBtn != null && primaryBtn.getWidth() > 0 && primaryBtn.getHeight() > 0) {
                int[] loc = new int[2];
                primaryBtn.getLocationInWindow(loc);

                View ghost = decor.findViewWithTag(TAG_GHOST_PRIMARY);
                if (ghost == null) {
                    ghost = new View(a);
                    ghost.setTag(TAG_GHOST_PRIMARY);
                    ghost.setBackgroundColor(Color.TRANSPARENT);
                    ghost.setClickable(true);

                    final View finalPrimary = primaryBtn;
                    ghost.setOnClickListener(view -> {
                        isFakeGeneralActive = false;
                        finalPrimary.performClick();
                        sweep(a);
                        ModuleLog.line("(IE|FakeGeneral) Fake General deactivated via ghost click.");
                    });
                    decor.addView(ghost);
                }

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ghost.getLayoutParams();
                if (lp.leftMargin != loc[0] || lp.topMargin != loc[1] || lp.width != primaryBtn.getWidth() || lp.height != primaryBtn.getHeight()) {
                    lp.leftMargin = loc[0];
                    lp.topMargin = loc[1];
                    lp.width = primaryBtn.getWidth();
                    lp.height = primaryBtn.getHeight();
                    ghost.setLayoutParams(lp);
                }
                ghost.bringToFront();
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
