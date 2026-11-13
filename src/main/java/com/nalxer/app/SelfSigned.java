package com.nalxer.app;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 不依赖 keytool 的自签证书生成器。
 *
 * 为什么要有这个类：很多服务器跑的是 JRE（没有 keytool），或者 keytool 被安全策略
 * 禁掉，插件就永远生成不出 keystore.p12，接口直接不启动，表现就是「重启也不生成证书」。
 * 这里用纯 Java 手写 DER，自己签一张带 SAN 的 X.509 v3 证书，再用 JDK 自带的
 * PKCS12 KeyStore 落盘，JDK8 及以上都能跑。
 */
final class SelfSigned {

    private SelfSigned() {
    }

    /** 生成密钥对 + 自签证书，写进 keystore（PKCS12）。返回证书。 */
    static X509Certificate generate(File keystore, char[] password, String dn, String san)
            throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair pair = kpg.generateKeyPair();

        String cn = pickCn(dn);
        byte[] cert = buildCertificate(pair, cn, dn, san);
        X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("linuxapp", pair.getPrivate(), password,
                new Certificate[]{x509});
        File parent = keystore.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("建不了目录：" + parent);
        }
        try (FileOutputStream out = new FileOutputStream(keystore)) {
            ks.store(out, password);
        }
        return x509;
    }

    /** 从已有 keystore 里把证书导成 PEM，给手机安装用。 */
    static boolean exportPem(File keystore, char[] password, File pem) {
        try (InputStream in = new java.io.FileInputStream(keystore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            Certificate c = ks.getCertificate("linuxapp");
            if (c == null) {
                return false;
            }
            String b64 = base64(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            sb.append("-----BEGIN CERTIFICATE-----\n");
            for (int i = 0; i < b64.length(); i += 64) {
                sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
            }
            sb.append("-----END CERTIFICATE-----\n");
            try (FileOutputStream out = new FileOutputStream(pem)) {
                out.write(sb.toString().getBytes("UTF-8"));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static SSLContext sslContext(File keystore, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new java.io.FileInputStream(keystore)) {
            ks.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    // ── 证书构造 ────────────────────────────────────────────

    private static byte[] buildCertificate(KeyPair pair, String cn, String dn, String san)
            throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 3600 * 1000);
        Date notAfter = new Date(now + 3650L * 24 * 3600 * 1000);

        byte[] signatureAlg = seq(oid("1.2.840.113549.1.1.11"), derNull());

        Der tbs = new Der();
        tbs.write(seq(
                new byte[]{(byte) 0xA0, 0x03, 0x02, 0x01, 0x02},  // version v3
                integer(BigInteger.valueOf(now / 1000)),          // serial
                signatureAlg,
                name(dn, cn),
                validity(notBefore, notAfter),
                name(dn, cn),
                subjectPublicKeyInfo(pair.getPublic().getEncoded()),
                extensions(san)
        ));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs.toByteArray());
        byte[] sig = signer.sign();

        return seq(tbs.toByteArray(), signatureAlg, bitString(sig));
    }

    private static byte[] name(String dn, String cn) {
        List<byte[]> rdns = new ArrayList<>();
        String src = (dn == null || dn.trim().isEmpty()) ? "CN=" + cn : dn;
        for (String part : src.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            String key = eq < 0 ? "CN" : p.substring(0, eq).trim();
            String val = eq < 0 ? p : p.substring(eq + 1).trim();
            rdns.add(set(seq(oid(oidOf(key)), utf8(val))));
        }
        if (rdns.isEmpty()) {
            rdns.add(set(seq(oid(oidOf("CN")), utf8(cn))));
        }
        return seq(rdns.toArray(new byte[0][]));
    }

    private static String oidOf(String key) {
        String k = key.toUpperCase(Locale.ROOT);
        if ("CN".equals(k)) return "2.5.4.3";
        if ("C".equals(k)) return "2.5.4.6";
        if ("L".equals(k)) return "2.5.4.7";
        if ("ST".equals(k)) return "2.5.4.8";
        if ("O".equals(k)) return "2.5.4.10";
        if ("OU".equals(k)) return "2.5.4.11";
        if ("EMAILADDRESS".equals(k)) return "1.2.840.113549.1.9.1";
        return "2.5.4.3";
    }

    private static byte[] validity(Date notBefore, Date notAfter) {
        return seq(utcTime(notBefore), utcTime(notAfter));
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] body = f.format(d).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return new Der().tag(0x17, body);
    }

    private static byte[] subjectPublicKeyInfo(byte[] spki) {
        return spki; // Key.getEncoded() 已经是完整 DER
    }

    private static byte[] extensions(String san) {
        List<byte[]> exts = new ArrayList<>();

        exts.add(extension("2.5.29.19", true, seq(new byte[]{0x01, 0x01, (byte) 0xFF})));
        exts.add(extension("2.5.29.15", true, bitString(new byte[]{0x03}))); // 简化：CA 用的位串
        exts.add(extension("2.5.29.37", false,
                seq(oid("1.3.6.1.5.5.7.3.1"), oid("1.3.6.1.5.5.7.3.2"))));

        List<byte[]> names = new ArrayList<>();
        if (san != null) {
            for (String raw : san.split(",")) {
                String s = raw.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.regionMatches(true, 0, "DNS:", 0, 4)) {
                    names.add(new Der().tag(0x82, s.substring(4).trim()
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                } else if (s.regionMatches(true, 0, "IP:", 0, 3)) {
                    names.add(new Der().tag(0x87, ipBytes(s.substring(3).trim())));
                } else {
                    names.add(new Der().tag(0x82, s.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                }
            }
        }
        if (!names.isEmpty()) {
            exts.add(extension("2.5.29.17", false, seq(names.toArray(new byte[0][]))));
        }

        Der list = new Der();
        list.write(seq(exts.toArray(new byte[0][])));
        byte[] inner = list.toByteArray();
        Der wrapped = new Der();
        wrapped.write(inner);
        return new Der().tag(0xA3, inner);
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(oid(oid));
        if (critical) {
            parts.add(new byte[]{0x01, 0x01, (byte) 0xFF});
        }
        parts.add(new Der().tag(0x04, value));
        return seq(parts.toArray(new byte[0][]));
    }

    private static byte[] ipBytes(String ip) {
        String[] p = ip.split("\\.");
        if (p.length == 4) {
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                out[i] = (byte) Integer.parseInt(p[i]);
            }
            return out;
        }
        // IPv6 简化处理：调用方一般用域名或 IPv4
        return ip.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String pickCn(String dn) {
        if (dn != null) {
            for (String part : dn.split(",")) {
                String p = part.trim();
                if (p.regionMatches(true, 0, "CN=", 0, 3)) {
                    return p.substring(3).trim();
                }
            }
        }
        return "LinuxAPP";
    }

    // ── DER 小工具 ──────────────────────────────────────────

    private static byte[] seq(byte[]... items) {
        Der d = new Der();
        for (byte[] it : items) {
            d.write(it);
        }
        return d.tag(0x30, d.toByteArray());
    }

    private static byte[] set(byte[] item) {
        return new Der().tag(0x31, item);
    }

    private static byte[] oid(String oid) {
        String[] parts = oid.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long v = Long.parseLong(parts[i]);
            if (v < 128) {
                body.write((int) v);
            } else {
                byte[] stack = new byte[10];
                int n = 0;
                stack[n++] = (byte) (v & 0x7F);
                v >>= 7;
                while (v > 0) {
                    stack[n++] = (byte) ((v & 0x7F) | 0x80);
                    v >>= 7;
                }
                for (int k = n - 1; k >= 0; k--) {
                    body.write(stack[k]);
                }
            }
        }
        return new Der().tag(0x06, body.toByteArray());
    }

    private static byte[] utf8(String s) {
        return new Der().tag(0x0C, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] integer(BigInteger v) {
        return new Der().tag(0x02, v.toByteArray());
    }

    private static byte[] bitString(byte[] body) {
        byte[] all = new byte[body.length + 1];
        all[0] = 0x00;
        System.arraycopy(body, 0, all, 1, body.length);
        return new Der().tag(0x03, all);
    }

    private static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    private static String base64(byte[] data) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    /** 简易 DER 写入器：只管长度编码。 */
    private static final class Der {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void write(byte[] b) {
            out.write(b, 0, b.length);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        byte[] tag(int tag, byte[] body) {
            write((byte) tag);
            int len = body.length;
            if (len < 0x80) {
                write((byte) len);
            } else if (len <= 0xFF) {
                write((byte) 0x81);
                write((byte) len);
            } else if (len <= 0xFFFF) {
                write((byte) 0x82);
                write((byte) (len >> 8));
                write((byte) len);
            } else {
                write((byte) 0x83);
                write((byte) (len >> 16));
                write((byte) (len >> 8));
                write((byte) len);
            }
            write(body);
            return toByteArray();
        }

        private void write(byte b) {
            out.write(b);
        }
    }
}
