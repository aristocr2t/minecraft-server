package ru.geekideas.mc.seasons;

import java.util.Locale;

/** Сезон и значение поля cells.applied_state. Порядок = порядок колонок счётчиков в chunks. */
enum Season {
    SPRING("spring"),
    SUMMER("summer"),
    AUTUMN("autumn"),
    WINTER("winter"),
    RESTORE("original");

    final String state;

    Season(String state) {
        this.state = state;
    }

    static Season parse(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "spring" -> SPRING;
            case "summer" -> SUMMER;
            case "autumn" -> AUTUMN;
            case "winter" -> WINTER;
            case "restore" -> RESTORE;
            default -> null;
        };
    }
}
