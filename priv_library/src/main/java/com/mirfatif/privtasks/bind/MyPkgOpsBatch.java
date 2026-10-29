package com.mirfatif.privtasks.bind;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** Result of a batched AppOps query: one instance per requested package. */
public class MyPkgOpsBatch implements Parcelable {

  public final String pkgName;
  public final List<MyPackageOps> pkgOps; // getOpsForPackage() result.
  public final List<MyPackageOps> uidOps; // getUidOps() result; null on SDK < O.

  public MyPkgOpsBatch(String pkgName, List<MyPackageOps> pkgOps, List<MyPackageOps> uidOps) {
    this.pkgName = pkgName;
    this.pkgOps = pkgOps;
    this.uidOps = uidOps;
  }

  protected MyPkgOpsBatch(Parcel in) {
    pkgName = in.readString();

    // Both lists carry an explicit presence flag. A failed package is reported with null lists,
    // which writeTypedList() encodes as -1; without the flag the reader could not tell that from
    // an empty list, and "failed" would silently turn into "this package has no ops".
    if (in.readInt() != 0) {
      pkgOps = in.createTypedArrayList(MyPackageOps.CREATOR);
    } else {
      pkgOps = null;
    }

    // uidOps is also null on SDK < O.
    if (in.readInt() != 0) {
      uidOps = in.createTypedArrayList(MyPackageOps.CREATOR);
    } else {
      uidOps = null;
    }
  }

  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(pkgName);
    dest.writeInt(pkgOps != null ? 1 : 0);
    if (pkgOps != null) {
      dest.writeTypedList(pkgOps);
    }
    dest.writeInt(uidOps != null ? 1 : 0);
    if (uidOps != null) {
      dest.writeTypedList(uidOps);
    }
  }

  public int describeContents() {
    return 0;
  }

  public static final Creator<MyPkgOpsBatch> CREATOR =
      new Creator<>() {
        public MyPkgOpsBatch createFromParcel(Parcel in) {
          return new MyPkgOpsBatch(in);
        }

        public MyPkgOpsBatch[] newArray(int size) {
          return new MyPkgOpsBatch[size];
        }
      };
}
