package io.synexia.maven.smart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON implementation for the fixed MCP reply shape; duplicate keys fail. */
final class TinyJson {
    private final String src;
    private int pos;
    private TinyJson(String src) { if (src.length() > 1_048_576) throw new IllegalArgumentException("oversize JSON"); this.src = src; }
    static Object parse(String s) {
        TinyJson p = new TinyJson(s);
        Object result = p.value(0);
        p.skip();
        if (p.pos != s.length()) throw new IllegalArgumentException("trailing JSON");
        return result;
    }
    static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
    private void skip() {
        while (pos < src.length() && " \n\r\t".indexOf(src.charAt(pos)) >= 0) pos++;
    }
    private boolean eat(char c) { skip(); if (pos < src.length() && src.charAt(pos) == c) { pos++; return true; } return false; }
    private void expect(char c) { if (!eat(c)) throw new IllegalArgumentException("invalid JSON"); }
    private Object value(int depth) {
        if (depth > 24) throw new IllegalArgumentException("JSON nesting too deep");
        skip();
        if (eat('{')) {
            Map<String,Object> o = new LinkedHashMap<>();
            if (!eat('}')) do {
                String k = string(); expect(':'); Object v = value(depth + 1);
                if (o.containsKey(k)) throw new IllegalArgumentException("duplicate JSON key");
                o.put(k,v);
                if (eat('}')) break;
                expect(',');
            } while (true);
            return o;
        }
        if (eat('[')) {
            List<Object> list = new ArrayList<>();
            if (!eat(']')) do {
                if (list.size() >= 16384) throw new IllegalArgumentException("JSON list too long");
                list.add(value(depth + 1));
                if (eat(']')) break;
                expect(',');
            } while(true);
            return list;
        }
        if (pos < src.length() && src.charAt(pos) == '"') return string();
        if (src.startsWith("true",pos)) { pos += 4; return true; }
        if (src.startsWith("false",pos)) { pos += 5; return false; }
        if (src.startsWith("null",pos)) { pos += 4; return null; }
        int start = pos;
        while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) pos++;
        if (pos == start) throw new IllegalArgumentException("JSON value expected");
        String num = src.substring(start,pos);
        if (!num.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) throw new IllegalArgumentException("invalid JSON number");
        return num;
    }
    private String string() {
        expect('"'); StringBuilder b = new StringBuilder();
        while(pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw new IllegalArgumentException("bad escape");
                c = src.charAt(pos++);
                switch(c) {
                    case '"', '\\', '/' -> b.append(c);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if(pos+4 > src.length()) throw new IllegalArgumentException("short unicode");
                        int v;
                        try { v = Integer.parseInt(src.substring(pos,pos+4),16); }
                        catch(NumberFormatException e) { throw new IllegalArgumentException("bad unicode",e); }
                        pos+=4;
                        if(Character.isHighSurrogate((char)v)) {
                            if(pos+6>src.length() || src.charAt(pos)!='\\' || src.charAt(pos+1)!='u') throw new IllegalArgumentException("unpaired surrogate");
                            int lo;
                            try { lo=Integer.parseInt(src.substring(pos+2,pos+6),16); }
                            catch(NumberFormatException e) {throw new IllegalArgumentException("bad unicode",e);}
                            if(!Character.isLowSurrogate((char)lo)) throw new IllegalArgumentException("unpaired surrogate");
                            b.append((char)v).append((char)lo); pos+=6;
                        } else if(Character.isLowSurrogate((char)v)) throw new IllegalArgumentException("unpaired surrogate");
                        else b.append((char)v);
                    }
                    default -> throw new IllegalArgumentException("unknown escape");
                }
            } else {
                if(c < 32 || Character.isSurrogate(c)) throw new IllegalArgumentException("invalid JSON character");
                b.append(c);
            }
            if(b.length()>1_000_000) throw new IllegalArgumentException("JSON string too long");
        }
        throw new IllegalArgumentException("unterminated JSON string");
    }
}
