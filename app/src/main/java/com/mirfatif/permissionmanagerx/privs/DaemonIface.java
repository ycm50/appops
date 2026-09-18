package com.mirfatif.permissionmanagerx.privs;

import android.os.IBinder;
import android.os.RemoteException;
import com.mirfatif.permissionmanagerx.R;
import com.mirfatif.permissionmanagerx.app.App;
import com.mirfatif.permissionmanagerx.prefs.MySettings;
import com.mirfatif.permissionmanagerx.util.UiUtils;
import com.mirfatif.privtasks.bind.AppOpsLists;
import com.mirfatif.privtasks.bind.MyPackageOps;
import com.mirfatif.privtasks.bind.PermFixedFlags;
import com.mirfatif.privtasks.bind.PrivsStatus;
import com.mirfatif.privtasks.iface.IPrivTasks;
import com.mirfatif.privtasks.util.MyLog;
import com.mirfatif.privtasks.util.bg.RateLimitedTask;
import com.mirfatif.privtasks.util.bg.ThreadUtils;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public enum DaemonIface {
  INS;

  private static final String TAG = "DaemonIface";

  private IPrivTasks mPrivTasks;

  void onDaemonStarted(IPrivTasks privTasks) {
    mPrivTasks = privTasks;
  }

  void onDaemonStopped() {
    synchronized (this) {
      mPrivTasks = null;
    }
  }

  private boolean isDaemonAlive() {
    return hasDaemonIface() && DaemonHandler.INS.isDaemonAlive(true, false);
  }

  /** True if the daemon has handed us its binder in this app process. */
  private boolean hasDaemonIface() {
    return mPrivTasks != null;
  }

  private final RateLimitedTask mNoDaemonToaster =
      new RateLimitedTask(
          10, TimeUnit.SECONDS, () -> UiUtils.showToast(R.string.daemon_not_running_toast));

  /**
   * Reports a change that could not be applied.
   *
   * <p>The write methods below used to return without a word when no daemon had ever been started,
   * while the caller had already updated the UI - so a grant or revoke that never reached the
   * system looked like it had worked. {@link #isDaemonAlive()} shows its own toast only once a
   * daemon connection exists, so the message is repeated here just for that remaining case, rate
   * limited so a run of taps cannot stack up toasts.
   */
  private void onDaemonNotRunning(String method) {
    MyLog.e(TAG, method, "Privileged daemon is not running, the change was not applied");
    if (!hasDaemonIface()) {
      mNoDaemonToaster.run();
    }
  }

  public void setExitOnAppDeath() {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          mPrivTasks.setExitOnAppDeath(MySettings.INS.shouldDaemonExitOnAppDeath());
        } catch (RemoteException e) {
          MyLog.e(TAG, "setExitOnAppDeath", e);
          UiUtils.showToast(R.string.daemon_set_exit_on_app_death_failed_toast);
        }
      }
    }
  }

  public PrivsStatus getPrivsStatus() {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          return mPrivTasks.getPrivsStatus();
        } catch (RemoteException e) {
          MyLog.e(TAG, "getPrivsStatus", e);
          UiUtils.showToast(R.string.daemon_get_priv_status_failed_toast);
        }
      }
    }

    return null;
  }

  public void setDebug(IBinder logCb) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          mPrivTasks.setDebug(logCb);
        } catch (RemoteException e) {
          MyLog.e(TAG, "setDebug", e);
          UiUtils.showToast(R.string.daemon_set_debug_failed_toast);
        }
      }
    }
  }

  public boolean dumpHeap() {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          mPrivTasks.dumpHeap(
              Objects.requireNonNull(App.getCxt().getExternalCacheDir()).getAbsolutePath());
          return true;
        } catch (RemoteException e) {
          MyLog.e(TAG, "dumpHeap", e);
          UiUtils.showToast(R.string.daemon_dump_heap_failed_toast);
        }
      }
    }

    return false;
  }

  public AppOpsLists getAppOpsLists() {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          return mPrivTasks.getAppOpsLists();
        } catch (RemoteException e) {
          MyLog.e(TAG, "getAppOpsLists", e);
          UiUtils.showToast(R.string.daemon_get_app_ops_lists_failed_toast);
        }
      }
    }

    return null;
  }

  public PermFixedFlags getPermFixedFlags() {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          return mPrivTasks.getPermFixedFlags();
        } catch (RemoteException e) {
          MyLog.e(TAG, "getPermFixedFlags", e);
          UiUtils.showToast(R.string.daemon_get_perm_fixed_flags_failed_toast);
        }
      }
    }

    return null;
  }

  public String[] getPackagesForUid(int uid) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          return mPrivTasks.getPackagesForUid(uid);
        } catch (RemoteException e) {
          MyLog.e(TAG, "getPackagesForUid", e);
          UiUtils.showToast(R.string.daemon_get_pkgs_for_uid_failed_toast);
        }
      }
    }

    return null;
  }

  private final RateLimitedTask mGetOpsForPkgToaster =
      new RateLimitedTask(
          10, TimeUnit.SECONDS, () -> UiUtils.showToast(R.string.daemon_get_pkg_ops_failed_toast));

  public List<MyPackageOps> getOpsForPkg(int uid, String pkgName, int[] ops) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          return mPrivTasks.getOpsForPackage(uid, pkgName, ops);
        } catch (RemoteException e) {
          MyLog.e(TAG, "getOpsForPkg", e);
          mGetOpsForPkgToaster.run();
        }
      }
    }

    return null;
  }

  private final RateLimitedTask mGetPermFlagsToaster =
      new RateLimitedTask(
          10,
          TimeUnit.SECONDS,
          () -> UiUtils.showToast(R.string.daemon_get_perm_flags_failed_toast));

  public Integer getPermFlags(String permName, String pkgName, int userId) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          return mPrivTasks.getPermFlags(permName, pkgName, userId);
        } catch (RemoteException e) {
          MyLog.e(TAG, "getPermFlags", e);
          mGetPermFlagsToaster.run();
        }
      }
    }

    return null;
  }

  public void setPermState(boolean grant, String pkgName, String permName, int userId) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          mPrivTasks.setPermState(grant, pkgName, permName, userId);
        } catch (RemoteException e) {
          MyLog.e(TAG, "setPermState", e);
          if (grant) {
            UiUtils.showToast(R.string.daemon_grant_perm_failed_toast);
          } else {
            UiUtils.showToast(R.string.daemon_revoke_perm_failed_toast);
          }
        }
        return;
      }
    }

    onDaemonNotRunning("setPermState");
  }

  public void setAppOpMode(int uid, String pkgName, int op, int mode) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          mPrivTasks.setAppOpMode(uid, pkgName, op, mode);
        } catch (RemoteException e) {
          MyLog.e(TAG, "setAppOpMode", e);
          UiUtils.showToast(R.string.daemon_set_app_ops_mode_failed_toast);
        }
        return;
      }
    }

    onDaemonNotRunning("setAppOpMode");
  }

  public void resetAppOps(int userId, String pkgName) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          mPrivTasks.resetAppOps(userId, pkgName);
        } catch (RemoteException e) {
          MyLog.e(TAG, "resetAppOps", e);
          UiUtils.showToast(R.string.daemon_reset_app_ops_failed_toast);
        }
        return;
      }
    }

    onDaemonNotRunning("resetAppOps");
  }

  public void setPkgState(boolean enable, String pkgName, int userId) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          mPrivTasks.setPkgState(enable, pkgName, userId);
        } catch (RemoteException e) {
          MyLog.e(TAG, "setPkgState", e);
          if (enable) {
            UiUtils.showToast(R.string.daemon_enable_pkg_failed_toast);
          } else {
            UiUtils.showToast(R.string.daemon_disable_pkg_failed_toast);
          }
        }
        return;
      }
    }

    onDaemonNotRunning("setPkgState");
  }

  public void openAppInfo(String pkgName, int userId) {
    ThreadUtils.assertNotMainThread();

    synchronized (this) {
      if (isDaemonAlive()) {
        try {
          mPrivTasks.openAppInfo(pkgName, userId);
        } catch (RemoteException e) {
          MyLog.e(TAG, "openAppInfo", e);
          UiUtils.showToast(R.string.daemon_open_app_info_failed_toast);
        }
        return;
      }
    }

    onDaemonNotRunning("openAppInfo");
  }
}
