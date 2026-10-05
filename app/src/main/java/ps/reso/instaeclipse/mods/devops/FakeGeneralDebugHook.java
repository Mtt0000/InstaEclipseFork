package ps.reso.instaeclipse.mods.devops;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import java.util.List;

public class FakeGeneralDebugHook {
    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            List<MethodData> anchorMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("DirectThreadStoreImpl.getSortedCopyOfThreadSummaries")));

            if (!anchorMethods.isEmpty()) {
                String targetClassName = anchorMethods.get(0).getClassName();
                Class<?> targetClass = classLoader.loadClass(targetClassName);
                for (java.lang.reflect.Method m : targetClass.getDeclaredMethods()) {
                    if (m.getReturnType() == java.util.List.class) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object r = param.getResult();
                                if (r instanceof java.util.List<?> list) {
                                    ModuleLog.line("(IE|Debug) DirectThreadStore hit! List size: " + list.size());
                                    if (list.size() > 0) {
                                        Object item = list.get(0);
                                        if (item != null) {
                                            ModuleLog.line("(IE|Debug) First item class: " + item.getClass().getName());
                                            ModuleLog.line("(IE|Debug) First item toString: " + item.toString());

                                            // Print fields to find folder
                                            try {
                                                for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                                                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                                                        if (f.getType() == int.class || f.getType() == Integer.class || f.getType() == String.class) {
                                                            f.setAccessible(true);
                                                            ModuleLog.line("(IE|Debug) Field " + f.getName() + " = " + f.get(item));
                                                        }
                                                    }
                                                }
                                            } catch (Throwable t) {}
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
            }

            List<MethodData> stateMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("updateInboxAfterThreadsStateChange folder: ")));
            for (MethodData md : stateMethods) {
                java.lang.reflect.Method m = md.getMethodInstance(classLoader);
                if (m != null) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ModuleLog.line("(IE|Debug) updateInboxAfterThreadsStateChange called");
                            for (int i = 0; i < param.args.length; i++) {
                                Object arg = param.args[i];
                                ModuleLog.line("(IE|Debug) arg[" + i + "]: " + (arg == null ? "null" : arg.getClass().getName() + " -> " + arg.toString()));
                            }
                        }
                    });
                }
            }

            ModuleLog.line("(IE|Debug) ✅ Data Debug Hooks installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Debug) ⚠️ Data Debug Hooks failed: " + t.getMessage());
        }
    }
}
