package com.mirfatif.privdaemon;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import com.mirfatif.err.HiddenAPIsException;
import com.mirfatif.privtasks.AppPrivTasks;
import com.mirfatif.privtasks.Constants;
import com.mirfatif.privtasks.HiddenSdkIntConstants;
import com.mirfatif.privtasks.bind.AppOpsLists;
import com.mirfatif.privtasks.bind.DaemonState;
import com.mirfatif.privtasks.bind.IPrivTasksCallback;
import com.mirfatif.privtasks.bind.MyPackageOps;
import com.mirfatif.privtasks.bind.MyPkgOpsBatch;
import com.mirfatif.privtasks.bind.PermFixedFlags;
import com.mirfatif.privtasks.bind.PrivsStatus;
import com.mirfatif.privtasks.hiddenapis.HiddenAPIs;
import com.mirfatif.privtasks.iface.IPrivTasks;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class IPrivTasksImpl extends IPrivTasks.Stub {

  private static final String TAG = "IPrivTasksImpl";

  private final AppPrivTasks mAppPrivTasks;

  private final AppPrivTasks.AppPrivTasksCallback mCallback = new AppPrivTasksCallbackImpl();

  IPrivTasksImpl() {
    mAppPrivTasks = new AppPrivTasks(mCallback, true);
  }

  public void sendStdErr(int port, String jniLibPath) throws RemoteException {
    Jni.INS.loadLib(jniLibPath);
    Callbacks.INS.sendStdErr(port);
  }

  public void hello(IBinder privTasksCb, String crashLogFile) throws RemoteException {
    IPrivTasksCallback callback = IPrivTasksCallback.Stub.asInterface(privTasksCb);
    Callbacks.INS.update(callback, crashLogFile);
    callback.hello(
        new DaemonState(
            Process.myPid(), Process.myUid(), Callbacks.getSEContext(), Server.INS.getPort()));
  }

  public void setExitOnAppDeath(boolean exitOnAppDeath) {
    Callbacks.INS.setExitOnAppDeath(exitOnAppDeath);
  }

  public PrivsStatus getPrivsStatus() throws RemoteException {
    return PrivsStatusReader.getStatus(mAppPrivTasks);
  }

  public boolean setDebug(IBinder logCb) {
    return Callbacks.INS.setDebug(logCb);
  }

  public void dumpHeap(String directory) throws RemoteException {
    System.gc();

    File dir = new File(directory);
    if (!dir.isDirectory()) {
      throw new HiddenAPIsException(directory + " is not a directory");
    }

    File file = new File(dir, "com.mirfatif.privdaemon.pmx.hprof");
    try {
      Debug.dumpHprofData(file.getAbsolutePath());
    } catch (IOException e) {
      throw new HiddenAPIsException(e);
    }
  }

  public void grantAppPrivileges(String pkgName, int uid) throws RemoteException {
    setAppOpMode(
        uid, pkgName, HiddenSdkIntConstants.OP_RUN_IN_BACKGROUND.get(), AppOpsManager.MODE_ALLOWED);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      setAppOpMode(
          uid,
          pkgName,
          HiddenSdkIntConstants.OP_RUN_ANY_IN_BACKGROUND.get(),
          AppOpsManager.MODE_ALLOWED);
    }

    HiddenAPIs.INS.addPowerSaveWhitelistApp(pkgName);

    HiddenAPIs.INS.grantRuntimePermission(pkgName, Constants.PERM_GET_APP_OPS_STATS, 0, mCallback);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      HiddenAPIs.INS.grantRuntimePermission(
          pkgName, Manifest.permission.POST_NOTIFICATIONS, 0, mCallback);
    }
  }

  public void stopDaemon() {
    Callbacks.INS.exit();
  }

  public AppOpsLists getAppOpsLists() throws RemoteException {
    return mAppPrivTasks.getAppOpsLists(null);
  }

  public PermFixedFlags getPermFixedFlags() throws RemoteException {
    return HiddenSdkIntConstants.getPermFixedFlags();
  }

  public String[] getPackagesForUid(int uid) throws RemoteException {
    return HiddenAPIs.INS.getPackagesForUid(uid);
  }

  public List<MyPackageOps> getOpsForPackage(int uid, String pkgName, int[] ops)
      throws RemoteException {
    return mAppPrivTasks.getOpsForPkg(uid, pkgName, ops);
  }

  public List<MyPkgOpsBatch> getOpsForPackages(int[] uids, String[] pkgNames, int[] ops)
      throws RemoteException {
    checkBatchArgs("getOpsForPackages", uids, pkgNames, ops);

    List<MyPkgOpsBatch> batches = new ArrayList<>(uids.length);

    for (int i = 0; i < uids.length; i++) {
      String pkgName = pkgNames[i];

      List<MyPackageOps> pkgOps;
      List<MyPackageOps> uidOps;

      // ops is a filter shared by the whole batch (null = every op), not a per-package array:
      // the single-package API it wraps takes exactly that shape.
      try {
        pkgOps = mAppPrivTasks.getOpsForPkg(uids[i], pkgName, ops);
        uidOps =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? mAppPrivTasks.getOpsForPkg(uids[i], null, ops)
                : null;
      } catch (Exception | NoSuchMethodError e) {
        // A single failed package must not fail the whole batch. Keep the order and the slot,
        // the caller sees null ops lists for this package.
        DaemonLog.e(TAG, "getOpsForPackages", e);
        pkgOps = null;
        uidOps = null;
      }

      batches.add(new MyPkgOpsBatch(pkgName, pkgOps, uidOps));
    }

    return batches;
  }

  public int getPermFlags(String permName, String pkgName, int userId) throws RemoteException {
    return HiddenAPIs.INS.getPermFlags(permName, pkgName, userId, mCallback);
  }

  public int[] getPermFlagsBatch(String[] permNames, String[] pkgNames, int[] userIds)
      throws RemoteException {
    checkBatchArgs("getPermFlagsBatch", permNames, pkgNames, userIds);

    int[] flags = new int[permNames.length];

    for (int i = 0; i < permNames.length; i++) {
      try {
        flags[i] = HiddenAPIs.INS.getPermFlags(permNames[i], pkgNames[i], userIds[i], mCallback);
      } catch (Exception | NoSuchMethodError e) {
        // Do not fail or shift the whole batch: mark this slot as unknown.
        DaemonLog.e(TAG, "getPermFlagsBatch", e);
        flags[i] = -1;
      }
    }

    return flags;
  }

  private static void checkBatchArgs(String method, int[] uids, String[] pkgNames, int[] ops) {
    // A null ops array means "every op", same as the single-package call. It is the only array
    // that may be null: a caller that passes null by accident would otherwise be silently
    // downgraded to the one-by-one path on the app side.
    if (uids == null || pkgNames == null || uids.length != pkgNames.length) {
      throw new IllegalArgumentException(
          method + ": uids and pkgNames must be non-null and of equal length");
    }
  }

  private static void checkBatchArgs(
      String method, String[] permNames, String[] pkgNames, int[] userIds) {
    if (permNames == null
        || pkgNames == null
        || userIds == null
        || permNames.length != pkgNames.length
        || permNames.length != userIds.length) {
      throw new IllegalArgumentException(method + ": arrays must be non-null and of equal length");
    }
  }

  public void setPermState(boolean grant, String pkgName, String permName, int userId)
      throws RemoteException {
    if (grant) {
      HiddenAPIs.INS.grantRuntimePermission(pkgName, permName, userId, mCallback);
    } else {
      HiddenAPIs.INS.revokeRuntimePermission(pkgName, permName, userId, mCallback);
    }
  }

  public void setAppOpMode(int uid, String pkgName, int op, int mode) throws RemoteException {
    if (pkgName == null) {
      HiddenAPIs.INS.setAppOpUidMode(uid, op, mode);
    } else {
      HiddenAPIs.INS.setAppOpMode(pkgName, uid, op, mode);
    }
  }

  public void resetAppOps(int userId, String pkgName) throws RemoteException {
    HiddenAPIs.INS.resetAllModes(userId, pkgName);
  }

  public void setPkgState(boolean enable, String pkgName, int userId) throws RemoteException {
    String callingPkg = "shell:" + Process.myUid();

    int state;
    if (enable) {
      state = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    } else {
      state = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    }

    HiddenAPIs.INS.setApplicationEnabledSetting(pkgName, state, 0, userId, callingPkg);
  }

  public void openAppInfo(String pkgName, int userId) throws RemoteException {
    int res = HiddenAPIs.INS.openAppInfo(pkgName, userId);
    if (res != HiddenSdkIntConstants.START_SUCCESS.get()) {
      throw new HiddenAPIsException("Failed to open app info. Result code: " + res);
    }
  }

  private static class AppPrivTasksCallbackImpl implements AppPrivTasks.AppPrivTasksCallback {

    public void logErr(String tag, String method, Throwable e) {
      DaemonLog.e(tag, method, e);
    }

    public void logErr(String tag, String method, String err) {
      DaemonLog.e(tag, method, err);
    }

    public void showError(int error) {
      Callbacks.INS.showError(error);
    }
  }
}
