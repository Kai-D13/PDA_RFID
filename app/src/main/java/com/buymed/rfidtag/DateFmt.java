package com.buymed.rfidtag;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DateFmt {
    private DateFmt() {}

    static String dateTime(long ms) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date(ms));
    }

    static String time(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }
}
