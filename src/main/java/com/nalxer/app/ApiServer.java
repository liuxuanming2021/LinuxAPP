package com.nalxer.app;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置 HTTP 接口。只监听 127.0.0.1，由 Nginx 反代成 https://api.nalxer.top。
 *
 * <pre>
 * GET  /api/health                 服务状态（不需要登录）
 * POST /api/login                  {name,password} → {token,...}
 * GET  /api/me                     自己的数据（Bearer 令牌）
 * GET  /api/me?name=xxx            管理员可查别人
 * GET  /api/online                 在线玩家列表（要登录）
 * POST /api/admin/ban              {name,reason,minutes} 管理员
 * POST /api/admin/unban            {name} 管理员
 * GET  /api/admin/bans             封禁列表 管理员
 * GET  /api/admin/search?q=xxx     模糊找人 管理员
 * </pre>
 */
public final class ApiServer implements HttpHandler {
    // ── 二次身份验证（ITNOL 保护账号）────────────────────
    private final java.util.Map<String, Long> verifiedTokens =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private final java.util.Map<String, long[]> verifyFails =
            new java.util.concurrent.ConcurrentHashMap<String, long[]>();


    private final NalxerApp plugin;
    private HttpServer server;
    private HttpServer secureServer;
    private ExecutorService pool;

    /** IP → 窗口计数 */
    private final Map<String, int[]> loginHits = new HashMap<String, int[]>();
    private final Map<String, int[]> apiHits = new HashMap<String, int[]>();
    private long windowStart = System.currentTimeMillis() / 60000L;

    /** 端到端附加层：App 与插件之间再做一层 AES-GCM，中间代理只看到密文 */
    private static final java.util.Map<String, EncSession> ENC_SESSIONS =
            new java.util.concurrent.ConcurrentHashMap<String, EncSession>();

    private static final class EncSession {
        final SecretKey key;
        final String ip;
        long expires;

        EncSession(SecretKey key, String ip) {
            this.key = key;
            this.ip = ip;
            this.expires = System.currentTimeMillis() + 30L * 60L * 1000L;
        }
    }

