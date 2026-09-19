package com.mirfatif.permissionmanagerx.permview;

import static com.mirfatif.permissionmanagerx.util.ApiUtils.getString;

import android.app.Activity;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog.Builder;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.mirfatif.permissionmanagerx.R;
import com.mirfatif.permissionmanagerx.app.App;
import com.mirfatif.permissionmanagerx.base.AlertDialogFragment;
import com.mirfatif.permissionmanagerx.databinding.ActivityPermViewAppsBinding;
import com.mirfatif.permissionmanagerx.fwk.PermViewAppsActivityM;
import com.mirfatif.permissionmanagerx.parser.PackageParser;
import com.mirfatif.permissionmanagerx.parser.PermViewParser;
import com.mirfatif.permissionmanagerx.parser.PermViewParser.PermApp;
import com.mirfatif.permissionmanagerx.parser.PermViewParser.PermSummary;
import com.mirfatif.permissionmanagerx.parser.Permission;
import com.mirfatif.permissionmanagerx.pkg.PackageActivity;
import com.mirfatif.permissionmanagerx.prefs.MySettings;
import com.mirfatif.permissionmanagerx.privs.DaemonHandler;
import com.mirfatif.permissionmanagerx.util.StringUtils;
import com.mirfatif.permissionmanagerx.util.UiUtils;
import com.mirfatif.permissionmanagerx.util.bg.UiRunner;
import com.mirfatif.privtasks.util.bg.BgRunner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Lists the apps using a permission. Selecting an app opens its permission list, where the state
 * can be changed as well.
 */
public class PermViewAppsActivity {

  private static final String CLASS = PermViewAppsActivity.class.getName();
  private static final String EXTRA_PERM_NAME = CLASS + ".extra.PERM_NAME";
  private static final String EXTRA_IS_APP_OP = CLASS + ".extra.IS_APP_OP";
  private static final String EXTRA_IS_PER_UID = CLASS + ".extra.IS_PER_UID";

  public final PermViewAppsActivityM mA;

  public PermViewAppsActivity(PermViewAppsActivityM activity) {
    mA = activity;
  }

  private ActivityPermViewAppsBinding mB;
  private PermViewAppsAdapter mAdapter;
  private SearchView mSearchView;

  private String mPermName;
  private boolean mIsAppOp, mIsPerUid;

  public void onCreated() {
    mPermName = mA.getIntent().getStringExtra(EXTRA_PERM_NAME);
    mIsAppOp = mA.getIntent().getBooleanExtra(EXTRA_IS_APP_OP, false);
    mIsPerUid = mA.getIntent().getBooleanExtra(EXTRA_IS_PER_UID, false);

    PermSummary summary = findPerm();
    if (summary == null) {
      UiUtils.showToast(R.string.something_went_wrong);
      mA.finishAfterTransition();
      return;
    }

    mB = ActivityPermViewAppsBinding.inflate(mA.getLayoutInflater());
    mA.setContentView(mB);

    ActionBar actionBar = mA.getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(summary.getLabel());
      actionBar.setSubtitle(summary.getLocalizedProtLevelString());
    }

    mAdapter = new PermViewAppsAdapter(mA, new AdapterCallback(), mIsAppOp);

    mB.recyclerV.setAdapter(mAdapter);
    mB.recyclerV.setLayoutManager(new LinearLayoutManager(mA, LinearLayoutManager.VERTICAL, false));
    mB.recyclerV.addItemDecoration(new DividerItemDecoration(mA, LinearLayoutManager.VERTICAL));

    if (mIsAppOp) {
      mB.appOpCont.setVisibility(View.VISIBLE);
      mB.appOpV.setText(mPermName);
    }

