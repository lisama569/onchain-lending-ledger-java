package com.github.lisama569.onchainlendingledgerjava.ledger;

import com.github.lisama569.onchainlendingledgerjava.domain.PoolState;
import com.github.lisama569.onchainlendingledgerjava.domain.PositionState;
import com.github.lisama569.onchainlendingledgerjava.domain.PriceSnapshot;
import com.github.lisama569.onchainlendingledgerjava.model.LedgerSnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照 JSON 编解码（无第三方依赖）。BigDecimal 以 toPlainString 落盘，杜绝精度丢失。
 */
public final class SnapshotCodec {

    private SnapshotCodec() {
    }

    // ---------------- encode ----------------

    public static String encode(LedgerSnapshot s) {
        JsonWriter w = new JsonWriter();
        w.beginObj()
                .key("version").num(s.version())
                .key("savedAtMillis").num(s.savedAtMillis())
                .key("pool").value(encodePool(s.pool()));

        w.keyRaw("positions").sep().beginObj();
        for (Map.Entry<String, PositionState> e : s.positions().entrySet()) {
            w.keyRaw(e.getKey()).sep().value(encodePosition(e.getValue()));
        }
        w.endObj();

        w.keyRaw("priceSnapshot").sep();
        if (s.priceSnapshot() == null) {
            w.nul();
        } else {
            w.value(encodePrice(s.priceSnapshot()));
        }

        w.keyRaw("requestLog").sep().beginObj();
        for (Map.Entry<String, String> e : s.requestLog().entrySet()) {
            w.keyRaw(e.getKey()).sep().str(e.getValue());
        }
        w.endObj();
        w.endObj();
        return w.sb.toString();
    }

    private static String encodePool(PoolState p) {
        JsonWriter w = new JsonWriter();
        w.beginObj()
                .key("assetId").str(p.assetId())
                .key("cash").str(p.cash().toPlainString())
                .key("totalSupplyScaled").str(p.totalSupplyScaled().toPlainString())
                .key("totalBorrowScaled").str(p.totalBorrowScaled().toPlainString())
                .key("supplyIndex").str(p.supplyIndex().toPlainString())
                .key("borrowIndex").str(p.borrowIndex().toPlainString())
                .key("lastAccrualTimeMillis").num(p.lastAccrualTimeMillis())
                .key("totalReserves").str(p.totalReserves().toPlainString())
                .endObj();
        return w.sb.toString();
    }

    private static String encodePosition(PositionState p) {
        JsonWriter w = new JsonWriter();
        w.beginObj()
                .key("scaledSupply").str(p.scaledSupply().toPlainString())
                .key("scaledBorrow").str(p.scaledBorrow().toPlainString())
                .key("collateral").str(p.collateral().toPlainString())
                .endObj();
        return w.sb.toString();
    }

    private static String encodePrice(PriceSnapshot p) {
        JsonWriter w = new JsonWriter();
        w.beginObj()
                .key("lastSeq").num(p.lastSeq())
                .key("price").str(p.price().toPlainString())
                .key("sourceTimeMillis").num(p.sourceTimeMillis())
                .key("ingestTimeMillis").num(p.ingestTimeMillis())
                .endObj();
        return w.sb.toString();
    }

    private static final class JsonWriter {
        final StringBuilder sb = new StringBuilder();
        /** 每个对象上下文栈顶记录该对象是否已写入过成员，用于在成员之间补逗号。 */
        private final java.util.Deque<Boolean> firstInObj = new java.util.ArrayDeque<>();

        JsonWriter beginObj() {
            sb.append('{');
            firstInObj.push(Boolean.TRUE);
            return this;
        }

        JsonWriter endObj() {
            sb.append('}');
            firstInObj.pop();
            return this;
        }

        JsonWriter key(String k) {
            if (!firstInObj.peek()) {
                sb.append(',');
            }
            firstInObj.pop();
            firstInObj.push(Boolean.FALSE);
            escape(k);
            sb.append(':');
            return this;
        }

        JsonWriter keyRaw(String k) {
            if (!firstInObj.peek()) {
                sb.append(',');
            }
            firstInObj.pop();
            firstInObj.push(Boolean.FALSE);
            escape(k);
            return this;
        }

