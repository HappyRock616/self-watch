/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The compact binary encoding of watch-app messages exchanged with Self Watch. It carries the same
 * value types as a Monkey C message: null, booleans, integers, floating point numbers, strings,
 * arrays and dictionaries. It is Self Watch's own format, not Garmin's wire format.
 *
 * <h2>Format (version 1)</h2>
 * <pre>
 * message = 0x01 value                      (format version, then exactly one value, no trailing bytes)
 * value   = 0x00                            null
 *         | 0x01 | 0x02                     false | true
 *         | 0x03 zigzag-varint              integer (64-bit)
 *         | 0x04 8 bytes                    double, IEEE 754 big-endian
 *         | 0x05 varint(n) n bytes          string, strict UTF-8
 *         | 0x06 varint(n) n*value          list
 *         | 0x07 varint(n) n*(key value)    map; keys are null, boolean, integer, double or string
 * </pre>
 * Varints are unsigned LEB128 with the shortest form only. Limits: {@link #MAX_MESSAGE_BYTES} in
 * total and {@link #MAX_DEPTH} nested lists or maps; map keys are unique.
 *
 * <h2>Java types</h2>
 * {@link #encode(Object)} accepts {@code null}, {@link Boolean}, {@link Byte}, {@link Short},
 * {@link Integer}, {@link Long}, {@link Float}, {@link Double}, {@link CharSequence}, {@link Character},
 * {@link List} and {@link Map}. {@link #decode(byte[])} returns {@code null}, {@link Boolean},
 * {@link Long}, {@link Double}, {@link String}, {@link ArrayList} and {@link LinkedHashMap} (in the
 * encoded order).
 */
public final class LinkCodec {
    /** The format version written as the first byte. */
    public static final int FORMAT_VERSION = 1;
    /** Largest encoded message accepted by either side, in bytes. The watch's own limit is far lower. */
    public static final int MAX_MESSAGE_BYTES = 16 * 1024;
    /** Deepest nesting of lists and maps. */
    public static final int MAX_DEPTH = 16;

    static final int T_NULL = 0x00;
    static final int T_FALSE = 0x01;
    static final int T_TRUE = 0x02;
    static final int T_INT = 0x03;
    static final int T_DOUBLE = 0x04;
    static final int T_STRING = 0x05;
    static final int T_LIST = 0x06;
    static final int T_MAP = 0x07;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private LinkCodec() {
    }

    /**
     * Encodes a message.
     *
     * @param value the message: see the class description for the accepted types.
     * @return the encoded bytes, at most {@link #MAX_MESSAGE_BYTES}.
     * @throws IllegalArgumentException if a value has an unsupported type, a string is not valid
     *     Unicode, a map has a list or map as a key or two equal keys, the nesting is deeper than
     *     {@link #MAX_DEPTH} (this also catches a list that contains itself), or the result is too large.
     */
    public static byte[] encode(Object value) {
        Writer w = new Writer();
        w.byte1(FORMAT_VERSION);
        w.value(value, 0);
        return w.toByteArray();
    }

    /**
     * Decodes a message. Never throws an unchecked exception for bad input.
     *
     * @param bytes the encoded message.
     * @return the value: see the class description for the returned types.
     * @throws LinkCodecException if the bytes are empty, too long, truncated, malformed, too deep, have
     *     trailing bytes, invalid UTF-8, a list or map as a map key, or a repeated map key.
     */
    public static Object decode(byte[] bytes) throws LinkCodecException {
        if (bytes == null || bytes.length == 0) {
            throw new LinkCodecException("empty message");
        }
        if (bytes.length > MAX_MESSAGE_BYTES) {
            throw new LinkCodecException("message too large");
        }
        if ((bytes[0] & 0xFF) != FORMAT_VERSION) {
            throw new LinkCodecException("unknown format version");
        }
        Reader r = new Reader(bytes);
        Object v = r.value(0);
        if (r.pos != bytes.length) {
            throw new LinkCodecException("trailing bytes");
        }
        return v;
    }

    // ---- encoding ----

    private static final class Writer {
        private byte[] buf = new byte[64];
        private int size;

        void byte1(int b) {
            if (size == buf.length) {
                if (buf.length >= MAX_MESSAGE_BYTES) {
                    throw new IllegalArgumentException("message larger than " + MAX_MESSAGE_BYTES + " bytes");
                }
                byte[] bigger = new byte[Math.min(buf.length * 2, MAX_MESSAGE_BYTES)];
                System.arraycopy(buf, 0, bigger, 0, size);
                buf = bigger;
            }
            buf[size++] = (byte) b;
        }

        void bytes(byte[] b) {
            if (b.length > MAX_MESSAGE_BYTES - size) {
                throw new IllegalArgumentException("message larger than " + MAX_MESSAGE_BYTES + " bytes");
            }
            for (byte x : b) {
                byte1(x);
            }
        }

        void varint(long v) {
            while ((v & ~0x7FL) != 0) {
                byte1((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            byte1((int) v);
        }

        void value(Object v, int depth) {
            if (v == null) {
                byte1(T_NULL);
            } else if (v instanceof Boolean) {
                byte1((Boolean) v ? T_TRUE : T_FALSE);
            } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
                long n = ((Number) v).longValue();
                byte1(T_INT);
                varint((n << 1) ^ (n >> 63));
            } else if (v instanceof Double || v instanceof Float) {
                long bits = Double.doubleToRawLongBits(((Number) v).doubleValue());
                byte1(T_DOUBLE);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    byte1((int) (bits >>> shift));
                }
            } else if (v instanceof CharSequence || v instanceof Character) {
                byte[] utf8 = utf8(v.toString());
                byte1(T_STRING);
                varint(utf8.length);
                bytes(utf8);
            } else if (v instanceof List) {
                checkDepth(depth);
                List<?> list = (List<?>) v;
                byte1(T_LIST);
                varint(list.size());
                for (Object item : list) {
                    value(item, depth + 1);
                }
            } else if (v instanceof Map) {
                checkDepth(depth);
                Map<?, ?> map = (Map<?, ?>) v;
                byte1(T_MAP);
                varint(map.size());
                Set<Object> seen = new HashSet<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    Object key = e.getKey();
                    if (key instanceof List || key instanceof Map) {
                        throw new IllegalArgumentException("map keys must not be lists or maps");
                    }
                    if (!seen.add(normalKey(key))) {
                        throw new IllegalArgumentException("two map keys encode to the same value");
                    }
                    value(key, depth + 1);
                    value(e.getValue(), depth + 1);
                }
            } else {
                throw new IllegalArgumentException("unsupported type " + v.getClass().getName());
            }
        }

        private static void checkDepth(int depth) {
            if (depth >= MAX_DEPTH) {
                throw new IllegalArgumentException("nested deeper than " + MAX_DEPTH);
            }
        }

        /** The decoded form of a scalar key, so Integer 1 and Long 1 count as the same key. */
        private static Object normalKey(Object key) {
            if (key instanceof Integer || key instanceof Short || key instanceof Byte) {
                return ((Number) key).longValue();
            }
            if (key instanceof Float) {
                return ((Float) key).doubleValue();
            }
            if (key instanceof CharSequence || key instanceof Character) {
                return key.toString();
            }
            return key;
        }

        private static byte[] utf8(String s) {
            try {
                ByteBuffer bb = UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(s));
                byte[] out = new byte[bb.remaining()];
                bb.get(out);
                return out;
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("string is not valid Unicode (unpaired surrogate)", e);
            }
        }

        byte[] toByteArray() {
            byte[] out = new byte[size];
            System.arraycopy(buf, 0, out, 0, size);
            return out;
        }
    }

    // ---- decoding ----

    private static final class Reader {
        private final byte[] b;
        int pos = 1; // after the version byte

        Reader(byte[] b) {
            this.b = b;
        }

        private int remaining() {
            return b.length - pos;
        }

        private int u8() throws LinkCodecException {
            if (pos >= b.length) {
                throw new LinkCodecException("truncated");
            }
            return b[pos++] & 0xFF;
        }

        /** Unsigned LEB128, at most 10 bytes, shortest form only. */
        private long varint() throws LinkCodecException {
            long result = 0;
            for (int i = 0; i < 10; i++) {
                int x = u8();
                if (i == 9 && x > 1) {
                    throw new LinkCodecException("varint overflow");
                }
                result |= (long) (x & 0x7F) << (7 * i);
                if ((x & 0x80) == 0) {
                    if (i > 0 && x == 0) {
                        throw new LinkCodecException("varint not in shortest form");
                    }
                    return result;
                }
            }
            throw new LinkCodecException("varint too long");
        }

        /** A count or length; each counted unit needs at least {@code unitBytes} more bytes. */
        private int count(int unitBytes) throws LinkCodecException {
            long n = varint();
            if (n < 0 || n > remaining() / unitBytes) {
                throw new LinkCodecException("length exceeds message");
            }
            return (int) n;
        }

        Object value(int depth) throws LinkCodecException {
            int tag = u8();
            switch (tag) {
                case T_NULL:
                    return null;
                case T_FALSE:
                    return Boolean.FALSE;
                case T_TRUE:
                    return Boolean.TRUE;
                case T_INT: {
                    long z = varint();
                    return (z >>> 1) ^ -(z & 1);
                }
                case T_DOUBLE: {
                    if (remaining() < 8) {
                        throw new LinkCodecException("truncated");
                    }
                    long bits = 0;
                    for (int i = 0; i < 8; i++) {
                        bits = (bits << 8) | (b[pos++] & 0xFFL);
                    }
                    return Double.longBitsToDouble(bits);
                }
                case T_STRING: {
                    int n = count(1);
                    String s = utf8(b, pos, n);
                    pos += n;
                    return s;
                }
                case T_LIST: {
                    checkDepth(depth);
                    int n = count(1);
                    List<Object> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(value(depth + 1));
                    }
                    return list;
                }
                case T_MAP: {
                    checkDepth(depth);
                    int n = count(2);
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < n; i++) {
                        Object key = value(depth + 1);
                        if (key instanceof List || key instanceof Map) {
                            throw new LinkCodecException("list or map used as a map key");
                        }
                        if (map.containsKey(key)) {
                            throw new LinkCodecException("repeated map key");
                        }
                        map.put(key, value(depth + 1));
                    }
                    return map;
                }
                default:
                    throw new LinkCodecException("unknown type byte");
            }
        }

        private static void checkDepth(int depth) throws LinkCodecException {
            if (depth >= MAX_DEPTH) {
                throw new LinkCodecException("nested too deep");
            }
        }

        private static String utf8(byte[] b, int off, int len) throws LinkCodecException {
            try {
                return UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(b, off, len))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new LinkCodecException("invalid UTF-8");
            }
        }
    }
}
