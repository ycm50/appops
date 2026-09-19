package com.mirfatif.privdaemon;

import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import com.mirfatif.privtasks.hiddenapis.HiddenAPIs;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;

class PrivDaemon {

  public static final String TAG = "PrivDaemon";

  PrivDaemon() {
    try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
      String myName = reader.readLine().split("\0")[0];
      for (int pid : HiddenAPIs.getPidsForCommands(new String[] {myName})) {
        if (pid != Process.myPid()) {
          DaemonLog.i(null, "start", "Killing PID " + pid);
          Process.killProcess(pid);
        }
      }
    } catch (IOException e) {
      DaemonLog.e(null, null, e);
    }
  }

  void start(String[] args) {
    if (args.length != 2 || !args[0].startsWith("com.mirfatif.") || !Server.isUUID(args[1])) {
      DaemonLog.e(null, "start", "Bad args: " + Arrays.toString(args));
      System.exit(1);
      return;
    }

    String appId = args[0];
    String codeWord = args[1];

    try {
      Callbacks.INS.talkToApp(appId, codeWord);
    } catch (RemoteException e) {
      DaemonLog.e(null, "start", e);
      System.exit(1);
    }

    DaemonLog.i(null, "start", "I'm up!");

    prepareMainLooper();
    Looper.loop();

    throw new RuntimeException("Main thread loop unexpectedly exited");
  }

  // The deprecation assumes the Android environment creates the main looper for you. The daemon is
  // started through app_process, where nothing does, and ThreadUtils.isMainThread() (priv_library)
  // reads Looper.getMainLooper(): Looper.prepare() would leave that null and silently change the
  // answer. So the daemon keeps marking its own thread as the main one.
  @SuppressWarnings("deprecation")
  private static void prepareMainLooper() {
    Looper.prepareMainLooper();
  }
}
