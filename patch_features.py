import re

with open('app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java', 'r') as f:
    content = f.read()

old_str = """        defs.add(getString(R.string.feat_group_privacy));
        defs.add(Arrays.asList(
                createNav(R.drawable.ic_eye, A_PRIVACY, getString(R.string.ig_dialog_menu_ghost_settings), this::loadGhostMenu),
                createNav(R.drawable.ic_shield, A_PRIVACY, getString(R.string.ig_dialog_misc_lock_section), this::loadLockMenu),
                createNav(R.drawable.ic_eye_off, A_PRIVACY, getString(R.string.ig_hide_chats_title), this::loadHideChatsMenu),
                createNav(R.drawable.ic_block, A_PRIVACY, getString(R.string.ig_dialog_menu_ad_analytics), this::loadAdsMenu),
                createNav(R.drawable.ic_notification, A_PRIVACY, getString(R.string.ig_dialog_menu_distraction_free), this::loadDistractionMenu)
        ));"""

new_str = """        defs.add(getString(R.string.feat_group_direct));
        defs.add(Arrays.asList(
                createNav(R.drawable.ic_eye_off, A_PRIVACY, getString(R.string.ig_hide_chats_title), this::loadHideChatsMenu),
                createNav(R.drawable.ic_eye_off, A_PRIVACY, getString(R.string.ig_dialog_fake_general_mode), this::loadFakeGeneralMenu)
        ));

        defs.add(getString(R.string.feat_group_privacy));
        defs.add(Arrays.asList(
                createNav(R.drawable.ic_eye, A_PRIVACY, getString(R.string.ig_dialog_menu_ghost_settings), this::loadGhostMenu),
                createNav(R.drawable.ic_shield, A_PRIVACY, getString(R.string.ig_dialog_misc_lock_section), this::loadLockMenu),
                createNav(R.drawable.ic_block, A_PRIVACY, getString(R.string.ig_dialog_menu_ad_analytics), this::loadAdsMenu),
                createNav(R.drawable.ic_notification, A_PRIVACY, getString(R.string.ig_dialog_menu_distraction_free), this::loadDistractionMenu)
        ));"""

content = content.replace(old_str, new_str)

new_func = """    private void loadFakeGeneralMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_eye_off, "#5E5CE6", getString(R.string.ig_dialog_fake_general_mode), "isFakeGeneralModeEnabled")
        ));

        showMenu(getString(R.string.ig_dialog_fake_general_mode), defs);
        currentMenu = "fakegeneral";
    }
"""

content = content.replace("    private void loadHideChatsMenu()", new_func + "\n    private void loadHideChatsMenu()")

old_str2 = """                    } else if ("hidechats".equals(currentMenu)) {
                        loadHideChatsMenu();
                    }"""

new_str2 = """                    } else if ("hidechats".equals(currentMenu)) {
                        loadHideChatsMenu();
                    } else if ("fakegeneral".equals(currentMenu)) {
                        loadFakeGeneralMenu();
                    }"""

content = content.replace(old_str2, new_str2)

with open('app/src/main/java/ps/reso/instaeclipse/fragments/FeaturesFragment.java', 'w') as f:
    f.write(content)
