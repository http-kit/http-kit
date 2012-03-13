package me.shenfeng.http.codec;

public enum State {
    PROTOCOL_ERROR, ALL_READ, READ_INITIAL, READ_HEADER, READ_FIXED_LENGTH_CONTENT
}