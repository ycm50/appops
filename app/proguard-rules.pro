# Preserve the line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Keep the line number information but hide the original source file name.
-renamesourcefileattribute SourceFile

# Move all obfuscated classes into the root package.
-repackageclasses
-allowaccessmodification

# Preference keys are accesses through reflection to reset filters and for backup / restore.
-keepclassmembers class com.mirfatif.permissionmanagerx.R$string {
  int pref_*_key;
}

# Throwable names must not be obfuscated to correctly print e.toString()
-keepnames class ** extends java.lang.Throwable

# Shizuku user service (the class Shizuku runs as shell / root on our behalf).
#
# Shizuku starts a process of its own, loads these classes out of our APK by name and instantiates
# them with reflection; from then on it talks to them over AIDL across the process boundary. None of
# that is visible to R8, which sees the whole user service as unreachable: it deletes every member
# of PmxUserService (leaving an empty abstract class that only keeps the name used by
# PmxUserService.class.getName()) and deletes IPmxUserService$Stub as well. The user service then
# cannot be created, so every bindUserService() call runs into Shizuku's start timeout and the app
# reports that Shizuku is not usable - while the permission itself is granted.
#
# The debug build is not minified (isMinifyEnabled / isShrinkResources are only set for release),
# which is why this only shows up in release builds.
-keep class com.mirfatif.permissionmanagerx.privs.PmxUserService { *; }
-keep interface com.mirfatif.permissionmanagerx.privs.IPmxUserService { *; }
-keep class com.mirfatif.permissionmanagerx.privs.IPmxUserService$Stub { *; }

# Privileged daemon IPC payloads (com.mirfatif.privtasks.bind).
#
# The app and the daemon exchange these Parcelables over the hand-written IPrivTasks Binder
# protocol. Every current read is a static CREATOR reference (createTypedArrayList /
# readTypedObject), so R8 can see them; keeping the package keeps the wire format independent of
# that reachability analysis. These objects are built on one side of a process boundary and
# consumed on the other, where a member R8 dropped only shows up as a runtime Parcel error.
#
# CREATOR needs no rule of its own: the default proguard-android-optimize.txt that apk-conventions
# puts on the release build type already includes proguard-common.txt, which keeps
# `public static final ** CREATOR` in every Parcelable. Class names need no -keepnames either:
# typed lists and readTypedObject never write a class name into the Parcel, and release builds
# disable obfuscation (postprocessing.obfuscate = false in app/build.gradle).
#
# IPrivTasks itself, including Stub and Proxy, is not kept on purpose: both ends instantiate and
# call it from code R8 can see, and onTransact is a virtual override of Binder, so it survives
# shrinking on its own. Only classes loaded by name need a rule: PmxUserService above and
# com.mirfatif.privdaemon.Main in priv_daemon/daemon-proguard-rules.pro.
-keep class com.mirfatif.privtasks.bind.** { *; }

-dontwarn io.github.muntashirakon.adb.AdbProtocol$AuthType
-dontwarn jakarta.annotation.Nullable
