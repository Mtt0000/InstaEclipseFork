#!/bin/bash
sed -i 's/<string name="feat_group_privacy">Privacy<\/string>/<string name="feat_group_direct">Direct features<\/string>\n    <string name="feat_group_privacy">Privacy<\/string>\n    <string name="ig_dialog_fake_general_mode">Fake General Mode<\/string>/' app/src/main/res/values/strings.xml
