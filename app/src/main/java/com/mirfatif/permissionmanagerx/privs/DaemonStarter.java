package com.mirfatif.permissionmanagerx.privs;

import static com.mirfatif.permissionmanagerx.util.ApiUtils.getString;

import android.os.SystemClock;
import com.mirfatif.permissionmanagerx.R;
import com.mirfatif.permissionmanagerx.parser.PackageParser;
import com.mirfatif.permissionmanagerx.prefs.MySettings;
import com.mirfatif.permissionmanagerx.util.bg.LiveEvent;
import com.mirfatif.privtasks.util.MyLog;
import com.mirfatif.privtasks.util.bg.BgRunner;
import com.mirfatif.privtasks.util.bg.ThreadUtils;

public enum DaemonStarter {
  INS;

  private static final String TAG = "DaemonStarter";

  public void startPrivDaemon(
      boolean restart, boolean isFirstRun, boolean preferRoot, boolean showNoPrivsDialog) {
    if (ThreadUtils.isMainThread()) {
      BgRunner.execute(() -> startDaemonLocked(restart, isFirstRun, preferRoot, showNoPrivsDialog));
    } else {
      startDaemonLocked(restart, isFirstRun, preferRoot, showNoPrivsDialog);
    }
  }

  private synchronized void startDaemonLocked(
      boolean restart, boolean isFirstRun, boolean preferRoot, boolean showNoPrivsDialog) {
    boolean wasAlive = DaemonHandler.INS.isDaemonAlive();

    mDaemonStartResult.postValue(
        new DaemonStartResult(
            connectOrStartDaemon(restart, wasAlive, preferRoot),
            showNoPrivsDialog,
            isFirstRun,
            wasAlive));
  }

  public synchronized int startPrivDaemon(boolean restart, boolean preferRoot) {
    return connectOrStartDaemon(restart, DaemonHandler.INS.isDaemonAlive(), preferRoot);
  }

  private synchronized int connectOrStartDaemon(
      boolean restart, boolean wasAlive, boolean preferRoot) {
    // Arm the late-answer handler before anything can ask for the Shizuku permission: its dialog
    // can stay on the screen longer than the ladder waits, and the grant must still take effect
    // instead of being noticed only on the next start-up.
    ShizukuDaemon.setGrantCallback(this::onShizukuPermissionGranted);

    if (wasAlive && !restart) {
      if (PackageParser.INS.getPkgList().isEmpty()) {
        setProgress(R.string.prog_msg_checking_privs);
      }
      return DaemonStartStatus.STARTED;
    }

    long waitTill = System.currentTimeMillis();

    if (wasAlive) {
      setProgress(R.string.prog_msg_stopping_daemon);
      if (DaemonHandler.INS.stopDaemon()) {
        waitTill += 1000;
      }
    } else {
      setProgress(R.string.prog_msg_connecting_to_daemon);
      if (DaemonHandler.INS.isDaemonAlive(false, true)) {
        return DaemonStartStatus.STARTED;
      }
    }

    boolean hasPrivs = false;
    boolean triedShizuku = false;

    if (MySettings.INS.isRootEnabled() || MySettings.INS.isAdbEnabled()) {
      // Shizuku first, root second. Two reasons to prefer it over root: it needs no su prompt, and
      // it already runs as shell or root, so nothing has to be escalated. The provider is chosen in
      // startServer() - the Shizuku manager when it answers, Sui when it does not. Both steps are
      // bounded, so a provider that never answers costs a few seconds and then falls through
      // instead of stalling the start-up.
      if (shouldUseShizuku()) {
        MyLog.i(TAG, "connectOrStartDaemon", "Trying Shizuku first");
        setProgress(R.string.prog_msg_checking_adb_access);
        triedShizuku = true;
        hasPrivs = NativeDaemon.getShizuku();
      }

      // Native ADB over wireless debugging, only when the user prefers it over root.
      if (!hasPrivs && !preferRoot && MySettings.INS.isAdbEnabled()) {
        setProgress(R.string.prog_msg_checking_adb_access);
        hasPrivs = NativeDaemon.getAdb();
      }

      // Root (su). A provider that is installed but would not answer - typically because its
      // permission was never granted - is the one case where su is asked for even though the root
      // tier itself was never switched on. The user did ask for privileged access, and pairing
      // over wireless debugging is not a usable substitute for it, so leaving root disabled would
      // end the start-up with no privileges at all. On a device without root the su process simply
      // fails to start and nothing is shown.
      if (!hasPrivs) {
        setProgress(R.string.prog_msg_checking_root_access);
        // ifEnabledOnly: normally su is only tried when the root tier was switched on. A provider
        // that was tried and did not answer overrides that, so the fallback the user asked for
        // happens.
        boolean ifEnabledOnly = MySettings.INS.isRootEnabled() || !triedShizuku;
        hasPrivs = NativeDaemon.getRoot(ifEnabledOnly);
      }

      // Native ADB as the last resort when the user prefers root.
      if (!hasPrivs && preferRoot && MySettings.INS.isAdbEnabled()) {
        setProgress(R.string.prog_msg_checking_adb_access);
        hasPrivs = NativeDaemon.getAdb();
      }
    }

    if (!hasPrivs) {
      MyLog.w(TAG, "connectOrStartDaemon", "Root / ABD access unavailable");
      if (!PackageParser.INS.getPkgList().isEmpty()) {
        mProgress.postValue(null);
      }
      return DaemonStartStatus.NO_PRIVS;
    }

    setProgress(R.string.prog_msg_starting_daemon);

    long sleep = waitTill - System.currentTimeMillis();
    if (sleep > 0) {
      SystemClock.sleep(sleep);
    }

    // The tier that actually answered decides which daemon to spawn, not the caller's preference:
    // with Shizuku the daemon has to go through INS_A even when root was requested.
    boolean shizukuServing = ShizukuDaemon.INS.isServerStarted();
    boolean useRootDaemon = preferRoot && !shizukuServing;

    // Shizuku reports its own UID, and it is 0 when it was started with root. That happens by
    // design on KernelSU devices, where the Sui module answers the Shizuku API and no su binary
    // exists at all. Naming the provider and the UID keeps the log from claiming shell-level access
    // while the daemon is actually running as root - and from blaming Shizuku when Sui answered.
    String tier;
    if (useRootDaemon) {
      tier = "root (su)";
    } else if (shizukuServing) {
      tier =
          ShizukuDaemon.getProviderName()
              + (ShizukuDaemon.isRoot() ? " (running as root)" : " (running as shell)");
    } else {
      tier = "the native ADB helper";
    }

    MyLog.i(TAG, "connectOrStartDaemon", "Starting the privileged daemon through " + tier);

    if (DaemonHandler.INS.startDaemon(useRootDaemon)) {
      return DaemonStartStatus.STARTED;
    }

    // A back end can answer and still fail to bring the privileged daemon up - Shizuku binds, but
    // the daemon cannot connect back to the app, which leaves the UI showing a privilege tier whose
    // writes all silently fail. Retry once with the other back end before giving up.
    boolean retryPreferRoot = !useRootDaemon;
    boolean otherBackEndAvailable =
        retryPreferRoot
            ? NativeDaemon.hasRoot(true) || MySettings.INS.isRootEnabled()
            : NativeDaemon.hasAdb(true) || shizukuServing;

    if (otherBackEndAvailable) {
      MyLog.i(
          TAG,
          "connectOrStartDaemon",
          "Daemon did not come up, retrying with " + (retryPreferRoot ? "root" : "ADB"));
      if (DaemonHandler.INS.startDaemon(retryPreferRoot)) {
        return DaemonStartStatus.STARTED;
      }
    }

    MyLog.e(TAG, "connectOrStartDaemon", "Privileged daemon could not be started");
    return DaemonStartStatus.FAILED;
  }

