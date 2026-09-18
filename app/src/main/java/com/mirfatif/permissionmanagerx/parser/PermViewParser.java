package com.mirfatif.permissionmanagerx.parser;

import android.text.TextUtils;
import com.mirfatif.permissionmanagerx.prefs.ExcFiltersData;
import com.mirfatif.permissionmanagerx.prefs.MySettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Permission View: aggregates every permission (manifest permission as well as AppOp) currently
 * shown in the apps list, together with the list of apps using it.
 *
 * <p>The data is derived from {@link PackageParser}'s package list, so all the app / permission
 * filters configured by the user (see {@link ExcFiltersData} and {@link MySettings}) apply here
 * too.
 */
public enum PermViewParser {
  INS;

  /**
   * Guards {@link #mPermList} and every {@link PermSummary} in it. A read lock is enough to read a
   * fully built list; the write lock is held while (re)building it.
   */
  private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();

  private final List<PermSummary> mPermList = new ArrayList<>();

  private volatile boolean mBuilt = false;

  private int mAppOpsCount = 0;

  public boolean isBuilt() {
    return mBuilt;
  }

  public int getAppOpsCount() {
    return mAppOpsCount;
  }

  public List<PermSummary> getPermList() {
    mLock.readLock().lock();
    try {
      return new ArrayList<>(mPermList);
    } finally {
      mLock.readLock().unlock();
    }
  }

  /**
   * Aggregates the current package list. Blocking; must not be called on the main thread. Pending
   * package list updates must be finished by the caller (e.g. by using a {@link
   * com.mirfatif.permissionmanagerx.util.bg.LiveWaitTask}) before calling this.
   */
  public void build() {
    mLock.writeLock().lock();
    try {
      mBuilt = false;
      mPermList.clear();

      List<Package> pkgList = PackageParser.INS.getPkgList();

      List<PermSummary> summaries = new ArrayList<>();
      Map<String, PermSummary> summariesMap = new HashMap<>();
      // Permission key -> package names already counted. An app using the same AppOp in both UID
      // mode and package mode is listed twice, but must be counted once in the apps count.
      Map<String, Set<String>> countedPkgsMap = new HashMap<>();

      int appOpsCount = 0;

      for (Package pkg : pkgList) {
        if (pkg == null) {
          continue;
        }

        List<Permission> permList = pkg.getFullPermsList();
        if (permList == null) {
          continue;
        }

        for (Permission perm : permList) {
          if (perm == null) {
            continue;
          }

          // Keep this list consistent with the permission list of the package view.
          if (!PackageParser.INS.isIncludedInView(perm)) {
            continue;
          }

          boolean isAppOp = perm.isAppOp();
          String key = createKey(perm.getName(), isAppOp, perm.isPerUid());

          PermSummary summary = summariesMap.get(key);
          if (summary == null) {
            summary =
                new PermSummary(
                    perm.getName(),
                    isAppOp,
                    perm.isPerUid(),
                    perm.getProtectionLevel(),
                    perm.isPrivileged(),
                    perm.isDevelopment(),
                    perm.isManifestPermAppOp(),
                    perm.hasDependsOnPerm());
            summariesMap.put(key, summary);
            countedPkgsMap.put(key, new HashSet<>());
            summaries.add(summary);
            if (isAppOp) {
              appOpsCount++;
            }
          }

          summary.totalCount++;

          boolean granted = perm.isGranted();
          if (granted) {
            summary.grantedCount++;
          }

          if (countedPkgsMap.get(key).add(pkg.getName())) {
            summary.appCount++;
          }

          summary.addApp(new PermApp(pkg, perm, granted, isAppOp));
        }
      }

      for (PermSummary summary : summaries) {
        summary.sortApps();
      }

      summaries.sort(
          (s1, s2) -> {
            int res = Boolean.compare(s1.isAppOp, s2.isAppOp);
            return res != 0 ? res : s1.permName.compareToIgnoreCase(s2.permName);
          });

      mPermList.addAll(summaries);

      mAppOpsCount = appOpsCount;
      mBuilt = true;
    } finally {
      mLock.writeLock().unlock();
    }
  }

