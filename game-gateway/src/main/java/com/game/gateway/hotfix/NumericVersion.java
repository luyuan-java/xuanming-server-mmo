package com.game.gateway.hotfix;

import java.util.regex.Pattern;

/** 客户端版本只认一至四个十进制段，每段至多九位；缺段按零比较，不接受预发布后缀。 */
record NumericVersion(int major, int minor, int patch, int revision) implements Comparable<NumericVersion> {
    private static final Pattern FORMAT = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,9}){0,3}");

    static NumericVersion parse(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("版本号须为一至四段十进制数，每段最多九位");
        }
        String[] parts = value.split("\\.");
        return new NumericVersion(part(parts, 0), part(parts, 1), part(parts, 2), part(parts, 3));
    }

    private static int part(String[] parts, int index) {
        return index < parts.length ? Integer.parseInt(parts[index]) : 0;
    }

    @Override
    public int compareTo(NumericVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result == 0) result = Integer.compare(revision, other.revision);
        return result;
    }
}
