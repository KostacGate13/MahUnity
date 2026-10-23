package com.mahunity.launcher;

import android.content.Context;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

final class Text {
    private Text() {}

    /** Capitals the way Greek signage writes them: "Πέμπτη" becomes "ΠΕΜΠΤΗ", the tonos dropped, other scripts untouched. */
    static String upper(String s) {
        if (s == null) return "";
        String nfd = Normalizer.normalize(s.toUpperCase(Locale.getDefault()), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(nfd.length());
        char base = 0;
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            boolean mark = Character.getType(c) == Character.NON_SPACING_MARK;
            if (mark && (c == '́' || c == '͂') && base >= 'Ͱ' && base <= 'Ͽ') continue;
            if (!mark) base = c;
            out.append(c);
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }

    /** Good morning, afternoon or evening, for the hour now. */
    static String greeting(Context context) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return context.getString(hour >= 5 && hour < 12 ? R.string.greet_morning
                : hour >= 12 && hour < 18 ? R.string.greet_afternoon : R.string.greet_evening);
    }

    /** Today, as the header shows it: "Παρασκευή · 23 Οκτ". */
    static String today() {
        Locale l = Locale.getDefault();
        return new SimpleDateFormat("EEEE", l).format(Calendar.getInstance().getTime()) + " · "
                + new SimpleDateFormat("d MMM", l).format(Calendar.getInstance().getTime()).replace(".", "");
    }

    /** Lower case without accents, for matching app labels and package names against keywords. */
    static String fold(String s) {
        if (s == null) return "";
        String nfd = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) out.append(c);
        }
        return out.toString();
    }
}
