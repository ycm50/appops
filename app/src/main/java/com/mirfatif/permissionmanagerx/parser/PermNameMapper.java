package com.mirfatif.permissionmanagerx.parser;

import androidx.annotation.Nullable;
import com.mirfatif.permissionmanagerx.R;
import com.mirfatif.permissionmanagerx.app.App;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps a raw Manifest permission or AppOps name (e.g. {@code READ_CONTACTS}) to a simple, readable
 * label (e.g. {@code 读取联系人}).
 *
 * <p>The dictionary is a {@code KEY=label} {@code string-array} named {@code perm_name_map}. Only
 * the {@code values-zh-rCN} configuration provides one, so every other locale keeps showing the raw
 * name, exactly as before.
 *
 * <p>Lookup order is:
 *
 * <ol>
 *   <li>the whole name, as given
 *   <li>the name with its package prefix removed ({@code com.foo.permission.BAR} to {@code BAR})
 *   <li>the name rebuilt from its {@code _}-separated tokens, but only when <i>every</i> token is
 *       known, so an unmapped name is never turned into a half translated string
 * </ol>
 *
 * A name that none of these resolve is returned unchanged, which keeps unrecognised and exotic OEM
 * permissions legible instead of blanking them out.
 */
public enum PermNameMapper {
  INS;

  /** Separates a key from its label in the {@code perm_name_map} array. */
  private static final char KEY_SEP = '=';

  private static final String PERMISSION_INFIX = ".permission.";

  private final Map<String, String> MAP = new HashMap<>();
  private final Map<String, String> CACHE = new HashMap<>();

  PermNameMapper() {
    String[] entries = App.getRes().getStringArray(R.array.perm_name_map);
    for (String entry : entries) {
      entry = entry.trim();
      if (entry.isEmpty() || entry.charAt(0) == '#') {
        continue;
      }
      int sep = entry.indexOf(KEY_SEP);
      if (sep <= 0 || sep == entry.length() - 1) {
        continue;
      }
      String key = entry.substring(0, sep).trim();
      String label = entry.substring(sep + 1).trim();
      if (!key.isEmpty() && !label.isEmpty()) {
        MAP.put(key, label);
      }
    }
  }

  /** Whether a dictionary is available for the current locale at all. */
  public boolean isEnabled() {
    return !MAP.isEmpty();
  }

  /**
   * Returns the readable label of {@code permName}, or {@code permName} itself when it is not
   * mapped.
   */
  public String getLocalizedName(@Nullable String permName) {
    if (permName == null || permName.isEmpty() || MAP.isEmpty()) {
      return permName;
    }

    String cached = CACHE.get(permName);
    if (cached != null) {
      return cached;
    }

    String label = translate(permName);
    CACHE.put(permName, label);
    return label;
  }

  private String translate(String permName) {
    String label = MAP.get(permName);
    if (label != null) {
      return label;
    }

    String bare = stripPackagePrefix(permName);
    label = MAP.get(bare);
    if (label != null) {
      return label;
    }

    return translateTokens(bare, permName);
  }

  /**
   * Rebuilds {@code bare} from its tokens, or falls back to {@code original} when any token is
   * unknown. Tokens are joined without a separator, which is what a Chinese label wants; an
   * unmapped name keeps its raw form so that it stays recognisable.
   */
  private String translateTokens(String bare, String original) {
    int start = 0;
    StringBuilder builder = null;

    while (start <= bare.length()) {
      int end = bare.indexOf('_', start);
      if (end < 0) {
        end = bare.length();
      }

      String token = bare.substring(start, end);
      String translated = token.isEmpty() ? null : MAP.get(token);
      if (translated == null) {
        return original;
      }

      if (builder == null) {
        builder = new StringBuilder(bare.length() + 8);
      }
      builder.append(translated);

      if (end == bare.length()) {
        break;
      }
      start = end + 1;
    }

    return builder == null ? original : builder.toString();
  }

  /**
   * Removes a package prefix from a permission name: {@code android.permission.READ_CONTACTS},
   * {@code com.android.launcher.permission.INSTALL_SHORTCUT} and friends all lose everything up to
   * and including their last {@code .permission.} component.
   */
  private static String stripPackagePrefix(String permName) {
    int idx = permName.lastIndexOf(PERMISSION_INFIX);
    return idx < 0 ? permName : permName.substring(idx + PERMISSION_INFIX.length());
  }

  /**
   * Removes the package prefix from an already mapped label if the raw name had one; otherwise
   * returns the label unchanged. Exposed for callers that hold the raw name separately.
   */
  public static String stripPrefix(String permName) {
    return stripPackagePrefix(permName);
  }
}
