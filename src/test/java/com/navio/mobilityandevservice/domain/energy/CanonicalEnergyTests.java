package com.navio.mobilityandevservice.domain.energy;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class CanonicalEnergyTests {
    @Test void sharedChronologicalTrips() throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var stream = getClass().getResourceAsStream("/energy/energy-v1.json")) {
            var fixtures = mapper.readTree(stream);
            for (var fixture : fixtures.get("trips")) {
                var model = mapper.treeToValue(fixture.get("model"), CanonicalEnergy.Model.class);
                var stops = mapper.treeToValue(fixture.get("stops"), CanonicalEnergy.Stop[].class);
                var results = CanonicalEnergy.project(model, fixture.get("initialSocPct").asDouble(), java.util.List.of(stops), 12);
                for (int i = 0; i < results.size(); i++) {
                    var actual = mapper.valueToTree(results.get(i).result());
                    var fields = fixture.get("expected").get(i).fields();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        var value = field.getKey().equals("startingSocPct") ? mapper.valueToTree(results.get(i).startingSocPct()) : actual.get(field.getKey());
                        if (field.getValue().isNumber()) assertThat(value.asDouble()).as(fixture.get("name").asText()).isCloseTo(field.getValue().asDouble(), org.assertj.core.data.Offset.offset(fixtures.get("tolerance").asDouble()));
                        else assertThat(value).isEqualTo(field.getValue());
                    }
                }
            }
        }
    }
    @Test void sharedGoldenFixtures() throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var stream = getClass().getResourceAsStream("/energy/energy-v1.json")) {
            var fixtures = mapper.readTree(stream);
            double tolerance = fixtures.get("tolerance").asDouble();
            for (var fixture : fixtures.get("cases")) {
                var result = CanonicalEnergy.calculate(mapper.treeToValue(fixture.get("input"), CanonicalEnergy.Input.class));
                var actual = mapper.valueToTree(result);
                var fields = fixture.get("expected").fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (field.getValue().isNumber()) {
                        assertThat(actual.get(field.getKey()).isNumber()).as(fixture.get("name").asText() + field.getKey()).isTrue();
                        assertThat(actual.get(field.getKey()).asDouble()).isCloseTo(field.getValue().asDouble(), org.assertj.core.data.Offset.offset(tolerance));
                    } else assertThat(actual.get(field.getKey())).as(fixture.get("name").asText() + field.getKey()).isEqualTo(field.getValue());
                }
            }
        }
    }
}
