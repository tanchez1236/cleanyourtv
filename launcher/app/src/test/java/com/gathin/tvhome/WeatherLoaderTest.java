package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The Open-Meteo current block the weather widget reads (see WeatherLoader). */
public class WeatherLoaderTest {

    private static final String OPEN_METEO = "{"
            + "\"current_units\":{\"temperature_2m\":\"°C\"},"
            + "\"current\":{\"time\":\"2026-10-29T12:30\",\"temperature_2m\":21.6,"
            + "\"weather_code\":3,\"is_day\":1}}";

    @Test
    public void readsTheCurrentTemperatureAndCondition() throws Exception {
        WeatherLoader.Data data = WeatherLoader.parse(OPEN_METEO, "Chimaltenango");

        assertEquals("Chimaltenango", data.city);
        assertEquals(21.6, data.temperature, 0.001);
        assertEquals(3, data.code);
        assertTrue(data.day);
    }

    @Test
    public void aNightReadingIsKeptAsNight() throws Exception {
        WeatherLoader.Data data = WeatherLoader.parse(
                OPEN_METEO.replace("\"is_day\":1", "\"is_day\":0"), "Chimaltenango");

        assertFalse(data.day);
    }
}
