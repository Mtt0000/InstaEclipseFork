package ps.reso.instaeclipse.mods.direct;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeDataHook {

    private static final String CACHE_KEY = "DirectThreadRepository_getThreads";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod(CACHE_KEY, classLoader);
            if (cached != null) {
                hookRepositoryMethod(cached);
                return;
            }
        }

        try {
            // Find methods related to thread list queries which take an integer/enum folder ID
            // "inbox_folder_type", "direct_inbox" are common hints in Instagram data repositories
            List<MethodData> methods = bridge.findMethod(
                FindMethod.create()
                    .matcher(
                        MethodMatcher.create()
                            .usingStrings("inbox_folder_type")
                    )
            );

            for (MethodData md : methods) {
                String className = md.getClassName();
                if (!className.startsWith("X.") && !className.startsWith("com.instagram.direct")) {
                    continue;
                }

                try {
                    Method method = md.getMethodInstance(classLoader);
                    // Usually the method returns a List or an object containing a list, and takes an int/enum for Folder ID
                    if (method.getParameterTypes().length > 0 && method.getReturnType() != void.class) {
                        DexKitCache.saveMethod(CACHE_KEY, method);
                        hookRepositoryMethod(method);
                        break; // Hook only the first confident match or all
                    }
                } catch (Throwable ignored) {
                }
            }

        } catch (Throwable e) {
            ModuleLog.line("(IE|FakeGeneralData) DexKit failed: " + e);
        }
    }

    private static void hookRepositoryMethod(Method method) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!FeatureFlags.isFakeGeneralModeEnabled) return;

                // Inspect parameters to identify if folder is General (usually 1 or specific enum)
                boolean isGeneralFolder = false;
                for (Object arg : param.args) {
                    if (arg == null) continue;
                    if (arg instanceof Integer && ((Integer) arg) == 1) { // 1 = General in most IG versions
                        isGeneralFolder = true;
                        break;
                    } else if (arg.getClass().isEnum() && arg.toString().contains("GENERAL")) {
                        isGeneralFolder = true;
                        break;
                    }
                }

                if (isGeneralFolder) {
                    ModuleLog.line("(IE|FakeGeneralData) Intercepted thread list request for General folder. Returning empty list.");

                    if (List.class.isAssignableFrom(method.getReturnType())) {
                        param.setResult(Collections.emptyList());
                    }
                }
            }
        });
        ModuleLog.line("(IE|FakeGeneralData) ✅ Hooked thread repository method: " + method.getName());
    }
}
