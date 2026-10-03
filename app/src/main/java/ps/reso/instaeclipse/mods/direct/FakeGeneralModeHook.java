package ps.reso.instaeclipse.mods.direct;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeHook {
    private static final String TAG_FAKE_BTN = "IE_FAKE_GENERAL_BTN";
    private static final Set<View> watchedDecors = Collections.newSetFromMap(new WeakHashMap<>());

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

            View composeView = null;
            View generalBtn = null;

            while (!stack.isEmpty()) {
                View v = stack.pop();

                // Find "General" button
                if (v instanceof Button) {
                    CharSequence desc = v.getContentDescription();
                    if (desc != null && desc.toString().equalsIgnoreCase("General")) {
                        // ignore if we already hit fake btn
                        if (!TAG_FAKE_BTN.equals(v.getTag())) {
                            generalBtn = v;
                        }
                    }
                }

                // Find target view: compose view
                if (v.getClass().getName().contains("ComposeView")) {
                    composeView = v;
                }

                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
                }
            }

            if (generalBtn != null && composeView != null) {
                ViewGroup parent = (ViewGroup) generalBtn.getParent();
                if (parent != null) {
                    // Check if already injected fake button
                    if (parent.findViewWithTag(TAG_FAKE_BTN) == null) {
                        Button fakeBtn = new Button(a);
                        fakeBtn.setText("General");
                        fakeBtn.setTag(TAG_FAKE_BTN);
                        fakeBtn.setContentDescription("Fake General");
                        fakeBtn.setBackgroundColor(Color.TRANSPARENT);
                        fakeBtn.setTextColor(Color.WHITE); // Default
                        fakeBtn.setLayoutParams(generalBtn.getLayoutParams());

                        // Fake click shows composeView and hides actual general messages
                        final View finalComposeView = composeView;
                        fakeBtn.setOnClickListener(v -> {
                             // Hide actual messages, show composeView
                             finalComposeView.setVisibility(View.VISIBLE);
                        });

                        int idx = parent.indexOfChild(generalBtn);
                        parent.addView(fakeBtn, idx);
                        generalBtn.setVisibility(View.GONE); // Hide real
                        ModuleLog.line("(IE|FakeGeneral) Injected fake General button.");
                    } else {
                        // keep original hidden just in case
                        generalBtn.setVisibility(View.GONE);
                    }
                }
            }

        } catch (Throwable t) {
             ModuleLog.line("(IE|FakeGeneral) sweep error: " + t);
        }
    }
}
