package com.mirfatif.permissionmanagerx.permview;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.mirfatif.permissionmanagerx.app.App;
import com.mirfatif.permissionmanagerx.base.MyListAdapter;
import com.mirfatif.permissionmanagerx.databinding.RvItemPermViewAppBinding;
import com.mirfatif.permissionmanagerx.parser.PermViewParser.PermApp;
import com.mirfatif.permissionmanagerx.permview.PermViewAppsAdapter.ItemViewHolder;
import com.mirfatif.permissionmanagerx.util.ApiUtils;
import com.mirfatif.permissionmanagerx.util.PkgIconCache;
import com.mirfatif.permissionmanagerx.util.bg.LiveSingleParamTask;
import com.mirfatif.permissionmanagerx.util.bg.UiRunner;

/** Lists the apps using a permission, allowing to change the permission state in place. */
public class PermViewAppsAdapter extends MyListAdapter<PermApp, ItemViewHolder> {

  private static final String TAG = "PermViewAppsAdapter";

  private final LifecycleOwner mLifecycleOwner;
  private final PermViewAppsAdapterCallback mCallback;
  private final boolean mIsAppOp;

  public PermViewAppsAdapter(
      LifecycleOwner owner, PermViewAppsAdapterCallback callback, boolean isAppOp) {
    super(new DiffUtilItemCallBack(), owner, TAG);
    mLifecycleOwner = owner;
    mCallback = callback;
    mIsAppOp = isAppOp;
  }

  public ItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    RvItemPermViewAppBinding b = RvItemPermViewAppBinding.inflate(inflater, parent, false);
    return new ItemViewHolder(b);
  }

  public void onBindViewHolder(ItemViewHolder holder, int position) {
    holder.bind(position);
  }

  class ItemViewHolder extends RecyclerView.ViewHolder implements OnClickListener {

    private final RvItemPermViewAppBinding mB;

    private final LiveSingleParamTask<PermApp> mIconSetter =
        new LiveSingleParamTask<>(mLifecycleOwner, this::setIcon, TAG + "-IconSetter");

    ItemViewHolder(RvItemPermViewAppBinding binding) {
      super(binding.getRoot());
      mB = binding;
      binding.getRoot().setOnClickListener(this);
    }

    void bind(int pos) {
      PermApp app;
      if (pos == RecyclerView.NO_POSITION || (app = getItem(pos)) == null) {
        return;
      }

      mIconSetter.cancelAndSubmit(app, true);

      mB.appLabelV.setText(app.getPkgLabel());
      mB.pkgNameV.setText(app.getPkgName());
      mB.uidV.setText(String.valueOf(app.getUid()));

      if (mIsAppOp) {
        // An AppOp in UID mode is not tied to this package only.
        mB.switchV.setVisibility(app.isPerUid() ? View.GONE : View.VISIBLE);
        mB.appOpModeV.setVisibility(app.isPerUid() ? View.VISIBLE : View.GONE);
        mB.appOpModeV.setText(app.perm.getLocalizedPermStateName());
      } else {
        mB.switchV.setVisibility(View.VISIBLE);
        mB.appOpModeV.setVisibility(View.GONE);
      }

      mB.switchV.setChecked(app.isGranted());
      mB.switchV.setEnabled(app.isChangeable());

      if (app.isChangeable()) {
        mB.switchV.setOnClickListener(
            v -> {
              mB.switchV.setChecked(app.isGranted());
              mCallback.onStateToggle(app);
            });
      } else {
        mB.switchV.setOnClickListener(null);
      }
    }

    public void onClick(View v) {
      int pos = getBindingAdapterPosition();
      PermApp app;
      if (pos != RecyclerView.NO_POSITION && (app = getItem(pos)) != null) {
        mCallback.onAppClick(app);
      }
    }

    private void setIcon(PermApp app) {
      if (Thread.currentThread().isInterrupted()) {
        return;
      }

      try {
        int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES;
        // Passing lastUpdateTime lets the cache hit by exact key: on a rebind of an unchanged
        // package it returns without invoking the loader, i.e. without any PackageManager IPC.
        Drawable icon =
            PkgIconCache.getOrLoad(
                app.getPkgName(),
                app.getUid(),
                app.pkg.getLastUpdateTime(),
                () -> {
                  PackageInfo info = ApiUtils.getPkgInfo(app.getPkgName(), flags);
                  return new PkgIconCache.LoadedIcon(
                      App.getPm().getApplicationIcon(info.applicationInfo), info.lastUpdateTime);
                });

        // A null icon is kept as the placeholder; setting it is not required and must not throw.
        if (icon == null || Thread.interrupted()) {
          return;
        }

        UiRunner.post(
            mLifecycleOwner,
            () -> {
              // The holder may have been rebound while the icon was loading.
              int pos = getBindingAdapterPosition();
              PermApp bound = pos == RecyclerView.NO_POSITION ? null : getItem(pos);
              if (bound != null
                  && bound.getPkgName().equals(app.getPkgName())
                  && bound.getUid() == app.getUid()) {
                mB.iconV.setImageDrawable(icon);
              }
            });
      } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        // A missing package or a bad drawable must not reach the default uncaught exception
        // handler from a list bind: the row keeps its placeholder icon instead.
      }
    }
  }

  private static class DiffUtilItemCallBack extends DiffUtil.ItemCallback<PermApp> {

    public boolean areItemsTheSame(PermApp oldItem, PermApp newItem) {
      return oldItem.equals(newItem);
    }

    public boolean areContentsTheSame(PermApp oldItem, PermApp newItem) {
      return oldItem.areContentsTheSame(newItem);
    }
  }

  public interface PermViewAppsAdapterCallback {

    void onAppClick(PermApp app);

    void onStateToggle(PermApp app);
  }
}
