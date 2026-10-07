package com.srividhya.bankrca.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class PayloadMaskerTest {

    private final PayloadMasker masker = new PayloadMasker(new PiiMasker());

    @Test
    void masksByFieldNameAtAnyDepthAndKeepsTheShape() {
        JsonNode masked = masker.mask("""
                {"pan":"4111111111111111","amount":"200","customer":{"emailAddress":"a.b@example.com","tier":"GOLD"},
                 "accounts":[{"accountNumber":"000123456789","type":"CHK"}],"envelope":null,"cardExpiry":"12/29"}""");

        assertThat(masked.toString()).isEqualTo("{\"pan\":\"***\",\"amount\":\"200\",\"customer\":{\"emailAddress\":\"***\","
                + "\"tier\":\"GOLD\"},\"accounts\":[{\"accountNumber\":\"***\",\"type\":\"CHK\"}],\"envelope\":null,"
                + "\"cardExpiry\":\"***\"}");
    }

    @Test
    void aSensitiveValueUnderAnInnocentNameIsStillCaughtByPattern() {
        JsonNode masked = masker.mask("{\"note\":\"customer read out 4111 1111 1111 1111\",\"ref\":\"a.b@example.com\"}");

        assertThat(masked.toString()).doesNotContain("4111 1111 1111 1111").doesNotContain("a.b@example.com");
    }

    @Test
    void aNullSensitiveFieldStaysNullBecauseItIsEvidence() {
        assertThat(masker.mask("{\"pan\":null}").toString()).isEqualTo("{\"pan\":null}");
    }

    @Test
    void textThatIsNotJsonIsPatternMasked() {
        assertThat(masker.mask("<pan>4111111111111111</pan>").asString()).doesNotContain("4111111111111111");
        assertThat(masker.mask(" ")).isNull();
    }
}
