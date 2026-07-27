package com.daou.erdstudio.service;

import java.util.List;

/** 스키마 문서의 배열 행([name, type, ...])을 안전하게 읽는 헬퍼. 길이 부족·NULL을 허용한다. */
public final class Rows {

    private Rows() {
    }

    public static String str(List<Object> row, int index) {
        if (row == null || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index));
    }

    public static boolean bool(List<Object> row, int index) {
        return row != null && index < row.size() && Boolean.TRUE.equals(row.get(index));
    }

    public static Double dbl(List<Object> row, int index) {
        if (row == null || index >= row.size() || !(row.get(index) instanceof Number number)) {
            return null;
        }
        return number.doubleValue();
    }
}
