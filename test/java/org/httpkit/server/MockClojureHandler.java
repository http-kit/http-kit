package org.httpkit.server;

import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;

public class MockClojureHandler implements IFn{

    private final IPersistentMap dummyResponse;

    public MockClojureHandler(IPersistentMap dummyResponse){
        this.dummyResponse = dummyResponse;
    }

    @Override
    public Object invoke() {
        return null;
    }

    @Override
    public Object invoke(Object o) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17, Object o18) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17, Object o18, Object o19) {
        return dummyResponse;
    }

    @Override
    public Object invoke(Object o, Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10, Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17, Object o18, Object o19, Object... objects) {
        return dummyResponse;
    }

    @Override
    public Object applyTo(ISeq iSeq) {
        return iSeq;
    }

    @Override
    public void run() {

    }

    @Override
    public Object call() throws Exception {
        return null;
    }
}
