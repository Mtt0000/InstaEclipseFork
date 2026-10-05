package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

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

    // Global flag updated by the ThreadStateActionHandler hook
    private static volatile boolean isGeneralTabCurrentlyActive = false;
    private static WeakReference<Activity> currentActivityRef = new WeakReference<>(null);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (!FeatureFlags.fakeGeneralMode) return;

        installDataHook(bridge, classLoader);
        installStateTrackerHook(bridge, classLoader);

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

    private void installStateTrackerHook(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            java.util.List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("updateInboxAfterThreadsStateChange folder: ")));

            XC_MethodHook stateHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.fakeGeneralMode) return;

                    // The folder name is passed to this method. Usually the first or second string argument.
                    // Or we can just look for the enum/string in the args.
                    boolean stateChanged = false;
                    for (Object arg : param.args) {
                        if (arg instanceof String) {
                            String s = ((String) arg).toLowerCase(java.util.Locale.ROOT);
                            if (s.equals("general")) {
                                isGeneralTabCurrentlyActive = true;
                                stateChanged = true;
                                ModuleLog.line("(IE|FakeGeneral) State tracker set to General");
                            } else if (s.equals("primary") || s.equals("requests")) {
                                isGeneralTabCurrentlyActive = false;
                                stateChanged = true;
                                ModuleLog.line("(IE|FakeGeneral) State tracker set to " + s);
                            }
                        }
                    }

                    if (stateChanged) {
                        Activity a = currentActivityRef.get();
                        if (a != null) {
                            mainHandler.post(() -> checkAndApplyFakeGeneral(a));
                        }
                    }
                }
            };

            int n = 0;
            for (MethodData methodData : methods) {
                java.lang.reflect.Method method = methodData.getMethodInstance(classLoader);
                if (method != null) {
                    try {
                        XposedBridge.hookMethod(method, stateHook);
                        n++;
                    } catch (Throwable ignored) {}
                }
            }
            ModuleLog.line("(IE|FakeGeneral) State tracker hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) ⚠️ state tracker hook failed: " + t.getMessage());
        }
    }

    private void installDataHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.fakeGeneralMode || !isGeneralTabCurrentlyActive) return;

                Object r = param.getResult();
                if (!(r instanceof java.util.List<?> list) || list.isEmpty()) return;

                // We are reliably in the General tab based on the ThreadStateActionHandler tracker.
                // Clear the list to hide the chats natively.
                try {
                    param.setResult(new java.util.ArrayList<>());
                    ModuleLog.line("(IE|FakeGeneral) Emptied data list.");
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
            ModuleLog.line("(IE|FakeGeneral) Data filter hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) ⚠️ Data filter: " + t.getMessage());
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
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneral) ⚠️ overlay creation failed: " + t.getMessage());
        }
    }

    private void checkAndApplyFakeGeneral(Activity a) {
        View decor = a.getWindow().getDecorView();
        boolean isGeneralActive = isGeneralTabCurrentlyActive; // Driven by the state tracker now

        if (fakeOverlay != null) {
            if (!isInbox(a)) {
                hideOverlay();
                return;
            }
            if (isGeneralActive && fakeOverlay.getVisibility() == View.VISIBLE) return;
            if (!isGeneralActive && fakeOverlay.getVisibility() == View.GONE) return;
        }

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

        View listContainer = null;
        if (searchBarId != 0) {
            View searchBar = a.findViewById(searchBarId);
            if (searchBar != null && searchBar.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) searchBar.getParent();
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
            listContainer.setVisibility(View.INVISIBLE);
        } else {
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
}
