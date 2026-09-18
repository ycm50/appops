package com.mirfatif.permissionmanagerx.privs;

import static com.mirfatif.permissionmanagerx.BuildConfig.APPLICATION_ID;
import static com.mirfatif.permissionmanagerx.BuildConfig.APP_ID;
import static java.lang.System.currentTimeMillis;

import android.os.SystemClock;
import com.mirfatif.err.AdbException;
import com.mirfatif.permissionmanagerx.BuildConfig;
import com.mirfatif.permissionmanagerx.app.App;
import com.mirfatif.permissionmanagerx.prefs.MySettings;
import com.mirfatif.permissionmanagerx.util.ApiUtils;
import com.mirfatif.permissionmanagerx.util.StdErrLogServer;
import com.mirfatif.privtasks.util.MyLog;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public enum NativeDaemon {
  INS_R(false),
  INS_A(true);

  private static final String STATIC_TAG = "NativeDaemon";

  private final boolean mAdb;
  private final String DAEMON_COMMAND;
  private final String TAG;

  NativeDaemon(boolean isAdb) {
    mAdb = isAdb;
    String libDir = ApiUtils.getMyAppInfo().nativeLibraryDir;
    String bin = new File(libDir, "libpmxe.so").getAbsolutePath();

    String suffix = APPLICATION_ID.replace(APP_ID, "");
    suffix = suffix.replaceFirst("^\\.", "");
    if (!suffix.isEmpty()) {
      suffix += "-";
    }

    DAEMON_COMMAND = "exec " + bin + " -D " + suffix + (isAdb ? "adb" : "root");
    TAG = isAdb ? "AdbNativeDaemon" : "RootNativeDaemon";
  }

  private final Object LOCK = new Object();

  /** True if this daemon is backed by Shizuku rather than by the native pmxe helper. */
  private boolean isShizuku() {
    return mAdb && ShizukuDaemon.INS.isServerStarted();
  }

  private void connectToCheckAlive() {
    synchronized (LOCK) {
      // Shizuku has no port to reconnect to; being "started" is all we track.
      if (isShizuku()) {
        return;
      }

      if (MySettings.INS.shouldRestartDaemon()) {
        savePort(0);
        return;
      }

      int port = mAdb ? MySettings.INS.getAdbDaemonPort() : MySettings.INS.getRootDaemonPort();
      if (port <= 0) {
        return;
      }

      connectToDaemon(port, false);

      if (!isAlive(false)) {
        savePort(0);
      }
    }
  }

  private void connectToDaemon(int port, boolean printException) {
    Socket socket = null;
    try {
      try {
        socket = new Socket(Inet4Address.getByAddress(new byte[] {127, 0, 0, 1}), port);
      } catch (IOException e) {
        if (printException) {
          throw e;
        } else {
          MyLog.e(TAG, "connectToDaemon", e.toString());
          return;
        }
      }

      socket.setTcpNoDelay(true);

      OutputStream os = socket.getOutputStream();
      PrintWriter writer = new PrintWriter(os, true);

      try (StdErrLogServer server = new StdErrLogServer(TAG, () -> daemonStopped(true))) {
        writer.println(CMD_HELLO + server.getLocalPort());
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        socket.setSoTimeout(5000);
        String resp = reader.readLine();
        socket.setSoTimeout(0);

        if (resp == null || !resp.startsWith(RESP_OK)) {
          return;
        }

        server.waitForConn();

        mState = new State(writer);
      }
    } catch (IOException | NumberFormatException | InterruptedException | ExecutionException e) {
      MyLog.e(TAG, "connectToDaemon", e);
    } finally {
      if (!isAlive(false) && socket != null) {
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  private boolean startRootDaemon() {
    synchronized (LOCK) {
      if (isAlive(false)) {
        MyLog.w(TAG, "startRootDaemon", "Daemon already running");
        return true;
      }

      String su = MySettings.INS.getSuExePath();
      Process proc;
      try {
        MyLog.i(TAG, "startRootDaemon", "Executing: [" + su + "]");
        proc = new ProcessBuilder(su).start();
      } catch (IOException e) {
        // Either su is not installed, or a root manager such as KernelSU with Sui hides it on
        // purpose because root is meant to be obtained through the Shizuku API instead. Both are
        // normal, so this is reported in one line rather than as a stack trace on every start-up.
        MyLog.w(TAG, "startRootDaemon", "Cannot run '" + su + "': " + e.getMessage());
        MySettings.INS.setRootEnabled(false);
        return false;
      }

      MyLog.i(TAG, "startRootDaemon", "Starting root native daemon");

      PrintWriter printer = new PrintWriter(proc.getOutputStream());
      printer.println(DAEMON_COMMAND);
      printer.close();

      waitForPort(new BufferedReader(new InputStreamReader(proc.getInputStream())));

      boolean rootEnabled = isAlive(false);
      MySettings.INS.setRootEnabled(rootEnabled);
      return rootEnabled;
    }
  }

  private boolean startAdbDaemon(
      boolean retryConnect, boolean printException, String host, int port) {
    synchronized (LOCK) {
      if (isAlive(false)) {
        MyLog.w(TAG, "startAdbDaemon", "Daemon already running");
        return true;
      }

      // Prefer Shizuku when it can serve us: no pairing code, no adb tcpip and no native helper
      // needed.
      if (ShizukuDaemon.isPossible()) {
        if (ShizukuDaemon.INS.startServer()) {
          boolean adbEnabled = isAlive(false);
          MySettings.INS.setAdbEnabled(adbEnabled);
          return adbEnabled;
        }

        // Shizuku is running but would not give us a usable service. Native pairing is then only
        // worth trying when the caller asked for it and it can actually work:
        //   - getShizuku() calls us with no host and no port on purpose, and must never fall back
        // to
        //     native pairing.
        //   - The ladder passes the saved host and port, but those only mean something once a
        // native
        //     connection has succeeded at least once. Without that, pairing against 127.0.0.1:5555
        //     just burns seconds and then clears the ADB tier the user asked for.
        // The tier is deliberately left enabled, otherwise one failed attempt would switch off the
        // only back end the user asked for and the next start-up would not even try Shizuku again.
        boolean explicitRequest = host != null && port > 0;
        if (!explicitRequest || !MySettings.INS.isAdbPortSaved()) {
          MyLog.e(TAG, "startAdbDaemon", "Shizuku is running but did not provide a usable service");
          return false;
        }
        MyLog.w(TAG, "startAdbDaemon", "Shizuku is unusable, trying the saved ADB host and port");
      }

      boolean err = false;
      Exception exception = null;

      long ts = currentTimeMillis() + 5000;
      for (int i = 0; i <= 10; i++) {
        err = false;
        exception = null;

        try (AdbConnManager connMgr = new AdbConnManager()) {
          connMgr.setTimeout(10, TimeUnit.SECONDS);
          boolean res;
          if (i == 0) {
            res = connMgr.autoConnect(App.getCxt(), 2500);
          } else if (host == null || port <= 0) {
            err = true;
            break;
          } else {
            res = connMgr.connect(host, port);
          }
          if (res) {
            try (AdbStream adbStream = connMgr.openStream("shell:" + DAEMON_COMMAND)) {
              waitForPort(new BufferedReader(new InputStreamReader(adbStream.openInputStream())));
            } catch (IOException | InterruptedException e) {
              MyLog.e(TAG, "startAdbDaemon", "ADB stream failed", e);
            }
            break;
          } else {
            err = true;
          }

        } catch (AdbException
            | InterruptedException
            | IOException
            | AdbPairingRequiredException e) {
          exception = e;
        }

        if (i == 0) {
          continue;
        }

        if (!retryConnect || (ts - currentTimeMillis() <= 0)) {
          break;
        }

        if (i < 10) {
          SystemClock.sleep(500);
          MyLog.i(TAG, "startAdbDaemon", "Retrying " + (i + 1));
        }
      }

      if (exception != null) {
        if (printException) {
          MyLog.e(TAG, "startAdbDaemon", "Adb connection failed", exception);
        } else {
          MyLog.e(TAG, "startAdbDaemon", "Adb connection failed: " + exception);
        }
      } else if (err) {
        MyLog.e(TAG, "startAdbDaemon", "Adb connection failed");
      }

      boolean adbEnabled = isAlive(false);
      MySettings.INS.setAdbEnabled(adbEnabled);
      return adbEnabled;
    }
  }

  private void waitForPort(BufferedReader reader) {
    try (reader) {
      String resp = reader.readLine();

      String[] respSplit;
      int port;
      if (resp == null
          || !resp.startsWith(RESP_PORT)
          || ((respSplit = resp.split(" ")).length) != 2
          || (port = Integer.parseInt(respSplit[1])) <= 0) {
        return;
      }

      connectToDaemon(port, true);
      if (isAlive(false)) {
        savePort(port);
        setExitOnAppDeathUnlocked();
      }
    } catch (IOException e) {
      MyLog.e(TAG, "waitForPort", e);
    }
  }

  public void stopDaemon() {
    synchronized (LOCK) {
      if (isAlive(true)) {
        stopDaemonUnlocked();
      }
    }
  }

  private void stopDaemonUnlocked() {
    if (isShizuku()) {
      ShizukuDaemon.INS.stop();
    } else {
      sendCmd(CMD_EXIT);
    }
    daemonStopped(false);
  }

  private void daemonStopped(boolean holdLock) {
    if (mState != null) {
      if (holdLock) {
        synchronized (LOCK) {
          mState = null;
          savePort(0);
        }
      } else {
        mState = null;
        savePort(0);
      }
    }
  }

  public static final int NATIVE_DAEMON_RESTART_WAIT = 5000;

  private void savePort(int port) {
    if (mAdb) {
      MySettings.INS.saveAdbDaemonPort(port);
    } else {
      MySettings.INS.saveRootDaemonPort(port);
    }
  }

  public void setExitOnAppDeath() {
    synchronized (LOCK) {
      if (isAlive(true, true)) {
        setExitOnAppDeathUnlocked();
      }
    }
  }

  public void setExitOnAppDeathUnlocked() {
    // With Shizuku there is no pmxe helper to talk to. The daemon learns about this over the
    // binder callback (DaemonIface.setExitOnAppDeath) instead.
    if (isShizuku()) {
      return;
    }

    sendCmd(CMD_AUTO_EXIT + " " + MySettings.INS.shouldDaemonExitOnAppDeath());
  }

  private boolean isAlive(boolean tryConnect) {
    return isAlive(tryConnect, false);
  }

  private boolean isAlive(boolean tryConnect, boolean forAutoExit) {
    if (isShizuku()) {
      // isShizuku() means the Shizuku user service is bound *and* usable, so there is no socket to
      // probe. This used to be an unconditional true for the whole ADB tier, which made a service
      // that Shizuku had dropped look alive and let every failed write go unnoticed.
      return true;
    }

    if (mState == null
        && tryConnect
        && (forAutoExit || !MySettings.INS.shouldDaemonExitOnAppDeath())) {
      connectToCheckAlive();
    }
    return mState != null;
  }

  private static final String CMD_HELLO = "hello ";
  private static final String CMD_EXIT = "exit";
  private static final String CMD_AUTO_EXIT = "auto_exit";
  private static final String CMD_RUN = "run ";
  private static final String CMD_RUN_BG = "run_bg ";
  private static final String CMD_RUN_DMN = "run_dmn ";

  private static final String RESP_PORT = "PORT:";
  private static final String RESP_OK = "OK";

  public void run(String cmd, boolean background) {
    if (isShizuku()) {
      ShizukuDaemon.INS.run(cmd);
      return;
    }

    sendCommand((background ? CMD_RUN_BG : CMD_RUN) + cmd);
  }

  public void runDaemon(String codeWord) {
    if (isShizuku()) {
      ShizukuDaemon.INS.startDaemon(App.getCxt().getPackageName(), codeWord);
      return;
    }

    var cp = ApiUtils.getMyAppInfo().sourceDir;
    var guid = MySettings.INS.getDaemonUid();
    var seLabel = MySettings.INS.getDaemonContext();
    var appId = App.getCxt().getPackageName();
    sendCommand(CMD_RUN_DMN + cp + " " + guid + " " + seLabel + " " + appId + " " + codeWord);
  }

  private void sendCommand(String cmd) {
    sendCommandLocked(cmd);
  }

  private void sendCommandLocked(String cmd) {
    synchronized (LOCK) {
      if (!isAlive(false)) {
        return;
      }
      sendCmd(cmd);
    }
  }

  void sendCmd(String cmd) {
    if (BuildConfig.DEBUG) {
      MyLog.d(TAG, "sendCmd", "Sending command: " + cmd);
    }
    mState.mCmdWriter.println(cmd);
  }

  private State mState;

  private static class State {

    private final PrintWriter mCmdWriter;

    private State(PrintWriter writer) {
      mCmdWriter = writer;
    }
  }

  public static boolean hasRoot(boolean tryConnect) {
    return INS_R.isAlive(tryConnect);
  }

  public static boolean getRoot() {
    return getRoot(true);
  }

  public static boolean getRoot(boolean ifEnabledOnly) {
    return hasRoot(true)
        || ((!ifEnabledOnly || MySettings.INS.isRootEnabled()) && INS_R.startRootDaemon());
  }

  public static boolean hasAdb(boolean tryConnect) {
    return INS_A.isAlive(tryConnect);
  }

  public static boolean getAdb() {
    return getAdb(true, false, false);
  }

  /** Obtains the ADB-level back end, using the saved wireless debugging host and port. */
  public static boolean getAdb(boolean ifEnabledOnly) {
    return getAdb(ifEnabledOnly, false, false);
  }

  private static boolean getAdb(
      boolean ifEnabledOnly, boolean retryConnect, boolean printException) {
    return getAdb(
        ifEnabledOnly,
        retryConnect,
        printException,
        MySettings.INS.getAdbHost(),
        MySettings.INS.getAdbPort());
  }

  public static boolean getAdb(
      boolean ifEnabledOnly, boolean retryConnect, boolean printException, String host, int port) {
    return hasAdb(true)
        || ((!ifEnabledOnly || MySettings.INS.isAdbEnabled())
            && INS_A.startAdbDaemon(retryConnect, printException, host, port));
  }

  /**
   * Obtains a Shizuku-backed back end, ignoring the saved ADB preference.
   *
   * <p>Used by the privilege ladder, which has already decided that Shizuku may be used: it is
   * running and either already permitted or the user enabled the ADB tier. Applying the {@code
   * ifEnabledOnly} check here would only get in the way. Returns false when no provider is left to
   * ask, so the caller is never dragged into the native ADB pairing flow by accident.
   */
  public static boolean getShizuku() {
    if (ShizukuDaemon.INS.isServerStarted()) {
      return true;
    }

    // No availability pre-check: startServer() selects the provider itself. The Shizuku manager
    // gets a bounded window to answer and Sui is tried when it does not, so being unavailable
    // right now says nothing about the next few seconds.
    if (!ShizukuDaemon.INS.startServer()) {
      return false;
    }

    // The tier is served by Shizuku, but the UI still tracks it as the ADB tier. Keep the two in
    // step, otherwise the drawer would show ADB as disabled while ADB-level access is in use.
    MySettings.INS.setAdbEnabled(true);
    return true;
  }

  public static boolean forceGetAdb(boolean retryConnect) {
    if (getAdb(false, retryConnect, false)) {
      return true;
    }

    if (!getRoot()) {
      return false;
    }

    MyLog.i(STATIC_TAG, "forceGetAdb", "Sending ADB switch commands");
    INS_R.run("settings put global adb_enabled 0", false);
    INS_R.run("stop adbd", false);
    INS_R.run("setprop service.adb.tcp.port " + MySettings.INS.getAdbPort(), false);
    SystemClock.sleep(2000);
    INS_R.run("settings put global adb_enabled 1", false);
    INS_R.run("start adbd", false);

    return getAdb(false, true, true);
  }
}
