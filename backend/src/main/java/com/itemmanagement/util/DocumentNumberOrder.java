package com.itemmanagement.util;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Pattern;

public final class DocumentNumberOrder {
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    public static final Comparator<String> ASCENDING = Comparator
            .comparing(DocumentNumberOrder::numericValue, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Comparator.naturalOrder());

    private DocumentNumberOrder() {
    }

    private static BigInteger numericValue(String reference) {
        return DIGITS.matcher(reference).matches() ? new BigInteger(reference) : null;
    }
}
