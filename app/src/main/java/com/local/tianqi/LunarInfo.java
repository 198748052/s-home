package com.local.tianqi;

public final class LunarInfo {

    public final String dateKey;
    public final String monthName;
    public final String dayName;
    public final int dayNumber;
    public final String lunarYear;
    public final String holiday;
    public final long updatedAt;
    public final boolean fromNetwork;

    public LunarInfo(String dateKey, String monthName, String dayName, int dayNumber,
                     String lunarYear, String holiday, long updatedAt, boolean fromNetwork) {
        this.dateKey = dateKey;
        this.monthName = monthName;
        this.dayName = dayName;
        this.dayNumber = dayNumber;
        this.lunarYear = lunarYear == null ? "" : lunarYear;
        this.holiday = holiday == null ? "" : holiday;
        this.updatedAt = updatedAt;
        this.fromNetwork = fromNetwork;
    }
}
