package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.view.MotionEvent;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.Set;
import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeHook {
    @SuppressLint("StaticFieldLeak")
    private static ImageView fakeOverlay = null;
    private static final Set<View> watchedDecors = new HashSet<>();
    private static int searchBarId = 0;
    private static int nullStateId = 0;
    private static int threadHeaderId = 0;

    // Global flag updated by UI layout listeners
    private static volatile boolean isGeneralTabCurrentlyActive = false;
    private static WeakReference<Activity> currentActivityRef = new WeakReference<>(null);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (!FeatureFlags.fakeGeneralMode) return;

        installDataHook(bridge, classLoader);

        XC_MethodHook resumeHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                currentActivityRef = new WeakReference<>(activity);
                ensureIds(activity);

                final View decor = activity.getWindow().getDecorView();
                if (!watchedDecors.add(decor)) {
                    checkAndApplyFakeGeneral(activity);
                    return;
                }

                ensureOverlay(activity);

                decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    if (!FeatureFlags.fakeGeneralMode) return;
                    checkAndApplyFakeGeneral(activity);
                });
                decor.getViewTreeObserver().addOnScrollChangedListener(() -> {
                    if (!FeatureFlags.fakeGeneralMode) return;
                    checkAndApplyFakeGeneral(activity);
                });
                decor.getViewTreeObserver().addOnPreDrawListener(() -> {
                    if (FeatureFlags.fakeGeneralMode) checkAndApplyFakeGeneral(activity);
                    return true;
                });
                hookTouchEvents(activity);
                checkAndApplyFakeGeneral(activity);
            }
        };

        for (String act : new String[]{"com.instagram.modal.ModalActivity", "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                XposedHelpers.findAndHookMethod(act, classLoader, "onResume", resumeHook);
            } catch (Throwable t) {
                ModuleLog.line("(IE|FakeGeneral) ⚠️ hook " + act + ": " + t.getMessage());
            }
        }
    }

    private void hookTouchEvents(Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            // Try hooking dispatchTouchEvent on the activity to track clicks on tabs
            XposedHelpers.findAndHookMethod(a.getClass(), "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isInbox(a)) return;
                    MotionEvent event = (MotionEvent) param.args[0];

                    if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_UP) {
                        View clicked = findChildByCoordinates((ViewGroup) decor, event.getRawX(), event.getRawY());
                        String text = extractTextFromView(clicked);

                        if (!text.isEmpty()) {
                            if (text.contains("general") || text.contains("generale") || text.contains("allgemein") || text.contains("général")) {
                                isGeneralTabCurrentlyActive = true;
                                if (event.getAction() == MotionEvent.ACTION_UP) {
                                    showOverlay(a, decor);
                                    ModuleLog.line("(IE|FakeGeneral) Active via touch predictive");
                                }
                            } else if (text.contains("primary") || text.contains("principale") || text.contains("request") || text.contains("richiest")) {
                                isGeneralTabCurrentlyActive = false;
                                if (event.getAction() == MotionEvent.ACTION_UP) {
                                    hideOverlay();
                                    ModuleLog.line("(IE|FakeGeneral) Inactive via touch predictive");
                                }
                            }
                        }
                    }

                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        // Give UI a moment to update selection state as fallback
                        decor.postDelayed(() -> {
                            if (!isInbox(a)) return;
                            boolean active = isGeneralTabActive(decor);
                            if (active != isGeneralTabCurrentlyActive) {
                                isGeneralTabCurrentlyActive = active;
                                ModuleLog.line("(IE|FakeGeneral) State updated via postDelayed to: " + active);
                                if (active) {
                                    showOverlay(a, decor);
                                } else {
                                    hideOverlay();
                                }
                            }
                        }, 100);
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) ⚠️ touch hook failed: " + t.getMessage());
        }
    }

    private static Integer extractFolderType(Object item) {
        if (item == null) return null;
        try {
            for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == int.class || f.getType() == Integer.class) {
                        String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                        if (name.contains("folder")) {
                            f.setAccessible(true);
                            return (Integer) f.get(item);
                        }
                    }
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    private void installDataHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.fakeGeneralMode) return;

                Object r = param.getResult();
                if (!(r instanceof java.util.List<?> list) || list.isEmpty()) return;

                // Check arguments in case folder type is passed directly
                for (Object arg : param.args) {
                    if (arg instanceof Integer) {
                        int val = (Integer) arg;
                        if (val == 1) isGeneralTabCurrentlyActive = true;
                        else if (val == 0) isGeneralTabCurrentlyActive = false;
                    }
                }

                // Fallback heuristic: check the list items using reflection for folder type.
                int generalCount = 0;
                int primaryCount = 0;
                try {
                    for (int i = 0; i < Math.min(list.size(), 15); i++) {
                        Object item = list.get(i);
                        Integer fType = extractFolderType(item);
                        if (fType != null) {
                            if (fType == 1) generalCount++;
                            else if (fType == 0) primaryCount++;
                        }
                    }
                } catch (Throwable ignored) {}

                if (generalCount > 0 && primaryCount == 0) {
                    isGeneralTabCurrentlyActive = true;
                    ModuleLog.line("(IE|FakeGeneral) Data heuristic detected General tab");
                } else if (primaryCount > 0 && generalCount == 0) {
                    isGeneralTabCurrentlyActive = false;
                    ModuleLog.line("(IE|FakeGeneral) Data heuristic detected Primary tab");
                }

                // If UI needs to be updated with new flag
                Activity a = currentActivityRef.get();
                if (a != null) {
                    mainHandler.post(() -> {
                        checkAndApplyFakeGeneral(a);
                    });
                }

                if (!isGeneralTabCurrentlyActive) return;

                // If we are in the General tab, return an empty list natively
                try {
                    param.setResult(new java.util.ArrayList<>());
                } catch (Throwable ignored) {}
            }
        };

        try {
            int n = 0;
            java.util.List<MethodData> anchorMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("DirectThreadStoreImpl.getSortedCopyOfThreadSummaries")));

            if (!anchorMethods.isEmpty()) {
                String targetClassName = anchorMethods.get(0).getClassName();
                try {
                    Class<?> targetClass = classLoader.loadClass(targetClassName);
                    for (java.lang.reflect.Method m : targetClass.getDeclaredMethods()) {
                        if (m.getReturnType() == java.util.List.class) {
                            try { XposedBridge.hookMethod(m, filter); n++; }
                            catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (n > 0) FeatureStatusTracker.setHooked("FakeGeneralMode");
            ModuleLog.line("(IE|FakeGeneral) data filter hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) ⚠️ data filter: " + t.getMessage());
        }
    }

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Activity a) {
        if (searchBarId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        searchBarId    = r.getIdentifier("direct_inbox_action_bar", "id", pkg);
        if (searchBarId == 0) searchBarId = r.getIdentifier("direct_search_bar_container", "id", pkg);
        nullStateId    = r.getIdentifier("direct_inbox_null_state", "id", pkg);
        threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
    }

    private static boolean isInbox(Activity a) {
        if (threadHeaderId != 0 && a.findViewById(threadHeaderId) != null) return false;
        return (searchBarId != 0 && a.findViewById(searchBarId) != null)
                || (nullStateId != 0 && a.findViewById(nullStateId) != null);
    }

    private void ensureOverlay(Activity a) {
        if (fakeOverlay != null) return;
        try {
            fakeOverlay = new ImageView(a);
            fakeOverlay.setImageResource(R.drawable.fake_general_empty);
            fakeOverlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
            fakeOverlay.setBackgroundColor(Color.BLACK);
            // Adjust top margin so we don't cover the tab layout entirely if needed,
            // but the prompt says "Al posto della lista dei messaggi, deve apparire a tutto schermo un'immagine vuota"
            // Let's place it over the inbox recycler list if possible, or full screen if not.
            // Full screen is easier and robust, but we must allow clicking back to Primary.
            // If it covers the tabs, the user can't click back. So it MUST NOT cover the tabs.

            // Wait, if it covers everything, user can't navigate.
            // Let's attach it to the inbox container if possible, instead of DecorView.
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) ⚠️ overlay creation failed: " + t.getMessage());
        }
    }

    private void checkAndApplyFakeGeneral(Activity a) {
        // Broadened check: do not strictly require isInbox to update the state,
        // just hide the overlay if we aren't in inbox.
        // But we DO want to detect if General is active anytime it's on screen.
        View decor = a.getWindow().getDecorView();
        boolean isGeneralActive = isGeneralTabActive(decor);

        if (isGeneralActive == isGeneralTabCurrentlyActive && fakeOverlay != null) {
            if (!isInbox(a)) {
                hideOverlay();
                return;
            }
            if (isGeneralActive && fakeOverlay.getVisibility() == View.VISIBLE) return;
            if (!isGeneralActive && fakeOverlay.getVisibility() == View.GONE) return;
        }

        // Update the global flag so the data hook knows what to do
        isGeneralTabCurrentlyActive = isGeneralActive;

        if (!isInbox(a)) {
            hideOverlay();
            return;
        }

        if (isGeneralActive) {
            showOverlay(a, decor);
        } else {
            hideOverlay();
        }
    }

    private void showOverlay(Activity a, View decor) {
        if (fakeOverlay == null) ensureOverlay(a);
        if (fakeOverlay == null) return;

        // Try to find the inbox list to cover just the list, not the tabs.
        // Usually the recycler view has id 'recycler_view' or similar inside the inbox layout.
        View listContainer = null;
        if (searchBarId != 0) {
            View searchBar = a.findViewById(searchBarId);
            if (searchBar != null && searchBar.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) searchBar.getParent();
                // Usually the RecyclerView is a sibling or inside a sibling.
                // Let's just traverse and find the first RecyclerView.
                listContainer = findRecyclerView(parent);
                if (listContainer == null) listContainer = findRecyclerView((ViewGroup) decor);
            }
        }
        if (listContainer == null) listContainer = findRecyclerView((ViewGroup) decor);

        if (listContainer != null && listContainer.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) listContainer.getParent();
            if (fakeOverlay.getParent() != parent) {
                if (fakeOverlay.getParent() != null) {
                    ((ViewGroup) fakeOverlay.getParent()).removeView(fakeOverlay);
                }
                parent.addView(fakeOverlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            fakeOverlay.setVisibility(View.VISIBLE);
            fakeOverlay.bringToFront();
            // Also hide the recycler view to prevent scrolling/clicks behind the overlay.
            listContainer.setVisibility(View.INVISIBLE);
        } else {
            // Fallback: attach to decor directly, it's safer than relying on android.R.id.content
            if (decor instanceof ViewGroup) {
                 if (fakeOverlay.getParent() != decor) {
                     if (fakeOverlay.getParent() != null) {
                         ((ViewGroup) fakeOverlay.getParent()).removeView(fakeOverlay);
                     }
                     ((ViewGroup) decor).addView(fakeOverlay, new ViewGroup.LayoutParams(
                             ViewGroup.LayoutParams.MATCH_PARENT,
                             ViewGroup.LayoutParams.MATCH_PARENT));
                 }
                 fakeOverlay.setVisibility(View.VISIBLE);
                 fakeOverlay.bringToFront();
            }
        }
    }

    private void hideOverlay() {
        if (fakeOverlay != null && fakeOverlay.getVisibility() == View.VISIBLE) {
            fakeOverlay.setVisibility(View.GONE);
            if (fakeOverlay.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) fakeOverlay.getParent();
                View recycler = findRecyclerView(parent);
                if (recycler != null) {
                    recycler.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private View findRecyclerView(ViewGroup vg) {
        if (vg == null) return null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View v = vg.getChildAt(i);
            if (v.getClass().getName().contains("RecyclerView")) {
                return v;
            } else if (v instanceof ViewGroup) {
                View found = findRecyclerView((ViewGroup) v);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isGeneralTabActive(View root) {
        if (root == null) return false;

        String s = "";
        if (root instanceof android.widget.TextView && ((android.widget.TextView) root).getText() != null) {
            s = ((android.widget.TextView) root).getText().toString().toLowerCase(java.util.Locale.ROOT).trim();
        } else if (root.getContentDescription() != null) {
            s = root.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT).trim();
        }

        if (!s.isEmpty()) {
            if (s.equals("general") || s.equals("generale") || s.equals("allgemein") || s.equals("général") || s.contains(" general ") || s.startsWith("general ")) {
                boolean selected = root.isSelected() || root.isActivated();

                if (!selected) {
                    CharSequence desc = root.getContentDescription();
                    if (desc != null) {
                        String d = desc.toString().toLowerCase(java.util.Locale.ROOT);
                        if (d.contains("selected") || d.contains("selezionato") || d.contains("ausgewählt") || d.contains("sélectionné")) {
                            selected = true;
                        }
                    }
                }

                if (!selected) {
                    View p = root;
                    for (int i = 0; i < 3; i++) {
                        if (p.getParent() instanceof View) {
                            p = (View) p.getParent();
                            if (p.isSelected() || p.isActivated()) {
                                selected = true;
                                break;
                            }
                        } else {
                            break;
                        }
                    }
                }

                if (selected) {
                    return true;
                }
            }
        }

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (isGeneralTabActive(vg.getChildAt(i))) {
                    return true;
                }
            }
        }

        return false;
    }

    private View findChildByCoordinates(ViewGroup root, float x, float y) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                int[] location = new int[2];
                child.getLocationOnScreen(location);
                int childX = location[0];
                int childY = location[1];
                if (x >= childX && x <= childX + child.getWidth() &&
                    y >= childY && y <= childY + child.getHeight()) {
                    if (child instanceof ViewGroup) {
                        View descendant = findChildByCoordinates((ViewGroup) child, x, y);
                        if (descendant != null) return descendant;
                    }
                    return child;
                }
            }
        }
        return null;
    }

    private String extractTextFromView(View v) {
        if (v == null) return "";
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            return cs != null ? cs.toString().toLowerCase(java.util.Locale.ROOT) : "";
        }
        if (v.getContentDescription() != null) {
            return v.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String t = extractTextFromView(vg.getChildAt(i));
                if (!t.isEmpty()) return t;
            }
        }
        return "";
    }
}
