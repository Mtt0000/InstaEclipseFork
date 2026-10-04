package ps.reso.instaeclipse.mods.direct;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class FakeGeneralModeDataHook {

    // Same anchor as HideChatsHook
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

                boolean foundValidThread = false;
                boolean isLikelyGeneralList = false;

                // Let's inspect the actual thread items in the list.
                // We check the first few valid items to see if they belong to the General folder.
                for (int i = 0; i < Math.min(list.size(), 5); i++) {
                    Object item = list.get(i);
                    if (item != null && (item.getClass().getName().contains("DirectThread") || threadIdOfRow(item) != null)) {
                        foundValidThread = true;

                        // We check the fields of the thread summary for a folder type indicator.
                        // Often folder is stored as an int (0=Primary, 1=General).
                        Integer folder = getThreadFolderType(item);
                        if (folder != null) {
                            if (folder == 1) {
                                isLikelyGeneralList = true;
                            } else if (folder == 0) {
                                // Definitive Primary
                                isLikelyGeneralList = false;
                                break;
                            }
                        }
                    }
                }

                if (!foundValidThread) return;

                if (isLikelyGeneralList) {
                    ModuleLog.line("(IE|FakeGeneralData) Identified General list from thread data, hiding all.");
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
        } catch (Throwable t) {
            ModuleLog.line("(IE|FakeGeneralData) ❌ " + t.getMessage());
        }
    }

    private static String threadIdOfRow(Object row) {
        if (row == null) return null;
        try {
            Class<?> c = row.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().contains("DirectThreadKey")) continue;
                    f.setAccessible(true);
                    Object key = f.get(row);
                    if (key != null) return "found"; // Just to mark valid thread
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer getThreadFolderType(Object row) {
        if (row == null) return null;
        try {
            Class<?> c = row.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    // Instagram usually stores thread folder type as an int.
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        int val = f.getInt(row);
                        // Folder IDs: 0 = Primary, 1 = General.
                        // If it's exactly 1 or 0, it's highly likely to be the folder id.
                        if (val == 0 || val == 1) {
                            // If we find an integer field with value 1, we assume it's General.
                            return val;
                        }
                    } else if (f.getType().isEnum()) {
                        f.setAccessible(true);
                        Object enumVal = f.get(row);
                        if (enumVal != null) {
                            String name = enumVal.toString();
                            if (name.equals("GENERAL") || name.equals("1")) return 1;
                            if (name.equals("PRIMARY") || name.equals("0")) return 0;
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
