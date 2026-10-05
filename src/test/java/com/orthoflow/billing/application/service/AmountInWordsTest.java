package com.orthoflow.billing.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmountInWordsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|zéro", "1|un", "16|seize", "17|dix-sept", "21|vingt et un", "22|vingt-deux", "31|trente et un", "60|soixante",
            "61|soixante et un", "70|soixante-dix", "71|soixante et onze", "72|soixante-douze", "77|soixante-dix-sept",
            "80|quatre-vingts", "81|quatre-vingt-un", "91|quatre-vingt-onze", "99|quatre-vingt-dix-neuf",
            "100|cent", "101|cent un", "180|cent quatre-vingts", "200|deux cents", "201|deux cent un", "280|deux cent quatre-vingts",
            "999|neuf cent quatre-vingt-dix-neuf", "1000|mille", "1001|mille un", "1080|mille quatre-vingts", "2000|deux mille",
            "80000|quatre-vingt mille", "200000|deux cent mille", "200001|deux cent mille un", "1000000|un million",
            "2500000|deux millions cinq cent mille", "1250|mille deux cent cinquante", "12000|douze mille",
            "1000000000|un milliard"})
    void writesNumbersAsFrenchAccountantsDo(long n, String expected) {
        assertThat(AmountInWords.words(n)).isEqualTo(expected);
    }

    @Test
    void anAmountNamesDirhamsAndCentimes() {
        assertThat(AmountInWords.french(new BigDecimal("1250.50"), "dirham", "centime"))
                .isEqualTo("mille deux cent cinquante dirhams et cinquante centimes");
        assertThat(AmountInWords.french(new BigDecimal("1"), "dirham", "centime")).isEqualTo("un dirham");
        assertThat(AmountInWords.french(new BigDecimal("0.01"), "dirham", "centime")).isEqualTo("zéro dirham et un centime");
        assertThat(AmountInWords.french(new BigDecimal("12000.00"), "dirham", "centime")).isEqualTo("douze mille dirhams");
        assertThat(AmountInWords.french(new BigDecimal("80.00"), "dirham", "centime")).isEqualTo("quatre-vingts dirhams");
    }
}
