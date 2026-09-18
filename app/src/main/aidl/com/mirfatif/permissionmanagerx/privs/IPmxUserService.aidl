// Implemented by PmxUserService, which Shizuku instantiates in a process that runs as shell (when
// Shizuku was started over ADB) or as root (when Shizuku was started with root). This is the
// supported way of running privileged commands; Shizuku.newProcess() is refused by newer servers.
package com.mirfatif.permissionmanagerx.privs;

interface IPmxUserService {

  /** Runs a shell command and returns its combined output. */
  String runCommand(String command) = 1;

  /**
   * Spawns the privileged daemon. Returns an empty string on success, otherwise the output the
   * child produced before it died, so a failure is diagnosable instead of silent.
   */
  String startDaemon(String appId, String codeWord, String classPath) = 2;

  /** Shizuku calls this to tear the user service down. The transaction code is fixed by Shizuku. */
  void destroy() = 16777114;
}
