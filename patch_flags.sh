#!/bin/bash
sed -i 's/    public static boolean isGhostSeen = false;/    public static boolean isFakeGeneralModeEnabled = false;\n    public static boolean isGhostSeen = false;/' app/src/main/java/ps/reso/instaeclipse/utils/feature/FeatureFlags.java
