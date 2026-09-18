package com.mirfatif.permissionmanagerx.permview;

import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.mirfatif.permissionmanagerx.base.MyListAdapter;
import com.mirfatif.permissionmanagerx.databinding.RvItemPermViewBinding;
import com.mirfatif.permissionmanagerx.parser.PermViewParser.PermSummary;
import com.mirfatif.permissionmanagerx.permview.PermViewAdapter.ItemViewHolder;

/** Lists all permissions with the count of apps using them. */
public class PermViewAdapter extends MyListAdapter<PermSummary, ItemViewHolder> {

  private static final String TAG = "PermViewAdapter";

  private final PermViewAdapterCallback mCallback;

  public PermViewAdapter(LifecycleOwner owner, PermViewAdapterCallback callback) {
    super(new DiffUtilItemCallBack(), owner, TAG);
    mCallback = callback;
  }

  public ItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    RvItemPermViewBinding b = RvItemPermViewBinding.inflate(inflater, parent, false);
    return new ItemViewHolder(b);
  }

  public void onBindViewHolder(ItemViewHolder holder, int position) {
    holder.bind(position);
  }

  class ItemViewHolder extends RecyclerView.ViewHolder implements OnClickListener {

    private final RvItemPermViewBinding mB;

    ItemViewHolder(RvItemPermViewBinding binding) {
      super(binding.getRoot());
      mB = binding;
      binding.getRoot().setOnClickListener(this);
    }

    void bind(int pos) {
      PermSummary summary;
      if (pos == RecyclerView.NO_POSITION || (summary = getItem(pos)) == null) {
        return;
      }

      mB.iconV.setImageResource(summary.getIconResId());
      mB.permNameV.setText(summary.getLabel());
      mB.protLevelV.setText(summary.getLocalizedProtLevelString());
      mB.appCountV.setText(summary.getCountsString());
    }

    public void onClick(View v) {
      int pos = getBindingAdapterPosition();
      PermSummary summary;
      if (pos != RecyclerView.NO_POSITION && (summary = getItem(pos)) != null) {
        mCallback.onPermClick(summary);
      }
    }
  }

  private static class DiffUtilItemCallBack extends DiffUtil.ItemCallback<PermSummary> {

    public boolean areItemsTheSame(PermSummary oldItem, PermSummary newItem) {
      return oldItem.permName.equals(newItem.permName)
          && oldItem.isAppOp == newItem.isAppOp
          && oldItem.isPerUid == newItem.isPerUid;
    }

    public boolean areContentsTheSame(PermSummary oldItem, PermSummary newItem) {
      return oldItem.areContentsTheSame(newItem);
    }
  }

  public interface PermViewAdapterCallback {

    void onPermClick(PermSummary summary);
  }
}
