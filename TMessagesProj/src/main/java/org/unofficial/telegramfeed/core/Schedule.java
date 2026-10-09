package org.unofficial.telegramfeed.core;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a rule is active: weekdays plus a daily window in local time, which may wrap past
 * midnight. The weekday is that of the moment the window starts.
 */
public final class Schedule {

    /** ISO weekdays, 1 = Monday ... 7 = Sunday. */
    public final Set<Integer> weekdays;
    /** Minutes since midnight, 0..1439. {@code from == to} means the whole day. */
    public final int from;
    public final int to;

    public static final Set<Integer> ALL_WEEK = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(1, 2, 3, 4, 5, 6, 7)));

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    public Schedule(Set<Integer> weekdays, int from, int to) {
        this.weekdays = Collections.unmodifiableSet(new HashSet<>(weekdays));
        this.from = from;
        this.to = to;
    }

    /** {@code "HH:mm"} to minutes. */
    public static int parseTime(String hhmm) {
        Matcher m = TIME.matcher(hhmm.trim());
        if (!m.matches()) throw new IllegalArgumentException("time must be HH:mm: " + hhmm);
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) throw new IllegalArgumentException("time out of range: " + hhmm);
        return h * 60 + min;
    }

    public static String formatTime(int minutes) {
        return String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    public boolean wrapsMidnight() {
        return to < from;
    }

    /** ISO weekday of the calendar, 1 = Monday ... 7 = Sunday. */
    public static int isoWeekday(Calendar c) {
        int d = c.get(Calendar.DAY_OF_WEEK);
        return d == Calendar.SUNDAY ? 7 : d - 1;
    }

    public boolean isActive(Calendar local) {
        int now = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
        int weekday = isoWeekday(local);
        if (from == to) return weekdays.contains(weekday);
        if (!wrapsMidnight()) {
            return now >= from && now < to && weekdays.contains(weekday);
        }
        if (now >= from) return weekdays.contains(weekday);
        if (now < to) {
            int startDay = weekday == 1 ? 7 : weekday - 1;
            return weekdays.contains(startDay);
        }
        return false;
    }

    /** {@code "1,2,3|09:00|18:00"}, the stored form. */
    public String encode() {
        StringBuilder b = new StringBuilder();
        for (int d = 1; d <= 7; d++) {
            if (weekdays.contains(d)) {
                if (b.length() > 0) b.append(',');
                b.append(d);
            }
        }
        return b + "|" + formatTime(from) + "|" + formatTime(to);
    }

    public static Schedule decode(String s) {
        String[] parts = s.split("\\|");
        if (parts.length != 3) throw new IllegalArgumentException("schedule: " + s);
        Set<Integer> days = new HashSet<>();
        for (String d : parts[0].split(",")) {
            if (!d.isEmpty()) days.add(Integer.parseInt(d.trim()));
        }
        return new Schedule(days, parseTime(parts[1]), parseTime(parts[2]));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Schedule)) return false;
        Schedule s = (Schedule) o;
        return from == s.from && to == s.to && weekdays.equals(s.weekdays);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(from, to, weekdays);
    }
}