  /** Searches the permission list the same way the apps list is searched. */
  public List<PermSummary> getSearchList(String queryText) {
    mLock.readLock().lock();
    try {
      List<PermSummary> list = new ArrayList<>();
      if (TextUtils.isEmpty(queryText)) {
        list.addAll(mPermList);
        return list;
      }

      for (PermSummary summary : mPermList) {
        if (summary.contains(queryText)) {
          list.add(summary);
        }
      }
      return list;
    } finally {
      mLock.readLock().unlock();
    }
  }

  private static String createKey(String permName, boolean isAppOp, boolean isPerUid) {
    return permName + "_" + isAppOp + "_" + isPerUid;
  }

  /** A permission row of the Permission View, aggregating all the apps using it. */
  public static class PermSummary {

    public final String permName;
    public final boolean isAppOp;
    public final boolean isPerUid;

    private final String mProtectionLevel;
    private final boolean mPrivileged;
    private final boolean mDevelopment;
    private final boolean mManifestPermAppOp;
    private final boolean mDependsOnPerm;

    private final int mGroupId;
    private final int mIconResId;

    public int appCount;
    public int grantedCount;
    public int totalCount;

    private final List<PermApp> mApps = new ArrayList<>();

    private PermSummary(
        String permName,
        boolean isAppOp,
        boolean isPerUid,
        String protectionLevel,
        boolean isPrivileged,
        boolean isDevelopment,
        boolean isManifestPermAppOp,
        boolean dependsOnPerm) {
      this.permName = permName;
      this.isAppOp = isAppOp;
      this.isPerUid = isPerUid;

      mProtectionLevel = protectionLevel;
      mPrivileged = isPrivileged;
      mDevelopment = isDevelopment;
      mManifestPermAppOp = isManifestPermAppOp;
      mDependsOnPerm = dependsOnPerm;

      mGroupId = PermGroupsMapping.INS.getGroupId(permName, isAppOp);
      mIconResId = PermGroupsMapping.INS.get(permName, isAppOp).icon;
    }

    public int getGroupId() {
      return mGroupId;
    }

    public int getIconResId() {
      return mIconResId;
    }

    /** The simple, readable label of this permission, e.g. {@code 读取联系人}. */
    public String getLabel() {
      return PermNameMapper.INS.getLocalizedName(permName);
    }

    public String getProtectionLevel() {
      return mProtectionLevel;
    }

    public String getLocalizedProtLevelString() {
      return Permission.getLocalizedProtLevelString(
          isAppOp,
          mProtectionLevel,
          mPrivileged,
          mDevelopment,
          mManifestPermAppOp,
          isPerUid,
          mDependsOnPerm,
          false);
    }

    /** True if this AppOp is tied to a manifest permission, so cannot be changed on its own. */
    public boolean hasDependsOnPerm() {
      return mDependsOnPerm;
    }

    /** True if the permission can be changed for at least one of the apps using it. */
    public boolean isChangeable() {
      for (PermApp app : mApps) {
        if (app.isChangeable()) {
          return true;
        }
      }
      return false;
    }

    public List<PermApp> getApps() {
      return new ArrayList<>(mApps);
    }

    public List<PermApp> getSearchApps(String queryText) {
      if (TextUtils.isEmpty(queryText)) {
        return getApps();
      }

      List<PermApp> apps = new ArrayList<>();
      for (PermApp app : mApps) {
        if (app.contains(queryText)) {
          apps.add(app);
        }
      }
      return apps;
    }

    /**
     * Apps count | granted permissions count / total permissions count. The latter can be greater
     * than the apps count, because an app may use an AppOp permission twice (in UID mode).
     */
    public String getCountsString() {
      return appCount + " | " + grantedCount + " / " + totalCount;
    }

