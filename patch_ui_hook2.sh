#!/bin/bash
sed -i 's/        \/\/ Fake General Mode/        \/\/ Fake General Mode (Legacy View hook removed)/' app/src/main/java/ps/reso/instaeclipse/mods/ui/UIHookManager.java
sed -i 's/        ps.reso.instaeclipse.mods.direct.FakeGeneralModeHook.watchActivity(activity);//' app/src/main/java/ps/reso/instaeclipse/mods/ui/UIHookManager.java
