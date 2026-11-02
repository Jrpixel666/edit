package dev.apkforge.core
import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.Date

/** Signs with a per-device key (generated once). Use your own keystore to update installs of your real app. */
object Signer {
  fun sign(ctx: Context, input: File, output: File) {
    val ks = File(ctx.filesDir, "forge.p12"); val pw = "apkforge".toCharArray()
    val store = KeyStore.getInstance("PKCS12")
    if (ks.exists()) ks.inputStream().use { store.load(it, pw) } else {
      store.load(null, null)
      val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
      val name = X500Name("CN=ApkForge"); val now = System.currentTimeMillis()
      val cert = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now),
        Date(now + 30L * 365 * 864e5.toLong()), name, kp.public).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)))
      store.setKeyEntry("forge", kp.private, pw, arrayOf(cert)); ks.outputStream().use { store.store(it, pw) }
    }
    val key = store.getKey("forge", pw) as PrivateKey; val cert = store.getCertificate("forge") as X509Certificate
    ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("forge", key, listOf(cert)).build()))
      .setInputApk(input).setOutputApk(output).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
      .build().sign()
  }
}
