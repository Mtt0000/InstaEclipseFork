package ps.reso.instaeclipse.mods.direct;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeDataHook {

    // Instagram's thread repository logic. Same anchor used by HideChatsHook.
    private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        installGeneralFilter(bridge, classLoader);
    }

    private void installGeneralFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.isFakeGeneralModeEnabled) return;
                Object r = param.getResult();
                if (!(r instanceof java.util.List<?> list) || list.isEmpty()) return;

                // Inspect arguments to see if it's the General folder (usually passed as an enum or int to thread summary methods).
                // Or try to deduce it from the stack trace if it's not directly in the arguments.
                boolean isGeneral = false;

                // Checking args for Folder identifiers
                for (Object arg : param.args) {
                    if (arg == null) continue;
                    String argStr = arg.toString();
                    if (argStr.contains("GENERAL") || argStr.contains("General")) {
                        isGeneral = true;
                    }
                    if (arg instanceof Integer && ((Integer) arg) == 1) { // 1 often equals General
                        isGeneral = true;
                    }
                }

                // If arguments didn't explicitly say GENERAL, we inspect the call stack
                if (!isGeneral) {
                    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                    for (int i = 0; i < Math.min(stack.length, 15); i++) {
                        String methodName = stack[i].getMethodName();
                        String className = stack[i].getClassName();
                        if (methodName.toLowerCase().contains("general") || className.toLowerCase().contains("general")) {
                            isGeneral = true;
                            break;
                        }
                    }
                }

                if (isGeneral) {
                    ModuleLog.line("(IE|FakeGeneralData) Identified General folder request, forcing empty list.");
                    param.setResult(new java.util.ArrayList<>());
                }
            }
        };

        try {
            int hooks = 0;
            java.util.List<MethodData> anchorMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)));

            if (!anchorMethods.isEmpty()) {
                String targetClassName = anchorMethods.get(0).getClassName();
                try {
                    Class<?> clazz = classLoader.loadClass(targetClassName);
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (java.util.List.class.isAssignableFrom(m.getReturnType())) {
                            XposedBridge.hookMethod(m, filter);
                            hooks++;
                        }
                    }
                } catch (Throwable t) {
                    ModuleLog.line("(IE|FakeGeneralData) class reflection failed: " + t.getMessage());
                }
            }
            if (hooks > 0) {
                ModuleLog.line("(IE|FakeGeneralData) ✅ Hooked thread store: " + hooks + " list methods.");
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneralData) ❌ " + t.getMessage());
        }
    }
}
