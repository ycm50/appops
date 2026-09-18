package com.mirfatif.permissionmanagerx.fwk;

import android.app.Application;
import android.content.res.Configuration;
import com.mirfatif.permissionmanagerx.app.App;
import rikka.shizuku.ShizukuProvider;

public class AppM extends Application {

  static {
    // The Shizuku API picks its provider when rikka.shizuku.ShizukuProvider is created, which
    // happens before this class' onCreate() and before every other ContentProvider of the app. By
    // default it asks Sui for a binder at that point and, once Sui has answered, ShizukuProvider
    // drops the binder the real Shizuku server sends us (see ShizukuProvider.call). That makes Sui
    // the only reachable provider for the whole process lifetime.
    //
    // We want the Shizuku manager first: the user may already have granted it, and its request is
    // the one PMX shows and falls back from. ShizukuDaemon initializes Sui itself, and only after
    // the manager has failed to answer. This static initializer runs while the Application object
    // is constructed, i.e. before any provider is installed, which is the only place early enough.
    ShizukuProvider.disableAutomaticSuiInitialization();
  }

  private final App mA = new App(this);

  public void onCreate() {
    super.onCreate();
    mA.onCreate();
  }

  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(mA.onConfigurationChanged(newConfig));
  }
}
