package ps.reso.instaeclipse.mods.direct;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
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
    private static final String TAG_FAKE_BTN = "IE_FAKE_GENERAL_BTN";
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

                // Exclude our own injected views
                if (TAG_OVERLAY.equals(v.getTag()) || TAG_FAKE_BTN.equals(v.getTag())) {
                    continue;
                }

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

                if (isGeneral) generalBtn = v;
                if (isPrimary) primaryBtn = v;

                // Find the chat list (largest RecyclerView vertically)
                if (v instanceof androidx.recyclerview.widget.RecyclerView) {
                    if (chatList == null || v.getHeight() > chatList.getHeight()) {
                        chatList = (androidx.recyclerview.widget.RecyclerView) v;
                    }
                }

                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
                }
            }

            if (chatList == null) return;

            // 1. Manage the Overlay
            View overlay = decor.findViewWithTag(TAG_OVERLAY);
            if (overlay == null) {
                overlay = createOverlay(a);
                if (overlay != null) {
                    decor.addView(overlay);
                }
            }

            if (overlay != null) {
                int[] listLoc = new int[2];
                chatList.getLocationInWindow(listLoc);
                FrameLayout.LayoutParams overlayLp = (FrameLayout.LayoutParams) overlay.getLayoutParams();

                if (overlayLp.leftMargin != listLoc[0] || overlayLp.topMargin != listLoc[1] ||
                    overlayLp.width != chatList.getWidth() || overlayLp.height != chatList.getHeight()) {
                    overlayLp.leftMargin = listLoc[0];
                    overlayLp.topMargin = listLoc[1];
                    overlayLp.width = chatList.getWidth();
                    overlayLp.height = chatList.getHeight();
                    overlay.setLayoutParams(overlayLp);
                }

                if (isFakeGeneralActive) {
                    overlay.setVisibility(View.VISIBLE);
                    overlay.bringToFront();
                    chatList.setVisibility(View.INVISIBLE); // Hide real chats
                } else {
                    overlay.setVisibility(View.GONE);
                    chatList.setVisibility(View.VISIBLE);
                }
            }

            // 2. Manage the Fake General Button
            if (generalBtn != null && generalBtn.getWidth() > 0 && generalBtn.getHeight() > 0) {
                // Hide the real General button, but keep it taking up space
                generalBtn.setVisibility(View.INVISIBLE);

                int[] loc = new int[2];
                generalBtn.getLocationInWindow(loc);

                TextView fakeBtn = decor.findViewWithTag(TAG_FAKE_BTN);
                if (fakeBtn == null) {
                    fakeBtn = new TextView(a);
                    fakeBtn.setTag(TAG_FAKE_BTN);
                    fakeBtn.setText("General");
                    fakeBtn.setGravity(Gravity.CENTER);
                    fakeBtn.setClickable(true);

                    final View finalPrimary = primaryBtn;
                    fakeBtn.setOnClickListener(view -> {
                        isFakeGeneralActive = true;
                        sweep(a);
                        ModuleLog.line("(IE|FakeGeneral) Fake General button clicked. Overlay shown.");
                    });
                    decor.addView(fakeBtn);
                }

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fakeBtn.getLayoutParams();
                if (lp.leftMargin != loc[0] || lp.topMargin != loc[1] || lp.width != generalBtn.getWidth() || lp.height != generalBtn.getHeight()) {
                    lp.leftMargin = loc[0];
                    lp.topMargin = loc[1];
                    lp.width = generalBtn.getWidth();
                    lp.height = generalBtn.getHeight();
                    fakeBtn.setLayoutParams(lp);
                }

                // If scrolled off screen (e.g. in a viewpager or horizontal recyclerview), hide it
                if (loc[0] < 0 || loc[0] > decor.getWidth()) {
                    fakeBtn.setVisibility(View.GONE);
                } else {
                    fakeBtn.setVisibility(View.VISIBLE);
                    fakeBtn.bringToFront();
                }

                // Style the fake button
                if (isFakeGeneralActive) {
                    fakeBtn.setTextColor(Color.WHITE); // Selected style
                    fakeBtn.setTypeface(null, Typeface.BOLD);
                } else {
                    fakeBtn.setTextColor(Color.parseColor("#8E8E93")); // Unselected style
                    fakeBtn.setTypeface(null, Typeface.NORMAL);
                }
            }

            // 3. Hijack the Primary Button to disable Fake mode
            if (primaryBtn != null) {
                // We don't replace primary, we just add a touch listener to reset our state when it's clicked
                if (primaryBtn.getTag(1001) == null) {
                    primaryBtn.setTag(1001, true);
                    primaryBtn.setOnTouchListener((view, event) -> {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            if (isFakeGeneralActive) {
                                isFakeGeneralActive = false;
                                sweep(a);
                                ModuleLog.line("(IE|FakeGeneral) Primary button clicked. Overlay hidden.");
                            }
                        }
                        return false; // Let IG handle the actual click
                    });
                }

                // If Fake mode is active, visually unselect Primary (since we intercepted the click and IG stays on Primary)
                if (isFakeGeneralActive && primaryBtn instanceof TextView) {
                    ((TextView) primaryBtn).setTextColor(Color.parseColor("#8E8E93"));
                    ((TextView) primaryBtn).setTypeface(null, Typeface.NORMAL);
                } else if (!isFakeGeneralActive && primaryBtn instanceof TextView) {
                     // We let Instagram handle its own styling when Fake mode is off
                }
            }

        } catch (Throwable t) {
            // silent fail
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
