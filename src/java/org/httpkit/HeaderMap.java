package org.httpkit;

import clojure.lang.ISeq;
import clojure.lang.Seqable;

import java.util.Arrays;
import java.util.Map;

import static org.httpkit.HttpUtils.*;

/**
 * Save a few bytes of memory => No Map.Entry, and a little faster
 * Date: 4/23/13
 *
 * @author feng <shenedu@gmail.com>
 * @since 2.0.2
 */
public class HeaderMap {
    public boolean isEmpty() {
        return size == 0;
    }

    public final int INIT_SIZE = 8;

    private int size = 0;
    private Object arrays[] = new Object[INIT_SIZE * 2];

    public void put(String key, Object obj) {
        final int total = size << 1;
        if (total == arrays.length) {
            arrays = Arrays.copyOf(arrays, arrays.length * 2);
        }
        arrays[total] = key;
        arrays[total + 1] = obj;
        size += 1;
    }

    public Object get(String key) {
        final int total = size << 1; // * 2
        for (int i = 0; i < total; i += 2) {
            if (key.equals(arrays[i])) {
                return arrays[i + 1];
            }
        }
        return null;
    }

    public boolean containsKey(String key) {
        final int total = size << 1; // * 2
        for (int i = 0; i < total; i += 2) {
            if (key.equals(arrays[i])) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        this.size = 0;
    }

    public static HeaderMap camelCase(Map<String, Object> map) {
        HeaderMap tmp = new HeaderMap();
        if (map != null) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                tmp.put(HttpUtils.camelCase(e.getKey()), e.getValue());
            }
        }
        return tmp;
    }

    public void encodeHeaders(DynamicBytes bytes) {
        final int total = size << 1;
        for (int i = 0; i < total; i += 2) {
            String k = (String) arrays[i];
            Object v = arrays[i + 1];
            // ring spec says it could be a seq
            if (v instanceof Seqable) {
                ISeq seq = ((Seqable) v).seq();
                while (seq != null) {
                    bytes.append(k);
                    bytes.append(COLON, SP);
                    bytes.append(seq.first().toString(), HttpUtils.UTF_8);
                    bytes.append(CR, LF);
                    seq = seq.next();
                }
            } else {
                bytes.append(k);
                bytes.append(COLON, SP);
                // supposed to be ISO-8859-1, but utf-8 is compatible.
                // filename in Content-Disposition can be utf8
                bytes.append(v.toString(), HttpUtils.UTF_8);
                bytes.append(CR, LF);
            }
        }
        bytes.append(CR, LF);
    }
}
