package com.nalxer.app;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 登录令牌：payload = name|admin|过期时间，签名 = HMAC-SHA256(payload, 密钥)。
 * <p>无状态：服务端只存一个密钥，重启后已发出的令牌仍然有效（在有效期内）。</p>
 */
public final class Tokens {

    /** 解出来的令牌内容 */
    public static final class Payload {
        public final String name;
        public final boolean admin;
        public final long expiresAt;
        /** 这一枚令牌的随机编号，用来做「单设备登录」 */
        public final String id;

        Payload(String name, boolean admin, long expiresAt, String id) {
            this.name = name;
            this.admin = admin;
            this.expiresAt = expiresAt;
            this.id = id;
        }
    }

    private final byte[] secret;

    public Tokens(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String name, boolean admin, long ttlSeconds) {
        return issue(name, admin, ttlSeconds, randomId());
    }

    public String issue(String name, boolean admin, long ttlSeconds, String id) {
        long exp = System.currentTimeMillis() / 1000L + ttlSeconds;
        String payload = name + "|" + (admin ? "1" : "0") + "|" + exp + "|" + id;
        String body = b64(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + b64(hmac(payload));
    }

    /** 校验签名与过期时间；不合法返回 null */
    public Payload verify(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        int dot = t.indexOf('.');
        if (dot <= 0 || dot == t.length() - 1) {
            return null;
        }
        try {
            String body = t.substring(0, dot);
            String sig = t.substring(dot + 1);
            String payload = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
            byte[] expect = hmac(payload);
            if (!MessageDigest.isEqual(expect, Base64.getUrlDecoder().decode(sig))) {
                return null;
            }
            int p2 = payload.lastIndexOf('|');
            int p1 = payload.lastIndexOf('|', p2 - 1);
            int p0 = payload.lastIndexOf('|', p1 - 1);
            if (p0 <= 0 || p1 <= p0 || p2 <= p1) {
                return null;
            }
            String name = payload.substring(0, p0);
            boolean admin = "1".equals(payload.substring(p0 + 1, p1));
            long exp = Long.parseLong(payload.substring(p1 + 1, p2));
            String id = payload.substring(p2 + 1);
            if (exp < System.currentTimeMillis() / 1000L) {
                return null;
            }
            return new Payload(name, admin, exp, id);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 令牌随机编号：8 个十六进制字符 */
    public static String randomId() {
        byte[] raw = new byte[4];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(8);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 生成随机密钥（首次启动用） */
    public static String randomSecret() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
