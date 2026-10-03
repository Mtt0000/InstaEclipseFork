import re

with open('app/src/main/java/ps/reso/instaeclipse/utils/dialog/DialogUtils.java', 'r') as f:
    content = f.read()

old_str = """        // ---- PRIVACY ---- ghost, lock, hidden chats, ad/analytics blocking, distraction-free
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_privacy)));
        LinearLayout privacy = createGroupCard(context);
        privacy.addView(createMenuRow(context, R.drawable.ic_eye, I18n.t(context, R.string.ig_dialog_menu_ghost_settings), A_PRIVACY, () -> showGhostOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_shield, I18n.t(context, R.string.ig_dialog_misc_lock_section), A_PRIVACY, () -> showLockOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_eye_off, I18n.t(context, R.string.ig_hide_chats_title), A_PRIVACY, () -> showHideChatsOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_block, I18n.t(context, R.string.ig_dialog_menu_ad_analytics), A_PRIVACY, () -> showAdOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_notification, I18n.t(context, R.string.ig_dialog_menu_distraction_free), A_PRIVACY, () -> showDistractionOptions(context)));
        mainLayout.addView(privacy);"""

new_str = """        // ---- DIRECT FEATURES ---- fake general mode, hidden chats
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_direct)));
        LinearLayout direct = createGroupCard(context);
        direct.addView(createMenuRow(context, R.drawable.ic_eye_off, I18n.t(context, R.string.ig_hide_chats_title), A_PRIVACY, () -> showHideChatsOptions(context)));
        direct.addView(createMenuRow(context, R.drawable.ic_eye_off, I18n.t(context, R.string.ig_dialog_fake_general_mode), A_PRIVACY, () -> showFakeGeneralOptions(context)));
        mainLayout.addView(direct);

        // ---- PRIVACY ---- ghost, lock, ad/analytics blocking, distraction-free
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_privacy)));
        LinearLayout privacy = createGroupCard(context);
        privacy.addView(createMenuRow(context, R.drawable.ic_eye, I18n.t(context, R.string.ig_dialog_menu_ghost_settings), A_PRIVACY, () -> showGhostOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_shield, I18n.t(context, R.string.ig_dialog_misc_lock_section), A_PRIVACY, () -> showLockOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_block, I18n.t(context, R.string.ig_dialog_menu_ad_analytics), A_PRIVACY, () -> showAdOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_notification, I18n.t(context, R.string.ig_dialog_menu_distraction_free), A_PRIVACY, () -> showDistractionOptions(context)));
        mainLayout.addView(privacy);"""

content = content.replace(old_str, new_str)

new_func = """    private static void showFakeGeneralOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        LinearLayout card = card(context);
        ToggleRow toggle = createSwitch(context, R.drawable.ic_eye_off, A_PRIVACY,
                I18n.t(context, R.string.ig_dialog_fake_general_mode), FeatureFlags.isFakeGeneralModeEnabled);
        toggle.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.isFakeGeneralModeEnabled = checked;
            SettingsManager.saveAllFlags();
        });
        card.addView(toggle);

        layout.addView(card);
        showMenuDialog(context, I18n.t(context, R.string.ig_dialog_fake_general_mode), layout);
    }
"""

content = content.replace("    private static void showHideChatsOptions", new_func + "\n    private static void showHideChatsOptions")

with open('app/src/main/java/ps/reso/instaeclipse/utils/dialog/DialogUtils.java', 'w') as f:
    f.write(content)
