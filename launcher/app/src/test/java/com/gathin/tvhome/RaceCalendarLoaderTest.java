package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

/** The remote calendar the launcher receives from a theme's race_calendar_url (see RaceCalendarLoader). */
public class RaceCalendarLoaderTest {

    private static final String WRAPPED = "{"
            + "\"status\":\"ok\","
            + "\"race_calendar\":["
            + "  {\"round\":19,\"name\":\"Mexico City Grand Prix\",\"place\":\"Mexico City\","
            + "   \"sessions\":["
            + "     {\"name\":\"RACE\",\"start\":\"2026-11-01T20:00:00Z\"},"
            + "     {\"name\":\"FP1\",\"start\":\"2026-10-30T18:30:00Z\"},"
            + "     {\"name\":\"QUALIFYING\",\"start\":\"2026-10-31T21:00:00Z\"}]},"
            + "  {\"round\":20,\"name\":\"Brazilian Grand Prix\",\"place\":\"São Paulo\","
            + "   \"sessions\":["
            + "     {\"name\":\"RACE\",\"start\":\"2026-11-08T17:00:00Z\"},"
            + "     {\"name\":\"FP1\",\"start\":\"not a time\"}]}"
            + "]}";

    @Test
    public void readsTheCalendarFromTheHostedWrapper() {
        List<RaceWeekend.Race> races = RaceCalendarLoader.parse(WRAPPED);

        assertEquals(2, races.size());
        RaceWeekend.Race mexico = races.get(0);
        assertEquals(19, mexico.round);
        assertEquals("Mexico City Grand Prix", mexico.name);
        assertEquals("Mexico City", mexico.place);
        // The constructor puts sessions in start order, whichever order the file uses.
        assertEquals(3, mexico.sessions.size());
        assertEquals("FP1", mexico.sessions.get(0).name);
        assertEquals(Instant.parse("2026-10-30T18:30:00Z"), mexico.sessions.get(0).start);
        assertTrue(mexico.sessions.get(2).isRace());
    }

    @Test
    public void aBadSessionIsDroppedAndTheRaceStillCounts() {
        List<RaceWeekend.Race> races = RaceCalendarLoader.parse(WRAPPED);

        // Brazil's FP1 has no readable start; its race is still usable.
        assertEquals(1, races.get(1).sessions.size());
        assertTrue(races.get(1).sessions.get(0).isRace());
    }

    @Test
    public void acceptsABareArraySoTheHostedFileCanBeJustTheList() {
        List<RaceWeekend.Race> races = RaceCalendarLoader.parse(
                "[{\"round\":1,\"name\":\"Australian Grand Prix\",\"place\":\"Melbourne\","
                        + "\"sessions\":[{\"name\":\"RACE\",\"start\":\"2027-03-07T05:00:00Z\"}]}]");

        assertEquals(1, races.size());
        assertEquals("Australian Grand Prix", races.get(0).name);
    }

    @Test
    public void skipsRacesWithoutAnyUsableSession() {
        List<RaceWeekend.Race> races = RaceCalendarLoader.parse(
                "{\"race_calendar\":[{\"round\":1,\"name\":\"No sessions\"},"
                        + "{\"round\":2,\"name\":\"Broken\",\"sessions\":[{\"name\":\"FP1\",\"start\":\"soon\"}]},"
                        + "{\"round\":3,\"name\":\"Fine\",\"sessions\":[{\"name\":\"RACE\",\"start\":\"2027-03-07T05:00:00Z\"}]}]}");

        assertEquals(1, races.size());
        assertEquals("Fine", races.get(0).name);
    }
}
