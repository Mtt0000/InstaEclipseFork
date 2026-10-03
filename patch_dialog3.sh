#!/bin/bash
sed -i 's/DialogUtils.showMenuDialog(context, I18n.t(context, R.string.ig_dialog_fake_general_mode), layout);/showSectionDialog(context, I18n.t(context, R.string.ig_dialog_fake_general_mode), layout, () -> {});/g' app/src/main/java/ps/reso/instaeclipse/utils/dialog/DialogUtils.java
