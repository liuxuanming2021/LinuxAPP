package com.nalxer.app;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 极简 NBT 读取器，只为从 world/playerdata/&lt;uuid&gt;.dat 里拿经验、等级、血量这类小字段。
 *
 * 为什么不用 NMS：NalxerApp 要跑在 1.12.2 的 Paper/Spigot 上，直接摸 NMS 会踩编译坑
 * （见 LinuxTAB 那次 RuntimeInvisibleParameterAnnotations 事故），而玩家存档是稳定的
 * GZIP + NBT，自己读 60 行就行，跨版本也不容易碎。
 *
 * 只保留 {@link #WANTED} 里的键，其余 tag 边读边丢（背包之类的大块数据不进内存）。
 */
public final class Nbt {

    /** 只关心这几个键，其它一律跳过。 */
    private static final Set<String> WANTED = new HashSet<String>(Arrays.asList(
            "XpLevel", "XpP", "XpTotal", "Health", "foodLevel", "Score", "playerGameType", "Dimension"
    ));

    private final DataInputStream in;

    private Nbt(DataInputStream in) {
        this.in = in;
    }

    /** 读玩家存档，失败返回 null（文件不存在、被占用、格式怪都算失败，调用方兜底）。 */
    public static Map<String, Object> readPlayerData(File file) {
        if (file == null || !file.isFile()) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)));
            Nbt reader = new Nbt(in);
            int type = in.readUnsignedByte();
            if (type != 10) return null;
            reader.readName();
            Map<String, Object> out = new HashMap<String, Object>();
            reader.readCompoundBody(out);
            return out;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String readName() throws Exception {
        int len = in.readUnsignedShort();
        if (len == 0) return "";
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, "UTF-8");
    }

    private void readCompoundBody(Map<String, Object> out) throws Exception {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return;
            String name = readName();
            Object value = readPayload(type, WANTED.contains(name));
            if (value != null) out.put(name, value);
        }
    }

    /** 读一个 payload；keep 为 false 时只是把字节走完，返回 null。 */
    private Object readPayload(int type, boolean keep) throws Exception {
        switch (type) {
            case 1: {
                byte v = in.readByte();
                return keep ? Byte.valueOf(v) : null;
            }
            case 2: {
                short v = in.readShort();
                return keep ? Short.valueOf(v) : null;
            }
            case 3: {
                int v = in.readInt();
                return keep ? Integer.valueOf(v) : null;
            }
            case 4: {
                long v = in.readLong();
                return keep ? Long.valueOf(v) : null;
            }
            case 5: {
                float v = in.readFloat();
                return keep ? Float.valueOf(v) : null;
            }
            case 6: {
                double v = in.readDouble();
                return keep ? Double.valueOf(v) : null;
            }
            case 7: {
                int len = in.readInt();
                if (len < 0 || len > 64 * 1024 * 1024) throw new IllegalStateException("bad byte array");
                byte[] b = new byte[len];
                in.readFully(b);
                return keep ? b : null;
            }
            case 8: {
                int len = in.readUnsignedShort();
                byte[] b = new byte[len];
                in.readFully(b);
                return keep ? new String(b, "UTF-8") : null;
            }
            case 9: {
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                if (len < 0 || len > 4 * 1024 * 1024) throw new IllegalStateException("bad list");
                List<Object> list = keep ? new ArrayList<Object>() : null;
                for (int i = 0; i < len; i++) {
                    Object element = readPayload(elemType, keep);
                    if (keep && list != null) list.add(element);
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = keep ? new HashMap<String, Object>() : null;
                Map<String, Object> sink = keep ? map : new HashMap<String, Object>();
                readCompoundBody(sink);
                return keep ? map : null;
            }
            case 11: {
                int len = in.readInt();
                if (len < 0 || len > 16 * 1024 * 1024) throw new IllegalStateException("bad int array");
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                return keep ? arr : null;
            }
            case 12: {
                int len = in.readInt();
                if (len < 0 || len > 16 * 1024 * 1024) throw new IllegalStateException("bad long array");
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                return keep ? arr : null;
            }
            default:
                throw new IllegalStateException("unknown tag " + type);
        }
    }

    /** 从 stats json 里抠一个整数字段，纯字符串查找，不引 JSON 库。 */
    public static long readStat(File file, String key) {
        if (file == null || !file.isFile()) return -1L;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] buf = new byte[(int) Math.min(file.length(), 1024 * 1024)];
            int read = fis.read(buf);
            if (read <= 0) return -1L;
            String text = new String(buf, 0, read, "UTF-8");
            int idx = text.indexOf("\"" + key + "\"");
            if (idx < 0) return -1L;
            int colon = text.indexOf(':', idx);
            if (colon < 0) return -1L;
            int i = colon + 1;
            while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
            int start = i;
            while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '-')) i++;
            if (i <= start) return -1L;
            return Long.parseLong(text.substring(start, i));
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
