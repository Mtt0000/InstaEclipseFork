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

                boolean isGeneral = false;
                boolean isPrimary = false;

                // Checking args for Folder identifiers
                for (Object arg : param.args) {
                    if (arg == null) continue;
                    String argStr = arg.toString();

                    if (arg.getClass().isEnum() || argStr.contains("Folder")) {
                        if (argStr.endsWith("GENERAL") || argStr.equals("1")) {
                            isGeneral = true;
                        } else if (argStr.endsWith("PRIMARY") || argStr.equals("0") || argStr.endsWith("INBOX")) {
                            isPrimary = true;
                        }
                    } else if (arg instanceof Integer) {
                        int val = (Integer) arg;
                        if (val == 1) isGeneral = true;
                        if (val == 0) isPrimary = true;
                    }
                }

                // If no exact match on arg, checking stack strictly for Inbox fragment logic
                if (!isGeneral && !isPrimary) {
                    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                    for (int i = 0; i < Math.min(stack.length, 15); i++) {
                        String methodName = stack[i].getMethodName();
                        String className = stack[i].getClassName();
                        if (methodName.equals("getGeneralFolder") ||
                            className.endsWith("GeneralFolderFragment") ||
                            methodName.endsWith("General")) {
                            isGeneral = true;
                            break;
                        }
                    }
                }

                // Only hide if we explicitly proved it's General, and not Primary.
                if (isGeneral && !isPrimary) {
                    ModuleLog.line("(IE|FakeGeneralData) Identified General folder request strictly, forcing empty list.");
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
                        // The method likely takes an Integer or Enum folder ID and returns a List
                        if (java.util.List.class.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length > 0) {
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
