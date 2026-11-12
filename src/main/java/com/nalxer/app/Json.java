package com.nalxer.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 工具：只做本项目需要的事。
 * <p>写：{@link #esc(String)} 转义字符串，配合 StringBuilder 拼对象；</p>
 * <p>读：{@link #parseFlat(String)} 把 {"a":"b","c":1} 这类一层对象解析成 Map。</p>
 */
public final class Json {

    private Json() {
    }

    /** 转义成可以塞进双引号里的内容 */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** "k":"v" 的片段（自动加引号与转义） */
    public static String field(String key, String value) {
        return "\"" + esc(key) + "\":\"" + esc(value) + "\"";
    }

    /** "k":123 的片段 */
    public static String field(String key, long value) {
        return "\"" + esc(key) + "\":" + value;
    }

    /** "k":true 的片段 */
    public static String field(String key, boolean value) {
        return "\"" + esc(key) + "\":" + value;
    }

    /** "k":null 的片段 */
    public static String nullField(String key) {
        return "\"" + esc(key) + "\":null";
    }

    /**
     * 解析一层 JSON 对象。支持字符串、数字、布尔、null；嵌套对象/数组会被跳过成空串。
     *
     * @return 解析失败返回 null
     */
    public static Map<String, String> parseFlat(String body) {
        if (body == null) {
            return null;
        }
        String s = body.trim();
        if (s.length() < 2 || s.charAt(0) != '{') {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        int i = 1;
        int n = s.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) {
                i++;
            }
            if (i >= n || s.charAt(i) == '}') {
                break;
            }
            if (s.charAt(i) != '"') {
                return null;
            }
            StringBuilder key = new StringBuilder();
            i = readString(s, i, key);
            if (i < 0) {
                return null;
            }
            while (i < n && s.charAt(i) != ':') {
                i++;
            }
            i++;
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return null;
            }
            StringBuilder val = new StringBuilder();
            char c = s.charAt(i);
            if (c == '"') {
                i = readString(s, i, val);
                if (i < 0) {
                    return null;
                }
            } else {
                int start = i;
                int depth = 0;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '{' || d == '[') {
                        depth++;
                    } else if (d == '}' || d == ']') {
                        if (depth == 0) {
                            break;
                        }
                        depth--;
                    } else if (d == ',' && depth == 0) {
                        break;
                    }
                    i++;
                }
                val.append(s, start, i);
                String raw = val.toString().trim();
                val.setLength(0);
                val.append(raw);
            }
            out.put(key.toString(), val.toString());
        }
        return out;
    }

    // ── 通用序列化 ──────────────────────────────────────────

    /** 把 Map / List / String / Number / Boolean / null 写成 JSON */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        append(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            sb.append('"').append(esc((String) v)).append('"');
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Number) {
            Number n = (Number) v;
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("null");
                } else {
                    sb.append(trimDouble(d));
                }
            } else {
                sb.append(n.toString());
            }
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(esc(String.valueOf(e.getKey()))).append("\":");
                append(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<Object>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, o);
            }
            sb.append(']');
        } else {
            sb.append('"').append(esc(String.valueOf(v))).append('"');
        }
    }

    /** 金额保留两位小数，整数不带小数点 */
    private static String trimDouble(double d) {
        if (Math.abs(d - Math.rint(d)) < 1e-9) {
            return String.valueOf((long) d);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    /** 从 s[i] == '"' 开始读一个字符串，写进 out，返回结束后的下标；失败返回 -1 */
    private static int readString(String s, int i, StringBuilder out) {        i++; // 跳过开头的引号
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    return -1;
                }
                char e = s.charAt(i + 1);
                switch (e) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (i + 5 >= n) {
                            return -1;
                        }
                        out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 4;
                        break;
                    default:
                        out.append(e);
                }
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return -1;
    }
}
