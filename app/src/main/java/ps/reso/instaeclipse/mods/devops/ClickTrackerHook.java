package ps.reso.instaeclipse.mods.devops;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class ClickTrackerHook {
    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "setOnClickListener", View.OnClickListener.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View view = (View) param.thisObject;
                    View.OnClickListener listener = (View.OnClickListener) param.args[0];

                    if (listener != null) {
                        View.OnClickListener proxyListener = new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String text = "";
                                if (v instanceof TextView) {
                                    CharSequence cs = ((TextView) v).getText();
                                    if (cs != null) text = cs.toString();
                                }
                                String desc = v.getContentDescription() != null ? v.getContentDescription().toString() : "";

                                String idName = "";
                                try {
                                    if (v.getId() != View.NO_ID && v.getResources() != null) {
                                        idName = v.getResources().getResourceEntryName(v.getId());
                                    }
                                } catch (Throwable ignored) {}

                                Log.d("InstaEclipse|ClickTrack", "==============================");
                                Log.d("InstaEclipse|ClickTrack", "Tasto cliccato! View Class: " + v.getClass().getName());
                                Log.d("InstaEclipse|ClickTrack", "View ID Name: " + idName + " (int: " + v.getId() + ")");
                                Log.d("InstaEclipse|ClickTrack", "Testo: " + text);
                                Log.d("InstaEclipse|ClickTrack", "Content Description: " + desc);
                                Log.d("InstaEclipse|ClickTrack", "Classe Listener: " + listener.getClass().getName());
                                Log.d("InstaEclipse|ClickTrack", "Stack Trace del click:", new Throwable());
                                Log.d("InstaEclipse|ClickTrack", "==============================");

                                // Pass through
                                listener.onClick(v);
                            }
                        };
                        param.args[0] = proxyListener;
                    }
                }
            });
            ModuleLog.line("(IE|ClickTrack) ✅ Global Click Tracker installed for debugging");
        } catch (Throwable t) {
            ModuleLog.line("(IE|ClickTrack) ⚠️ Failed to hook setOnClickListener: " + t.getMessage());
        }
    }
}