    private PrivateKey serverKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        String pass = plugin.getConfig().getString("http.keystore-password", "");
        try (FileInputStream in = new FileInputStream(keystoreFile())) {
            ks.load(in, pass.toCharArray());
        }
        return (PrivateKey) ks.getKey("linuxapp", pass.toCharArray());
    }

    private PublicKey serverPub() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        String pass = plugin.getConfig().getString("http.keystore-password", "");
        try (FileInputStream in = new FileInputStream(keystoreFile())) {
            ks.load(in, pass.toCharArray());
        }
        java.security.cert.Certificate c = ks.getCertificate("linuxapp");
        return c == null ? null : c.getPublicKey();
    }

    private EncSession encOf(HttpExchange ex) {
        Object a = ex.getAttribute("enc");
        if (!(a instanceof EncSession)) {
            return null;
        }
        EncSession e = (EncSession) a;
        if (e.expires < System.currentTimeMillis()) {
            return null;
        }
        return e;
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }

    private static byte[] encGcm(SecretKey key, byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static byte[] decGcm(SecretKey key, byte[] data) throws Exception {
        byte[] iv = new byte[12];
        System.arraycopy(data, 0, iv, 0, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return c.doFinal(data, 12, data.length - 12);
    }

    /** GET /api/session：给 App 公钥；POST /api/session：收 AES 会话密钥 */
    private void session(HttpExchange ex, String method, String body) {
        try {
            if ("GET".equals(method)) {
                PublicKey pub = serverPub();
                if (pub == null) {
                    send(ex, 500, obj(false, "服务端没有证书，无法建立加密通道"));
                    return;
                }
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("ok", Boolean.TRUE);
                out.put("alg", "RSA-OAEP-256");
                out.put("pub", b64(pub.getEncoded()));
                out.put("ttl", Long.valueOf(1800L));
                send(ex, 200, Json.write(out));
                return;
            }
            Map<String, String> in = Json.parseFlat(body);
            String id = in == null ? null : in.get("id");
            String key = in == null ? null : in.get("key");
            if (id == null || key == null || id.length() < 8 || id.length() > 64) {
                send(ex, 400, obj(false, "会话参数不对"));
                return;
            }
            Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
            c.init(Cipher.DECRYPT_MODE, serverKey(), new javax.crypto.spec.OAEPParameterSpec(
                    "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
                    javax.crypto.spec.PSource.PSpecified.DEFAULT));
            byte[] raw = c.doFinal(unb64(key));
            if (raw.length != 32) {
                send(ex, 400, obj(false, "会话密钥长度不对"));
                return;
            }
            ENC_SESSIONS.put(id, new EncSession(new SecretKeySpec(raw, "AES"), clientIp(ex)));
            if (plugin.getConfig().getBoolean("security.log-bodies", false)) {
                plugin.getLogger().info("加密会话已建立：" + id);
            }
            send(ex, 200, obj(true, "加密通道已建立"));
        } catch (Exception e) {
            send(ex, 500, obj(false, "建立加密通道失败：" + e.getMessage()));
        }
    }

    public ApiServer(NalxerApp plugin) {
        this.plugin = plugin;
    }

    // ── 启停 ────────────────────────────────────────────────

    public void start() throws Exception {
        String host = plugin.getConfig().getString("http.host", "0.0.0.0");
        int port = plugin.getConfig().getInt("http.port", 49282);
        int threads = Math.max(2, plugin.getConfig().getInt("http.threads", 8));

        boolean wantTls = plugin.getConfig().getBoolean("http.tls", true);
        boolean needKeys = wantTls || plugin.getConfig().getBoolean("security.require-encryption", true);
        if (needKeys) {
            // 即使只开明文 HTTP，也要有 RSA 密钥对才能做应用层加密（/api/session 用它换 AES 密钥）
            ensureKeystore();
        }
        boolean tls = wantTls && tlsReady();
        boolean allowPlain = plugin.getConfig().getBoolean("security.allow-plaintext", false);
        int tlsPort = plugin.getConfig().getInt("http.tls-port", port + 1);

        if (!tls && !allowPlain) {
            plugin.getLogger().severe("========================================================");
            plugin.getLogger().severe(" 没有可用的 TLS 证书，且 security.allow-plaintext = false。");
            plugin.getLogger().severe(" 为保护账号与聊天内容，接口不会以明文 HTTP 启动。");
            plugin.getLogger().severe(" 解决：http.tls 设为 true（插件会自动生成自签证书），");
            plugin.getLogger().severe("       或把证书放到 " + keystoreFile().getAbsolutePath());
            plugin.getLogger().severe("========================================================");
            return;
        }

        if (tls) {
          try {
            HttpsServer https = HttpsServer.create(new InetSocketAddress(host, tlsPort), 64);
            final SSLContext sslContext = buildSslContext();
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(com.sun.net.httpserver.HttpsParameters params) {
                    SSLParameters sp = sslContext.getDefaultSSLParameters();
                    SSLParameters sup = sslContext.getSupportedSSLParameters();
                    java.util.List<String> protos = new java.util.ArrayList<String>();
                    for (String pr : sup.getProtocols()) {
                        if ("TLSv1.3".equals(pr) || "TLSv1.2".equals(pr)) {
                            protos.add(pr);
                        }
                    }
                    if (!protos.isEmpty()) {
                        sp.setProtocols(protos.toArray(new String[0]));
                    }
                    java.util.Set<String> okCiphers = new java.util.HashSet<String>(
                            java.util.Arrays.asList(sup.getCipherSuites()));
                    java.util.List<String> ciphers = new java.util.ArrayList<String>();
                    for (String c : STRONG_CIPHERS) {
                        if (okCiphers.contains(c)) {
                            ciphers.add(c);
                        }
                    }
                    if (!ciphers.isEmpty()) {
                        sp.setCipherSuites(ciphers.toArray(new String[0]));
                    }
                    sp.setNeedClientAuth(false);
                    params.setSSLParameters(sp);
                }
            });
            https.createContext("/", this);
            secureServer = https;
          } catch (Exception e) {
            plugin.getLogger().warning("HTTPS 端口 " + tlsPort + " 绑定失败：" + e.getMessage() + "（继续尝试 HTTP）");
          }
        }
        if (allowPlain) {
          try {
            HttpServer plain = HttpServer.create(new InetSocketAddress(host, port), 64);
            plain.createContext("/", this);
            server = plain;
          } catch (Exception e) {
            plugin.getLogger().warning("HTTP 端口 " + port + " 绑定失败：" + e.getMessage());
          }
        }

        pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "NalxerApp-Http-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        if (secureServer == null && server == null) {
            plugin.getLogger().severe("HTTP 与 HTTPS 端口都没能绑定，接口未启动（检查端口占用）。");
            return;
        }
        if (secureServer != null) {
            secureServer.setExecutor(pool);
            secureServer.start();
            plugin.getLogger().info("接口已启动（HTTPS）：https://" + host + ":" + tlsPort + "/api/health");
            plugin.getLogger().info("证书：" + keystoreFile().getName() + "，SHA-256 指纹 " + fingerprint());
        }
        if (server != null) {
            server.setExecutor(pool);
            server.start();
            boolean enc = plugin.getConfig().getBoolean("security.require-encryption", true);
            plugin.getLogger().info("接口已启动（HTTP）：http://" + host + ":" + port + "/api/health"
                    + (enc ? "（明文传输，但接口强制应用层加密）" : "（明文传输，未强制加密）"));
        }
        warnAuthMeStorage();
    }

    /** 只用 AEAD / 前向保密套件，TLS 1.2+ */
    private static final String[] STRONG_CIPHERS = new String[]{
            "TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256", "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
    };

    /** 没有证书就自己签一张（PKCS12，10 年），并把公钥导成 server.crt 供手机安装 */
    private void ensureKeystore() {
        File f = keystoreFile();
        String dn = plugin.getConfig().getString("http.cert-dn", "CN=LinuxAPP");
        String san = plugin.getConfig().getString("http.cert-san", "");
        if (san == null) san = "";
        if (san.trim().isEmpty()) {
            san = autoSan();
            plugin.getLogger().info("http.cert-san 没填，自动把本机地址写进证书：" + san);
        }
        String want = dn + "|" + san;
        File mark = new File(plugin.getDataFolder(), "cert-info.txt");
        String have = "";
        if (mark.isFile()) {
            try {
                byte[] buf = java.nio.file.Files.readAllBytes(mark.toPath());
                have = new String(buf, "UTF-8").trim();
            } catch (Throwable ignored) {
            }
        }
        boolean exists = f.isFile();
        boolean stale = exists && !want.equals(have);
        if (exists && !stale) {
            File crt0 = new File(plugin.getDataFolder(), "server.crt");
            if (!crt0.isFile()) {
                String p0 = plugin.getConfig().getString("http.keystore-password", "");
                SelfSigned.exportPem(f, p0 == null ? new char[0] : p0.toCharArray(), crt0);
                plugin.getLogger().info("证书还在，但 server.crt 丢了，已重新导出：" + crt0.getName());
            }
            return;
        }
        if (stale) {
            plugin.getLogger().warning("证书配置变了（cert-dn / cert-san），重新生成证书：" + want);
            if (!f.delete()) {
                plugin.getLogger().warning("旧证书删不掉，先继续用旧的：" + f.getAbsolutePath());
                return;
            }
        }
        String pass = plugin.getConfig().getString("http.keystore-password", "");
        if (pass == null || pass.trim().isEmpty()) {
            pass = Long.toHexString(new java.security.SecureRandom().nextLong()) + "Aa1";
            plugin.getConfig().set("http.keystore-password", pass);
            plugin.saveConfig();
            plugin.getLogger().info("已生成 http.keystore-password（写进 config.yml，备份时注意）");
        }
        boolean ok = false;
        try {
            buildWithKeytool(f, pass, dn, san);
            ok = f.isFile();
        } catch (Throwable t) {
            plugin.getLogger().warning("keytool 生成证书失败（" + t + "），改用内置生成器");
        }
        if (!ok) {
            try {
                java.security.cert.X509Certificate cert =
                        SelfSigned.generate(f, pass.toCharArray(), dn, san);
                ok = true;
                plugin.getLogger().info("已用内置生成器签发自签证书（SAN：" + (san.isEmpty() ? "无" : san) + "）");
            } catch (Throwable t) {
                plugin.getLogger().severe("内置生成器也失败了：" + t);
                t.printStackTrace();
            }
        }
        if (!ok || !f.isFile()) {
            plugin.getLogger().severe("自动生成证书失败：没有产出 " + f.getAbsolutePath());
            return;
        }
        File crt = new File(plugin.getDataFolder(), "server.crt");
        if (!SelfSigned.exportPem(f, pass.toCharArray(), crt)) {
            plugin.getLogger().warning("导出 server.crt 失败，可以用 keytool -exportcert 手动导");
        }
        try {
            java.nio.file.Files.write(mark.toPath(), want.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
        plugin.getLogger().info("已自动生成自签证书：" + f.getName() + "（手机要装同目录 server.crt 并信任）");
    }

    /** 给 /linuxapp cert regen 用：删掉旧证书与标记，重新生成一套 */
    public void regenerateCertificate() {
        File f = keystoreFile();
        File mark = new File(plugin.getDataFolder(), "cert-info.txt");
        if (f.isFile() && !f.delete()) {
            plugin.getLogger().warning("旧证书删不掉：" + f.getAbsolutePath());
        }
        if (mark.isFile()) {
            mark.delete();
        }
        ensureKeystore();
    }

    /** 证书的简要信息，给 /linuxapp cert 用 */
    public String certificateInfo() {
        File f = keystoreFile();
        File crt = new File(plugin.getDataFolder(), "server.crt");
        String pass = plugin.getConfig().getString("http.keystore-password", "");
        String san = plugin.getConfig().getString("http.cert-san", "");
        String nl = String.valueOf((char) 10);
        StringBuilder sb = new StringBuilder();
        sb.append("证书文件: ").append(f.getAbsolutePath()).append(f.isFile() ? "（存在）" : "（不存在）");
        sb.append(nl).append("公钥文件: ").append(crt.getAbsolutePath()).append(crt.isFile() ? "（存在）" : "（不存在）");
        sb.append(nl).append("配置 SAN: ").append(san == null || san.isEmpty() ? "（空，手机按 IP 连会报名称不匹配）" : san);
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(f)) {
                ks.load(in, pass.toCharArray());
            }
            java.security.cert.Certificate c = ks.getCertificate("linuxapp");
            if (c instanceof java.security.cert.X509Certificate) {
                java.security.cert.X509Certificate x = (java.security.cert.X509Certificate) c;
                sb.append(nl).append("有效期至: ").append(x.getNotAfter());
                sb.append(nl).append("SAN: ").append(x.getSubjectAlternativeNames());
            }
        } catch (Throwable t) {
            sb.append(nl).append("读取证书失败: ").append(t);
        }
        return sb.toString();
    }

    /** 没填 cert-san 时，把本机所有 IPv4 地址 + localhost 写进证书，手机用 IP 直连才不会报名称不匹配 */
    private String autoSan() {
        StringBuilder sb = new StringBuilder("IP:127.0.0.1,DNS:localhost");
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address) {
                        String ip = a.getHostAddress();
                        if (ip != null && !sb.toString().contains("IP:" + ip)) {
                            sb.append(",IP:").append(ip);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private void buildWithKeytool(File f, String pass, String dn, String san) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<String>();
        cmd.add(keytool());
        cmd.add("-genkeypair");
        cmd.add("-alias");
        cmd.add("linuxapp");
        cmd.add("-keyalg");
        cmd.add("RSA");
        cmd.add("-keysize");
        cmd.add("2048");
        cmd.add("-validity");
        cmd.add("3650");
        cmd.add("-storetype");
        cmd.add("PKCS12");
        cmd.add("-keystore");
        cmd.add(f.getAbsolutePath());
        cmd.add("-storepass");
        cmd.add(pass);
        cmd.add("-keypass");
        cmd.add(pass);
        cmd.add("-dname");
        cmd.add(dn);
        if (san != null && !san.trim().isEmpty()) {
            cmd.add("-ext");
            cmd.add("SAN=" + san.trim());
        }
        int code = runTool(cmd);
        if (code != 0 || !f.isFile()) {
            throw new IllegalStateException("keytool 退出码 " + code);
        }
    }

    private String keytool() {
        String home = System.getProperty("java.home", "");
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        File k = new File(new File(home, "bin"), win ? "keytool.exe" : "keytool");
        return k.isFile() ? k.getAbsolutePath() : "keytool";
    }

    private int runTool(java.util.List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            plugin.getLogger().info("[keytool] " + line.trim());
        }
        return p.waitFor();
    }

    /** 证书 SHA-256 指纹（让用户核对用） */
    private String fingerprint() {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            String pass = plugin.getConfig().getString("http.keystore-password", "");
            try (FileInputStream in = new FileInputStream(keystoreFile())) {
                ks.load(in, pass.toCharArray());
            }
            java.security.cert.Certificate c = ks.getCertificate("linuxapp");
            if (c == null) {
                return "(未找到)";
            }
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(读不到：" + t.getMessage() + ")";
        }
    }

    /** AuthMe 的库文件只看权限、不读内容；权限过宽时给 chmod 建议 */
    private void warnAuthMeStorage() {
        if (!plugin.getConfig().getBoolean("security.protect-authme", true)) {
            return;
        }
        try {
            File dir = new File(plugin.getDataFolder().getParentFile(), "AuthMe");
            File db = new File(dir, "authme.db");
            if (!db.isFile()) {
                return;
            }
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perm =
                    java.nio.file.Files.getPosixFilePermissions(db.toPath());
            boolean loose = perm.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                    || perm.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)
                    || perm.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                    || perm.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
            if (loose) {
                plugin.getLogger().warning("AuthMe 数据库权限过宽：" + db.getPath()
                        + " —— 建议 chmod 600，并把 plugins/AuthMe 目录 chmod 700");
            }
        } catch (Throwable ignored) {
        }
    }

    /** p12 证书文件：plugins/NalxerApp/<http.keystore> */
    private File keystoreFile() {
        String name = plugin.getConfig().getString("http.keystore", "keystore.p12");
        File f = new File(name);
        return f.isAbsolute() ? f : new File(plugin.getDataFolder(), name);
    }

    private boolean tlsReady() {
        File f = keystoreFile();
        if (!f.isFile()) {
            plugin.getLogger().warning("http.tls 已开启，但证书文件不存在：" + f.getAbsolutePath());
            return false;
        }
        String pass = plugin.getConfig().getString("http.keystore-password", "");
        if (pass == null || pass.isEmpty()) {
            plugin.getLogger().warning("http.tls 已开启，但 http.keystore-password 是空的");
            return false;
        }
        return true;
    }

    private SSLContext buildSslContext() throws Exception {
        String pass = plugin.getConfig().getString("http.keystore-password", "");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keystoreFile())) {
            ks.load(in, pass.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    public void stop() {
        if (secureServer != null) {
            secureServer.stop(0);
            secureServer = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    public boolean isRunning() {
        return server != null || secureServer != null;
    }

    // ── 路由 ────────────────────────────────────────────────

    @Override
    public void handle(HttpExchange ex) {
        try {
            Headers h = ex.getResponseHeaders();
            h.add("Access-Control-Allow-Origin", "*");
            h.add("Access-Control-Allow-Headers", "Authorization,Content-Type");
            h.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            h.add("Content-Type", "application/json; charset=utf-8");
            h.add("Cache-Control", "no-store");
            h.add("Pragma", "no-cache");
            h.add("X-Content-Type-Options", "nosniff");
            h.add("X-Frame-Options", "DENY");
            h.add("Referrer-Policy", "no-referrer");
            h.add("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
            if (ex instanceof com.sun.net.httpserver.HttpsExchange) {
                h.add("Strict-Transport-Security", "max-age=31536000");
            }

            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            String ip = clientIp(ex);
            String path = ex.getRequestURI().getPath();
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            String method = ex.getRequestMethod().toUpperCase();

            String sid = ex.getRequestHeaders().getFirst("X-Session");
            if (sid != null && "1".equals(ex.getRequestHeaders().getFirst("X-Enc"))) {
                EncSession es = ENC_SESSIONS.get(sid);
                if (es != null && es.expires > System.currentTimeMillis()) {
                    es.expires = System.currentTimeMillis() + 30L * 60L * 1000L;
                    ex.setAttribute("enc", es);
                }
            }

            boolean requireEnc = plugin.getConfig().getBoolean("security.require-encryption", true);
            if (requireEnc && encOf(ex) == null
                    && !"/api/health".equals(path) && !"/api/session".equals(path)) {
                send(ex, 426, obj(false, "这个接口只接受加密请求（请升级到支持加密通道的 App）"));
                return;
            }

            String body = readBody(ex);

            if (!allowed(ip, path.startsWith("/api/login"), path)) {
                send(ex, 429, obj(false, "请求太频繁，请稍后再试"));
                return;
            }

            if ("/api/health".equals(path)) {
                health(ex);
                return;
            }
            if ("/api/session".equals(path)) {
                session(ex, method, body);
                return;
            }
            if ("/api/login".equals(path)) {
                if (!"POST".equals(method)) {
                    send(ex, 405, obj(false, "请用 POST"));
                    return;
                }
                login(ex, ip, body);
                return;
            }
            if (path.startsWith("/api/")) {
                Tokens.Payload token = auth(ex);
                if (token == null) {
                    String why = authReason == null || authReason.isEmpty()
                            ? "登录已过期，请重新登录" : authReason;
                    Map<String, Object> deny = new LinkedHashMap<String, Object>();
                    deny.put("ok", Boolean.FALSE);
                    deny.put("code", authReason != null && authReason.contains("另一台设备") ? "session_kicked" : "unauthorized");
                    deny.put("message", why);
                    send(ex, 401, Json.write(deny));
                    return;
                }
                if ("/api/verify".equals(path)) {
                    if (!"POST".equals(method)) {
                        send(ex, 405, obj(false, "请用 POST"));
                        return;
                    }
                    verifyIdentity(ex, token, body);
                    return;
                }
                if (needsVerify(token.name) && !isVerified(token)
                        && !"/api/me".equals(path) && !"/api/logout".equals(path)) {
                    Map<String, Object> gate = new LinkedHashMap<String, Object>();
                    gate.put("ok", Boolean.FALSE);
                    gate.put("code", "need_verify");
                    gate.put("verifyRequired", Boolean.TRUE);
                    gate.put("message", "请先完成身份验证");
                    send(ex, 403, Json.write(gate));
                    return;
                }
                if ("/api/logout".equals(path)) {
                    plugin.sessions().end(token.name);
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("ok", Boolean.TRUE);
                    out.put("message", "已退出登录，可以在其他设备登录了");
                    send(ex, 200, Json.write(out));
                    return;
                }
                if ("/api/me".equals(path)) {
                    me(ex, token, query.get("name"));
                    return;
                }
                if ("/api/online".equals(path)) {
                    online(ex);
                    return;
                }
                if ("/api/players".equals(path)) {
                    players(ex);
                    return;
                }
                if ("/api/chat".equals(path)) {
                    if ("POST".equals(method)) {
                        chatPost(ex, token, body);
                    } else {
                        chatGet(ex, query);
                    }
                    return;
                }
                if ("/api/team".equals(path)) {
                    if ("POST".equals(method)) {
                        teamPost(ex, token, body);
                    } else {
                        teamGet(ex, token, query);
                    }
                    return;
                }
                if ("/api/titles".equals(path)) {
                    if ("POST".equals(method)) {
                        titlePost(ex, token, body);
                    } else {
                        titleGet(ex, token, query);
                    }
                    return;
                }
                if ("/api/transfer".equals(path)) {
                    if ("GET".equals(method)) {
                        java.util.Map<String, Object> bal = plugin.hooks().transferBalance(token.name);
                        java.util.Map<String, Object> obj = new java.util.LinkedHashMap<String, Object>();
                        obj.put("ok", Boolean.TRUE);
                        obj.put("available", bal.get("available"));
                        obj.put("coins", bal.get("coins"));
                        obj.put("points", bal.get("points"));
                        send(ex, 200, Json.write(obj));
                    } else {
                        transferPost(ex, token, body);
                    }
                    return;
                }
                if ("/api/checkin".equals(path)) {
                    if ("POST".equals(method)) {
                        checkinPost(ex, token);
                    } else {
                        checkinGet(ex, token);
                    }
                    return;
                }
                if ("/api/reports".equals(path)) {
                    reportsMine(ex, token);
                    return;
                }
                if ("/api/report".equals(path)) {
                    if (!"POST".equals(method)) {
                        send(ex, 405, obj(false, "请用 POST"));
                        return;
                    }
                    reportCreate(ex, token, body);
                    return;
                }
                if (path.startsWith("/api/admin/")) {
                    if (!canManage(token)) {
                        send(ex, 403, obj(false, "没有管理权限"));
                        return;
                    }
                    admin(ex, path, method, query, body, token);
                    return;
                }
            }
            send(ex, 404, obj(false, "没有这个接口"));
        } catch (Throwable t) {
            plugin.getLogger().warning("[接口] 处理请求出错：" + t);
            try {
                send(ex, 500, obj(false, "服务器内部错误"));
            } catch (Exception ignored) {
                // 响应已经发出去或连接断了
            }
        } finally {
            ex.close();
        }
    }

    // ── 具体接口 ────────────────────────────────────────────

    private void health(HttpExchange ex) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", Boolean.TRUE);
        m.put("app", "LinuxAPP");
        m.put("version", plugin.getDescription().getVersion());
        m.put("time", Long.valueOf(System.currentTimeMillis()));
        Map<String, Object> caps = new LinkedHashMap<String, Object>();
        caps.put("authme", Boolean.valueOf(plugin.hooks().hasAuthMe()));
        caps.put("coins", Boolean.valueOf(plugin.hooks().hasEconomy()));
        caps.put("points", Boolean.valueOf(plugin.hooks().hasPoints()));
        caps.put("bans", Boolean.valueOf(plugin.hooks().hasBans()));
        caps.put("teams", Boolean.valueOf(plugin.hooks().hasTeams()));
        caps.put("titles", Boolean.valueOf(plugin.hooks().hasTitles()));
        caps.put("identity", Boolean.valueOf(plugin.hooks().hasIdentity()));
        caps.put("xland", Boolean.valueOf(plugin.hooks().hasXland()));
        m.put("capabilities", caps);
        // 二次验证 / su 的实际生效情况，排查「为什么没弹验证」时先看这里
        Map<String, Object> verify = new LinkedHashMap<String, Object>();
        verify.put("enabled", Boolean.valueOf(plugin.getConfig().getBoolean("verify.enabled", true)));
        verify.put("players", verifyPlayers());
        verify.put("identity", Boolean.valueOf(plugin.hooks().hasIdentity()));
        verify.put("identityTarget", plugin.hooks().identityTarget());
        m.put("verify", verify);
        m.put("su", suPlayers());
        send(ex, 200, Json.write(m));
    }


    // ── 二次身份验证 ───────────────────────────────────────

    /** 这个号要不要二次验证（默认只对 ITNOL 的保护账号要求）。 */
    private boolean needsVerify(String name) {
        // 1.7.0：身份验证一直没用，整套关掉（配置里留 verify.enabled: false 作记录）
        if (true) return false;
        if (!plugin.getConfig().getBoolean("verify.enabled", true)) return false;
        if (name == null || name.isEmpty()) return false;
        if (!plugin.hooks().hasIdentity()) return false;
        List<String> list = plugin.getConfig().getStringList("verify.players");
        if (list == null || list.isEmpty()) {
            String target = plugin.hooks().identityTarget();
            return target != null && target.equalsIgnoreCase(name);
        }
        for (String s : list) {
            if (s != null && s.trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** 弹窗里要写的账号名：一般是配置里列的那个受保护号，取不到就用自己 */
    private String verifyTargetName(String name) {
        String target = plugin.hooks().identityTarget();
        if (target == null || target.trim().isEmpty()) {
            target = name;
        }
        return target;
    }

    private String verifyKey(Tokens.Payload token) {
        return token.name.toLowerCase(java.util.Locale.ROOT) + "|" + token.expiresAt;
    }

    private boolean isVerified(Tokens.Payload token) {
        if (!needsVerify(token.name)) return true;
        return verifiedTokens.containsKey(verifyKey(token));
    }

    private void verifyIdentity(HttpExchange ex, Tokens.Payload token, String body) {
        if (!needsVerify(token.name)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("message", "这个号不需要二次验证");
            out.put("verified", Boolean.TRUE);
            send(ex, 200, Json.write(out));
            return;
        }
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        String pass = in == null ? null : in.get("password");
        if (pass == null || pass.isEmpty()) {
            send(ex, 400, obj(false, "请输入 ITNOL 的验证密码"));
            return;
        }
        String key = verifyKey(token);
        long[] st = verifyFails.get(key);
        long now = System.currentTimeMillis();
        if (st != null && st[0] >= 5 && now < st[1]) {
            long left = (st[1] - now + 59999L) / 60000L;
            send(ex, 429, obj(false, "密码错太多次了，" + left + " 分钟后再试"));
            return;
        }
        boolean ok = plugin.hooks().identityCheck(pass);
        if (!ok) {
            long[] s2 = st == null ? new long[]{0L, 0L} : st;
            s2[0] = s2[0] + 1;
            if (s2[0] >= 5) s2[1] = now + 10L * 60L * 1000L;
            verifyFails.put(key, s2);
            audit(token.name + " App 二次验证 → 密码错误（第 " + s2[0] + " 次）");
            String msg = s2[0] >= 5
                    ? "密码不对，已锁定 10 分钟"
                    : "密码不对（再错 " + (5 - s2[0]) + " 次就要锁 10 分钟）";
            send(ex, 400, obj(false, msg));
            return;
        }
        verifyFails.remove(key);
        verifiedTokens.put(key, Long.valueOf(now));
        audit(token.name + " App 二次验证 → 通过");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("verified", Boolean.TRUE);
        out.put("message", "验证通过，所有功能已解锁");
        send(ex, 200, Json.write(out));
    }

    private void login(HttpExchange ex, String ip, String body) {
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        if (in == null) {
            send(ex, 400, obj(false, "请求格式不对"));
            return;
        }
        String name = trim(in.get("name"));
        String pass = in.get("password");
        boolean needPassword = plugin.getConfig().getBoolean("login.require-authme", true);
        if (name.isEmpty() || (needPassword && (pass == null || pass.isEmpty()))) {
            send(ex, 400, obj(false, needPassword ? "请填写游戏 ID 和密码" : "请填写游戏 ID"));
            return;
        }
        // ① 这台设备/这个 IP 先看冷却（5 次 / 分钟 → 冷却 30 秒）
        long ipWait = ipCooldownLeft(ip);
        if (ipWait > 0L) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.FALSE);
            out.put("code", "login_cooldown");
            out.put("retryAfter", Long.valueOf(ipWait));
            out.put("message", "登录太频繁了，休息 " + ipWait + " 秒再试");
            send(ex, 429, Json.write(out));
            return;
        }
        String resolved = plugin.hooks().resolveName(name);
        // ② 这个账号被锁了没有（连续失败 10 次 → 5 分钟）
        long accWait = accountLockLeft(resolved);
        if (accWait > 0L) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.FALSE);
            out.put("code", "login_locked");
            out.put("retryAfter", Long.valueOf(accWait));
            out.put("message", "这个账号密码错太多次了，锁 " + accWait + " 秒；稍后再试，或去游戏里改密码");
            send(ex, 429, Json.write(out));
            return;
        }
        // ③ 这个 IP 短时间登录次数太多（60 秒 5 次）→ 冷却 30 秒
        if (hitIp(ip)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.FALSE);
            out.put("code", "login_cooldown");
            out.put("retryAfter", Long.valueOf(IP_COOLDOWN_MS / 1000L));
            out.put("message", "登录太频繁了，休息 30 秒再试");
            send(ex, 429, Json.write(out));
            return;
        }
        // login.require-authme = true（默认）：密码交给 AuthMe 校验
        // login.require-authme = false：不校验密码，但只放行「此刻正在游戏里在线」的玩家
        //   （正版服 / 没装 AuthMe 的服用这个；代价是知道别人 ID 的人也能在对方在线时登进去）
        boolean useAuthMe = plugin.getConfig().getBoolean("login.require-authme", true);
        boolean ok;
        if (useAuthMe) {
            if (!plugin.hooks().hasAuthMe()) {
                send(ex, 503, obj(false, "服务器没装 AuthMe，暂时不能用游戏密码登录"));
                return;
            }
            ok = plugin.hooks().checkPassword(resolved, pass);
        } else {
            Player online = Bukkit.getPlayerExact(resolved);
            ok = online != null;
            if (!ok) {
                audit(ip + " 登录 " + name + " → 拒绝（不在线）");
                send(ex, 401, obj(false, "服务器没开密码校验，只允许在线玩家登录；请先进入游戏再来"));
                return;
            }
        }
        audit(ip + " 登录 " + name + " → " + (ok ? "成功" : "密码错误"));
        if (!ok) {
            int left = noteFail(resolved);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.FALSE);
            if (left < 0) {
                out.put("code", "login_locked");
                out.put("retryAfter", Long.valueOf(ACCOUNT_LOCK_MS / 1000L));
                out.put("message", "密码错太多次了，这个账号锁 5 分钟；稍后再试，或去游戏里改密码");
                send(ex, 429, Json.write(out));
                return;
            }
            out.put("message", "游戏 ID 或密码不对（就是进游戏用的那个密码）");
            if (left <= 5) {           // 失败 5 次起，每次都提醒还剩几次
                out.put("remaining", Integer.valueOf(left));
                out.put("message", "游戏 ID 或密码不对（就是进游戏用的那个密码）。"
                        + "还能再试 " + left + " 次，用完锁 5 分钟");
            }
            send(ex, 401, Json.write(out));
            return;
        }
        clearFail(resolved);
        // 单设备登录：同一账号同一时间只能有一枚有效令牌。
        // ★ 不允许「登录时把别人踢掉」：只能在原设备主动退出，
        //   或管理员在控制台执行 /nalxerapp logout <玩家名> 释放。
        if (plugin.sessions().isBusy(resolved)) {
            Map<String, Object> busy = new LinkedHashMap<String, Object>();
            busy.put("ok", Boolean.FALSE);
            busy.put("code", "session_taken");
            busy.put("message", "这个账号已经在另一台设备上登录了。要么在那台设备上退出登录，"
                    + "要么让管理员在控制台执行 /nalxerapp logout " + resolved + " 后再登。");
            send(ex, 403, Json.write(busy));
            audit(ip + " 登录 " + resolved + " → 拒绝（已在其他设备登录）");
            return;
        }
        boolean admin = isAdminName(resolved);
        String tokenId = Tokens.randomId();
        String token = plugin.tokens().issue(resolved, admin, plugin.tokenTtl(), tokenId);
        plugin.sessions().begin(resolved, tokenId, System.currentTimeMillis() + plugin.tokenTtl() * 1000L);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("token", token);
        out.put("name", resolved);
        out.put("admin", Boolean.valueOf(admin));
        out.put("role", roleOf(resolved));
        out.put("caps", capsOf(resolved));
        boolean needVerify = needsVerify(resolved);
        out.put("verifyRequired", Boolean.valueOf(needVerify));
        out.put("verified", Boolean.valueOf(!needVerify));
        out.put("verifyTarget", needVerify ? verifyTargetName(resolved) : "");
        out.put("su", Boolean.valueOf(suAllowed(resolved)));
        out.put("owner", Boolean.valueOf(isSuperUser(resolved)));
        out.put("expiresIn", Long.valueOf(plugin.tokenTtl()));
        out.put("welcome", "登录成功，欢迎回来，" + resolved + "（" + roleLabel(roleOf(resolved)) + "）");
        send(ex, 200, Json.write(out));
    }


    /** 这个账号在服务器上真正有的权限（不是按角色猜的）。
     *  OP / 管理员 / 服主额外放行；协管只能拿到它有对应权限节点的那些。 */
    /** 角色英文 key → 给用户看的中文名。 */
    private static String roleLabel(String role) {
        if ("owner".equals(role)) return "服主";
        if ("su".equals(role)) return "服主";
        if ("admin".equals(role)) return "管理员";
        if ("moderator".equals(role)) return "协管";
        return "普通玩家";
    }

    private Map<String, Object> capsOf(String name) {
        boolean owner = isSuperUser(name);
        boolean su = suAllowed(name);
        boolean admin = isAdminName(name);
        boolean mod = plugin.hooks().isModerator(name);
        boolean penal = plugin.hooks().hasPerm(name, "inthenameoflinux.penalty");
        boolean banPerm = plugin.hooks().hasPerm(name, "inthenameoflinux.ban");
        Map<String, Object> caps = new LinkedHashMap<String, Object>();
        caps.put("view", Boolean.valueOf(admin || mod));          // 看在线 / 详情 / 管理层
        caps.put("report", Boolean.valueOf(admin || mod));        // 处理举报
        // 协管在服务器上本来就带封禁 / 惩罚权限，这里一并放行（时长由 30 天上限管住）
        caps.put("mute", Boolean.valueOf(admin || su || owner || mod || penal));
        caps.put("ban", Boolean.valueOf(admin || su || owner || mod || banPerm));
        caps.put("fake", Boolean.valueOf(admin || su || owner));
        caps.put("config", Boolean.valueOf(owner));
        caps.put("su", Boolean.valueOf((admin || su || owner) && su));
        return caps;
    }

    private void me(HttpExchange ex, Tokens.Payload token, String askName) {
        String target = token.name;
        if (askName != null && !askName.trim().isEmpty()) {
            if (!isAdmin(token)) {
                send(ex, 403, obj(false, "只能看自己的数据"));
                return;
            }
            target = plugin.hooks().resolveName(askName.trim());
        }
        Map<String, Object> stats = plugin.stats().snapshot(target);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("data", stats);
        boolean needVerify = needsVerify(token.name);
        out.put("verifyRequired", Boolean.valueOf(needVerify));
        out.put("verified", Boolean.valueOf(isVerified(token)));
        out.put("verifyTarget", needVerify ? verifyTargetName(token.name) : "");
        out.put("su", Boolean.valueOf(suAllowed(token.name)));
        out.put("owner", Boolean.valueOf(isSuperUser(token.name)));
        out.put("admin", Boolean.valueOf(isAdmin(token)));
        out.put("role", roleOf(token.name));
        out.put("caps", capsOf(token.name));
        send(ex, 200, Json.write(out));
    }


    // ── 聊天 ────────────────────────────────────────────────

    private void chatGet(HttpExchange ex, Map<String, String> query) {
        long since = parseLong(query.get("since"), 0L);
        int limit = (int) parseLong(query.get("limit"), 60L);
        if (limit <= 0 || limit > 120) limit = 60;
        // channel=world（默认）只看公屏；channel=team&team=<id> 只看这个队
        String channel = trim(query.get("channel"));
        if (!"team".equalsIgnoreCase(channel)) channel = "world";
        String teamId = trim(query.get("team"));
        if ("team".equalsIgnoreCase(channel) && teamId.isEmpty()) {
            send(ex, 400, obj(false, "队伍频道要带 team 参数"));
            return;
        }
        // 服务器重启过的话 id 会从头开始，客户端还拿着旧的 since 就永远查不到新消息
        long serverLast = plugin.chat().lastId();
        boolean reset = since > serverLast;
        if (reset) since = 0L;
        List<Object> list = new ArrayList<Object>();
        long lastReturned = since;
        for (ChatBus.Msg m : plugin.chat().since(since, limit, channel, teamId)) {
            lastReturned = m.id;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", Long.valueOf(m.id));
            row.put("time", Long.valueOf(m.time));
            row.put("name", m.name);
            row.put("text", m.text);
            row.put("source", m.source);
            row.put("channel", m.channel);
            row.put("teamId", m.teamId);
            row.put("teamName", m.teamName);
            list.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        // last 必须是「这次真的返回的最后一条」，不能用全局 lastId，
        // 否则别的频道（队伍 / 控制台）的消息会把游标推过还没读到的公屏消息
        out.put("last", Long.valueOf(lastReturned));
        out.put("reset", Boolean.valueOf(reset));
        out.put("messages", list);
        out.put("online", Integer.valueOf(Bukkit.getOnlinePlayers().size()));
        send(ex, 200, Json.write(out));
    }

    private void chatPost(HttpExchange ex, Tokens.Payload token, String body) {
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        String text = in == null ? null : in.get("message");
        if (text == null || text.trim().isEmpty()) {
            send(ex, 400, obj(false, "消息不能为空"));
            return;
        }
        if (text.length() > ChatBus.MAX_LENGTH) {
            send(ex, 400, obj(false, "消息太长了（最多 " + ChatBus.MAX_LENGTH + " 个字）"));
            return;
        }
        if (plugin.hooks().isMuted(token.name)) {
            send(ex, 403, obj(false, "你被禁言了，现在不能在服务器说话"));
            return;
        }
        String channel = trim(in.get("channel"));
        String teamId = trim(in.get("team"));
        ChatBus.Msg msg;
        if ("team".equalsIgnoreCase(channel)) {
            if (teamId.isEmpty()) {
                Map<String, Object> mine = plugin.hooks().teamInfo(token.name);
                if (mine != null) teamId = String.valueOf(mine.get("id"));
            }
            if (teamId.isEmpty()) {
                send(ex, 400, obj(false, "你还没有队伍，发不了队伍频道"));
                return;
            }
            if (plugin.hooks().teamById(teamId) == null) {
                send(ex, 400, obj(false, "找不到这个队伍"));
                return;
            }
            if (!plugin.hooks().teamHasMember(plugin.hooks().teamById(teamId), token.name)) {
                send(ex, 403, obj(false, "你不在这个队伍里"));
                return;
            }
            msg = plugin.chat().postTeam(token.name, teamId, text);
            if (msg == null) {
                send(ex, 400, obj(false, "这个队伍的聊天被队长关掉了"));
                return;
            }
            audit(token.name + " App 队伍聊天(" + teamId + ")：" + text);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("id", Long.valueOf(msg.id));
            out.put("time", Long.valueOf(msg.time));
            out.put("channel", "team");
            out.put("teamId", teamId);
            out.put("message", "已发到队伍频道");
            send(ex, 200, Json.write(out));
            return;
        }
        msg = plugin.chat().post(token.name, text);
        if (msg == null) {
            send(ex, 400, obj(false, "消息发不出去"));
            return;
        }
        audit(token.name + " App 聊天：" + text);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("id", Long.valueOf(msg.id));
        out.put("time", Long.valueOf(msg.time));
        out.put("message", "已发到服务器");
        send(ex, 200, Json.write(out));
    }

    // ── 队伍 ────────────────────────────────────────────────

    private void teamGet(HttpExchange ex, Tokens.Payload token, Map<String, String> query) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("available", Boolean.valueOf(plugin.hooks().hasTeamApi()));
        out.put("team", plugin.hooks().teamInfo(token.name));
        // 一个人可以同时在多个队伍里 —— 全给出来，别只显示主队
        List<Map<String, Object>> mine = plugin.hooks().myTeams(token.name);
        for (Map<String, Object> t : mine) {
            if (t == null) continue;
            String leader = String.valueOf(t.get("leaderName"));
            if (leader.equalsIgnoreCase(token.name)) {
                t.put("applications", plugin.hooks().teamApplications(String.valueOf(t.get("id"))));
            }
        }
        out.put("myTeams", mine);
        String q = query.get("q");
        List<Map<String, Object>> list = plugin.hooks().teamList(q, 200);
        out.put("teams", list);
        out.put("teamCount", Integer.valueOf(list.size()));
        send(ex, 200, Json.write(out));
    }

    private void teamPost(final HttpExchange ex, final Tokens.Payload token, String body) {
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        if (in == null) {
            send(ex, 400, obj(false, "请求格式不对"));
            return;
        }
        final String action = trim(in.get("action"));
        final String arg = trim(in.get("arg"));
        final String teamArg = trim(in.get("team"));
        if (action.isEmpty()) {
            send(ex, 400, obj(false, "缺少 action"));
            return;
        }
        final boolean accept = "1".equals(trim(in.get("accept"))) || "true".equalsIgnoreCase(trim(in.get("accept")));
        final String message = onMain(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                if ("apply".equalsIgnoreCase(action)) {
                    String id = teamArg.isEmpty() ? arg : teamArg;
                    return plugin.hooks().teamApplyJoin(token.name, id);
                }
                if ("decide".equalsIgnoreCase(action)) {
                    return plugin.hooks().teamDecision(token.name, teamArg, arg, accept);
                }
                if ("land".equalsIgnoreCase(action)) {
                    String id = teamArg.isEmpty() ? arg : teamArg;
                    boolean on = "1".equals(trim(in.get("on"))) || "true".equalsIgnoreCase(trim(in.get("on")));
                    return plugin.hooks().teamLandToggle(token.name, id, on);
                }
                return plugin.hooks().teamAction(token.name, action, arg, teamArg);
            }
        }, "队伍操作超时（服务器卡了？）");
        audit(token.name + " 队伍操作 " + action + " " + arg + " → " + message);
        boolean ok = message != null && !message.contains("失败")
                && !message.contains("不在游戏里") && !message.startsWith("找不到")
                && !message.contains("只有队长") && !message.contains("不支持")
                && !message.contains("不能为空") && !message.contains("太长");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(ok));
        out.put("message", message);
        out.put("team", plugin.hooks().teamInfo(token.name));
        out.put("data", out.get("team"));
        send(ex, ok ? 200 : 400, Json.write(out));
    }

    // ── 头衔 ────────────────────────────────────────────────

    // ── 举报（玩家侧）──────────────────────────────────────

    private void reportsMine(HttpExchange ex, Tokens.Payload token) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("available", Boolean.valueOf(plugin.hooks().hasReports()));
        out.put("reports", plugin.hooks().reports(false, token.name));
        send(ex, 200, Json.write(out));
    }

    private void reportCreate(HttpExchange ex, Tokens.Payload token, String body) {
        if (!plugin.getConfig().getBoolean("features.report", true)) {
            send(ex, 403, obj(false, "服务器关掉了举报功能"));
            return;
        }
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        String target = in == null ? "" : trim(in.get("target"));
        String reason = in == null ? "" : trim(in.get("reason"));
        if (target.isEmpty() || reason.isEmpty()) {
            send(ex, 400, obj(false, "要举报谁、为什么，两项都得填"));
            return;
        }
        if (reason.length() > 120) reason = reason.substring(0, 120);
        String msg = plugin.hooks().createReport(token.name, target, reason);
        boolean ok = msg.startsWith("举报已提交");
        if (ok) audit(token.name + " App 举报 " + target + "：" + reason);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(ok));
        out.put("message", msg);
        send(ex, ok ? 200 : 400, Json.write(out));
    }

    private void reportHandle(HttpExchange ex, Tokens.Payload token, String body) {
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        String op = in == null ? "" : trim(in.get("op"));
        int id = in == null ? 0 : (int) parseLong(in.get("id"), 0L);
        String note = in == null ? "" : trim(in.get("note"));
        if (id <= 0) {
            send(ex, 400, obj(false, "举报编号不对"));
            return;
        }
        boolean accept = "accept".equalsIgnoreCase(op);
        if (!accept && !"reject".equalsIgnoreCase(op)) {
            send(ex, 400, obj(false, "op 只能是 accept 或 reject"));
            return;
        }
        String msg = plugin.hooks().handleReport(id, accept, note, token.name);
        boolean ok = !msg.contains("找不到") && !msg.contains("处理过") && !msg.contains("不能处理") && !msg.contains("出错");
        if (ok) audit(token.name + (accept ? " 接受举报 #" : " 驳回举报 #") + id
                + (note.isEmpty() ? "" : "（" + note + "）"));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(ok));
        out.put("message", msg);
        send(ex, ok ? 200 : 400, Json.write(out));
    }

    // ── 惩罚 ───────────────────────────────────────────────

    /** 协管的处罚时长上限：30 天 */
    private static final long MODERATOR_MAX_MINUTES = 30L * 24L * 60L;

    private void penaltyAction(HttpExchange ex, Tokens.Payload token, String body) {
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        Map<String, Object> caps = capsOf(token.name);
        boolean canMute = Boolean.TRUE.equals(caps.get("mute"));
        boolean canBan = Boolean.TRUE.equals(caps.get("ban"));
        String wantType = in == null ? "banmsg" : trim(in.get("type"));
        if (!wantType.isEmpty()) {
            boolean needBan = !"banmsg".equalsIgnoreCase(wantType);
            if (needBan && !canBan) {
                send(ex, 403, obj(false, "你没有封禁权限（需要 inthenameoflinux.ban），这个处罚做不了"));
                return;
            }
            if (!needBan && !canMute) {
                send(ex, 403, obj(false, "你没有处罚权限（需要 inthenameoflinux.penalty），禁言做不了"));
                return;
            }
        }
        String op = in == null ? "" : trim(in.get("op"));
        String name = in == null ? "" : trim(in.get("name"));
        String type = in == null ? "banmsg" : trim(in.get("type"));
        if (type.isEmpty()) type = "banmsg";
        // 协管最多只能罚 30 天（43200 分钟），超出的直接拒掉，不做静默截断
        if (!isRealAdmin(token) && "apply".equalsIgnoreCase(op)) {
            long mins = in == null ? 0L : parseLong(in.get("minutes"), 0L);
            if (mins <= 0L) {
                send(ex, 403, obj(false, "协管必须填写处罚时长（1 ~ 43200 分钟，最多 30 天），不能永久"));
                return;
            }
            if (mins > MODERATOR_MAX_MINUTES) {
                send(ex, 403, obj(false, "协管最多只能处罚 30 天，要更久请让管理员操作"));
                return;
            }
        }
        if (name.isEmpty()) {
            send(ex, 400, obj(false, "要罚谁？"));
            return;
        }
        name = plugin.hooks().resolveName(name);   // 大小写不敏感，回执里也用标准写法
        String msg;
        if ("release".equalsIgnoreCase(op)) {
            msg = plugin.hooks().releasePenalty(type, name, token.name);
        } else if ("apply".equalsIgnoreCase(op)) {
            int minutes = in == null ? 0 : (int) parseLong(in.get("minutes"), 0L);
            String reason = in == null ? "" : trim(in.get("reason"));
            if (reason.isEmpty()) reason = "管理员处罚";
            msg = plugin.hooks().penalize(type, name, minutes, reason, token.name);
        } else {
            send(ex, 400, obj(false, "op 只能是 apply 或 release"));
            return;
        }
        boolean ok = !msg.contains("失败") && !msg.contains("没有被") && !msg.contains("没接上")
                && !msg.contains("不给执行") && !msg.contains("不能") && !msg.contains("保护对象")
                && !msg.contains("只有") && !msg.contains("拦");
        if (ok) audit(token.name + " 惩罚操作：" + msg);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(ok));
        out.put("message", msg);
        send(ex, ok ? 200 : 400, Json.write(out));
    }

    // ── 假人 ───────────────────────────────────────────────

    private void fakeAction(HttpExchange ex, Tokens.Payload token, String body) {
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        String op = in == null ? "" : trim(in.get("op"));
        String name = in == null ? "" : trim(in.get("name"));
        String msg;
        if ("add".equalsIgnoreCase(op)) {
            if (name.isEmpty()) {
                send(ex, 400, obj(false, "假人叫什么名字？"));
                return;
            }
            // 管理员本人在线就站在他脚下；不在线就以控制台身份在出生点附近召唤
            msg = plugin.hooks().spawnFake(name, Bukkit.getPlayerExact(token.name));
        } else if ("remove".equalsIgnoreCase(op)) {
            msg = plugin.hooks().removeFake(name);
        } else if ("respawn".equalsIgnoreCase(op)) {
            msg = plugin.hooks().fakeRespawn(name);
        } else if ("set".equalsIgnoreCase(op)) {
            String key = in == null ? "" : trim(in.get("key"));
            String value = in == null ? "" : trim(in.get("value"));
            msg = plugin.hooks().fakeSet(key, value);
        } else if ("toggle".equalsIgnoreCase(op)) {
            // 单个假人的开关：load / spawn / covered
            String key = in == null ? "" : trim(in.get("key"));
            String on = in == null ? "" : trim(in.get("on"));
            msg = plugin.hooks().fakeToggle(name, key, !"false".equalsIgnoreCase(on) && !"0".equals(on));
        } else {
            send(ex, 400, obj(false, "op 只能是 add / remove / respawn / set / toggle"));
            return;
        }
        boolean ok = !msg.contains("失败") && !msg.contains("没装") && !msg.contains("出错")
                && !msg.contains("要在游戏里") && !msg.contains("找不到") && !msg.contains("只能");
        if (ok) audit(token.name + " 假人：" + msg);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(ok));
        out.put("message", msg);
        send(ex, ok ? 200 : 400, Json.write(out));
    }

    // ── 玩家详情（管理页点开一个人）────────────────────────

    private void playerDetail(HttpExchange ex, String name, Tokens.Payload token) {
        if (name.isEmpty()) {
            send(ex, 400, obj(false, "要给个名字"));
            return;
        }
        name = plugin.hooks().resolveName(name);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("data", plugin.stats().snapshot(name));
        out.put("penalties", plugin.hooks().penaltiesOf(name));
        List<Object> reports = new ArrayList<Object>();
        for (Map<String, Object> r : plugin.hooks().reports(false, null)) {
            if (name.equalsIgnoreCase(String.valueOf(r.get("target")))) reports.add(r);
        }
        out.put("reports", reports);
        send(ex, 200, Json.write(out));
    }

    // ── 功能开关 & 联动状态 ────────────────────────────────

    private Map<String, Object> features() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("chat", Boolean.valueOf(plugin.getConfig().getBoolean("features.chat", true)));
        m.put("team", Boolean.valueOf(plugin.getConfig().getBoolean("features.team", true)));
        m.put("titles", Boolean.valueOf(plugin.getConfig().getBoolean("features.titles", true)));
        m.put("checkin", Boolean.valueOf(plugin.getConfig().getBoolean("features.checkin", true)));
        m.put("report", Boolean.valueOf(plugin.getConfig().getBoolean("features.report", true)));
        m.put("penalty", Boolean.valueOf(plugin.getConfig().getBoolean("features.penalty", true)));
        m.put("fakes", Boolean.valueOf(plugin.getConfig().getBoolean("features.fakes", true)));
        m.put("console", Boolean.valueOf(plugin.getConfig().getBoolean("admin.commands.enabled", true)));
        return m;
    }

    private Map<String, Object> hooksState() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("authme", Boolean.valueOf(plugin.hooks().hasAuthMe()));
        m.put("economy", Boolean.valueOf(plugin.hooks().hasEconomy()));
        m.put("points", Boolean.valueOf(plugin.hooks().hasPoints()));
        m.put("bans", Boolean.valueOf(plugin.hooks().hasBans()));
        m.put("teams", Boolean.valueOf(plugin.hooks().hasTeams()));
        m.put("titles", Boolean.valueOf(plugin.hooks().hasTitles()));
        m.put("reports", Boolean.valueOf(plugin.hooks().hasReports()));
        m.put("fakes", Boolean.valueOf(plugin.hooks().hasFakes()));
        return m;
    }

    // ── 每日签到 ────────────────────────────────────────────

    /** POST /api/transfer {type: coins|points, target, amount} —— 走 NalxerECO 的实现。 */
    private void transferPost(com.sun.net.httpserver.HttpExchange ex, Tokens.Payload token, String body)
            throws java.io.IOException {
        java.util.Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        String type = in == null ? "" : in.get("type");
        String target = in == null ? "" : in.get("target");
        if (type == null || type.trim().isEmpty()) type = "coins";
        double amount = 0D;
        try {
            amount = Double.parseDouble(String.valueOf(in == null ? "0" : in.get("amount")).trim());
        } catch (Exception ignored) {
        }
        if (target != null && !target.trim().isEmpty()) {
            target = plugin.hooks().resolveName(target.trim());   // 名字大小写不敏感
        }
        String msg = plugin.hooks().transfer(type, token.name, target, amount);
        boolean ok = msg != null && msg.contains("已转给");
        audit(token.name + " App 转账 " + ("points".equals(type) ? "点券 " : "金币 ")
                + amount + " → " + (target == null ? "" : target) + "：" + msg);
        java.util.Map<String, Object> obj = new java.util.LinkedHashMap<String, Object>();
        obj.put("ok", Boolean.valueOf(ok));
        obj.put("message", msg == null ? "转账失败" : msg);
        obj.put("coins", "");
        send(ex, ok ? 200 : 400, Json.write(obj));
    }

    private void checkinGet(HttpExchange ex, Tokens.Payload token) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("server", Boolean.valueOf(plugin.hooks().hasEconomy() || plugin.hooks().hasPoints()));
        out.put("info", plugin.checkIn().info(token.name).json());
        send(ex, 200, Json.write(out));
    }

    private void checkinPost(HttpExchange ex, Tokens.Payload token) {
        CheckIn.Result r = plugin.checkIn().claim(token.name);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(r.ok));
        out.put("message", r.message);
        out.put("reward", r.reward == null ? "" : r.reward);
        out.put("info", r.info.json());
        send(ex, r.ok ? 200 : 400, Json.write(out));
    }

    private void titleGet(HttpExchange ex, Tokens.Payload token, Map<String, String> query) {
        String target = token.name;
        String ask = query.get("name");
        if (ask != null && !ask.trim().isEmpty() && !ask.trim().equalsIgnoreCase(token.name)) {
            if (!isAdmin(token)) {
                send(ex, 403, obj(false, "只能看自己的头衔"));
                return;
            }
            target = plugin.hooks().resolveName(ask.trim());
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("available", Boolean.valueOf(plugin.hooks().hasTitleApi()));
        out.put("data", plugin.hooks().titleOverview(target));
        send(ex, 200, Json.write(out));
    }

    private void titlePost(final HttpExchange ex, final Tokens.Payload token, String body) {
        Map<String, String> in = Json.parseFlat(body == null ? "" : body);
        if (in == null) {
            send(ex, 400, obj(false, "请求格式不对"));
            return;
        }
        final String titleId = trim(in.get("id"));
        final String message = onMain(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                return plugin.hooks().equipTitle(token.name, titleId);
            }
        }, "佩戴超时，等一下再试");
        audit(token.name + " 佩戴头衔 " + (titleId.isEmpty() ? "(卸下)" : titleId) + " → " + message);
        boolean ok = message != null && !message.contains("失败") && !message.contains("还没解锁");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.valueOf(ok));
        out.put("message", message);
        out.put("data", plugin.hooks().titleOverview(token.name));
        send(ex, ok ? 200 : 400, Json.write(out));
    }

    // ── 在线玩家 ────────────────────────────────────────────

    private void players(HttpExchange ex) {
        List<Object> list = new ArrayList<Object>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("name", p.getName());
            row.put("level", Integer.valueOf(p.getLevel()));
            row.put("ping", Integer.valueOf(pingOf(p)));
            row.put("world", p.getWorld().getName());
            row.put("gamemode", p.getGameMode() == null ? "" : p.getGameMode().name());
            Map<String, Object> team = plugin.hooks().teamInfo(p.getName());
            row.put("team", team == null ? null : team.get("coloredName"));
            row.put("teamName", team == null ? null : team.get("name"));
            row.put("titleId", plugin.hooks().activeTitleId(p.getName()));
            list.add(row);
            if (list.size() >= 200) break;
        }
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("ok", Boolean.TRUE);
        resp.put("count", Integer.valueOf(list.size()));
        resp.put("players", list);
        send(ex, 200, Json.write(resp));
    }

    /** 在主线程执行并等结果（HTTP 线程不能直接摸 Bukkit API）。 */
    private <T> T onMain(final java.util.concurrent.Callable<T> task, T fallback) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                return fallback;
            }
        }
        final Object[] holder = new Object[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = task.call();
                } catch (Throwable ignored) {
                    // 超时/异常都回 fallback
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        @SuppressWarnings("unchecked")
        T result = (T) holder[0];
        return result == null ? fallback : result;
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void online(HttpExchange ex) {
        List<Object> list = new ArrayList<Object>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", p.getName());
            m.put("level", Integer.valueOf(p.getLevel()));
            m.put("ping", Integer.valueOf(pingOf(p)));
            m.put("world", p.getWorld().getName());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", Boolean.TRUE);
        out.put("count", Integer.valueOf(list.size()));
        out.put("players", list);
        send(ex, 200, Json.write(out));
    }

    private void admin(HttpExchange ex, String path, String method,
                       Map<String, String> query, String body, Tokens.Payload token) {
        boolean modOnly = !isRealAdmin(token);
        if (modOnly) {
            boolean readOnly = "/api/admin/status".equals(path) || "/api/admin/players".equals(path)
                    || "/api/admin/staff".equals(path) || "/api/admin/player".equals(path)
                    || "/api/admin/penalties".equals(path) || "/api/admin/reports".equals(path)
                    || "/api/admin/bans".equals(path);
            boolean allowedForMod = readOnly || "/api/admin/report".equals(path) || "/api/admin/penalty".equals(path);
            if (!allowedForMod) {
                send(ex, 403, obj(false, "协管只能看在线和玩家详情、处理举报、禁言/解禁"));
                return;
            }
        }
        if ("/api/admin/commands".equals(path)) {
            if (!suAllowed(token.name)) {
                send(ex, 403, obj(false, "命令控制台只对服主账号开放"));
                return;
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("enabled", Boolean.valueOf(plugin.getConfig().getBoolean("admin.commands.enabled", true)));
            out.put("allow", plugin.getConfig().getStringList("admin.commands.allow"));
            out.put("groups", Catalog.groups());
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/exec".equals(path)) {
            if (!suAllowed(token.name)) {
                send(ex, 403, obj(false, "命令控制台只对服主账号开放"));
                return;
            }
            if (!"POST".equals(method)) {
                send(ex, 405, obj(false, "请用 POST"));
                return;
            }
            Map<String, String> in = Json.parseFlat(body == null ? "" : body);
            String cmd = in == null ? "" : trim(in.get("command"));
            if (cmd.isEmpty()) {
                send(ex, 400, obj(false, "命令是空的"));
                return;
            }
            audit(token.name + " 控制台执行 " + cmd);
            Console.Result r = plugin.console().run(cmd);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.valueOf(r.ok));
            out.put("message", r.message);
            out.put("command", cmd);
            out.put("output", r.lines);
            send(ex, r.ok ? 200 : 400, Json.write(out));
            return;
        }
        if ("/api/admin/su".equals(path)) {
            if (!suAllowed(token.name)) {
                send(ex, 403, obj(false, "su 只对服主账号开放"));
                return;
            }
            if (!"POST".equals(method)) {
                send(ex, 405, obj(false, "请用 POST"));
                return;
            }
            Map<String, String> in = Json.parseFlat(body == null ? "" : body);
            String cmd = in == null ? "" : trim(in.get("command"));
            if (cmd.isEmpty()) {
                send(ex, 400, obj(false, "命令是空的"));
                return;
            }
            audit(token.name + " su 执行 " + cmd);
            Console.Result r = plugin.console().runSu(cmd);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.valueOf(r.ok));
            out.put("message", r.message);
            out.put("command", cmd);
            out.put("output", r.lines);
            send(ex, r.ok ? 200 : 400, Json.write(out));
            return;
        }
        if ("/api/admin/linux".equals(path)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("plugins", linuxList());
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/linux/config".equals(path)) {
            if ("GET".equals(method)) {
                linuxConfigGet(ex, trim(query.get("name")), token);
                return;
            }
            if (!"POST".equals(method)) {
                send(ex, 405, obj(false, "请用 GET 或 POST"));
                return;
            }
            linuxConfigSave(ex, body, token);
            return;
        }
        if ("/api/admin/linux/op".equals(path)) {
            if (!"POST".equals(method)) {
                send(ex, 405, obj(false, "请用 POST"));
                return;
            }
            linuxOp(ex, body, token);
            return;
        }
        if ("/api/admin/status".equals(path)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("plugins", plugin.hooks().pluginStatus());
            out.put("features", features());
            out.put("hooks", hooksState());
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/players".equals(path)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("players", plugin.hooks().searchPlayers(trim(query.get("q")), 60));
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/staff".equals(path)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("staff", plugin.hooks().staffList());
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/player".equals(path)) {
            playerDetail(ex, trim(query.get("name")), token);
            return;
        }
        if ("/api/admin/penalties".equals(path)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("penalties", plugin.hooks().penalties());
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/penalty".equals(path)) {
            penaltyAction(ex, token, body);
            return;
        }
        if ("/api/admin/reports".equals(path)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            String filter = trim(query.get("filter"));
            boolean pending = "1".equals(query.get("pending")) || "true".equals(query.get("pending"));
            if ("all".equalsIgnoreCase(filter)) {
                pending = false;
            } else if ("handled".equalsIgnoreCase(filter)) {
                pending = false;
            } else if ("pending".equalsIgnoreCase(filter)) {
                pending = true;
            }
            List<Map<String, Object>> rows = plugin.hooks().reports(pending, trim(query.get("reporter")));
            if ("handled".equalsIgnoreCase(filter)) {
                List<Map<String, Object>> only = new java.util.ArrayList<Map<String, Object>>();
                for (Map<String, Object> r : rows) {
                    Object h = r.get("handled");
                    if (Boolean.TRUE.equals(h) || "true".equals(String.valueOf(h))) only.add(r);
                }
                rows = only;
            }
            int handled = 0;
            for (Map<String, Object> r : rows) {
                Object h = r.get("handled");
                if (Boolean.TRUE.equals(h) || "true".equals(String.valueOf(h))) handled++;
            }
            out.put("ok", Boolean.TRUE);
            out.put("pending", Boolean.valueOf(pending));
            out.put("count", Integer.valueOf(rows.size()));
            out.put("handledCount", Integer.valueOf(handled));
            out.put("pendingCount", Integer.valueOf(rows.size() - handled));
            out.put("reports", rows);
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/report".equals(path)) {
            reportHandle(ex, token, body);
            return;
        }
        if ("/api/admin/fakes".equals(path)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("available", Boolean.valueOf(plugin.hooks().hasFakes()));
            out.put("fakes", plugin.hooks().fakes());
            out.put("settings", plugin.hooks().fakeSettings());
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/fake".equals(path)) {
            fakeAction(ex, token, body);
            return;
        }
        if ("/api/admin/bans".equals(path)) {
            List<Object> list = new ArrayList<Object>();
            for (String[] e : plugin.hooks().banList()) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("player", e[0]);
                m.put("reason", PlayerStats.cleanReason(e[1], e[2]));
                m.put("operator", e[2]);
                m.put("expire", e[3]);
                m.put("time", e[4]);
                list.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("count", Integer.valueOf(list.size()));
            out.put("bans", list);
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/search".equals(path)) {
            String q = trim(query.get("q"));
            List<String> names = new ArrayList<String>();
            if (!q.isEmpty()) {
                String lower = q.toLowerCase();
                for (String n : plugin.hooks().knownNames()) {
                    if (n.toLowerCase().contains(lower)) {
                        names.add(n);
                        if (names.size() >= 30) {
                            break;
                        }
                    }
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().contains(lower) && !names.contains(p.getName())) {
                        names.add(p.getName());
                    }
                }
            }
            // 回给 App 的是对象：名字 + 在线 + 是否已封禁（列表上直接能看出来）
            List<Object> hits = new ArrayList<Object>();
            for (String n : names) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", n);
                Player online = Bukkit.getPlayerExact(plugin.hooks().resolveName(n));
                m.put("online", Boolean.valueOf(online != null));
                m.put("banned", Boolean.valueOf(plugin.hooks().isBanned(n)));
                hits.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("hits", hits);
            send(ex, 200, Json.write(out));
            return;
        }
        if ("/api/admin/ban".equals(path)) {
            if (!"POST".equals(method)) {
                send(ex, 405, obj(false, "请用 POST"));
                return;
            }
            Map<String, String> in = Json.parseFlat(body == null ? "" : body);
            if (in == null) {
                send(ex, 400, obj(false, "请求格式不对"));
                return;
            }
            String name = trim(in.get("name"));
            if (name.isEmpty()) {
                send(ex, 400, obj(false, "请填写要封禁的玩家"));
                return;
            }
            String reason = trim(in.get("reason"));
            if (reason.isEmpty()) {
                reason = "违反服务器规则";
            }
            long minutes = parseLong(in.get("minutes"), 0L);
            long ms = minutes > 0 ? minutes * 60000L : 0L;
            String resolved = plugin.hooks().resolveName(name);
            boolean ok = plugin.hooks().ban(resolved, reason, ms, token.name);
            audit(token.name + " 封禁 " + resolved + "（" + (ms > 0 ? minutes + " 分钟" : "永久") + "）原因：" + reason);
            String detail = plugin.hooks().lastMessage();
            send(ex, ok ? 200 : 502, obj(ok, ok
                    ? "已封禁 " + resolved + (ms > 0 ? "（" + minutes + " 分钟）" : "（永久）")
                    : (detail == null || detail.isEmpty()
                    ? "封禁失败：请检查 InTheNameOfLinux 是否正常加载"
                    : detail)));
            return;
        }
        if ("/api/admin/unban".equals(path)) {
            if (!"POST".equals(method)) {
                send(ex, 405, obj(false, "请用 POST"));
                return;
            }
            Map<String, String> in = Json.parseFlat(body == null ? "" : body);
            String name = in == null ? "" : trim(in.get("name"));
            if (name.isEmpty()) {
                send(ex, 400, obj(false, "请填写要解封的玩家"));
                return;
            }
            String resolved = plugin.hooks().resolveName(name);
            boolean ok = plugin.hooks().unban(resolved, token.name);
            audit(token.name + " 解封 " + resolved);
            String detail = plugin.hooks().lastMessage();
            send(ex, ok ? 200 : 502, obj(ok, ok
                    ? "已解封 " + resolved
                    : (detail == null || detail.isEmpty() ? "解封失败（该玩家可能没被封）" : detail)));
            return;
        }
        send(ex, 404, obj(false, "没有这个管理接口"));
    }

    // ── 工具 ────────────────────────────────────────────────

    private boolean isAdmin(Tokens.Payload token) {
        return token.admin || isAdminName(token.name);
    }

    private java.util.List<String> verifyPlayers() {
        java.util.List<String> list = plugin.getConfig().getStringList("verify.players");
        if (list == null || list.isEmpty()) {
            String t = plugin.hooks().identityTarget();
            list = (t == null || t.isEmpty())
                    ? java.util.Collections.<String>emptyList()
                    : java.util.Collections.singletonList(t);
        }
        return list;
    }

    private java.util.List<String> suPlayers() {
        java.util.List<String> list = plugin.getConfig().getStringList("su.players");
        if (list == null || list.isEmpty()) {
            list = java.util.Arrays.asList("Linux582021", "fs1");
        }
        return list;
    }

    /** 只有这里列出来的账号能碰命令控制台 / su；其他管理员只能做封禁这类操作 */
    private boolean suAllowed(String name) {
        if (name == null || name.isEmpty()) return false;
        java.util.List<String> list = plugin.getConfig().getStringList("su.players");
        if (list == null || list.isEmpty()) {
            list = java.util.Arrays.asList("Linux582021", "fs1");
        }
        for (String s : list) {
            if (s != null && s.trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private boolean isAdminName(String name) {
        if (name == null) {
            return false;
        }
        for (String s : plugin.getConfig().getStringList("admin.players")) {
            if (s != null && s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        String resolved = plugin.hooks().resolveName(name);
        // 离线也要能判定：op 名单存在 ops.json 里，getOfflinePlayer 就能读到
        try {
            if (Bukkit.getOfflinePlayer(resolved).isOp()) {
                return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        Player p = Bukkit.getPlayerExact(resolved);
        return p != null && p.hasPermission("nalxerapp.admin");
    }

    // ── 登录限流 ─────────────────────────────────────────────────────
    // 同一 IP：60 秒内最多 5 次尝试，超了就冷却 30 秒
    // 同一账号：连续失败 10 次锁定 5 分钟；失败到第 5 次起，每次回剩余次数
    private static final int IP_WINDOW_MAX = 5;
    private static final long IP_WINDOW_MS = 60000L;
    private static final long IP_COOLDOWN_MS = 30000L;
    private static final int ACCOUNT_MAX_FAILS = 10;
    private static final long ACCOUNT_LOCK_MS = 300000L;

    private final java.util.Map<String, java.util.ArrayDeque<Long>> ipHits =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>>();
    private final java.util.Map<String, Long> ipCooldown =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private final java.util.Map<String, Integer> accountFails =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    private final java.util.Map<String, Long> accountLock =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 该 IP 现在是不是还在冷却里；是就返回剩余秒数。 */
    private long ipCooldownLeft(String ip) {
        Long until = ipCooldown.get(ip);
        long now = System.currentTimeMillis();
        if (until == null) return 0L;
        if (until <= now) { ipCooldown.remove(ip); return 0L; }
        return (until - now + 999L) / 1000L;
    }

    /** 记一次来自这个 IP 的尝试，返回 true = 这次超限、需要冷却。 */
    private boolean hitIp(String ip) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> q = ipHits.get(ip);
        if (q == null) { q = new java.util.ArrayDeque<Long>(); ipHits.put(ip, q); }
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > IP_WINDOW_MS) q.pollFirst();
            q.addLast(Long.valueOf(now));
            if (q.size() > IP_WINDOW_MAX) {
                q.clear();
                ipCooldown.put(ip, Long.valueOf(now + IP_COOLDOWN_MS));
                return true;
            }
        }
        if (ipHits.size() > 4096) ipHits.clear();   // 兜底，别无限长
        return false;
    }

    private long accountLockLeft(String name) {
        if (name == null) return 0L;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        Long until = accountLock.get(key);
        long now = System.currentTimeMillis();
        if (until == null) return 0L;
        if (until <= now) { accountLock.remove(key); accountFails.remove(key); return 0L; }
        return (until - now + 999L) / 1000L;
    }

    /** 密码错：+1 次失败；返回还能试几次（<0 表示这次已经锁了）。 */
    private int noteFail(String name) {
        if (name == null) return ACCOUNT_MAX_FAILS;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        Integer cur = accountFails.get(key);
        int n = (cur == null ? 0 : cur.intValue()) + 1;
        if (n >= ACCOUNT_MAX_FAILS) {
            accountFails.remove(key);
            accountLock.put(key, Long.valueOf(System.currentTimeMillis() + ACCOUNT_LOCK_MS));
            return -1;
        }
        accountFails.put(key, Integer.valueOf(n));
        return ACCOUNT_MAX_FAILS - n;
    }

    private void clearFail(String name) {
        if (name == null) return;
        String key = name.toLowerCase(java.util.Locale.ROOT);
        accountFails.remove(key);
        accountLock.remove(key);
    }

    /** null = 没登录；authReason 记下为什么被拒，供响应里说明 */
    private String authReason;

    private Tokens.Payload auth(HttpExchange ex) {
        authReason = "";
        String head = ex.getRequestHeaders().getFirst("Authorization");
        if (head == null) {
            return null;
        }
        String t = head.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        Tokens.Payload p = plugin.tokens().verify(t);
        if (p == null) {
            return null;
        }
        // 这个账号已经在别的设备上重新登录过，这一枚就作废了
        if (!plugin.sessions().matches(p.name, p.id)) {
            authReason = "这个账号已经在另一台设备登录了，这台需要重新登录";
            return null;
        }
        return p;
    }

    /** 角色：owner 服主 / su 超管 / admin 管理 / moderator 协管 / player 普通玩家 */
    private String roleOf(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "player";
        }
        if (isSuperUser(name)) {
            return "owner";
        }
        if (suAllowed(name)) {
            return "su";
        }
        if (isAdminName(name)) {
            return "admin";
        }
        if (plugin.hooks().isModerator(name)) {
            return "moderator";
        }
        return "player";
    }

    /** 能进管理页的人（管理 / 协管都算） */
    private boolean canManage(Tokens.Payload token) {
        return token != null && (token.admin || isAdminName(token.name) || plugin.hooks().isModerator(token.name));
    }

    /** 只有正牌管理员能做的事（封禁、假人、插件配置、su） */
    private boolean isRealAdmin(Tokens.Payload token) {
        return token != null && isAdmin(token);
    }

    private boolean allowed(String ip, boolean isLogin, String path) {
        long win = System.currentTimeMillis() / 60000L;
        synchronized (this) {
            if (win != windowStart) {
                windowStart = win;
                loginHits.clear();
                apiHits.clear();
            }
            // 登录频次交给 login() 自己管（要回「还剩几次 / 冷却多少秒」），
            // 这里只保留全局请求闸门，免得两个限制互相抢先、提示不清。
            Map<String, int[]> map = apiHits;
            int[] c = map.get(ip);
            if (c == null) {
                c = new int[]{0};
                map.put(ip, c);
            }
            int limit = plugin.getConfig().getInt("login.requests-per-minute", 180);
            if (c[0] >= limit) {
                return false;
            }
            c[0]++;
            return true;
        }
    }

    private static String clientIp(HttpExchange ex) {
        String xff = ex.getRequestHeaders().getFirst("X-Real-IP");
        if (xff == null || xff.trim().isEmpty()) {
            xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        }
        if (xff != null && !xff.trim().isEmpty()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static int pingOf(Player p) {
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Object ping = handle.getClass().getField("ping").get(handle);
            return ping instanceof Number ? ((Number) ping).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ── Linux 系插件：列表 / 配置 / 启停 ──────────────────────

    /** 名字以 Linux 开头的插件才算「Linux 系」。 */
    private static boolean isLinuxPlugin(String name) {
        if (name == null) {
            return false;
        }
        // Linux 系（LinuxACE / LinuxTAB / LinuxTeam / LinuxTitle …）+ 假人插件，
        // 这几个都是自家插件，一起放进这个管理页
        return name.startsWith("Linux") || name.startsWith("FakePlayer")
                || "InTheNameOfLinux".equalsIgnoreCase(name);
    }

    /** 插件配置（Linux 系 / 假人 / ITNOL）只给服主账号改，普通管理员只能看不能动。 */
    private static boolean isSuperOnlyPlugin(String name) {
        return name != null && !name.isEmpty();
    }

    /** 服主账号：优先读 su.players（默认 Linux582021 / fs1），没配就用内置名单。 */
    private boolean isSuperUser(String name) {
        if (name == null) {
            return false;
        }
        String who = name.trim();
        java.util.List<String> su = suPlayers();
        for (int i = 0; i < su.size(); i++) {
            if (who.equalsIgnoreCase(su.get(i))) {
                return true;
            }
        }
        return "linux582021".equalsIgnoreCase(who) || "fs1".equalsIgnoreCase(who);
    }

    /** 不是服主账号、又要动插件配置时，统一回这句话。 */
    private boolean denySuperOnly(HttpExchange ex, String name, Tokens.Payload token) {
        if (!isSuperOnlyPlugin(name) || isSuperUser(token == null ? null : token.name)) {
            return false;
        }
        send(ex, 403, obj(false, "插件配置只有服主能改（" + name + "）"));
        return true;
    }

    private java.util.List<Map<String, Object>> linuxList() {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<Map<String, Object>>();
        org.bukkit.plugin.Plugin[] all = Bukkit.getPluginManager().getPlugins();
        java.util.Arrays.sort(all, new java.util.Comparator<org.bukkit.plugin.Plugin>() {
            @Override
            public int compare(org.bukkit.plugin.Plugin a, org.bukkit.plugin.Plugin b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (org.bukkit.plugin.Plugin p : all) {
            if (!isLinuxPlugin(p.getName())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("name", p.getName());
            row.put("version", p.getDescription().getVersion());
            row.put("superOnly", Boolean.TRUE);
            row.put("loaded", Boolean.valueOf(p.isEnabled()));
            java.io.File dir = p.getDataFolder();
            java.io.File cfg = new java.io.File(dir, "config.yml");
            row.put("folder", dir == null ? "" : dir.getPath());
            row.put("hasConfig", Boolean.valueOf(cfg.isFile()));
            row.put("configSize", Long.valueOf(cfg.isFile() ? cfg.length() : 0L));
            row.put("configTime", Long.valueOf(cfg.isFile() ? cfg.lastModified() : 0L));
            java.util.List<String> mounts = new java.util.ArrayList<String>();
            if (new java.io.File(dir, "config.yml").isFile()) {
                mounts.add("config.yml");
            }
            if (new java.io.File(dir, "titles.yml").isFile()) {
                mounts.add("titles.yml");
            }
            if (new java.io.File(dir, "players.yml").isFile()) {
                mounts.add("players.yml");
            }
            if (new java.io.File(dir, "teams.yml").isFile()) {
                mounts.add("teams.yml");
            }
            row.put("files", mounts);
            list.add(row);
        }
        return list;
    }

    private org.bukkit.plugin.Plugin linuxPlugin(String name) {
        if (!isLinuxPlugin(name)) {
            return null;
        }
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p == null ? null : p;
    }

    private void linuxConfigGet(HttpExchange ex, String name, Tokens.Payload token) {
        if (denySuperOnly(ex, name, token)) {
            return;
        }
        org.bukkit.plugin.Plugin p = linuxPlugin(name);
        if (p == null) {
            send(ex, 404, obj(false, "找不到插件 " + name));
            return;
        }
        String file = "config.yml";
        java.io.File f = new java.io.File(p.getDataFolder(), file);
        if (!f.isFile()) {
            send(ex, 404, obj(false, name + " 没有 " + file));
            return;
        }
        try {
            String text = readAll(f);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("name", p.getName());
            out.put("file", file);
            out.put("path", f.getPath());
            out.put("size", Long.valueOf(f.length()));
            out.put("time", Long.valueOf(f.lastModified()));
            out.put("text", text);
            send(ex, 200, Json.write(out));
        } catch (Exception e) {
            send(ex, 500, obj(false, "读配置出错：" + e.getMessage()));
        }
    }

    private void linuxConfigSave(HttpExchange ex, String body, Tokens.Payload token) {
        Map<String, String> in = Json.parseFlat(body);
        if (in == null) {
            send(ex, 400, obj(false, "请求体不是 JSON 对象"));
            return;
        }
        String name = trim(in.get("name"));
        String text = in.get("text");
        if (denySuperOnly(ex, name, token)) {
            return;
        }
        if (text == null) {
            send(ex, 400, obj(false, "缺少 text"));
            return;
        }
        org.bukkit.plugin.Plugin p = linuxPlugin(name);
        if (p == null) {
            send(ex, 404, obj(false, "找不到插件 " + name));
            return;
        }
        java.io.File f = new java.io.File(p.getDataFolder(), "config.yml");
        if (!f.isFile()) {
            send(ex, 404, obj(false, name + " 没有 config.yml"));
            return;
        }
        // 先按 YAML 解析一遍，语法不对就不写，免得把插件配置写成砖头
        try {
            Object parsed = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            org.bukkit.configuration.file.YamlConfiguration probe =
                    new org.bukkit.configuration.file.YamlConfiguration();
            probe.loadFromString(text);
            if (parsed == null && probe == null) {
                send(ex, 400, obj(false, "解析失败"));
                return;
            }
        } catch (Throwable e) {
            send(ex, 400, obj(false, "YAML 写法有问题，没保存：" + e.getMessage()));
            return;
        }
        try {
            java.io.File bak = new java.io.File(f.getParentFile(), "config.yml.app-bak");
            copy(f, bak);
            writeAll(f, text);
            final org.bukkit.plugin.Plugin target = p;
            onMain(new java.util.concurrent.Callable<Boolean>() {
                @Override
                public Boolean call() {
                    try {
                        target.reloadConfig();
                        return Boolean.TRUE;
                    } catch (Throwable ignored) {
                        return Boolean.FALSE;
                    }
                }
            }, Boolean.FALSE);
            audit(token.name + " 改了 " + p.getName() + "/config.yml");
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.TRUE);
            out.put("message", "已保存并重载 " + p.getName() + " 的 config.yml（旧文件留了 config.yml.app-bak）");
            out.put("size", Long.valueOf(f.length()));
            send(ex, 200, Json.write(out));
        } catch (Exception e) {
            send(ex, 500, obj(false, "写配置出错：" + e.getMessage()));
        }
    }

    private void linuxOp(HttpExchange ex, String body, Tokens.Payload token) {
        Map<String, String> in = Json.parseFlat(body);
        if (in == null) {
            send(ex, 400, obj(false, "请求体不是 JSON 对象"));
            return;
        }
        final String name = trim(in.get("name"));
        String op = trim(in.get("op"));
        if (denySuperOnly(ex, name, token)) {
            return;
        }
        final org.bukkit.plugin.Plugin p = linuxPlugin(name);
        if (p == null) {
            send(ex, 404, obj(false, "找不到插件 " + name));
            return;
        }
        if ("enable".equals(op)) {
            final org.bukkit.plugin.PluginManager pm = Bukkit.getPluginManager();
            String r = onMain(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    try {
                        pm.enablePlugin(p);
                        return p.isEnabled() ? "已启用 " + name : "启用失败（看控制台日志）";
                    } catch (Throwable e) {
                        return "启用失败：" + e.getMessage();
                    }
                }
            }, "超时");
            audit(token.name + " 启用插件 " + name);
            send(ex, 200, obj(true, r));
            return;
        }
        if ("disable".equals(op)) {
            final org.bukkit.plugin.PluginManager pm = Bukkit.getPluginManager();
            String r = onMain(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    try {
                        pm.disablePlugin(p);
                        return p.isEnabled() ? "停用失败（可能被别的插件依赖）" : "已停用 " + name + "，下个服务器重启前它不会再跑";
                    } catch (Throwable e) {
                        return "停用失败：" + e.getMessage();
                    }
                }
            }, "超时");
            audit(token.name + " 停用插件 " + name);
            send(ex, 200, obj(true, r));
            return;
        }
        if ("reload".equals(op)) {
            String r = onMain(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    try {
                        p.reloadConfig();
                        return "已重载 " + name + " 的配置";
                    } catch (Throwable e) {
                        return "重载失败：" + e.getMessage();
                    }
                }
            }, "超时");
            audit(token.name + " 重载插件 " + name);
            send(ex, 200, obj(true, r));
            return;
        }
        send(ex, 400, obj(false, "op 只能是 enable / disable / reload"));
    }

    private static String readAll(java.io.File f) throws java.io.IOException {
        byte[] buf = new byte[(int) Math.min(f.length(), 262144L)];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeAll(java.io.File f, String text) throws java.io.IOException {
        java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(f), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    private static void copy(java.io.File from, java.io.File to) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(from);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(to);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static String readBody(HttpExchange ex) throws Exception {
        String raw = readBodyPlain(ex);
        EncSession es = ENC_SESSIONS.get(String.valueOf(ex.getRequestHeaders().getFirst("X-Session")));
        if (es == null || raw == null || raw.isEmpty()
                || !"1".equals(ex.getRequestHeaders().getFirst("X-Enc"))) {
            return raw;
        }
        try {
            byte[] plain = decGcm(es.key, unb64(raw.trim()));
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String readBodyPlain(HttpExchange ex) throws Exception {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 64 * 1024) {
                break;
            }
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String json) {
        EncSession es = ENC_SESSIONS.get(String.valueOf(ex.getRequestHeaders().getFirst("X-Session")));
        if (es != null && es.expires > System.currentTimeMillis()
                && "1".equals(ex.getRequestHeaders().getFirst("X-Enc"))) {
            try {
                byte[] ct = encGcm(es.key, json.getBytes("UTF-8"));
                byte[] out = b64(ct).getBytes("UTF-8");
                ex.getResponseHeaders().set("X-Enc", "1");
                ex.sendResponseHeaders(status, out.length);
                java.io.OutputStream os = ex.getResponseBody();
                os.write(out);
                os.close();
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            byte[] raw = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, raw.length);
            OutputStream os = ex.getResponseBody();
            os.write(raw);
            os.flush();
        } catch (Exception e) {
            // 连接已断或响应已发出，忽略
        }
    }

    private static String obj(boolean ok, String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", Boolean.valueOf(ok));
        m.put("message", message);
        return Json.write(m);
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) {
                    out.put(URLDecoder.decode(pair, "UTF-8"), "");
                } else {
                    out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            } catch (Exception ignored) {
                // 坏参数直接跳过
            }
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private void audit(String line) {
        plugin.audit(line);
    }
}
