package app.svan

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One fixed, explicitly authorized development-permission grant, without launching a helper process. */
object ShizukuDetectionGrant {
    // Android's IBinder.SHELL_COMMAND_TRANSACTION ('_CMD'), stable on supported API 29–35.
    // Wire layout: AOSP BinderProxy.shellCommand / ShellCallback.writeToParcel.
    private const val SHELL_COMMAND = 0x5f434d44

    fun enable(context: Context): String {
        if (!Shizuku.pingBinder()) return "Shizuku disconnected before the detection grant. Start it and retry."
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "Allow Svan in Shizuku's Authorized applications, then retry."
        val binder = SystemServiceHelper.getSystemService("package") ?: return "Android's package service is unavailable."
        val done = CountDownLatch(1)
        var result = Int.MIN_VALUE
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { result = resultCode; done.countDown() }
        }
        val output = File.createTempFile("detection-grant-", ".txt", context.cacheDir)
        try {
            ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { log ->
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeFileDescriptor(input.fileDescriptor)
                        data.writeFileDescriptor(log.fileDescriptor)
                        data.writeFileDescriptor(log.fileDescriptor)
                        // No caller-supplied target, permission or shell command. Use the app's actual Android user.
                        data.writeStringArray(arrayOf("grant", "--user", (Process.myUid() / 100_000).toString(), "app.svan", "android.permission.DUMP"))
                        data.writeStrongBinder(null) // no ShellCallback/file-opening capability
                        receiver.writeToParcel(data, 0)
                        ShizukuBinderWrapper(binder).transact(SHELL_COMMAND, data, reply, 0)
                        reply.readException()
                    } finally { data.recycle(); reply.recycle() }
                }
            }
            if (!done.await(8, TimeUnit.SECONDS)) return "Android did not return a result for the detection grant. Share report for setup details."
            val detail = output.bufferedReader().use { reader ->
                val text = CharArray(800)
                val count = reader.read(text)
                if (count > 0) String(text, 0, count).trim() else ""
            }
            return if (result == 0) "OK" else "Android rejected detection access (code $result): $detail"
        } finally { output.delete() }
    }
}
