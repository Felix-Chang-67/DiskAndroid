# JNI：原生代码按名字找这些类和方法（native/diskplayer/android/dp_android.c）
-keep class io.github.felixchang67.diskandroid.core.player.NativeBridge {
    native <methods>;
    public static void log(int, java.lang.String, java.lang.String);
}
-keep interface io.github.felixchang67.diskandroid.core.player.SectorReader { *; }
-keep interface io.github.felixchang67.diskandroid.core.player.PlayerListener { *; }
-keep class * implements io.github.felixchang67.diskandroid.core.player.SectorReader {
    public int read(long, java.nio.ByteBuffer, int);
    public int scsi(byte[], int, byte[], byte[], int);
}
-keep class * implements io.github.felixchang67.diskandroid.core.player.PlayerListener {
    public void onEvent(int, int, int, java.lang.String);
}
