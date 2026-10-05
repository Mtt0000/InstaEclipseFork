package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hide Specific Chats: let the user hide chosen DM threads from the Direct inbox.
 *
 * Two parts:
 *  1. Inbox filter — hooks the DirectThreadStore's inbox thread-summary builder (anchored by the
 *     stable string "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries") and removes rows whose
 *     thread id is in the persistent hidden set (HiddenThreads). Each row exposes a
 *     com.instagram.model.direct.DirectThreadKey; its first String field is the thread id (same
 *     resolution KeepUnsentMessagesHook/UnsentThreadButtonHook already use).
 *  2. Hide toggle — injects an eye-off button into the OPEN thread's header (same global-layout
 *     pattern as UnsentThreadButtonHook); tapping it hides/unhides the current thread.
 *
 * Gated on FeatureFlags.hideSpecificChats.
 */
public class HideChatsHook {

    public static volatile String currentThreadId = null;

    private static final String TAG = "ie_hidechat_btn";
    private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";
    private static int threadHeaderId, backButtonId, tagKeyId;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        hookCurrentThread(bridge, classLoader);
        installInboxFilter(bridge, classLoader);
        installHeaderButton(classLoader);
    }

    // ── 1. Inbox thread-list filter ────────────────────────────────────────────
    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSpecificChats || HiddenThreads.isEmpty()) return;
                Object r = param.getResult();
                if (!(r instanceof java.util.List<?> list) || list.isEmpty()) return;

                // Type safety check: ensure the list contains thread summaries
                boolean foundValidId = false;
                for (int i = 0; i < Math.min(list.size(), 5); i++) {
                    Object item = list.get(i);
                    if (item != null && (item.getClass().getName().contains("DirectThread") || threadIdOfRow(item) != null)) {
                        foundValidId = true;
                        break;
                    }
                }
                if (!foundValidId) return;

                try {
                    java.util.List<Object> filtered = new java.util.ArrayList<>();
                    for (Object row : list) {
                        String id = threadIdOfRow(row);
                        if (id == null || !HiddenThreads.isHidden(id)) {
                            filtered.add(row);
                        }
                    }
                    if (filtered.size() != list.size()) {
                        param.setResult(filtered);
                    }
                } catch (Throwable ignored) {}
            }
        };
        try {
            int n = 0;
            java.util.List<MethodData> anchorMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)));

            if (!anchorMethods.isEmpty()) {
                String targetClassName = anchorMethods.get(0).getClassName();
                // Find all methods returning java.util.List by loading the class and reflecting it
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
            if (n > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
            ModuleLog.line("(IE|HideChats) inbox filter hooked " + n + " method(s) returning List in target class");
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inbox filter: " + t.getMessage());
        }
    }

    /** Finds the DirectThreadKey on an inbox row object and returns its thread id (first String). */
    private static String threadIdOfRow(Object row) {
        if (row == null) return null;
        try {
            Class<?> c = row.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().contains("DirectThreadKey")) continue;
                    f.setAccessible(true);
                    Object key = f.get(row);
                    String id = firstStringField(key);
                    if (id != null) return sanitizeId(id);
                }
                c = c.getSuperclass();
            }

            // Fallback for newer Instagram versions where thread ID might be a direct String field (e.g. mThreadId)
            c = row.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        String name = f.getName().toLowerCase();
                        if (name.contains("thread") && name.contains("id")) {
                            f.setAccessible(true);
                            Object v = f.get(row);
                            if (v instanceof String s && !s.isEmpty()) return sanitizeId(s);
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String sanitizeId(String id) {
        if (id == null) return null;
        id = id.trim();
        id = id.replace("\"", "");
        id = id.replace("'", "");
        id = id.replace("{", "");
        id = id.replace("}", "");
        id = id.replace("[", "");
        id = id.replace("]", "");
        return id.isEmpty() ? null : id;
    }

    // ── 2. Hide/unhide button in the thread header ─────────────────────────────
    private void installHeaderButton(ClassLoader classLoader) {
        XC_MethodHook resume = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSpecificChats) return;
                final Activity a = (Activity) param.thisObject;
                a.runOnUiThread(() -> registerListener(a));
            }
        };
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try { XposedHelpers.findAndHookMethod(act, classLoader, "onResume", resume); }
            catch (Throwable t) { ModuleLog.line("(IE|HideChats) ⚠️ hook " + act + ": " + t.getMessage()); }
        }
        ModuleLog.line("(IE|HideChats) ✅ installed");
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity a) {
        if (threadHeaderId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
        backButtonId   = r.getIdentifier("action_bar_button_back", "id", pkg);
        tagKeyId       = r.getIdentifier("direct_thread_view_layout_tag_key", "id", pkg);
    }

    private void registerListener(final Activity activity) {
        try {
            ensureIds(activity);
            if (tryInject(activity)) return;
            final View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (tryInject(activity)) {
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private boolean tryInject(Activity activity) {
        try {
            int mode = FeatureFlags.hideChatsMode;
            if (mode == 0) return true; // None

            View header = threadHeaderId != 0 ? activity.findViewById(threadHeaderId) : null;
            if (header == null) return false;

            View backTemp = backButtonId != 0 ? activity.findViewById(backButtonId) : null;
            if (backTemp == null) backTemp = findBackButton(header);
            final View back = backTemp;

            final View headerRoot = header;

            android.view.View.OnLongClickListener hideAction = v -> {
                String threadId = resolveThreadId(headerRoot);
                if (threadId == null) {
                    Toast.makeText(activity, I18n(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
                    return true;
                }
                boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
                Toast.makeText(activity,
                        I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden),
                        Toast.LENGTH_SHORT).show();
                ModuleLog.line("(IE|HideChats) toggled thread=" + threadId + " hidden=" + nowHidden);
                return true;
            };

            if (mode == 2) {
                // Long press back button
                if (back != null) {
                    if (back.getTag(backButtonId) == null) { // prevent multiple listener attachments if we inject again
                        back.setOnTouchListener(new android.view.View.OnTouchListener() {
                            private android.os.Handler handler = new android.os.Handler();
                            private boolean longPressTriggered = false;
                            private Runnable runnable = new Runnable() {
                                @Override
                                public void run() {
                                    longPressTriggered = true;
                                    hideAction.onLongClick(back);
                                }
                            };

                            @Override
                            public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
                                switch (event.getAction()) {
                                    case android.view.MotionEvent.ACTION_DOWN:
                                        longPressTriggered = false;
                                        handler.postDelayed(runnable, FeatureFlags.hideChatsLongPressTime * 1000L); // from settings
                                        break;
                                    case android.view.MotionEvent.ACTION_UP:
                                    case android.view.MotionEvent.ACTION_CANCEL:
                                        handler.removeCallbacks(runnable);
                                        if (longPressTriggered) {
                                            return true; // consume event if long press triggered
                                        }
                                        break;
                                }
                                return false; // let normal click pass through
                            }
                        });
                        back.setTag(backButtonId, true);
                    }
                    return true;
                }
                return false;
            }

            // mode 1: Eye button
            ViewGroup target;
            int insertAt;
            if (back != null && back.getParent() instanceof ViewGroup) {
                target = (ViewGroup) back.getParent();
                int bi = target.indexOfChild(back);
                boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                insertAt = rtl ? bi : bi + 1;
            } else if (header instanceof ViewGroup) {
                target = (ViewGroup) header;
                insertAt = 0;
            } else return false;

            if (target.findViewWithTag(TAG) != null) return true;

            android.widget.ImageView btn = new android.widget.ImageView(activity);
            btn.setTag(TAG);
            android.graphics.drawable.Drawable icon =
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_eye_off, Color.WHITE);
            if (icon != null) btn.setImageDrawable(icon);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            int pad = dp(activity, 9);
            btn.setPadding(pad, pad, pad, pad);
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setContentDescription("Hide chat");
            int sz = dp(activity, 40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.gravity = Gravity.CENTER_VERTICAL;
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> hideAction.onLongClick(v));

            try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
            catch (Throwable t) { target.addView(btn); }
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inject: " + t.getMessage());
            return false;
        }
    }

    private static String I18n(Activity a, int res) {
        return ps.reso.instaeclipse.utils.i18n.I18n.t(a, res);
    }

    /**
     * Best-effort title of the open thread (username, for the unhide-manager label). The
     * thread_title id resolves but findViewById returns null here, so scan the header subtree for
     * its TextViews and pick the @username (a handle-like token), falling back to the first name.
     */
    private String threadTitle(Activity a) {
        try {
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            if (!(header instanceof ViewGroup)) return "";
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectTexts((ViewGroup) header, texts);
            for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t; // handle-like
            return texts.isEmpty() ? "" : texts.get(0);
        } catch (Throwable t) { return ""; }
    }

    private void collectTexts(ViewGroup vg, java.util.List<String> out) {
        for (int i = 0; i < vg.getChildCount() && out.size() < 12; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) c).getText();
                if (cs != null && cs.toString().trim().length() > 0) out.add(cs.toString().trim());
            } else if (c instanceof ViewGroup) {
                collectTexts((ViewGroup) c, out);
            }
        }
    }


    private String resolveThreadId(View header) {
        String found = null;
        View v = header;
        int up = 0;
        while (v != null && up++ < 8) {
            try {
                if (tagKeyId != 0) {
                    Object t = v.getTag(tagKeyId);
                    if (t != null) {
                        String id = threadIdFromAny(t);
                        if (id != null && found == null) found = id;
                    }
                }
                Object plain = v.getTag();
                if (plain != null) {
                    String id = threadIdFromAny(plain);
                    if (id != null && found == null) found = id;
                }
            } catch (Throwable ignored) {}
            v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
        }
        return sanitizeId(found);
    }

    private String threadIdFromAny(Object obj) {
        if (obj == null) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return firstStringField(obj);
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().getName().contains("DirectThreadKey")) {
                    f.setAccessible(true);
                    Object k = f.get(obj);
                    if (k != null) return firstStringField(k);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String firstStringField(Object o) {
        if (o == null) return null;
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof String s && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void hookCurrentThread(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            java.util.List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("igThreadIgid")
                            .paramTypes("com.instagram.model.direct.DirectThreadKey", "boolean")));
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0) {
                        String id = threadIdOf(param.args[0]);
                        Object flag = param.args.length > 1 ? param.args[1] : null;
                        if (id != null && Boolean.TRUE.equals(flag)) {
                            currentThreadId = id;
                            ModuleLog.line("(IE|HideChats|PROBE) igThreadIgid tracked id=" + id);
                        }
                    }
                }
            };
            int n = 0;
            for (MethodData md : methods) {
                try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), hook); n++; }
                catch (Throwable ignored) {}
            }
            if (n > 0) {
                ModuleLog.line("(IE|HideChats) current-thread tracker hooked " + n + " method(s)");
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ current-thread tracker: " + t.getMessage());
        }
    }

    private static String threadIdOf(Object dtk) {
        if (dtk == null) return null;
        try {
            for (Class<?> c = dtk.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(dtk);
                    if (v instanceof String && !((String) v).isEmpty()) return (String) v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }




    private View findBackButton(View root) {
        if (root == null) return null;
        if (root instanceof android.widget.ImageView) {
            CharSequence desc = root.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                if (d.contains("back") || d.contains("indietro") || d.contains("volver") || d.contains("retour") || d.contains("zurück")) {
                    return root;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findBackButton(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
