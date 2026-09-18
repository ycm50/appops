package com.mirfatif.permissionmanagerx.privs;

import android.content.Context;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Runs inside a process that Shizuku spawns on our behalf, as shell (when Shizuku was started over
 * ADB) or as root (when Shizuku was started with root). Everything that the native {@code pmxe}
 * helper does for the ADB path happens here instead: spawning the privileged daemon and running
 * shell commands.
 *
 * <p>The class is loaded by Shizuku from our APK, so it must stay public and keep a public no-arg
 * constructor. Shizuku instantiates it reflectively in another process and calls it over AIDL, so
 * nothing here looks used to R8: {@code @Keep} plus the matching rules in {@code
 * app/proguard-rules.pro} are what keeps the class instantiable in release builds. Without them R8
 * deletes the whole implementation and the user service can never start.
 */
@Keep
public class PmxUserService extends IPmxUserService.Stub {

  private static final String DAEMON_NAME = "com.mirfatif.privdaemon.pmx";

  private static final String DAEMON_CLASS = "com.mirfatif.privdaemon.Main";

  public PmxUserService() {}

  /** Shizuku may instantiate user services with a Context argument instead. */
  @Keep
  @SuppressWarnings("unused")
  public PmxUserService(Context context) {}

  @Override
  public String runCommand(String command) {
    StringBuilder output = new StringBuilder();

    try {
      Process proc = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append('\n');
        }
      }
      proc.waitFor();
    } catch (Exception e) {
      output.append(e).append('\n');
    }

    return output.toString();
  }

  @Override
  public String startDaemon(String appId, String codeWord, String classPath) {
    final Process proc;
    try {
      ProcessBuilder builder =
          new ProcessBuilder(
              "app_process", "/", "--nice-name=" + DAEMON_NAME, DAEMON_CLASS, appId, codeWord);
      builder.environment().put("CLASSPATH", classPath);
      builder.redirectErrorStream(true);
      proc = builder.start();
    } catch (Exception e) {
      return e.toString();
    }

    // The daemon connects back to the app over Binder, so nothing waits for it here. Its output
    // still has to be drained, otherwise the pipe can fill up and block it.
    final StringBuilder output = new StringBuilder();
    Thread drainer =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  // Keep the tail, so a child that dies can be reported with its own message.
                  synchronized (output) {
                    if (output.length() < 4000) {
                      output.append(line).append('\n');
                    }
                  }
                }
              } catch (Exception ignored) {
              }
            },
            "pmx-daemon-drain");
    drainer.setDaemon(true);
    drainer.start();

    // A child that is already gone never reached the point of talking to the app. Report what it
    // said instead of letting the caller wait out its hello timeout for no reason.
    try {
      if (proc.waitFor(2, TimeUnit.SECONDS)) {
        synchronized (output) {
          String msg = output.toString().trim();
          return msg.isEmpty() ? "daemon exited with code " + proc.exitValue() : msg;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted while waiting for the daemon";
    }

    return "";
  }

  @Override
  public void destroy() {
    System.exit(0);
  }
}
