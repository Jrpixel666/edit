package dev.apkforge.core
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import java.io.File

/** Installs base + all splits atomically via a PackageInstaller session (works for plain APKs too). */
object Installer {
  const val ACTION = "dev.apkforge.INSTALL_STATUS"
  fun install(ctx: Context, base: File, splits: List<File>) {
    val pi = ctx.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val id = pi.createSession(params)
    pi.openSession(id).use { s ->
      (listOf(base) + splits).forEachIndexed { i, f ->
        s.openWrite(if (i == 0) "base.apk" else f.name, 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) } }
      val intent = Intent(ACTION).setPackage(ctx.packageName)
      s.commit(PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
    }
  }
  /** Status receiver: launches the system confirm dialog, then reports result. */
  fun receiver(log: (String) -> Unit) = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
      when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
        PackageInstaller.STATUS_PENDING_USER_ACTION ->
          (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        PackageInstaller.STATUS_SUCCESS -> log("✔ installed")
        else -> log("✘ install failed ($st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}" +
          if (st == PackageInstaller.STATUS_FAILURE_CONFLICT) " — signature differs from installed copy; uninstall it first" else "")
      }
    }
  }
}