  /**
   * True when asking Shizuku (the manager, or Sui as its fallback) is worth an attempt.
   *
   * <p>An already granted permission is enough on its own: the user granted it on purpose, so there
   * is nothing left to ask. Otherwise the ADB tier is the user's request, and that is all that can
   * be checked here - whether a provider is present is decided inside {@code startServer()}, which
   * waits for the Shizuku manager and falls back to Sui only when the manager is not serving at
   * all. Requiring {@code isAvailable()} at this point would skip both of them on a device where
   * the manager's binder has not arrived yet, which is exactly the case the wait exists for.
   */
  private static boolean shouldUseShizuku() {
    return ShizukuDaemon.isPermitted() || MySettings.INS.isAdbEnabled();
  }

  /**
   * Called when the Shizuku permission is granted after the ladder had already stopped waiting for
   * the answer - the dialog can stay on the screen much longer than the wait allows. The privileged
   * daemon is started right away, so the grant takes effect without restarting the app.
   */
  public void onShizukuPermissionGranted() {
    if (ShizukuDaemon.INS.isServerStarted() && DaemonHandler.INS.isDaemonAlive()) {
      return;
    }

    MyLog.i(TAG, "onShizukuPermissionGranted", "Shizuku is granted, starting the daemon now");
    startPrivDaemon(true, false, false, false);
  }

  public void switchToRootOrAdbDaemon(boolean preferRoot) {
    if (Boolean.valueOf(preferRoot).equals(DaemonHandler.INS.isPreferRoot())) {
      MyLog.i(
          TAG,
          "switchToRootOrAdbDaemon",
          (preferRoot ? "root" : "adb") + " daemon already running");
      return;
    }
    startPrivDaemon(true, false, preferRoot, true);
  }

  public void stopDaemon(boolean preferRoot) {
    boolean restart = true;

    if (Boolean.valueOf(preferRoot).equals(DaemonHandler.INS.isPreferRoot())) {
      MyLog.i(TAG, "stopDaemon", (preferRoot ? "root" : "adb") + " daemon already running");
      restart = false;
    }

    startPrivDaemon(restart, false, false, false);
  }

  private final LiveEvent<String> mProgress = new LiveEvent<>(true);
  private final LiveEvent<DaemonStartResult> mDaemonStartResult = new LiveEvent<>(true);

  public LiveEvent<String> getLiveProg() {
    return mProgress;
  }

  public LiveEvent<DaemonStartResult> getLiveStartResult() {
    return mDaemonStartResult;
  }

  private void setProgress(int msg) {
    mProgress.postValue(getString(msg));
  }

  public static class DaemonStartResult {
    public final int daemonStarted;
    public final boolean showNoPrivsDialog;
    public final boolean isFirstRun;
    public final boolean wasAlive;

    private DaemonStartResult(
        int started, boolean showNoPrivsDialog, boolean isFirstRun, boolean wasAlive) {
      this.daemonStarted = started;
      this.showNoPrivsDialog = showNoPrivsDialog;
      this.isFirstRun = isFirstRun;
      this.wasAlive = wasAlive;
    }
  }

  public @interface DaemonStartStatus {
    int NO_PRIVS = 0;
    int STARTED = 1;
    int FAILED = 2;
  }
}
