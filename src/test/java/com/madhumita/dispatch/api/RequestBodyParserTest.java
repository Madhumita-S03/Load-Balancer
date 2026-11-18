package com.madhumita.dispatch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.madhumita.dispatch.domain.Priority;
import com.madhumita.dispatch.dto.OrderInput;
import com.madhumita.dispatch.dto.VehicleInput;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestBodyParserTest {
    private final RequestBodyParser parser = new RequestBodyParser(new ObjectMapper(),
            Validation.buildDefaultValidatorFactory().getValidator());

    private static final String VEHICLE = "{\"vehicleId\":\"V1\",\"capacity\":100,\"currentLatitude\":28.7041,"
            + "\"currentLongitude\":77.1025,\"currentAddress\":\"Karol Bagh, Delhi, India\"}";

    @Test
    void parsesBareArrayWithIntegerCapacity() {
        List<VehicleInput> result = parser.parseList("[" + VEHICLE + "]", VehicleInput.class, "vehicles");
        assertEquals(1, result.size());
        assertEquals(100.0, result.get(0).capacity());
    }

    @Test
    void parsesWrapperObjectAndSingleObject() {
        assertEquals(1, parser.parseList("{\"vehicles\":[" + VEHICLE + "]}", VehicleInput.class, "vehicles").size());
        assertEquals(1, parser.parseList(VEHICLE, VehicleInput.class, "vehicles").size());
    }

    @Test
    void parsesEmptyArrayAsNoOp() {
        assertTrue(parser.parseList("[]", VehicleInput.class, "vehicles").isEmpty());
    }

    @Test
    void toleratesNonBreakingSpacesBomAndSmartQuotes() {
        String pasted = "\uFEFF[\u00A0{\u201CvehicleId\u201D:\u201CV1\u201D,\u201Ccapacity\u201D:100,\u201CcurrentLatitude\u201D:1,"
                + "\u201CcurrentLongitude\u201D:2,\u201CcurrentAddress\u201D:\u201CDepot\u201D}]";
        List<VehicleInput> result = parser.parseList(pasted, VehicleInput.class, "vehicles");
        assertEquals("V1", result.get(0).vehicleId());
    }

    @Test
    void malformedJsonReportsLineAndColumn() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> parser.parseList("[{\"vehicleId\": }]", VehicleInput.class, "vehicles"));
        assertTrue(ex.getMessage().contains("Malformed JSON at line 1, column"), ex.getMessage());
    }

    @Test
    void blankAndScalarBodiesAreRejected() {
        assertThrows(InvalidRequestException.class, () -> parser.parseList("  ", VehicleInput.class, "vehicles"));
        assertThrows(InvalidRequestException.class, () -> parser.parseList("42", VehicleInput.class, "vehicles"));
        assertThrows(InvalidRequestException.class, () -> parser.parseList("null", VehicleInput.class, "vehicles"));
        assertThrows(InvalidRequestException.class, () -> parser.parseList("[1]", VehicleInput.class, "vehicles"));
        assertThrows(InvalidRequestException.class, () -> parser.parseList("{\"vehicles\":5}", VehicleInput.class, "vehicles"));
    }

    @Test
    void validationErrorsUseIndexedFieldPaths() {
        String body = "[" + VEHICLE + ",{\"vehicleId\":\"V2\",\"capacity\":-5,\"currentLatitude\":95,\"currentLongitude\":0,\"currentAddress\":\"x\"}]";
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> parser.parseList(body, VehicleInput.class, "vehicles"));
        assertEquals("Validation failed", ex.getMessage());
        assertTrue(ex.getDetails().containsKey("vehicles[1].capacity"));
        assertTrue(ex.getDetails().containsKey("vehicles[1].currentLatitude"));
    }

    @Test
    void wrongTypeAndBadEnumGiveSpecificMessages() {
        InvalidRequestException type = assertThrows(InvalidRequestException.class, () -> parser.parseList(
                "[{\"vehicleId\":\"V1\",\"capacity\":\"abc\",\"currentLatitude\":1,\"currentLongitude\":1,\"currentAddress\":\"x\"}]",
                VehicleInput.class, "vehicles"));
        assertTrue(type.getMessage().contains("'capacity'") && type.getMessage().contains("vehicles[0]"), type.getMessage());

        InvalidRequestException priority = assertThrows(InvalidRequestException.class, () -> parser.parseList(
                "[{\"orderId\":\"O1\",\"latitude\":1,\"longitude\":1,\"address\":\"x\",\"packageWeight\":1,\"priority\":\"URGENT\"}]",
                OrderInput.class, "orders"));
        assertTrue(priority.getMessage().contains("Priority must be HIGH, MEDIUM, or LOW"));
    }

    @Test
    void parsesOrders() {
        List<OrderInput> orders = parser.parseList(
                "[{\"orderId\":\"O1\",\"latitude\":1,\"longitude\":1,\"address\":\"x\",\"packageWeight\":2,\"priority\":\"HIGH\"}]",
                OrderInput.class, "orders");
        assertEquals(Priority.HIGH, orders.get(0).priority());
    }
}
