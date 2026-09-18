package com.mirfatif.permissionmanagerx.privs;

import static com.mirfatif.permissionmanagerx.BuildConfig.APPLICATION_ID;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import com.mirfatif.permissionmanagerx.BuildConfig;
import com.mirfatif.permissionmanagerx.app.App;
import com.mirfatif.permissionmanagerx.util.ApiUtils;
import com.mirfatif.privtasks.util.MyLog;
import com.mirfatif.privtasks.util.bg.ThreadUtils;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;
import rikka.sui.Sui;

/**
 * Provides ADB-level access through Shizuku instead of pairing with ADB over wireless debugging.
 *
 * <p>Shizuku keeps a process running as shell (when it was started over ADB) or as root (when it
 * was started with root). It lets us run commands in that context, which is exactly what the native
 * {@code pmxe} helper does for the ADB path: it spawns the privileged daemon with an {@code
 * app_process} command, and it runs shell commands such as {@code appops set}. Using Shizuku means
 * no pairing code, no {@code adb tcpip} and no native helper.
 *
 * <p>The privileged work is delegated to {@link PmxUserService}, a Shizuku "user service". Shizuku
 * runs it in a dedicated process at its own UID. That is the supported replacement for the {@code
 * Shizuku.newProcess()} API, which newer Shizuku servers refuse to serve.
 *
 * <p>{@code pmxe} additionally drops privileges and adjusts the SELinux context. Shizuku already
 * runs the service at the right UID and context, so that step is not needed here.
 *
 * <p>Two different servers can answer the Shizuku API on the same device: the Shizuku manager
 * ({@code moe.shizuku.privileged.api}, started over ADB or with root) and Sui, the root module that
 * implements the same API on KernelSU / Magisk. The API library picks one provider when the app
 * process starts, and once Sui has answered it refuses the manager's binder. See {@link
 * #startServer()}: the manager is asked first, Sui only when the manager did not answer, which is
 * the order the ladder relies on.
 */
public enum ShizukuDaemon {
  INS;

  private static final String TAG = "ShizukuDaemon";

  /** Request code for the Shizuku runtime permission. */
  private static final int PERM_REQ_CODE = 301;

  /**
   * Window for a provider to answer at all: when no binder has been received within this time, the
   * provider is treated as not running and the ladder moves on to the next one.
   *
   * <p>This is the "5 seconds without a response" rule: the Shizuku manager is asked first and Sui
   * second, so a manager that is not running must not hold the start-up up.
   */
  private static final int PROVIDER_WAIT_MS = 5000;

  /**
   * Window for the user to answer a permission dialog. It is deliberately longer than {@link
   * #PROVIDER_WAIT_MS}: a dialog that is on the screen is a provider that has answered, and asking
   * the next provider while the user is still reading the previous dialog would put two requests on
   * top of each other.
   */
  private static final int PERM_WAIT_MS = 20000;

  /** Floor for the bind step: the total budget can be exceeded by at most this much. */
  private static final int BIND_FLOOR_MS = 1000;

  /** The daemon renames itself to this, and kills any older process using the same name. */
  private static final String DAEMON_NAME = "com.mirfatif.privdaemon.pmx";

  /** The runtime permission declared by the Shizuku manager. */
  public static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";

  public interface PermCallback {
    void onResult(boolean granted);
  }

  // ---------------------------------------------------------------------------------------------
  // Status
  // ---------------------------------------------------------------------------------------------

