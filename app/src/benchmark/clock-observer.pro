# Only the optional measurement APK keeps the Canvas character field for the draw observer.
-keepnames class com.ahao.clicker.ClockFace
-keepclassmembers class com.ahao.clicker.ClockFace { private char[] chars; }
# Read-only screenshot geometry, only in the optional benchmark APK.
-keepclassmembers class com.ahao.clicker.ClockFace { private android.graphics.Paint paint; private float textWidth; }