        JsonWriter sep() {
            sb.append(':');
            return this;
        }

        JsonWriter comma() {
            sb.append(',');
            return this;
        }

        JsonWriter num(long v) {
            sb.append(v);
            return this;
        }

        JsonWriter str(String v) {
            escape(v);
            return this;
        }

        JsonWriter nul() {
            sb.append("null");
            return this;
        }

        JsonWriter value(String rawJson) {
            sb.append(rawJson);
            return this;
        }

        private void escape(String v) {
            sb.append('"');
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }
    }

    // ---------------- decode ----------------

    public static LedgerSnapshot decode(String json) {
        Parser p = new Parser(json);
        Object root = p.parseValue();
        p.end();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) root;

        @SuppressWarnings("unchecked")
        Map<String, Object> po = (Map<String, Object>) m.get("pool");
        PoolState pool = new PoolState(
                (String) po.get("assetId"),
                bd(po.get("cash")),
                bd(po.get("totalSupplyScaled")),
                bd(po.get("totalBorrowScaled")),
                bd(po.get("supplyIndex")),
                bd(po.get("borrowIndex")),
                asLong(po.get("lastAccrualTimeMillis")),
                bd(po.get("totalReserves")));

        Map<String, PositionState> positions = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> pm = (Map<String, Object>) m.get("positions");
        if (pm != null) {
            for (Map.Entry<String, Object> e : pm.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pso = (Map<String, Object>) e.getValue();
                positions.put(e.getKey(), new PositionState(e.getKey(),
                        bd(pso.get("scaledSupply")), bd(pso.get("scaledBorrow")),
                        bd(pso.get("collateral"))));
            }
        }

        PriceSnapshot price = null;
        @SuppressWarnings("unchecked")
        Map<String, Object> pr = (Map<String, Object>) m.get("priceSnapshot");
        if (pr != null) {
            price = new PriceSnapshot(asLong(pr.get("lastSeq")), bd(pr.get("price")),
                    asLong(pr.get("sourceTimeMillis")), asLong(pr.get("ingestTimeMillis")));
        }

        Map<String, String> requestLog = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> rl = (Map<String, Object>) m.get("requestLog");
        if (rl != null) {
            for (Map.Entry<String, Object> e : rl.entrySet()) {
                requestLog.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        return new LedgerSnapshot(asInt(m.get("version")), asLong(m.get("savedAtMillis")),
                pool, positions, price, requestLog);
    }

    private static BigDecimal bd(Object o) {
        return new BigDecimal(o.toString());
    }

    private static long asLong(Object o) {
        return Long.parseLong(o.toString());
    }

    private static int asInt(Object o) {
        return Integer.parseInt(o.toString());
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void end() {
            skipWs();
            if (i != s.length()) {
                throw new IllegalArgumentException("JSON 解析尾部有多余字符 @ " + i);
            }
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) {
                throw new IllegalArgumentException("JSON 提前结束");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObj();
                case '[' -> parseArr();
                case '"' -> parseStr();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNum();
            };
        }

        private Map<String, Object> parseObj() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String k = parseStr();
                skipWs();
                if (s.charAt(i) != ':') {
                    throw new IllegalArgumentException("期望 ':' @ " + i);
                }
                i++;
                map.put(k, parseValue());
                skipWs();
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return map;
                } else {
                    throw new IllegalArgumentException("期望 ',' 或 '}' @ " + i);
                }
            }
        }

        private List<Object> parseArr() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return list;
                } else {
                    throw new IllegalArgumentException("期望 ',' 或 ']' @ " + i);
                }
            }
        }

        private String parseStr() {
            skipWs();
            if (s.charAt(i) != '"') {
                throw new IllegalArgumentException("期望字符串 @ " + i);
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            int code = Integer.parseInt(s.substring(i, i + 4), 16);
                            i += 4;
                            sb.append((char) code);
                        }
                        default -> throw new IllegalArgumentException("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Boolean parseBool() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("非法布尔值 @ " + i);
        }

        private Object parseNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw new IllegalArgumentException("非法 null @ " + i);
        }

        private String parseNum() {
            int start = i;
            while (i < s.length() && "+-.0123456789eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("非法数字 @ " + i);
            }
            return s.substring(start, i);
        }
    }
}