    void addApp(PermApp app) {
      mApps.add(app);
    }

    private void sortApps() {
      mApps.sort((a1, a2) -> a1.pkg.getLabel().compareToIgnoreCase(a2.pkg.getLabel()));
    }

    boolean contains(String queryText) {
      if (!MySettings.INS.isSpecialSearch()) {
        return containsNot(queryText);
      }

      boolean isEmpty = true;
      for (String str : queryText.split("\\|")) {
        if (TextUtils.isEmpty(str)) {
          continue;
        }
        isEmpty = false;
        if (containsAnd(str)) {
          return true;
        }
      }
      return isEmpty;
    }

    private boolean containsAnd(String queryText) {
      for (String str : queryText.split("&")) {
        if (TextUtils.isEmpty(str)) {
          continue;
        }
        if (!containsNot(str)) {
          return false;
        }
      }
      return true;
    }

    private boolean containsNot(String queryText) {
      boolean contains = true;
      if (MySettings.INS.isSpecialSearch() && queryText.startsWith("!")) {
        queryText = queryText.replaceAll("^!", "");
        contains = false;
      }

      boolean caseSensitive = MySettings.INS.isCaseSensitiveSearch();
      if (!caseSensitive) {
        queryText = queryText.toUpperCase();
      }

      for (String field : searchableFields()) {
        if (!caseSensitive) {
          field = field.toUpperCase();
        }
        if (field.contains(queryText)) {
          return contains;
        }
      }
      return !contains;
    }

    private String[] searchableFields() {
      return new String[] {
        permName,
        getLabel(),
        ":" + getLocalizedProtLevelString(),
        ((isAppOp || mManifestPermAppOp) ? SearchConstants.INS.SEARCH_APP_OPS : ""),
        ((isAppOp && isPerUid) ? SearchConstants.INS.SEARCH_UID : ""),
        (mPrivileged ? SearchConstants.INS.SEARCH_PRIVILEGED : ""),
        (mDevelopment ? SearchConstants.INS.SEARCH_DEV : ""),
        (mDependsOnPerm ? SearchConstants.INS.SEARCH_EXTRA : "")
      };
    }

    public boolean areContentsTheSame(PermSummary other) {
      return appCount == other.appCount
          && grantedCount == other.grantedCount
          && totalCount == other.totalCount;
    }
  }

  /** One app using a permission. */
  public static class PermApp {

    public final Package pkg;
    public final Permission perm;
    public final boolean isAppOp;

    private final boolean mGranted;

    private PermApp(Package pkg, Permission perm, boolean granted, boolean isAppOp) {
      this.pkg = pkg;
      this.perm = perm;
      mGranted = granted;
      this.isAppOp = isAppOp;
    }

    public boolean isGranted() {
      return mGranted;
    }

    public boolean isChangeable() {
      return perm.isChangeable();
    }

    /** True if this AppOp applies to the whole UID (i.e. to all packages sharing the UID). */
    public boolean isPerUid() {
      return perm.isPerUid();
    }

    public String getPkgLabel() {
      return pkg.getLabel();
    }

    public String getPkgName() {
      return pkg.getName();
    }

    public int getUid() {
      return pkg.getUid();
    }

    public boolean contains(String queryText) {
      return queryText == null || pkg.contains(queryText);
    }

    public boolean areContentsTheSame(PermApp other) {
      return mGranted == other.mGranted
          && perm.getAppOpMode() == other.perm.getAppOpMode()
          && pkg.areContentsTheSame(other.pkg);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof PermApp)) {
        return false;
      }
      PermApp other = (PermApp) o;
      return isAppOp == other.isAppOp
          && perm.isPerUid() == other.perm.isPerUid()
          && pkg.getName().equals(other.pkg.getName())
          && perm.getName().equals(other.perm.getName());
    }

    @Override
    public int hashCode() {
      return Objects.hash(pkg.getName(), perm.getName(), isAppOp, perm.isPerUid());
    }
  }
}