  /** True if the Shizuku service is running and reachable. Never throws. */
  public static boolean isAvailable() {
    try {
      return Shizuku.pingBinder();
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * True when asking a provider is worth an attempt: one has already answered, or the Shizuku
   * manager is installed, so its binder is only a moment away.
   *
   * <p>A device where only Sui is present cannot be detected without initializing Sui - and Sui
   * cannot be un-chosen afterwards - so the ladder calls {@link #startServer()} instead of asking
   * this. It is only used to avoid the native ADB flow being dragged into a Shizuku attempt on
   * devices where neither provider exists.
   */
  public static boolean isPossible() {
    return isAvailable() || isManagerInstalled();
  }

  /** True when the Shizuku manager app is installed. Never throws. */
  public static boolean isManagerInstalled() {
    try {
      App.getPm().getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0);
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Name of the provider that serves us: {@code "Sui"}, {@code "Shizuku"} (the manager) or {@code
   * "none"}. Sui is the module that answers the Shizuku API on KernelSU / Magisk; the manager is
   * the app started over ADB or with root.
   */
  public static String getProviderName() {
    if (!isAvailable()) {
      return "none";
    }
    return isSui() ? "Sui" : "Shizuku";
  }

  /** True once Sui has taken us over. Never throws. */
  public static boolean isSui() {
    try {
      return Sui.isSui();
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * True if we are allowed to talk to Shizuku. Never throws.
   *
   * <p>Do not use {@code Shizuku.checkRemotePermission()} here: the server implements it by
   * checking the permission of its own UID, so it reports "granted" for anything when Shizuku runs
   * as root. The value that matters is the one the server reported when our binder attached, which
   * is what {@code checkSelfPermission()} returns.
   */
  public static boolean isPermitted() {
    try {
      if (Shizuku.isPreV11()) {
        return true;
      }
      return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * UID of the Shizuku server: 0 when Shizuku was started with root, 2000 (shell) when it was
   * started over ADB. Returns -1 if unknown.
   */
  public static int getUid() {
    try {
      return Shizuku.getUid();
    } catch (Throwable t) {
      return -1;
    }
  }

  public static boolean isRoot() {
    return getUid() == 0;
  }

  /** Logs what Shizuku reports about itself and about our permission. Used for diagnostics. */
  public static void logStatus() {
    MyLog.i(TAG, "logStatus", "provider: " + getProviderName());
    MyLog.i(TAG, "logStatus", "pingBinder: " + safe(Shizuku::pingBinder));
    MyLog.i(TAG, "logStatus", "isPreV11: " + safe(Shizuku::isPreV11));
    MyLog.i(TAG, "logStatus", "serverVersion: " + safe(Shizuku::getVersion));
    MyLog.i(TAG, "logStatus", "serverPatch: " + safe(Shizuku::getServerPatchVersion));
    MyLog.i(TAG, "logStatus", "uid: " + safe(Shizuku::getUid));
    MyLog.i(TAG, "logStatus", "seLinux: " + safe(Shizuku::getSELinuxContext));
    MyLog.i(TAG, "logStatus", "checkSelfPermission: " + safe(Shizuku::checkSelfPermission));
  }

  private interface ThrowingSupplier {
    Object get() throws Throwable;
  }

  private static String safe(ThrowingSupplier supplier) {
    try {
      return String.valueOf(supplier.get());
    } catch (Throwable t) {
      return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Runtime permission
  // ---------------------------------------------------------------------------------------------

  private PermCallback mPermCb;
  private Shizuku.OnRequestPermissionResultListener mPermListener;

  /** Asks the user to grant the Shizuku permission. Must be called on the main thread. */
  public void requestPermission(PermCallback callback) {
    if (!isAvailable()) {
      MyLog.e(TAG, "requestPermission", "Shizuku is not running");
      callback.onResult(false);
      return;
    }

    if (isPermitted()) {
      callback.onResult(true);
      return;
    }

    // A previous request can have timed out with its listener still registered. Drop it first,
    // otherwise Shizuku would keep calling a callback that belongs to an abandoned request.
    Shizuku.OnRequestPermissionResultListener stale = mPermListener;
    if (stale != null) {
      mPermListener = null;
      mPermCb = null;
      try {
        Shizuku.removeRequestPermissionResultListener(stale);
      } catch (Throwable ignored) {
      }
    }

    mPermCb = callback;
    mPermListener =
        (requestCode, grantResult) -> {
          if (requestCode != PERM_REQ_CODE) {
            return;
          }

          Shizuku.OnRequestPermissionResultListener listener = mPermListener;
          PermCallback cb = mPermCb;
          mPermListener = null;
          mPermCb = null;

          if (listener != null) {
            try {
              Shizuku.removeRequestPermissionResultListener(listener);
            } catch (Throwable ignored) {
            }
          }

          if (cb != null) {
            cb.onResult(grantResult == PackageManager.PERMISSION_GRANTED);
          }
        };

    try {
      Shizuku.addRequestPermissionResultListener(mPermListener);
      Shizuku.requestPermission(PERM_REQ_CODE);
    } catch (Throwable t) {
      MyLog.e(TAG, "requestPermission", t);
      mPermListener = null;
      mPermCb = null;
      callback.onResult(false);
    }
  }

  /**
   * True once the ladder has already put the permission dialog of that provider in front of the
   * user in this app process. Without this, every start-up would show it again and every start-up
   * would stall for {@link #PERM_WAIT_MS}.
   *
   * <p>The two providers are tracked separately on purpose: a denial by the Shizuku manager must
   * not stop Sui from being asked, which is the whole point of the fallback.
   */
  private static boolean mManagerPermAsked = false;

  private static boolean mSuiPermAsked = false;

  /**
   * Called when the user explicitly enables the ADB / Shizuku tier, so the dialog is shown again.
   */
  public static void resetPermissionAsked() {
    mManagerPermAsked = false;
    mSuiPermAsked = false;
  }

  /**
   * Asks for the permission of the provider that currently serves us and waits at most {@code
   * timeoutMs} for the answer.
   *
   * <p>Safe to call from a background thread: both the request and its callback are dispatched to
   * the main thread. Returns false when no provider is running, when the user denied the request or
   * when the dialog was not answered within the budget. An answer that arrives after that is not
   * lost: it is handed to the callback registered with {@link #setGrantCallback(GrantCallback)}.
   */
  public boolean requestPermissionBlocking(long timeoutMs) {
    if (!isAvailable()) {
      MyLog.e(TAG, "requestPermissionBlocking", "Shizuku is not running");
      return false;
    }

    if (isPermitted()) {
      return true;
    }

    boolean sui = isSui();
    if (sui ? mSuiPermAsked : mManagerPermAsked) {
      MyLog.w(
          TAG,
          "requestPermissionBlocking",
          "Permission was already requested from " + getProviderName() + " in this process");
      return false;
    }
    if (sui) {
      mSuiPermAsked = true;
    } else {
      mManagerPermAsked = true;
    }

    final CountDownLatch answered = new CountDownLatch(1);
    final AtomicBoolean granted = new AtomicBoolean(false);
    // A permission dialog stays on the screen until the user answers it, which can happen long
    // after the wait below has given up. The listener stays registered for that case, and this
    // flag tells the late result where it belongs.
    final AtomicBoolean abandoned = new AtomicBoolean(false);

    new Handler(Looper.getMainLooper())
        .post(
            () ->
                requestPermission(
                    res -> {
                      if (abandoned.get()) {
                        if (res) {
                          notifyGranted();
                        }
                        return;
                      }
                      granted.set(res);
                      answered.countDown();
                    }));

    try {
      if (!answered.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        abandoned.set(true);

        // The answer can arrive between the deadline and this line.
        if (granted.get()) {
          MyLog.i(
              TAG, "requestPermissionBlocking", getProviderName() + " permission granted: true");
          return true;
        }

        MyLog.w(
            TAG,
            "requestPermissionBlocking",
            "No answer to the "
                + getProviderName()
                + " permission request within "
                + timeoutMs
                + "ms");
        return false;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }

    MyLog.i(
        TAG,
        "requestPermissionBlocking",
        getProviderName() + " permission granted: " + granted.get());
    return granted.get();
  }

  /** Called when a permission request is answered after the caller had stopped waiting for it. */
  public interface GrantCallback {
    void onGranted();
  }

  private static volatile GrantCallback mGrantCb;

  /**
   * Registers the handler for a grant that arrives after {@link #requestPermissionBlocking(long)}
   * has given up. Called on the thread Shizuku delivers results on, which is the main thread.
   */
  public static void setGrantCallback(GrantCallback callback) {
    mGrantCb = callback;
  }

  /**
   * Tells the ladder that a permission it had stopped waiting for was granted after all. Without
   * this the grant would only be noticed on the next start-up, which looks like the permission
   * never being detected.
   */
  private static void notifyGranted() {
    MyLog.i(
        TAG,
        "notifyGranted",
        "Permission granted after the wait gave up, provider: " + getProviderName());

    GrantCallback cb = mGrantCb;
    if (cb == null) {
      return;
    }

    try {
      cb.onGranted();
    } catch (Throwable t) {
      MyLog.e(TAG, "notifyGranted", t);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // User service
  // ---------------------------------------------------------------------------------------------

  private final Object LOCK = new Object();

  // These are read from UI paths - NativeDaemon.isAlive() reaches isServerStarted() through
  // DaemonHandler.connectToDaemon(), which AdbConnectSvc calls on the main thread. LOCK is held for
  // the whole of startServer(), including the bind wait, so reading them under LOCK would block
  // that
  // thread for seconds. volatile keeps the state visible without blocking on the start-up.
  private volatile IPmxUserService mService;
  private volatile Shizuku.UserServiceArgs mArgs;
  private volatile ServiceConnection mConn;
  private volatile boolean mServerStarted = false;

  /**
   * Counts bind attempts. Shizuku keeps one {@code ShizukuServiceConnection} per user service in a
   * static cache and registers our {@link ServiceConnection} inside it; unbinding when the tier is
   * switched off does not clear that cache - only the death of the user service process does, and
   * that is reported on the main thread. A callback belonging to an earlier attempt can therefore
   * still arrive while a newer attempt is connecting. It must not be allowed to touch the newer
   * attempt's state, or a service that did connect would be reported as broken.
   */
  private final AtomicInteger mBindEpoch = new AtomicInteger();

  private Shizuku.UserServiceArgs getArgs() {
    if (mArgs == null) {
      ComponentName component = new ComponentName(APPLICATION_ID, PmxUserService.class.getName());
      mArgs =
          new Shizuku.UserServiceArgs(component)
              .daemon(true)
              .processNameSuffix("pmx")
              .debuggable(BuildConfig.DEBUG)
              .version(BuildConfig.VERSION_CODE);
    }
    return mArgs;
  }

  /**
   * Binds to the Shizuku user service, asking the Shizuku manager first and Sui second.
   *
   * <p>Unlike the ADB path there is no helper process to spawn: the provider is already running as
   * shell or root. We only pick a provider, make sure we are permitted and bind to the service.
   *
   * <p>Must not be called on the main thread: the connection callback is delivered there.
   */
  public boolean startServer() {
    synchronized (LOCK) {
      if (mServerStarted && mService != null) {
        return true;
      }

      if (ThreadUtils.isMainThread()) {
        MyLog.e(TAG, "startServer", "Called on the main thread, cannot bind the user service");
        return false;
      }

      // The Shizuku manager is asked first. When it answers at all it also stays the provider for
      // this attempt: the permission answer belongs to the manager, and staying with it keeps the
      // request repeatable - the user can grant it later, or from the ADB tier checkbox, without
      // restarting the app.
      //
      // Sui is therefore the fallback for a manager that does not answer at all - not installed,
      // or its server did not hand its binder over within {@link #PROVIDER_WAIT_MS}. It cannot be
      // the other way round: once Sui has been initialized, the API library refuses the manager's
      // binder for the rest of the process, so losing the manager would cost us the manager itself.
      if (waitForProvider(true)) {
        return bindAndAskPermission();
      }

      MyLog.i(TAG, "startServer", "The Shizuku manager is not serving, falling back to Sui");
      return waitForProvider(false) && bindAndAskPermission();
    }
  }

  /**
   * Asks the provider that serves us for its permission, waits for the answer and binds the user
   * service.
   */
  private boolean bindAndAskPermission() {
    logStatus();

    // Even a provider that answers only gets a bounded window to have the request confirmed, so a
    // dialog that nobody looks at cannot hold the start-up up forever.
    if (!isPermitted() && !requestPermissionBlocking(PERM_WAIT_MS)) {
      MyLog.e(TAG, "bindAndAskPermission", "Permission is not granted by " + getProviderName());
      return false;
    }

    mServerStarted = bindService(PROVIDER_WAIT_MS);
    if (mServerStarted) {
      MyLog.i(
          TAG,
          "bindAndAskPermission",
          "Bound to the " + getProviderName() + " user service (UID: " + getUid() + ")");
    }
    return mServerStarted;
  }

  /** True once Sui has been initialized in this process. Sui may only be initialized once. */
  private static boolean mSuiTried = false;

  /**
   * Makes sure a Shizuku API provider serves us, without binding the user service. The Shizuku
   * manager gets a bounded window to hand its binder over, Sui is initialized when it does not.
   *
   * <p>Used by the ADB tier checkbox, where the user asks for Shizuku explicitly. Must not be
   * called on the main thread: it can block for {@link #PROVIDER_WAIT_MS}.
   */
  public boolean ensureProvider() {
    if (isAvailable()) {
      return true;
    }
    return waitForProvider(true) || waitForProvider(false);
  }

  /**
   * Makes the requested provider serve us. The Shizuku manager is waited for, because the server
   * pushes its binder to our {@code ShizukuProvider} right after the app process starts; Sui is
   * asked for its binder with a transaction. False means that provider is not there.
   */
  private static boolean waitForProvider(boolean manager) {
    if (!manager) {
      return switchToSui();
    }

    // Once Sui has answered, the API library refuses the manager's binder for the rest of the
    // process, so waiting for it would only burn the window.
    if (isSui()) {
      MyLog.w(TAG, "waitForProvider", "Sui is already serving, the manager cannot be reached");
      return false;
    }

    if (isAvailable()) {
      return true;
    }

    // Waiting is pointless when the manager is not installed at all.
    return isManagerInstalled() && waitForManagerBinder(PROVIDER_WAIT_MS);
  }

  /**
   * Waits up to {@code timeoutMs} for the Shizuku manager to hand its binder over. The server sends
   * the binder right after the app process starts, so this normally returns within a second; it
   * only runs out when the server is not running at all.
   */
  private static boolean waitForManagerBinder(long timeoutMs) {
    long deadline = SystemClock.elapsedRealtime() + timeoutMs;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (isAvailable() && !isSui()) {
        return true;
      }
      SystemClock.sleep(50);
    }

    boolean answered = isAvailable() && !isSui();
    if (!answered) {
      MyLog.w(
          TAG,
          "waitForManagerBinder",
          "The Shizuku manager did not answer within " + timeoutMs + "ms");
    }
    return answered;
  }

  /**
   * Switches to Sui, the root module that implements the Shizuku API on KernelSU / Magisk. This is
   * a one-way choice: {@code ShizukuProvider} drops the manager's binder once Sui has answered, so
   * it is only done after the manager failed.
   */
  private static synchronized boolean switchToSui() {
    if (isSui()) {
      return true;
    }

    if (mSuiTried) {
      return false;
    }
    mSuiTried = true;

    boolean initialized;
    try {
      initialized = Sui.init(APPLICATION_ID);
    } catch (Throwable t) {
      MyLog.e(TAG, "switchToSui", t);
      initialized = false;
    }

    MyLog.i(TAG, "switchToSui", "Sui initialized: " + initialized);
    return initialized;
  }

  private boolean bindService(long timeoutMs) {
    if (mService != null) {
      return true;
    }

    // Keep a floor, so the bind still gets a fair chance when a caller passes a short window.
    long waitMs = Math.max(timeoutMs, BIND_FLOOR_MS);

    // A previous attempt can have left a dead connection behind (Shizuku restarted, or the service
    // was killed). Binding on top of it would leak the old one, so drop it first.
    if (mConn != null) {
      unbindService();
    }

    // The service is started by Shizuku, which can take a moment. Wait for the callback, which is
    // delivered on the main thread, so this must run in the background.
    final CountDownLatch connected = new CountDownLatch(1);
    final int epoch = mBindEpoch.incrementAndGet();

    mConn =
        new ServiceConnection() {
          @Override
          public void onServiceConnected(ComponentName name, IBinder binder) {
            if (mBindEpoch.get() != epoch) {
              MyLog.w(TAG, "bindService", "Ignoring a connection of an earlier bind attempt");
              return;
            }
            mService = IPmxUserService.Stub.asInterface(binder);
            connected.countDown();
          }

          @Override
          public void onServiceDisconnected(ComponentName name) {
            if (mBindEpoch.get() != epoch) {
              return;
            }
            mService = null;
          }
        };

    try {
      Shizuku.bindUserService(getArgs(), mConn);
      if (!connected.await(waitMs, TimeUnit.MILLISECONDS)) {
        MyLog.e(TAG, "bindService", "Timed out waiting " + waitMs + "ms for the user service");
        return false;
      }
      return mService != null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Throwable t) {
      MyLog.e(TAG, "bindService", t);
      return false;
    }
  }

  private void unbindService() {
    // Anything still on its way from the connection being dropped belongs to the past now.
    mBindEpoch.incrementAndGet();

    if (mConn == null) {
      return;
    }

    try {
      Shizuku.unbindUserService(getArgs(), mConn, true);
    } catch (Throwable t) {
      MyLog.e(TAG, "unbindService", t);
    }

    mConn = null;
    mService = null;
    mServerStarted = false;
  }

  /**
   * True when the user service is bound and usable. Deliberately lock-free: {@link #startServer()}
   * holds {@link #LOCK} across the bind wait, and this is called from the main thread.
   */
  public boolean isServerStarted() {
    return mServerStarted && mService != null;
  }

  /** Same as {@link #isServerStarted()}, named for readability at the call sites. */
  public boolean isServiceConnected() {
    return isServerStarted();
  }

  /** Spawns the privileged daemon in the Shizuku context. */
  public boolean startDaemon(String appId, String codeWord) {
    // priv_daemon classes are packaged into the app's own APK, which is what pmxe passes as
    // CLASSPATH too.
    String classPath = ApiUtils.getMyAppInfo().sourceDir;

    synchronized (LOCK) {
      if (mService == null) {
        MyLog.e(TAG, "startDaemon", "User service is not connected");
        return false;
      }

      try {
        String error = mService.startDaemon(appId, codeWord, classPath);
        if (error == null || error.isEmpty()) {
          MyLog.i(TAG, "startDaemon", "Privileged daemon spawned via Shizuku");
          return true;
        }

        MyLog.e(TAG, "startDaemon", "User service failed to spawn the daemon: " + error);
        return false;
      } catch (Throwable t) {
        MyLog.e(TAG, "startDaemon", t);
        return false;
      }
    }
  }

  /**
   * Runs a shell command as shell or root, depending on how Shizuku was started. This replaces
   * sending the command to the native {@code pmxe} helper.
   */
  public boolean run(String cmd) {
    IPmxUserService service = mService;
    if (service == null) {
      MyLog.e(TAG, "run", "User service is not connected");
      return false;
    }

    try {
      String output = service.runCommand(cmd);
      MyLog.i(TAG, "run", "Ran via Shizuku: " + cmd + " -> " + output.trim());
      return true;
    } catch (Throwable t) {
      MyLog.e(TAG, "run", t);
      return false;
    }
  }

  /** Stops the daemon and releases the user service. */
  public void stop() {
    synchronized (LOCK) {
      mServerStarted = false;

      // The daemon renames itself, so it can be killed without knowing its PID. Closing it over the
      // binder is enough when that works, but a restart must not leave an orphan behind.
      if (mService != null) {
        try {
          mService.runCommand("pkill -f " + DAEMON_NAME);
        } catch (Throwable ignored) {
        }
      }

      unbindService();
    }
  }
}
