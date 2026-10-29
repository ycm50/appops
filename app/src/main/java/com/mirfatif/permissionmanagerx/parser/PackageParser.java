package com.mirfatif.permissionmanagerx.parser;

import static android.content.pm.PermissionInfo.PROTECTION_INTERNAL;
import static com.mirfatif.privtasks.util.Util.PM_GET_SIGNATURES;

import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.RemoteException;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ProcessLifecycleOwner;
import com.mirfatif.permissionmanagerx.R;
import com.mirfatif.permissionmanagerx.app.App;
import com.mirfatif.permissionmanagerx.parser.PermGroupsMapping.PermGroupInfo;
import com.mirfatif.permissionmanagerx.parser.permsdb.PermsDb;
import com.mirfatif.permissionmanagerx.prefs.ExcFiltersData;
import com.mirfatif.permissionmanagerx.prefs.MySettings;
import com.mirfatif.permissionmanagerx.privs.DaemonHandler;
import com.mirfatif.permissionmanagerx.privs.DaemonIface;
import com.mirfatif.permissionmanagerx.util.ApiUtils;
import com.mirfatif.permissionmanagerx.util.PkgIconCache;
import com.mirfatif.permissionmanagerx.util.UserUtils;
import com.mirfatif.permissionmanagerx.util.bg.LiveEvent;
import com.mirfatif.permissionmanagerx.util.bg.UiRunner;
import com.mirfatif.privtasks.Constants;
import com.mirfatif.privtasks.HiddenSdkIntConstants;
import com.mirfatif.privtasks.bind.MyPackageOps;
import com.mirfatif.privtasks.bind.MyPkgOpsBatch;
import com.mirfatif.privtasks.bind.PermFixedFlags;
import com.mirfatif.privtasks.util.MyLog;
import com.mirfatif.privtasks.util.Util;
import com.mirfatif.privtasks.util.bg.RateLimitedTaskTyped;
import com.mirfatif.privtasks.util.bg.SingleParamTask;
import com.mirfatif.privtasks.util.bg.SingleSchedTaskExecutor;
import com.mirfatif.privtasks.util.bg.SingleTaskExecutorTyped;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public enum PackageParser {
  INS;

  private static final String TAG = "PackageParser";

  private final PackageManager mPm = App.getPm();

  private final LiveEvent<List<Package>> mPkgListLive = new LiveEvent<>(false);
  private final LiveEvent<Package> mChangedPkg = new LiveEvent<>(true);
  private final LiveEvent<Integer> mProgMax = new LiveEvent<>(true);
  private final LiveEvent<Integer> mProgNow = new LiveEvent<>(true);
  private final LiveEvent<Boolean> mListInProgress = new LiveEvent<>(false);
  private final LiveEvent<Integer> mListCompleted = new LiveEvent<>(true);

  private final List<PackageInfo> mPkgInfoList = new ArrayList<>();

  /**
   * Guards {@link #mPkgInfoList}, {@link #mPkgLabels} and {@link #mPkgInfoListExcludeSystem}. The
   * list and its labels form one generation of prefetched data, so they are replaced and cleared
   * together under this lock; otherwise a clear between the two writes would leave labels of one
   * generation paired with PackageInfo objects of another.
   */
  private final Object mPkgInfoLock = new Object();

  /**
   * Labels of the packages in {@link #mPkgInfoList}, resolved once when that list is built so that
   * {@code loadLabel()} (an IPC to system_server) is not called from the sort comparator and then
   * again for every package. PackageInfo objects are used as identity keys: a package name alone is
   * not unique across users. The map shares the lifetime of {@link #mPkgInfoList} and is cleared
   * together with it.
   */
  private final Map<PackageInfo, String> mPkgLabels = new IdentityHashMap<>();

  private final List<Package> mPkgList = new ArrayList<>();

  public boolean mOpModesConsistent = true;

  private final SingleTaskExecutorTyped<List<Package>> mPkgUpdater =
      new SingleTaskExecutorTyped<>(
          this::updatePkgListInternal, null, TAG + "-PkgUpdater", Thread.MAX_PRIORITY);

  private final SingleSchedTaskExecutor mPkgInfoListCleaner =
      new SingleSchedTaskExecutor(this::clearPkgInfoList, TAG + "-PkgInfoListCleaner");

  public void updatePkgListIfChanged() {
    boolean pkgInfoListEmpty;
    synchronized (mPkgInfoLock) {
      pkgInfoListEmpty = mPkgInfoList.isEmpty();
    }
    if (pkgInfoListEmpty || mPkgList.isEmpty()) {
      updatePkgList();
    }
  }

  public void updatePkgList() {
    updatePkgList(false);
  }

  public void updatePkgList(boolean rebuildPkgInfoList) {
    if (rebuildPkgInfoList) {
      clearPkgInfoList();
    }

    mPkgUpdater.cancelAndSubmit(true);
  }

  public List<Package> updatePkgListWithResult(boolean rebuildPkgInfoList) {
    if (rebuildPkgInfoList) {
      clearPkgInfoList();
    }
    return mPkgUpdater.cancelSubmitGet(true);
  }

  private List<Package> updatePkgListInternal() {
    mListInProgress.postValue(true);

    List<Package> pkgList = Collections.synchronizedList(new ArrayList<>());

    // Publishing a copy of the whole list for every package is quadratic. Intermediate snapshots
    // are throttled by count and by time instead; the final snapshot below is sent only when the
    // loop really completes.
    int pkgsSinceLastPost = 0;
    long lastPostTime = SystemClock.elapsedRealtime();
    boolean interrupted = false;
    boolean completed = false;

    try {
      // Building the source list belongs to the same unit of work as the loop: it asks the
      // PackageManager and can fail the same way, and an exception escaping from here would kill
      // the worker thread instead of being reported.
      mPkgInfoListCleaner.cancel(false);
      List<PackageInfo> pkgInfoList = buildPkgInfoList();

      buildRequiredData();

      setProgress(pkgInfoList.size(), true, true);

      int size = pkgInfoList.size();
      // Prefetch the AppOps and permission flags the loop below is about to fetch one IPC at a
      // time. The maps are thread local and removed in the finally block, so they never outlive
      // this call and never leak into a single-package refresh (which stays on the old path).
      mAppOpsPrefetched.set(prefetchAppOps(pkgInfoList));
      mPermFlagsPrefetched.set(prefetchPermFlags(pkgInfoList));

      for (int i = 0; i < size; i++) {
        if (Thread.interrupted()) {
          interrupted = true;
          break;
        }

        setProgress(i, false, false);
        PackageInfo pkgInfo = pkgInfoList.get(i);

        if (pkgInfo == null) {
          continue;
        }

        Package pkg = new Package();
        if (isPkgUpdated(pkgInfo, pkg, true)) {
          pkgList.add(pkg);

          if (mDoRepeatUpdates) {
            pkgsSinceLastPost++;
            boolean countReached = pkgsSinceLastPost >= PKG_LIST_POST_MIN_COUNT;
            boolean timeReached =
                SystemClock.elapsedRealtime() - lastPostTime >= PKG_LIST_POST_MIN_INTERVAL_MS;
            if (countReached || timeReached) {
              pkgsSinceLastPost = 0;
              lastPostTime = SystemClock.elapsedRealtime();
              updateAndPostLivePkgList(pkgList, false);
            }
          }
        }
      }

      if (!interrupted) {
        setProgress(size, false, true);
        completed = true;
      }
    } catch (RuntimeException e) {
      // A scan must never take the worker thread down: the executor only wraps the task in a
      // try/finally, so an escaping exception would leave isUpdating() true for good and make
      // every later refresh block on the queue. The common source is a batch that fell back to
      // the per-item path and then hit a RuntimeException there.
      MyLog.e(TAG, "updatePkgListInternal", e);
    } finally {
      mAppOpsPrefetched.remove();
      mPermFlagsPrefetched.remove();

      // Only a completed scan may publish the final snapshot. An interrupted or failed scan would
      // otherwise broadcast a partial (even empty) list as "scan finished": the main screen would
      // flash an empty list and "0 apps" until the next scan republishes its own result.
      if (completed) {
        updateAndPostLivePkgList(pkgList, true);
      } else {
        // The list is not published, but the completion event still has to be sent: the pull
        // refresh indicator and the progress bar wait for it and would otherwise spin until the
        // next scan. A negative count tells the UI not to report a package count.
        sendListCompleted(PostListStatus.FINAL, -1);
      }

      // These two steps must run on every return path, including an interrupt or an exception:
      // otherwise the list stays marked as "in progress" and the PackageInfo cache is never
      // scheduled for its delayed cleanup.
      mPkgInfoListCleaner.cancelAndSchedule(false, 30, TimeUnit.SECONDS);
      mListInProgress.postValue(false);
    }

    if (interrupted) {
      return null;
    }

    return getPkgList();
  }

  private static final int PKG_LIST_POST_MIN_COUNT = 25;
  private static final long PKG_LIST_POST_MIN_INTERVAL_MS = 100;

  /**
   * Number of packages per batched AppOps prefetch. A Binder transaction is limited to 1 MB, and
   * the reply is the expensive direction: every op entry is 16 bytes plus a 4-byte presence marker
   * written by writeTypedList, so a package costs roughly {@code 44 + 4 * nameLen + 20 * (pkgOps +
   * uidOps)} bytes. A system app with a few hundred ops set is about 8 KB, which puts 50 packages
   * near 400 KB in the worst case and around 40 KB typically. Chunking keeps a transaction well
   * inside the limit (TransactionTooLargeException) while still replacing up to 50 single-package
   * round trips - 100 IPCs - with one.
   */
  private static final int MAX_PKGS_PER_BATCH = 50;

  /**
   * Number of (permission, package, user) triples per batched permission flags request. The request
   * is the expensive direction here: an item costs {@code 16 + 2 * (permNameLen + pkgNameLen)}
   * bytes, so 1000 items with 60-character names are about 256 KB, while 2000 of them would already
   * sit at half of the 1 MB Binder limit.
   */
  private static final int MAX_FLAGS_PER_BATCH = 1000;

  /** Minimum interval between two "batched IPC is not available" log lines. */
  private static final long BATCH_FALLBACK_LOG_INTERVAL_MS = 60_000;

  private long mLastBatchFallbackLog;

  /**
   * AppOps fetched by {@link #prefetchAppOps(List)} for the package list being parsed, keyed by
   * {@link #appOpsBatchKey(int, String)}. It is a {@link ThreadLocal} on purpose: it only exists
   * for the duration of one {@link #updatePkgListInternal()} run, so a single-package refresh from
   * another thread keeps using the per-package IPC path and the data can never outlive the refresh.
   */
  private final ThreadLocal<Map<String, MyPkgOpsBatch>> mAppOpsPrefetched = new ThreadLocal<>();

  /** Same as {@link #mAppOpsPrefetched}, for {@link #prefetchPermFlags(List)}. */
  private final ThreadLocal<Map<String, Integer>> mPermFlagsPrefetched = new ThreadLocal<>();

  private static String appOpsBatchKey(int uid, String pkgName) {
    return uid + ":" + pkgName;
  }

  private static String permFlagsBatchKey(String permName, String pkgName, int userId) {
    return permName + ":" + pkgName + ":" + userId;
  }

  /**
   * Logs an exception thrown by a batched request. A {@code BadParcelableException} is reported at
   * error level and without the rate limit: it means the two sides disagree on the parcel layout,
   * which happens with a daemon that was started by an older build and is still alive. Rate
   * limiting that would hide a permanent, silent fall back to the per-item path.
   */
  private void logBatchException(String method, RuntimeException e) {
    if (e instanceof BadParcelableException) {
      MyLog.e(TAG, method, e);
      return;
    }
    logBatchFallback(method, e.toString());
  }

  /**
   * Logs that a batched request could not be used and the per-item calls are used instead. Rate
   * limited: the fallback is decided per chunk, and a whole refresh must not flood the log.
   */
  private void logBatchFallback(String method, String reason) {
    long now = SystemClock.elapsedRealtime();
    if (mLastBatchFallbackLog != 0
        && now - mLastBatchFallbackLog < BATCH_FALLBACK_LOG_INTERVAL_MS) {
      return;
    }
    mLastBatchFallbackLog = now;
    MyLog.i(TAG, method, "Batched IPC not available, using per-item calls: " + reason);
  }

  /**
   * Fetches the AppOps of the packages that are about to be parsed, one IPC per {@link
   * #MAX_PKGS_PER_BATCH} packages instead of two IPCs per package, and returns them keyed by uid
   * and package name.
   *
   * <p>The request is chunked because of the 1 MB Binder transaction limit (see {@link
   * #MAX_PKGS_PER_BATCH}). A chunk that fails, returns null (an old daemon which does not know the
   * batch transaction) or returns a differently sized list is simply left out of the map: the
   * packages it covers are then fetched one by one by {@link #createAppOpsList}, exactly as before,
   * so a broken or older daemon only loses the speed-up and never data.
   *
   * <p>Chunk results are matched to the request by index, never by package name: uid-mode results
   * carry a null package name, so a name lookup would misattribute them.
   */
  private Map<String, MyPkgOpsBatch> prefetchAppOps(List<PackageInfo> pkgInfoList) {
    Map<String, MyPkgOpsBatch> prefetched = new HashMap<>();

    if (!AppOpsParser.INS.hasAppOps()) {
      // Nothing to prefetch: createSetAppOps() is not called at all in this case.
      return prefetched;
    }

    List<PackageInfo> packages = new ArrayList<>(pkgInfoList.size());
    for (PackageInfo pkgInfo : pkgInfoList) {
      if (pkgInfo != null) {
        packages.add(pkgInfo);
      }
    }

    for (int start = 0; start < packages.size(); start += MAX_PKGS_PER_BATCH) {
      int end = Math.min(start + MAX_PKGS_PER_BATCH, packages.size());
      prefetchAppOpsChunk(packages.subList(start, end), prefetched);
    }

    return prefetched;
  }

  private void prefetchAppOpsChunk(List<PackageInfo> chunk, Map<String, MyPkgOpsBatch> prefetched) {
    int count = chunk.size();
    int[] uids = new int[count];
    String[] pkgNames = new String[count];
    for (int i = 0; i < count; i++) {
      PackageInfo pkgInfo = chunk.get(i);
      uids[i] = pkgInfo.applicationInfo.uid;
      pkgNames[i] = pkgInfo.packageName;
    }

    List<MyPkgOpsBatch> batches;
    try {
      // Null ops means all ops, the same request as getOpsForPackage(uid, pkgName, null).
      batches = AppOpsParser.INS.getOpsForPkgs(uids, pkgNames, null);
    } catch (RuntimeException e) {
      logBatchException("prefetchAppOps", e);
      return;
    }

    if (batches == null || batches.size() != count) {
      logBatchFallback("prefetchAppOps", "no batched result");
      return;
    }

    for (int i = 0; i < count; i++) {
      MyPkgOpsBatch batch = batches.get(i);
      if (batch == null) {
        continue;
      }
      // A null package-ops list means the daemon could not read this package, not that it has no
      // ops. Leave it out so that createAppOpsList() fetches it the old way.
      //
      // uidOps being null is NOT a failure: with a null pkgName the framework call answers the
      // uid-mode ops of a package, and on some ROMs it throws an NPE, which the daemon reports as
      // null while the package ops of the same package are perfectly good. The old per-package
      // path skips a null uid-ops list, so doing the same here is equivalent and keeps the batch
      // useful on those ROMs.
      if (batch.pkgOps == null) {
        continue;
      }
      prefetched.put(appOpsBatchKey(uids[i], pkgNames[i]), batch);
    }
  }

  /**
   * Same idea as {@link #prefetchAppOps(List)}, for the permission flags of system and framework
   * apps: those flags are only needed while walking requestedPermissions, so the (permission,
   * package, user) triples are collected up front and fetched in chunks of {@link
   * #MAX_FLAGS_PER_BATCH}. Ordinary user apps are skipped, exactly as {@link #createPerm} does.
   *
   * <p>A failed chunk (null result, old daemon, exception) or a negative flag (the daemon's marker
   * for a failed item) is not cached, so {@link #getPermissionFlags} falls back to the single-item
   * IPC for those triples. Behaviour is unchanged, only the number of round trips is.
   */
  private Map<String, Integer> prefetchPermFlags(List<PackageInfo> pkgInfoList) {
    Map<String, Integer> prefetched = new HashMap<>();

    if (!DaemonHandler.INS.isDaemonAlive()) {
      // Without a daemon getPermissionFlags() always yields -1, so do not even ask.
      return prefetched;
    }

    List<String> permNames = new ArrayList<>(MAX_FLAGS_PER_BATCH);
    List<String> pkgNames = new ArrayList<>(MAX_FLAGS_PER_BATCH);
    List<Integer> userIds = new ArrayList<>(MAX_FLAGS_PER_BATCH);

    for (PackageInfo pkgInfo : pkgInfoList) {
      if (pkgInfo == null || pkgInfo.requestedPermissions == null) {
        continue;
      }
      if (!isSystemApp(pkgInfo) && !isFrameworkApp(pkgInfo)) {
        continue;
      }

      int userId = UserUtils.getUserId(pkgInfo.applicationInfo.uid);
      for (String requestedPerm : pkgInfo.requestedPermissions) {
        // The same normalisation as getPermList() applies, so that the keys match.
        permNames.add(requestedPerm.replaceAll("\\s", ""));
        pkgNames.add(pkgInfo.packageName);
        userIds.add(userId);

        if (permNames.size() == MAX_FLAGS_PER_BATCH) {
          prefetchPermFlagsChunk(permNames, pkgNames, userIds, prefetched);
          permNames.clear();
          pkgNames.clear();
          userIds.clear();
        }
      }
    }

    if (!permNames.isEmpty()) {
      prefetchPermFlagsChunk(permNames, pkgNames, userIds, prefetched);
    }

    return prefetched;
  }

  private void prefetchPermFlagsChunk(
      List<String> permNames,
      List<String> pkgNames,
      List<Integer> userIds,
      Map<String, Integer> prefetched) {
    int count = permNames.size();
    String[] perms = permNames.toArray(new String[0]);
    String[] pkgs = pkgNames.toArray(new String[0]);
    int[] users = new int[count];
    for (int i = 0; i < count; i++) {
      users[i] = userIds.get(i);
    }

    int[] flags;
    try {
      flags = DaemonIface.INS.getPermFlagsBatch(perms, pkgs, users);
    } catch (RuntimeException e) {
      logBatchException("prefetchPermFlags", e);
      return;
    }

    if (flags == null || flags.length != count) {
      logBatchFallback("prefetchPermFlags", "no batched result");
      return;
    }

    for (int i = 0; i < count; i++) {
      if (flags[i] < 0) {
        // Failed item: leave it out so that getPermissionFlags() retries it with a single IPC.
        continue;
      }
      prefetched.put(permFlagsBatchKey(perms[i], pkgs[i], users[i]), flags[i]);
    }
  }

  private boolean isUpdating() {
    return mPkgUpdater.hasRunningOrPendingTasks();
  }

  /**
   * The system apps filter state the cached {@link #mPkgInfoList} was built with, so that flipping
   * the "Show system apps" setting (or the exclusion filter) rebuilds it instead of reusing a list
   * which no longer matches.
   */
  private Boolean mPkgInfoListExcludeSystem;

  private List<PackageInfo> buildPkgInfoList() {
    boolean excludeSystem = isSystemAppsExcluded();

    synchronized (mPkgInfoLock) {
      List<PackageInfo> cachedList = new ArrayList<>(mPkgInfoList);
      if (!cachedList.isEmpty() && Objects.equals(mPkgInfoListExcludeSystem, excludeSystem)) {
        return cachedList;
      }
    }

    setProgress(CREATE_LIST, true, false);

    List<PackageInfo> pkgInfoList = new ArrayList<>();
    pkgInfoList.addAll(
        ApiUtils.getInstalledPackages(PackageManager.GET_PERMISSIONS | PM_GET_SIGNATURES));

    // Drop what is not going to be listed before loading labels below: loadLabel is an IPC to
    // system_server, and there are usually several hundreds of system apps. Their permissions are
    // not parsed either, and the progress shown is proportional to the real work.
    pkgInfoList.removeIf(pkgInfo -> pkgInfo == null || pkgInfo.applicationInfo == null);
    if (excludeSystem) {
      pkgInfoList.removeIf(this::isSystemApp);
    }

    // Resolve one label per package before sorting: loadLabel() is an IPC, and the comparator
    // below would otherwise call it O(n log n) times. The same prefetched string is reused while
    // building the Package objects.
    Map<PackageInfo, String> labels = new IdentityHashMap<>(pkgInfoList.size());
    for (PackageInfo pkgInfo : pkgInfoList) {
      labels.put(pkgInfo, pkgInfo.applicationInfo.loadLabel(mPm).toString());
    }

    setProgress(SORT_LIST, true, false);
    pkgInfoList.sort(Comparator.comparing(pkgInfo -> labels.get(pkgInfo).toUpperCase()));

    // Publish the PackageInfo list and its labels as one generation, under one lock.
    synchronized (mPkgInfoLock) {
      mPkgInfoList.clear();
      mPkgInfoList.addAll(pkgInfoList);
      mPkgLabels.clear();
      mPkgLabels.putAll(labels);
      mPkgInfoListExcludeSystem = excludeSystem;
    }

    return pkgInfoList;
  }

  /** Returns the prefetched label of {@code pkgInfo}, loading it on demand if it is not cached. */
  private String getPkgLabel(PackageInfo pkgInfo) {
    synchronized (mPkgInfoLock) {
      String label = mPkgLabels.get(pkgInfo);
      if (label != null) {
        return label;
      }
    }
    return pkgInfo.applicationInfo.loadLabel(mPm).toString();
  }

  {
    UiRunner.post(
        () ->
            ProcessLifecycleOwner.get()
                .getLifecycle()
                .addObserver(
                    (LifecycleEventObserver)
                        (source, event) -> {
                          if (event == Lifecycle.Event.ON_STOP) {
                            clearPkgInfoList();
                            setRepeatUpdates(true);
                          }
                        }));
  }

  public void clearPkgInfoList() {
    synchronized (mPkgInfoLock) {
      mPkgInfoList.clear();
      mPkgLabels.clear();
      mPkgInfoListExcludeSystem = null;
    }
  }

  private boolean mAppCanReadFlags = true;
  private Integer SYSTEM_FIXED_FLAG, POLICY_FIXED_FLAG;

  private static final Object BUILD_DATA_LOCK = new Object();

  public void buildRequiredData() {
    synchronized (BUILD_DATA_LOCK) {
      if (!PermsDb.INS.refsBuilt()) {
        setProgress(REF_PERMS_LIST, true, false);
        if (!PermsDb.INS.buildRefs()) {
          // We've been interrupted
          return;
        }
      }

      setProgress(APP_OPS_LISTS, true, false);
      AppOpsParser.INS.buildAppOpsList();

      if (SYSTEM_FIXED_FLAG != null && POLICY_FIXED_FLAG != null) {
        return;
      }

      PermFixedFlags flags;

      if (DaemonHandler.INS.isDaemonAlive()) {
        flags = DaemonIface.INS.getPermFixedFlags();
        if (flags == null) {
          return;
        }
      } else if (mAppCanReadFlags) {
        try {
          flags = HiddenSdkIntConstants.getPermFixedFlags();
        } catch (RemoteException e) {
          mAppCanReadFlags = false;
          MyLog.e(TAG, "buildRequiredData", e.toString());
          return;
        }
      } else {
        return;
      }

      SYSTEM_FIXED_FLAG = flags.systemFixed;
      POLICY_FIXED_FLAG = flags.policyFixed;
    }
  }

  public List<Package> getPkgList() {
    synchronized (mPkgList) {
      return new ArrayList<>(mPkgList);
    }
  }

  public Package getPkg(int position) {
    synchronized (mPkgList) {
      if (position < 0 || position >= mPkgList.size()) {
        MyLog.e(TAG, "getPkg", "Bad position: " + position);
        return null;
      }
      return mPkgList.get(position);
    }
  }

  public int getPkgPosition(Package pkg) {
    synchronized (mPkgList) {
      int position = mPkgList.indexOf(pkg);
      if (position == -1) {
        MyLog.e(TAG, "getPkgPosition", "Bad Package provided");
        return -1;
      }
      return position;
    }
  }

  public void updatePackage(Package pkg, boolean filterPerms) {
    // An updated package needs no explicit icon cache invalidation: the cache key contains the
    // update time copied in isPkgUpdated() from PackageInfo.lastUpdateTime, so the upgrade itself
    // makes the old entry unreachable. The icon is reloaded on the next bind with the new key.
    PackageInfo packageInfo =
        PackageParser.getPkgInfo(pkg.getName(), PackageManager.GET_PERMISSIONS);

    if (packageInfo == null || !isPkgUpdated(packageInfo, pkg, filterPerms)) {
      removePackage(pkg);
      return;
    }

    mChangedPkg.postValue(pkg, true);

    if (MySettings.INS.isSearching()) {
      updateSearchLists(pkg, true);
    }
  }

  public void removePackage(Package pkg) {
    // An uninstalled package must not leave a cached icon behind, for any user profile. This is
    // also safe while a rebuild is pending: the cache is only a cache.
    PkgIconCache.remove(pkg.getName());

    if (isUpdating()) {
      updatePkgList();
      return;
    }
    boolean res;
    synchronized (mPkgList) {
      res = mPkgList.remove(pkg);
    }
    if (res) {
      if (MySettings.INS.isSearching()) {
        removeSearchPackage(pkg);
      } else {
        postLivePkgList(mPkgList, true);
      }
      pkg.setPkgRemoved(true);
    } else {
      MyLog.e(TAG, "removePackage", "Bad Package provided");
    }
  }

  public LiveData<List<Package>> getPkgListLive() {
    return mPkgListLive;
  }

  public LiveData<Package> getChangedPkg() {
    return mChangedPkg;
  }

  public LiveData<Integer> getProgMax() {
    return mProgMax;
  }

  public LiveData<Integer> getProgNow() {
    return mProgNow;
  }

  public LiveData<Integer> getListCompleted() {
    return mListCompleted;
  }

  private void updateAndPostLivePkgList(List<Package> pkgList, boolean isFinal) {
    // Take a single snapshot. Observers must get a list which the building thread never modifies
    // again, and mPkgList is filled from that very snapshot instead of being copied a second time.
    List<Package> snapshot;
    synchronized (mPkgList) {
      if (pkgList != null) {
        mPkgList.clear();
        mPkgList.addAll(pkgList);
      }
      snapshot = new ArrayList<>(mPkgList);
    }

    if (!MySettings.INS.isSearching()) {
      mPkgListLive.postValue(snapshot, isFinal);
      if (isFinal) {
        sendListCompleted(PostListStatus.FINAL, snapshot.size());
      }
    } else {
      handleSearchQuery(isFinal ? PostListStatus.FINAL : PostListStatus.NOT_FINAL);
    }
  }

  private void postLivePkgList(List<Package> pkgList, boolean mustSend) {
    mPkgListLive.postValue(new ArrayList<>(pkgList), mustSend);
  }

  private boolean mDoRepeatUpdates = true;

  public void setRepeatUpdates(boolean doRepeatUpdates) {
    mDoRepeatUpdates = doRepeatUpdates;
  }

  private final RateLimitedTaskTyped<Integer> mProgPoster =
      new RateLimitedTaskTyped<>(100, TimeUnit.MILLISECONDS, mProgNow::postValue);

  private void setProgress(int value, boolean isMax, boolean isFinal) {
    if (isMax) {
      mProgMax.postValue(value, isFinal);
    } else {
      if (isFinal) {
        mProgNow.postValue(value, true);
      } else {
        mProgPoster.run(value);
      }
    }
  }

  private void sendListCompleted(int isFinal, int pkgCount) {
    if (isFinal == PostListStatus.FINAL || (isFinal == PostListStatus.UNDEFINED && !isUpdating())) {
      mListCompleted.postValue(pkgCount, true);
    }
  }

  private static final int CREATE_LIST = -1;
  private static final int SORT_LIST = -2;
  private static final int REF_PERMS_LIST = -3;
  private static final int APP_OPS_LISTS = -4;

  public int getProgMsg(int progMax) {
    return switch (progMax) {
      case CREATE_LIST -> R.string.creating_packages_list;
      case SORT_LIST -> R.string.sorting_packages_list;
      case REF_PERMS_LIST -> R.string.reading_reference_perms;
      case APP_OPS_LISTS -> R.string.creating_app_ops_lists;
      default -> 0;
    };
  }

  boolean isPkgUpdated(PackageInfo pkgInfo, Package pkg, boolean filterPerms) {
    if (isFilteredOutPkgName(pkgInfo.packageName)) {
      return false;
    }

    boolean isSystemApp = isSystemApp(pkgInfo);
    if (isFilteredOutSystemPkg(isSystemApp)) {
      return false;
    }

    boolean isFrameworkApp = isFrameworkApp(pkgInfo);
    if (isFilteredOutFrameworkPkg(isFrameworkApp)) {
      return false;
    }
    if (isFilteredOutUserPkg(isFrameworkApp, isSystemApp)) {
      return false;
    }

    ApplicationInfo appInfo = pkgInfo.applicationInfo;
    boolean isEnabled = appInfo.enabled;
    if (isFilteredOutDisabledPkg(!isEnabled)) {
      return false;
    }

    if (isFilteredOutNoIconPkg(appInfo.icon == 0)) {
      return false;
    }

    List<Permission> permList = getPermList(pkgInfo, pkg, filterPerms);

    if (isFilteredOutNoPermPkg(pkg)) {
      return false;
    }

    Boolean pkgIsReferenced = true;

    // A single pass instead of two parallel streams: "not referenced" wins over "unknown", and
    // isChangeable() is only consulted for permissions whose reference state is null, exactly as
    // before.
    boolean hasNotReferencedPerm = false;
    boolean hasChangeableNullRefPerm = false;
    for (Permission perm : permList) {
      Boolean isReferenced = perm.isReferenced();
      if (Boolean.FALSE.equals(isReferenced)) {
        hasNotReferencedPerm = true;
        break;
      }
      if (isReferenced == null && perm.isChangeable()) {
        hasChangeableNullRefPerm = true;
      }
    }

    if (hasNotReferencedPerm) {
      pkgIsReferenced = false;
    } else if (hasChangeableNullRefPerm) {
      pkgIsReferenced = null;
    }

    pkg.updatePackage(
        getPkgLabel(pkgInfo),
        pkgInfo.packageName,
        permList,
        isFrameworkApp,
        isSystemApp,
        isEnabled,
        appInfo.uid,
        pkgInfo.lastUpdateTime,
        pkgIsReferenced);

    return true;
  }

  private boolean isSystemApp(PackageInfo packageInfo) {
    return (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
  }

  private List<Integer> mSystemSignatures;

  public List<Integer> getSystemSignatures() {
    if (mSystemSignatures == null) {
      PackageInfo pkgInfo = getPkgInfo("android", null);
      mSystemSignatures =
          Arrays.asList(Util.getPackageSignatures(pkgInfo)).parallelStream()
              .mapToInt(Signature::hashCode)
              .boxed()
              .collect(Collectors.toList());
    }
    return mSystemSignatures;
  }

  private boolean isFrameworkApp(PackageInfo packageInfo) {
    return Util.isFwkPkg(packageInfo, getSystemSignatures());
  }

  private boolean isFilteredOutPkgName(String pkgName) {
    return ExcFiltersData.INS.isPkgExcluded(pkgName);
  }

  /**
   * Whether system apps must not be listed at all. Two switches can cause that: the exclusion
   * filter (which belongs to the filters group and is off until the filters master switch is on),
   * and the "Show system apps" setting in General settings. Hiding wins over showing: an explicit
   * exclusion filter is not silently overridden by the switch.
   */
  private boolean isSystemAppsExcluded() {
    return MySettings.INS.excludeSystemApps() || !MySettings.INS.showSystemApps();
  }

  private boolean isFilteredOutSystemPkg(boolean isSystemPkg) {
    return isSystemPkg && isSystemAppsExcluded();
  }

  private boolean isFilteredOutFrameworkPkg(boolean isFrameworkPkg) {
    return MySettings.INS.excludeFrameworkApps() && isFrameworkPkg;
  }

  private boolean isFilteredOutUserPkg(boolean isFrameworkPkg, boolean isSystemPkg) {
    return MySettings.INS.excludeUserApps() && !isFrameworkPkg && !isSystemPkg;
  }

  private boolean isFilteredOutDisabledPkg(boolean isDisabledPkg) {
    return MySettings.INS.excludeDisabledApps() && isDisabledPkg;
  }

  private boolean isFilteredOutNoIconPkg(boolean isNoIconPkg) {
    return MySettings.INS.excludeNoIconApps() && isNoIconPkg;
  }

  private boolean isFilteredOutNoPermPkg(Package pkg) {
    return MySettings.INS.excludeNoPermsApps()
        && pkg.getTotalPermCount() == 0
        && pkg.getTotalAppOpsCount() == 0;
  }

  private List<Permission> getPermList(PackageInfo pkgInfo, Package pkg, boolean filterPerms) {
    String[] requestedPerms = pkgInfo.requestedPermissions;
    List<Permission> permList = new ArrayList<>();

    int permCount = 0;
    int[] appOpsCount1 = new int[] {0, 0};
    List<Integer> processedAppOps = new ArrayList<>();

    if (requestedPerms != null) {
      for (int count = 0; count < requestedPerms.length; count++) {
        String permName = requestedPerms[count].replaceAll("\\s", "");
        if (createPerm(pkgInfo, permName, count, filterPerms, permList)) {
          permCount++;
        }

        if (AppOpsParser.INS.hasAppOps()) {
          int[] appOpsCount =
              createPermsAppOpsNotSet(pkgInfo, permName, permList, processedAppOps, filterPerms);
          appOpsCount1[0] += appOpsCount[0];
          appOpsCount1[1] += appOpsCount[1];
        }
      }
    }

    int[] appOpsCount2 = new int[] {0, 0};
    int[] appOpsCount3 = new int[] {0, 0};
    if (AppOpsParser.INS.hasAppOps()) {
      appOpsCount2 = createSetAppOps(pkgInfo, permList, processedAppOps, filterPerms);

      if (MySettings.INS.showExtraAppOps()
          && (!MySettings.INS.excludeNoPermsApps()
              || requestedPerms != null
              || appOpsCount2[0] != 0)) {

        List<Integer> ops1 = new ArrayList<>();
        for (String opName : ExcFiltersData.INS.getExtraAppOps()) {
          Integer op = AppOpsParser.INS.getAppOpCode(opName);
          if (op != null && !processedAppOps.contains(op)) {
            ops1.add(op);
          }
        }

        if (!ops1.isEmpty()) {
          int[] ops2 = new int[ops1.size()];
          for (int i = 0; i < ops1.size(); i++) {
            ops2[i] = ops1.get(i);
          }
          appOpsCount3 = createExtraAppOps(pkgInfo, permList, ops2, filterPerms);
        }
      }
    }

    pkg.setTotalPermCount(requestedPerms == null ? 0 : requestedPerms.length);
    pkg.setPermCount(permCount);
    pkg.setTotalAppOpsCount(appOpsCount1[0] + appOpsCount2[0] + appOpsCount3[0]);
    pkg.setAppOpsCount(appOpsCount1[1] + appOpsCount2[1] + appOpsCount3[1]);

    return permList;
  }

  /**
   * Whether a permission is shown in the package view, i.e. it is not excluded by the filters. Also
   * used by {@link PermViewParser}, so that both views show the same set of permissions.
   */
  public boolean isIncludedInView(Permission perm) {
    return isNotFilteredOut(perm);
  }

  private boolean isNotFilteredOut(Permission perm) {
    if (perm.isExtraAppOp()) {
      return true;
    }

    if (ExcFiltersData.INS.isPermExcluded(perm.getName())) {
      return false;
    }
    if (MySettings.INS.excludeNotChangeablePerms() && !perm.isChangeable()) {
      return false;
    }
    if (MySettings.INS.excludeNotGrantedPerms() && !perm.isGranted()) {
      return false;
    }

    if (perm.isAppOp()) {
      return !MySettings.INS.excludeNotSetAppOps() || perm.isAppOpModeSet();
    }

    if (MySettings.INS.excludePrivilegedPerms() && perm.isPrivileged()) {
      return false;
    }
    if (MySettings.INS.excludeSignaturePerms()
        && perm.getProtectionLevel().equals(Permission.PROTECTION_SIGNATURE)) {
      return false;
    }
    if (MySettings.INS.excludeDangerousPerms()
        && perm.getProtectionLevel().equals(Permission.PROTECTION_DANGEROUS)) {
      return false;
    }
    if (MySettings.INS.excludeNormalPerms()
        && perm.getProtectionLevel().equals(Permission.PROTECTION_NORMAL)) {
      return false;
    }
    return !MySettings.INS.excludeInvalidPerms() || perm.hasProviderPkg();
  }

  private boolean createPerm(
      PackageInfo pkgInfo,
      String permName,
      int count,
      boolean filterPerms,
      List<Permission> permList) {
    int[] requestedPermissionsFlags = pkgInfo.requestedPermissionsFlags;

    boolean isGranted =
        (requestedPermissionsFlags[count] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;

    ManifestPermFlags flags = getManifestPermFlags(permName);

    PermGroupInfo permGroupInfo = PermGroupsMapping.INS.get(permName, false);

    boolean isSystemApp = isSystemApp(pkgInfo);
    boolean isFrameworkApp = isFrameworkApp(pkgInfo);

    boolean isSystemFixed = false, isPolicyFixed = false;

    if (isSystemApp || isFrameworkApp) {
      int permFlags = getPermissionFlags(permName, pkgInfo);
      if (permFlags >= 0 && SYSTEM_FIXED_FLAG != null) {
        isSystemFixed = (permFlags & SYSTEM_FIXED_FLAG) != 0;
      }
      if (permFlags >= 0 && POLICY_FIXED_FLAG != null) {
        isPolicyFixed = (permFlags & POLICY_FIXED_FLAG) != 0;
      }
    }

    Permission perm =
        new Permission(
            permGroupInfo.groupId,
            permGroupInfo.icon,
            pkgInfo.packageName,
            permName,
            isGranted,
            isSystemApp,
            isFrameworkApp,
            flags.protection,
            flags.isPrivileged,
            flags.isDevelopment,
            flags.isManifestPermAppOp,
            isSystemFixed,
            isPolicyFixed,
            flags.providerPkg);

    if (!filterPerms || isNotFilteredOut(perm)) {
      String refState = PermsDb.INS.getRef(pkgInfo.packageName, permName, false, false);

      perm.setReference(Permission.isReferenced(refState, perm.isGranted()), refState);
      permList.add(perm);

      return true;
    }

    return false;
  }

  private int getPermissionFlags(String perm, PackageInfo pkgInfo) {
    int userId = UserUtils.getUserId(pkgInfo.applicationInfo.uid);

    // Filled by prefetchPermFlags() during a full list refresh; null on the single-package path.
    Map<String, Integer> prefetched = mPermFlagsPrefetched.get();
    if (prefetched != null) {
      Integer prefetchedFlags =
          prefetched.get(permFlagsBatchKey(perm, pkgInfo.packageName, userId));
      if (prefetchedFlags != null) {
        return prefetchedFlags;
      }
    }

    if (!DaemonHandler.INS.isDaemonAlive()) {
      return -1;
    }

    Integer flags = DaemonIface.INS.getPermFlags(perm, pkgInfo.packageName, userId);

    if (flags != null) {
      return flags;
    }

    return -1;
  }

  private int[] createPermsAppOpsNotSet(
      PackageInfo pkgInfo,
      String permName,
      List<Permission> permList,
      List<Integer> processedAppOps,
      boolean filterPerms) {

    Integer op = AppOpsParser.INS.getPermToOpCode(permName);
    if (op == null) {
      return new int[] {0, 0};
    }

    List<MyPackageOps> pkgOpsList =
        AppOpsParser.INS.getOpsForPackage(pkgInfo.applicationInfo.uid, pkgInfo.packageName, op);

    if (pkgOpsList == null || !pkgOpsList.isEmpty()) {
      return new int[] {0, 0};
    }

    return createAppOp(pkgInfo, op, null, permList, processedAppOps, false, false, -1, filterPerms);
  }

  private int[] createSetAppOps(
      PackageInfo packageInfo,
      List<Permission> permissionsList,
      List<Integer> processedAppOps,
      boolean filterPerms) {
    return createAppOpsList(packageInfo, permissionsList, processedAppOps, null, filterPerms);
  }

  private int[] createExtraAppOps(
      PackageInfo packageInfo, List<Permission> permissionsList, int[] ops, boolean filterPerms) {
    return createAppOpsList(packageInfo, permissionsList, null, ops, filterPerms);
  }

  private int[] createAppOpsList(
      PackageInfo packageInfo,
      List<Permission> permissionsList,
      List<Integer> processedAppOps,
      int[] ops,
      boolean filterPerms) {

    List<MyPackageOps> pkgOpsList = new ArrayList<>();
    int totalAppOpsCount = 0;
    int appOpsCount = 0;
    boolean isExtraAppOp = ops != null;
    int uid = packageInfo.applicationInfo.uid;

    if (isExtraAppOp) {
      for (int op : ops) {
        List<MyPackageOps> list =
            AppOpsParser.INS.getOpsForPackage(uid, packageInfo.packageName, op);
        if (list == null) {
          continue;
        }
        if (list.isEmpty()) {
          int[] count =
              createAppOp(
                  packageInfo,
                  op,
                  null,
                  permissionsList,
                  processedAppOps,
                  true,
                  false,
                  -1,
                  filterPerms);
          totalAppOpsCount += count[0];
          appOpsCount += count[1];
        } else {
          pkgOpsList.addAll(list);
        }
      }
    } else {
      Map<String, MyPkgOpsBatch> prefetched = mAppOpsPrefetched.get();
      MyPkgOpsBatch batch =
          prefetched == null ? null : prefetched.get(appOpsBatchKey(uid, packageInfo.packageName));

      if (batch != null) {
        pkgOpsList.addAll(batch.pkgOps);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && batch.uidOps != null) {
          pkgOpsList.addAll(batch.uidOps);
        }
      } else {
        List<MyPackageOps> list =
            AppOpsParser.INS.getOpsForPackage(uid, packageInfo.packageName, null);
        if (list != null) {
          pkgOpsList.addAll(list);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          list = AppOpsParser.INS.getUidOps(uid);
          if (list != null) {
            pkgOpsList.addAll(list);
          }
        }
      }
    }

    int[] counts =
        createAppOpsListFromOps(
            packageInfo, pkgOpsList, permissionsList, processedAppOps, isExtraAppOp, filterPerms);

    return new int[] {totalAppOpsCount + counts[0], appOpsCount + counts[1]};
  }

  /**
   * Builds the {@link Permission} objects of a list of ops and returns {@code {total, shown}}
   * counts. Split out of {@link #createAppOpsList} so that both the batched and the per-item
   * fetches share exactly the same assembling logic.
   */
  private int[] createAppOpsListFromOps(
      PackageInfo packageInfo,
      List<MyPackageOps> pkgOpsList,
      List<Permission> permissionsList,
      List<Integer> processedAppOps,
      boolean isExtraAppOp,
      boolean filterPerms) {
    int totalAppOpsCount = 0;
    int appOpsCount = 0;

    for (MyPackageOps myPackageOps : pkgOpsList) {
      boolean isPerUid = myPackageOps.pkgName == null;
      for (MyPackageOps.MyOpEntry myOpEntry : myPackageOps.opEntryList) {
        int op = myOpEntry.op;
        long lastAccessTime = myOpEntry.lastAccessTime;
        int[] count =
            createAppOp(
                packageInfo,
                op,
                myOpEntry.opMode,
                permissionsList,
                processedAppOps,
                isExtraAppOp,
                isPerUid,
                lastAccessTime,
                filterPerms);
        totalAppOpsCount += count[0];
        appOpsCount += count[1];
      }
    }

    return new int[] {totalAppOpsCount, appOpsCount};
  }

  private int[] createAppOp(
      PackageInfo pkgInfo,
      int op,
      Integer opMode,
      List<Permission> permList,
      List<Integer> processedAppOps,
      boolean isExtraAppOp,
      boolean isPerUid,
      long accessTime,
      boolean filterPerms) {
    String opName = AppOpsParser.INS.getAppOpName(op);

    if (opName == null) {
      return new int[] {1, 0};
    }

    if (Constants.UNKNOWN_OP.equals(opName)) {
      return new int[] {0, 0};
    }

    String dependsOn = AppOpsParser.INS.getDependsOn(op);

    boolean opModeSet = true, validMode = true;
    if (opMode == null) {
      opModeSet = false;
    } else if (!AppOpsParser.INS.isValidAppOpMode(opMode)) {
      mOpModesConsistent = validMode = false;
    }

    if (!opModeSet || !validMode) {
      opMode = AppOpsParser.INS.getOpDefMode(op);
      if (opMode == null || !AppOpsParser.INS.isValidAppOpMode(opMode)) {
        opMode = AppOpsManager.MODE_DEFAULT;
        mOpModesConsistent = validMode = false;
      }
    }

    PermGroupInfo permGroupInfo = PermGroupsMapping.INS.get(opName, true);

    Permission perm =
        new Permission(
            permGroupInfo.groupId,
            permGroupInfo.icon,
            pkgInfo.packageName,
            opName,
            Permission.isAppOpGranted(opMode),
            isSystemApp(pkgInfo),
            isFrameworkApp(pkgInfo),
            isPerUid,
            opModeSet,
            !validMode,
            opMode,
            accessTime,
            dependsOn,
            isExtraAppOp);

    if (!isExtraAppOp) {
      processedAppOps.add(op);
    }

    int appOpsCount = 0;

    if (!filterPerms || isNotFilteredOut(perm)) {
      appOpsCount = 1;
    } else if (!isExtraAppOp && ExcFiltersData.INS.isExtraAppOp(perm.getName())) {
      perm.setExtraAppOp();
      if (isNotFilteredOut(perm)) {
        appOpsCount = 1;
      }
    }

    if (appOpsCount == 1) {
      String refState = PermsDb.INS.getRef(pkgInfo.packageName, opName, true, isPerUid);

      perm.setReference(Permission.isReferenced(refState, opMode), refState);
      permList.add(perm);
    }

    return new int[] {1, appOpsCount};
  }

  static PackageInfo getPkgInfo(String pkgName, Integer pmFlags) {
    int flags = pmFlags == null ? Util.PM_GET_SIGNATURES : Util.PM_GET_SIGNATURES | pmFlags;
    try {
      return ApiUtils.getPkgInfo(pkgName, flags);
    } catch (NameNotFoundException e) {
      MyLog.e(TAG, "getPkgInfo", e.toString());
      return null;
    }
  }

  // Legacy "signatureOrSystem" protection level. PermissionInfo#PROTECTION_SIGNATURE_OR_SYSTEM is
  // deprecated since API 23: from then on the framework encodes it as PROTECTION_SIGNATURE together
  // with PROTECTION_FLAG_PRIVILEGED, which the branch below already handles. Permissions declared
  // against older platforms can still report the old value.
  private static final int LEGACY_PROTECTION_SIGNATURE_OR_SYSTEM = 3;

  // PermissionInfo#getProtection() and getProtectionFlags() need API 28, minSdk is 24, so on older
  // releases the deprecated protectionLevel field is read instead of them. The two masks below
  // partition the field, so the result is exactly the same as reading it and splitting it by hand.
  @SuppressWarnings("deprecation")
  public static int getProtection(PermissionInfo permInfo) {
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      return permInfo.getProtection();
    }
    return permInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
  }

  // Additional protection flags, e.g. PermissionInfo#PROTECTION_FLAG_PRIVILEGED, base level masked
  // out.
  @SuppressWarnings("deprecation")
  public static int getProtectionFlags(PermissionInfo permInfo) {
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      return permInfo.getProtectionFlags();
    }
    return permInfo.protectionLevel & ~PermissionInfo.PROTECTION_MASK_BASE;
  }

  // The raw protection level: the base level together with the additional flags.
  public static int getProtectionLevel(PermissionInfo permInfo) {
    return getProtection(permInfo) | getProtectionFlags(permInfo);
  }

  private final Map<String, ManifestPermFlags> mManifestFlags = new HashMap<>();

  private ManifestPermFlags getManifestPermFlags(String permName) {
    ManifestPermFlags flags = mManifestFlags.get(permName);

    if (flags == null) {
      try {
        flags = getManifestPermFlags(PackageParser.INS.mPm.getPermissionInfo(permName, 0));
      } catch (NameNotFoundException ignored) {
        flags = new ManifestPermFlags();
      }
      mManifestFlags.put(permName, flags);
    }

    return flags;
  }

  public static ManifestPermFlags getManifestPermFlags(PermissionInfo permInfo) {
    ManifestPermFlags flags = new ManifestPermFlags();

    int protectionLevel = getProtection(permInfo);
    int protectionFlags = getProtectionFlags(permInfo);

    if (protectionLevel == PermissionInfo.PROTECTION_NORMAL) {
      flags.protection = Permission.PROTECTION_NORMAL;
    } else if (protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
      flags.protection = Permission.PROTECTION_DANGEROUS;
    } else if (protectionLevel == PermissionInfo.PROTECTION_SIGNATURE) {
      flags.protection = Permission.PROTECTION_SIGNATURE;
    } else if (protectionLevel == LEGACY_PROTECTION_SIGNATURE_OR_SYSTEM) {
      flags.protection = Permission.PROTECTION_SIGNATURE;
    } else if (VERSION.SDK_INT >= VERSION_CODES.S && protectionLevel == PROTECTION_INTERNAL) {
      flags.protection = Permission.PROTECTION_INTERNAL;
    } else {
      MyLog.e(TAG, "createPerm", "Protection level for " + permInfo.name + ": " + protectionLevel);
    }

    flags.isPrivileged = (protectionFlags & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0;
    flags.isDevelopment =
        protectionLevel == PermissionInfo.PROTECTION_SIGNATURE
            && (protectionFlags & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0;
    flags.isManifestPermAppOp = (protectionFlags & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;

    flags.providerPkg = permInfo.packageName;

    return flags;
  }

  public static class ManifestPermFlags {
    public String protection = Permission.PROTECTION_UNKNOWN;
    public boolean isPrivileged = false;
    public boolean isDevelopment = false;
    public boolean isManifestPermAppOp = false;
    public String providerPkg = null;
  }

  private @interface PostListStatus {
    int UNDEFINED = 0;
    int FINAL = 1;
    int NOT_FINAL = 2;
  }

  private final SingleParamTask<Integer> mSearchQueryExecutor =
      new SingleParamTask<>(this::doSearchInBg, TAG + "-SearchExecutor");

  public void handleSearchQuery() {
    handleSearchQuery(PostListStatus.UNDEFINED);
  }

  private void handleSearchQuery(int isFinal) {
    if (isFinal == PostListStatus.NOT_FINAL && mSearchQueryExecutor.hasRunningOrPendingTasks()) {
      return;
    }

    if (MySettings.INS.isSearching()) {
      mSearchQueryExecutor.cancelAndSubmit(isFinal, true);
    } else {
      mSearchQueryExecutor.cancel(true);
      clearSearchLists();

      postLivePkgList(mPkgList, isFinal != PostListStatus.NOT_FINAL);

      sendListCompleted(isFinal, -1);
    }
  }

  private final Object SEARCH_BG_LOCK = new Object();

  private void doSearchInBg(int isFinal) {
    synchronized (SEARCH_BG_LOCK) {
      List<Package> origPkgList = new ArrayList<>(mPkgList);
      mSearchPkgList.clear();

      boolean sendProgress = false;
      int size = origPkgList.size();

      for (int i = 0; i < size; i++) {
        if (!isUpdating() && Thread.interrupted()) {
          return;
        }

        Package pkg = origPkgList.get(i);

        if (pkg == null) {
          continue;
        }

        if (MySettings.INS.isDeepSearching()) {
          if (!sendProgress) {
            if (!isUpdating()) {
              sendProgress = true;
              setProgress(origPkgList.size(), true, true);
            }
          } else if (isUpdating()) {
            sendProgress = false;
          }

          if (sendProgress) {
            setProgress(i, false, false);
          }
        }

        updateSearchLists(pkg, false);

        if (isFinal == PostListStatus.UNDEFINED && mDoRepeatUpdates && !mSearchPkgList.isEmpty()) {
          postLivePkgList(mSearchPkgList, false);
        }
      }

      setProgress(size, false, true);

      postLivePkgList(mSearchPkgList, isFinal != PostListStatus.NOT_FINAL);
      sendListCompleted(isFinal, mSearchPkgList.size());
    }
  }

  private final List<Package> mSearchPkgList = Collections.synchronizedList(new ArrayList<>());

  private void updateSearchLists(Package pkg, boolean removeOnly) {
    String queryText = MySettings.INS.getQueryText();
    if (queryText == null) {
      return;
    }
    if (!MySettings.INS.isDeepSearching()) {
      if (pkg.contains(queryText)) {
        if (!removeOnly) {
          mSearchPkgList.add(pkg);
        }
      } else if (removeOnly) {
        removeSearchPackage(pkg);
      }
      return;
    }

    List<Permission> permList = new ArrayList<>();
    int permCount = 0, appOpsCount = 0;
    for (Permission perm : pkg.getFullPermsList()) {
      if (perm.contains(queryText, true)) {
        permList.add(perm);
        if (perm.isAppOp()) {
          appOpsCount++;
        } else {
          permCount++;
        }
      }
    }

    pkg.setSearchPermList(permList);
    pkg.setSearchPermCount(permCount);
    pkg.setSearchAppOpsCount(appOpsCount);

    if (!permList.isEmpty()) {
      if (!removeOnly) {
        mSearchPkgList.add(pkg);
      }
    } else if (removeOnly) {
      removeSearchPackage(pkg);
    }
  }

  private void removeSearchPackage(Package pkg) {
    if (mSearchPkgList.remove(pkg)) {
      pkg.setPkgRemoved(true);
      postLivePkgList(mSearchPkgList, true);
    }
  }

  private void clearSearchLists() {
    synchronized (SEARCH_BG_LOCK) {
      mSearchPkgList.clear();
      new ArrayList<>(mPkgList).parallelStream().forEach(pkg -> pkg.setSearchPermList(null));
    }
  }
}
