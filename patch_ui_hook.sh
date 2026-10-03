#!/bin/bash
sed -i 's/        ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.watchActivity(activity);/        ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.watchActivity(activity);\n\n        \/\/ Fake General Mode\n        ps.reso.instaeclipse.mods.direct.FakeGeneralModeHook.watchActivity(activity);/' app/src/main/java/ps/reso/instaeclipse/mods/ui/UIHookManager.java
