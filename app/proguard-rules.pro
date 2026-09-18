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

-dontwarn io.github.muntashirakon.adb.AdbProtocol$AuthType
-dontwarn jakarta.annotation.Nullable
