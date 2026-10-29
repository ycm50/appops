package com.mirfatif.permissionmanagerx.util;

import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.LruCache;
import com.mirfatif.permissionmanagerx.app.App;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Process-wide, bounded cache of package icons.
 *
 * <p>Drawable instances are stateful and must not be shared between list rows, so only {@link
 * Drawable.ConstantState}s are stored. Every cache hit returns a fresh drawable created with {@link
 * Drawable.ConstantState#newDrawable} and {@link Drawable#mutate()}.
 *
 * <p>Entries are keyed by Android user id, package name and {@code PackageInfo.lastUpdateTime}, so
 * a package update naturally maps to a different key: the icon of an updated package can never be
 * served from a stale entry. Callers which already know the update time (see {@link
 * #getOrLoad(String, int, long, IconLoader)}) get an exact key lookup, so a cache hit performs zero
 * PackageManager IPC. The older {@link #getOrLoad(String, int, IconLoader)} entry point does not
 * know the update time and relies on a time based revalidation instead.
 */
public final class PkgIconCache {

  private PkgIconCache() {}

  /** Total byte budget of the cache, adjustable if the icons turn out larger or smaller. */
  private static final int MAX_BYTES = 8 * 1024 * 1024;

  /**
   * How long a cached icon is trusted by the legacy {@link #getOrLoad(String, int, IconLoader)}
   * entry point, which does not know the package update time. It is not used by {@link
   * #getOrLoad(String, int, long, IconLoader)}: there freshness is guaranteed by the update time in
   * the key, so no periodic revalidation is needed.
   */
  private static final long UPDATE_CHECK_INTERVAL_MS = 60 * 1000L;

  /** Assumed footprint of an icon whose bytes cannot be measured (e.g. a vector drawable). */
  private static final int FALLBACK_BYTES = 8 * 1024;

  /** Upper bound for a single estimated entry, so a bogus intrinsic size cannot evict the cache. */
  private static final int MAX_SINGLE_ENTRY_BYTES = 4 * 1024 * 1024;

  private static final String ID_SEP = ":";

  /** Loads an icon on a background thread, together with the package's last update time. */
  public interface IconLoader {
    LoadedIcon load() throws NameNotFoundException;
  }

  /** The result of an {@link IconLoader} run. */
  public static final class LoadedIcon {

    private final Drawable mIcon;
    private final long mLastUpdateTime;

    public LoadedIcon(Drawable icon, long lastUpdateTime) {
      mIcon = icon;
      mLastUpdateTime = lastUpdateTime;
    }
  }

  private static final Object LOCK = new Object();

  /** Most recent key per package identity, used as the cache lookup key. */
  private static final Map<String, Key> sLastKeys = new HashMap<>();

  private static final LruCache<Key, Entry> CACHE =
      new LruCache<Key, Entry>(MAX_BYTES) {
        @Override
        protected int sizeOf(Key key, Entry value) {
          return value.mBytes;
        }
      };

  /**
   * Returns a private copy of the icon of {@code pkgName} for the given update generation.
   *
   * <p>The caller already knows {@code lastUpdateTime} (from {@code PackageInfo.lastUpdateTime} via
   * the package model), so freshness is guaranteed by the cache key: a package update changes the
   * key and can never return a stale icon. A hit does an exact map lookup and performs <b>zero
   * PackageManager IPC</b>; the 60 second revalidation used by the legacy entry point does not
   * apply here. Must not be called on the main thread: {@code loader} performs the PackageManager
   * queries on a miss.
   */
  public static Drawable getOrLoad(String pkgName, int uid, long lastUpdateTime, IconLoader loader)
      throws NameNotFoundException {
    if (pkgName == null || loader == null) {
      return null;
    }
    return getOrLoadInternal(pkgName, uid, lastUpdateTime, true, loader);
  }

  /**
   * Returns a private copy of the icon of {@code pkgName}, for callers which do not have the
   * PackageInfo at hand. This entry point cannot know the package update time, so an entry is
   * trusted for at most {@link #UPDATE_CHECK_INTERVAL_MS} and the loader is invoked again
   * afterwards to learn whether the package changed. List adapters have the update time on their
   * model and should use {@link #getOrLoad(String, int, long, IconLoader)} instead. Must not be
   * called on the main thread.
   */
  public static Drawable getOrLoad(String pkgName, int uid, IconLoader loader)
      throws NameNotFoundException {
    if (pkgName == null || loader == null) {
      return null;
    }
    return getOrLoadInternal(pkgName, uid, 0L, false, loader);
  }

  private static Drawable getOrLoadInternal(
      String pkgName, int uid, long lastUpdateTime, boolean timeKnown, IconLoader loader)
      throws NameNotFoundException {
    String identity = UserUtils.getUserId(uid) + ID_SEP + pkgName;

    Drawable.ConstantState cachedState = null;
    Key hitKey = null;
    synchronized (LOCK) {
      if (timeKnown) {
        // Exact lookup: hit or miss only, there is nothing to revalidate.
        Key key = new Key(identity, lastUpdateTime);
        Entry entry = CACHE.get(key);
        if (entry != null) {
          cachedState = entry.mState;
          hitKey = key;
        }
      } else {
        Key lastKey = sLastKeys.get(identity);
        if (lastKey != null) {
          Entry entry = CACHE.get(lastKey);
          long now = SystemClock.elapsedRealtime();
          if (entry != null && now - entry.mCheckedAt < UPDATE_CHECK_INTERVAL_MS) {
            cachedState = entry.mState;
            hitKey = lastKey;
          }
        }
      }
    }

    if (cachedState != null) {
      Drawable copy = newDrawable(cachedState);
      if (copy != null) {
        return copy;
      }
      // The cached state can no longer be instantiated (a theme or density change does that).
      // Drop the entry, otherwise this package would hit the same broken state on every bind and
      // keep a blank icon for the rest of the process.
      synchronized (LOCK) {
        CACHE.remove(hitKey);
        Key lastKey = sLastKeys.get(identity);
        if (hitKey.equals(lastKey)) {
          sLastKeys.remove(identity);
        }
      }
    }

    LoadedIcon loaded = loader.load();
    if (loaded == null || loaded.mIcon == null) {
      return null;
    }

    Drawable icon = loaded.mIcon;
    Drawable.ConstantState state = icon.getConstantState();
    if (state == null) {
      // A drawable without a constant state can be neither copied nor shared safely.
      return icon.mutate();
    }

    // Store under the time the caller will look up with. The loader may observe a newer update time
    // (the package changed after the list was scanned); storing under that one would make every
    // later exact lookup miss and re-run the loader for the whole life of the entry. The next full
    // scan brings the newer time in and gets a natural miss, which is the intended invalidation.
    Entry entry = new Entry(state, sizeOf(icon), SystemClock.elapsedRealtime());
    Key key = new Key(identity, timeKnown ? lastUpdateTime : loaded.mLastUpdateTime);
    synchronized (LOCK) {
      Key previous = sLastKeys.put(identity, key);
      if (previous != null && !previous.equals(key)) {
        CACHE.remove(previous);
      }
      CACHE.put(key, entry);
    }

    return newDrawable(state);
  }

  /** Drops all cached icons. */
  public static void clear() {
    synchronized (LOCK) {
      CACHE.evictAll();
      sLastKeys.clear();
    }
  }

  /** Drops the cached icon of {@code pkgName} for every user. */
  public static void remove(String pkgName) {
    if (pkgName == null) {
      return;
    }

    String suffix = ID_SEP + pkgName;
    synchronized (LOCK) {
      Iterator<Map.Entry<String, Key>> it = sLastKeys.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<String, Key> entry = it.next();
        if (entry.getKey().endsWith(suffix)) {
          CACHE.remove(entry.getValue());
          it.remove();
        }
      }
    }
  }

  private static Drawable newDrawable(Drawable.ConstantState state) {
    try {
      Drawable drawable = state.newDrawable(App.getCxt().getResources());
      return drawable == null ? null : drawable.mutate();
    } catch (RuntimeException e) {
      // A theme or density change can make a cached state unusable. A missing icon on one row is
      // far better than taking the process down from a bind.
      return null;
    }
  }

  /**
   * Footprint of a cached icon. Only a bitmap can be measured exactly; adaptive icons (API 26+) and
   * vectors rasterise at their intrinsic size, and counting those as a fixed small number let the
   * cache grow far past its budget, so that case is estimated from the intrinsic size instead.
   */
  private static int sizeOf(Drawable drawable) {
    if (drawable instanceof BitmapDrawable) {
      Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
      if (bitmap != null) {
        // A recycled bitmap reports 0, and a zero size would make the cache unbounded. The upper
        // bound keeps one huge bitmap from evicting every other icon on its own.
        long bytes = bitmap.getAllocationByteCount();
        return (int) Math.min(Math.max(1, bytes), MAX_SINGLE_ENTRY_BYTES);
      }
    }

    int width = drawable.getIntrinsicWidth();
    int height = drawable.getIntrinsicHeight();
    if (width > 0 && height > 0) {
      long bytes = (long) width * height * 4;
      return (int) Math.min(Math.max(1, bytes), MAX_SINGLE_ENTRY_BYTES);
    }

    return FALLBACK_BYTES;
  }

  private static final class Key {

    private final String mIdentity;
    private final long mLastUpdateTime;

    private Key(String identity, long lastUpdateTime) {
      mIdentity = identity;
      mLastUpdateTime = lastUpdateTime;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key)) {
        return false;
      }
      Key key = (Key) o;
      return mLastUpdateTime == key.mLastUpdateTime && mIdentity.equals(key.mIdentity);
    }

    @Override
    public int hashCode() {
      return 31 * mIdentity.hashCode() + (int) (mLastUpdateTime ^ (mLastUpdateTime >>> 32));
    }
  }

  private static final class Entry {

    private final Drawable.ConstantState mState;
    private final int mBytes;
    private final long mCheckedAt;

    private Entry(Drawable.ConstantState state, int bytes, long checkedAt) {
      mState = state;
      mBytes = bytes;
      mCheckedAt = checkedAt;
    }
  }
}
