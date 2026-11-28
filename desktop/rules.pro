# ProGuard for the packaged app. Only one thing is wanted from it: dropping the thousands of
# unused Material icons (about 38 MB). Everything else is kept as it is, since several libraries
# load classes by reflection or through native code, and shrinking them would risk breaking them.
-dontobfuscate
-dontoptimize
-dontwarn **
-dontnote **
-keepattributes *
-keep class !androidx.compose.material.icons.**, ** { *; }
