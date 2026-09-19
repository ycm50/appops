package com.mirfatif.permissionmanagerx.permview;

import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.mirfatif.permissionmanagerx.R;
import com.mirfatif.permissionmanagerx.databinding.ActivityPermViewBinding;
import com.mirfatif.permissionmanagerx.fwk.PermViewActivityM;
import com.mirfatif.permissionmanagerx.parser.PackageParser;
import com.mirfatif.permissionmanagerx.parser.PermViewParser;
import com.mirfatif.permissionmanagerx.parser.PermViewParser.PermSummary;
import com.mirfatif.permissionmanagerx.prefs.MySettings;
import com.mirfatif.permissionmanagerx.util.ApiUtils;
import com.mirfatif.permissionmanagerx.util.bg.LiveTasksQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Permission View: lists all the permissions with a count of how many apps are using them.
 * Selecting a permission opens the list of apps using it.
 */
public class PermViewActivity {

  private static final String TAG = "PermViewActivity";

  public final PermViewActivityM mA;

  public PermViewActivity(PermViewActivityM activity) {
    mA = activity;
  }

  private ActivityPermViewBinding mB;
  private PermViewAdapter mAdapter;
  private SearchView mSearchView;

  public void onCreated() {
    mB = ActivityPermViewBinding.inflate(mA.getLayoutInflater());
    mA.setContentView(mB);

    ActionBar actionBar = mA.getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(R.string.perm_view_menu_item);
    }

    mAdapter = new PermViewAdapter(mA, this::openPerm);

    mB.recyclerV.setAdapter(mAdapter);
    mB.recyclerV.setLayoutManager(new LinearLayoutManager(mA, LinearLayoutManager.VERTICAL, false));
    mB.recyclerV.addItemDecoration(new DividerItemDecoration(mA, LinearLayoutManager.VERTICAL));

    MySettings.INS.mPrefsWatcher.observe(mA, this::onPrefChanged);

    build();
  }

  public boolean onCreateOptionsMenu(Menu menu) {
    mA.getMenuInflater().inflate(R.menu.perm_view, menu);

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

    mSearchView.setQueryHint(ApiUtils.getString(R.string.perm_view_search_hint));

    return true;
  }

  public boolean onPrepareOptionsMenu(Menu menu) {
    boolean havePerms = mAdapter != null && mAdapter.getItemCount() != 0;
    menu.findItem(R.id.action_search).setVisible(havePerms);
    return true;
  }

  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.action_refresh) {
      build();
      return true;
    }
    return false;
  }

  /** Builds the permission list on a background thread, then submits it to the adapter. */
  private void build() {
    new LiveTasksQueue(mA, () -> PackageParser.INS.updatePkgListWithResult(false))
        .onUi(
            () -> {
              PermViewParser.INS.build();
              submitList();
            })
        .start();
  }

  private void submitList() {
    if (mAdapter == null || mB == null) {
      return;
    }

    String queryText = mSearchView == null ? null : mSearchView.getQuery().toString();
    List<PermSummary> list = PermViewParser.INS.getSearchList(queryText);

    mAdapter.submitList(new ArrayList<>(list));
    mB.noPermsV.setVisibility(list.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);

    ActionBar actionBar = mA.getSupportActionBar();
    if (actionBar != null) {
      actionBar.setSubtitle(getCountsString(list));
    }
  }

  private String getCountsString(List<PermSummary> list) {
    int perms = 0, appOps = 0;
    for (PermSummary summary : list) {
      if (summary.isAppOp) {
        appOps++;
      } else {
        perms++;
      }
    }
    return ApiUtils.getString(R.string.perm_view_counts, perms, appOps);
  }

  private void onPrefChanged(Integer pref) {
    if (pref == MySettings.PREF_UI_CHANGED || pref == MySettings.PREF_PERM_VIEW_CHANGED) {
      build();
    }
  }

  private void openPerm(PermSummary summary) {
    PermViewAppsActivity.start(mA, summary);
  }
}
