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
    private String keys[] = new String[INIT_SIZE];
    private Object values[] = new String[INIT_SIZE];

    public void put(String key, Object obj) {
        if (size == keys.length - 1) {
            keys = Arrays.copyOf(keys, keys.length * 2);
            values = Arrays.copyOf(values, keys.length * 2);
        }
        keys[size] = key;
        values[size] = obj;
        size += 1;
    }

    public Object get(String key) {
        for (int i = 0; i < size; ++i) {
            if (key.equals(keys[i])) {
                return values[i];
            }
        }
        return null;
    }

    public boolean containsKey(String key) {
        for (int i = 0; i < keys.length; ++i) {
            if (key.equals(keys[i])) {
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
        for (int i = 0; i < size; ++i) {
            String k = keys[i];
            Object v = values[i];
            if (v instanceof String) {
                bytes.append(k);
                bytes.append(COLON);
                bytes.append(SP);
                // supposed to be ISO-8859-1, but utf-8 is compatible.
                // filename in Content-Disposition can be utf8
                bytes.append((String) v, HttpUtils.UTF_8);
                bytes.append(CR);
                bytes.append(LF);
                // ring spec says it could be a seq
            } else if (v instanceof Seqable) {
                ISeq seq = ((Seqable) v).seq();
                while (seq != null) {
                    bytes.append(k);
                    bytes.append(COLON);
                    bytes.append(SP);
                    bytes.append(seq.first().toString(), HttpUtils.UTF_8);
                    bytes.append(CR);
                    bytes.append(LF);
                    seq = seq.next();
                }
            }
        }
        bytes.append(CR);
        bytes.append(LF);
    }
}