    MySettings.INS.mPrefsWatcher.observe(mA, this::onPrefChanged);
    submitList();
  }

  public boolean onCreateOptionsMenu(Menu menu) {
    mA.getMenuInflater().inflate(R.menu.perm_view_apps, menu);

    MenuItem searchMenuItem = menu.findItem(R.id.action_search);
    mSearchView = (SearchView) searchMenuItem.getActionView();
    Objects.requireNonNull(mSearchView).setMaxWidth(Integer.MAX_VALUE);

    mSearchView.setOnQueryTextListener(
        new SearchView.OnQueryTextListener() {
          public boolean onQueryTextSubmit(String query) {
            submitList();
            return true;
          }

          public boolean onQueryTextChange(String newText) {
            submitList();
            return true;
          }
        });

    mSearchView.setOnQueryTextFocusChangeListener(
        (v, hasFocus) -> {
          if (!hasFocus && mSearchView != null && mSearchView.getQuery().length() == 0) {
            mSearchView.onActionViewCollapsed();
          }
        });

    mSearchView.setQueryHint(getString(R.string.search_menu_item));

    return true;
  }

  public boolean onPrepareOptionsMenu(Menu menu) {
    boolean haveApps = mAdapter != null && mAdapter.getItemCount() != 0;
    menu.findItem(R.id.action_search).setVisible(haveApps);

    // Bulk items only make sense for the apps which can be changed right here, so that the user is
    // not offered an action which would silently skip everything.
    menu.findItem(R.id.action_enable_all).setVisible(!getBulkApps(true).isEmpty());
    menu.findItem(R.id.action_disable_all).setVisible(!getBulkApps(false).isEmpty());

    return true;
  }

  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_enable_all) {
      confirmBulkStateChange(true);
      return true;
    }
    if (id == R.id.action_disable_all) {
      confirmBulkStateChange(false);
      return true;
    }
    return false;
  }

  @Nullable
  private PermSummary findPerm() {
    if (mPermName == null) {
      return null;
    }

    for (PermSummary summary : PermViewParser.INS.getPermList()) {
      if (summary.permName.equals(mPermName)
          && summary.isAppOp == mIsAppOp
          && summary.isPerUid == mIsPerUid) {
        return summary;
      }
    }
    return null;
  }

  private void submitList() {
    if (mAdapter == null || mB == null) {
      return;
    }

    PermSummary summary = findPerm();
    if (summary == null) {
      return;
    }

    CharSequence query = mSearchView == null ? null : mSearchView.getQuery();
    List<PermApp> apps = summary.getSearchApps(query == null ? null : query.toString());

    mAdapter.submitList(new ArrayList<>(apps));
    mB.noAppsV.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
  }

  private void onStateToggle(PermApp app) {
    if (!DaemonHandler.INS.isDaemonAlive()) {
      return;
    }

    BgRunner.execute(
        () -> {
          if (app.isAppOp) {
            app.perm.setAppOpMode(app.pkg, Permission.getAppOpMode(!app.isGranted()));
          } else {
            app.perm.toggleState(app.pkg);
          }

          PackageParser.INS.updatePackage(app.pkg, true);
          PermViewParser.INS.build();

          if (mB != null) {
            mB.recyclerV.post(
                () -> {
                  if (!mDestroyed) {
                    submitList();
                  }
                });
          }
        });
  }

  private volatile boolean mDestroyed = false;

  public void onDestroy() {
    mDestroyed = true;
  }

  /**
   * The apps in the current (possibly searched) list whose state can be changed here and which are
   * not already in the requested state. Apps which cannot be changed are skipped, and so are AppOps
   * in UID mode: the list shows their mode as a text instead of a switch, because changing them
   * affects every package sharing the UID (see {@link PermViewAppsAdapter}).
   */
  private List<PermApp> getBulkApps(boolean granted) {
    List<PermApp> apps = new ArrayList<>();
    if (mAdapter == null) {
      return apps;
    }

    for (PermApp app : mAdapter.getCurrentList()) {
      if (app.isChangeable() && !(app.isAppOp && app.isPerUid()) && app.isGranted() != granted) {
        apps.add(app);
      }
    }
    return apps;
  }

  private void confirmBulkStateChange(boolean granted) {
    if (!DaemonHandler.INS.isDaemonAlive()) {
      return;
    }

    List<PermApp> apps = getBulkApps(granted);
    if (apps.isEmpty()) {
      UiUtils.showToast(R.string.perm_view_bulk_nothing_to_do_toast);
      return;
    }

    StringBuilder msg =
        new StringBuilder(
            getString(
                granted ? R.string.perm_view_bulk_enable_msg : R.string.perm_view_bulk_disable_msg,
                apps.size()));

    String warn = createBulkDangWarn(apps);
    if (warn != null) {
      msg.append("\n").append(warn);
    }

    Builder builder =
        new Builder(mA)
            .setTitle(R.string.warning)
            .setMessage(StringUtils.breakParas(msg.toString()))
            .setPositiveButton(R.string.yes, (d, w) -> bulkChangeState(apps, granted))
            .setNegativeButton(R.string.no, null);

    AlertDialogFragment.show(mA, builder.create(), "PERM_VIEW_BULK_CHANGE");
  }

  /** Same warning the package view shows before changing the permissions of a system app. */
  private String createBulkDangWarn(List<PermApp> apps) {
    if (!MySettings.INS.warnDangerousPermChanges()) {
      return null;
    }

    boolean framework = false, system = false;
    for (PermApp app : apps) {
      if (app.pkg.isFrameworkApp()) {
        framework = true;
        break;
      }
      if (app.pkg.isSystemApp()) {
        system = true;
      }
    }

    if (framework) {
      return getString(R.string.change_perms_warning, getString(R.string.framework));
    }
    if (system) {
      return getString(R.string.change_perms_warning, getString(R.string.system));
    }
    return null;
  }

  private void bulkChangeState(List<PermApp> apps, boolean granted) {
    BgRunner.execute(
        () -> {
          for (PermApp app : apps) {
            if (app.isAppOp) {
              app.perm.setAppOpMode(app.pkg, Permission.getAppOpMode(granted));
            } else {
              app.perm.setPermState(app.pkg, granted);
            }
          }

          // A package may be listed more than once (e.g. the same AppOp in both modes), so re-read
          // each affected package only once.
          Set<String> updatedPkgs = new HashSet<>();
          for (PermApp app : apps) {
            if (updatedPkgs.add(app.pkg.getName())) {
              PackageParser.INS.updatePackage(app.pkg, true);
            }
          }

          PermViewParser.INS.build();

          if (mB != null) {
            mB.recyclerV.post(
                () -> {
                  if (!mDestroyed) {
                    submitList();
                    UiUtils.showToast(
                        getString(R.string.perm_view_bulk_changed_toast, apps.size()));
                  }
                });
          }
        });
  }

  private void onPrefChanged(Integer pref) {
    if (pref != MySettings.PREF_PERM_VIEW_CHANGED) {
      submitList();
      return;
    }

    // The system apps switch can remove this permission from the list altogether.
    BgRunner.execute(
        () -> {
          PermViewParser.INS.build();
          UiRunner.post(
              mA,
              () -> {
                if (mDestroyed) {
                  return;
                }
                if (findPerm() == null) {
                  mA.finishAfterTransition();
                } else {
                  submitList();
                }
              });
        });
  }

  private class AdapterCallback implements PermViewAppsAdapter.PermViewAppsAdapterCallback {

    public void onAppClick(PermApp app) {
      PackageActivity.start(mA, app.pkg, mPermName);
    }

    public void onStateToggle(PermApp app) {
      PermViewAppsActivity.this.onStateToggle(app);
    }
  }

  public static void start(Activity activity, PermSummary summary) {
    Intent intent = new Intent(App.getCxt(), PermViewAppsActivityM.class);
    intent.putExtra(EXTRA_PERM_NAME, summary.permName);
    intent.putExtra(EXTRA_IS_APP_OP, summary.isAppOp);
    intent.putExtra(EXTRA_IS_PER_UID, summary.isPerUid);
    activity.startActivity(intent);
  }
}
