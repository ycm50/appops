package com.mirfatif.permissionmanagerx.util;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import com.mirfatif.permissionmanagerx.prefs.MySettings;
import java.text.NumberFormat;
import java.util.Locale;

public class LocaleUtils {

  private LocaleUtils() {}

  public static Context setLocale(Context context) {
    Locale locale = getLocale();
    Locale.setDefault(locale);
    sNumFmt = NumberFormat.getIntegerInstance(Locale.getDefault());
    Configuration config = setLocale(context.getResources().getConfiguration(), locale);
    updateConfiguration(context.getResources(), config, context.getResources().getDisplayMetrics());
    return context;
  }

  // Deprecated since API 25 in favour of createConfigurationContext(), which returns a new Context
  // instead of mutating one. The app applies the locale by mutating the resources of the context it
  // is given (see App.setLocale() and BaseActivity.attachBaseContext()), and the help screen
  // re-applies it to its own base context at runtime, so moving to the configuration context model
  // is a separate change. The deprecated call still works from minSdk 24 up.
  @SuppressWarnings("deprecation")
  private static void updateConfiguration(Resources res, Configuration config, DisplayMetrics dm) {
    res.updateConfiguration(config, dm);
  }

  public static Configuration setLocale(Configuration config) {
    return setLocale(config, getLocale());
  }

  private static Configuration setLocale(Configuration config, Locale locale) {
    config = new Configuration(config);
    config.setLocale(locale);
    return config;
  }

  private static Locale getLocale() {
    String lang = MySettings.INS.getLocale();
    if (TextUtils.isEmpty(lang)) {
      return Resources.getSystem().getConfiguration().getLocales().get(0);
    } else {
      // The codes in arrays.xml are plain ISO language codes ("en", "zh", ...), for which
      // forLanguageTag() is equivalent to the deprecated Locale(String) constructor.
      return Locale.forLanguageTag(lang);
    }
  }

  private static NumberFormat sNumFmt = NumberFormat.getIntegerInstance(Locale.getDefault());

  public static String toLocalizedNum(long num) {
    return sNumFmt.format(num);
  }
}
