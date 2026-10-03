#!/bin/bash
sed -i 's/        public static boolean isFakeGeneralModeEnabled = false;/    public static boolean isFakeGeneralModeEnabled = false;/g' app/src/main/java/ps/reso/instaeclipse/utils/feature/FeatureFlags.java
