package com.madhumita.dispatch.api;

import com.madhumita.dispatch.repository.DeliveryOrderRepository;
import com.madhumita.dispatch.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises real HTTP serialization, bean validation, controller wiring and H2/JPA persistence. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DispatchApiIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private DeliveryOrderRepository orderRepository;
    @Autowired private VehicleRepository vehicleRepository;

    @BeforeEach
    void cleanDatabase() {
        orderRepository.deleteAll();
        vehicleRepository.deleteAll();
    }

    @Test
    void rejectsMissingCoordinatesInsteadOfTreatingThemAsZero() throws Exception {
        String body = """
                {"orders":[{"orderId":"O-1","longitude":77.5,"address":"MG Road","packageWeight":2,"priority":"HIGH"}]}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details['orders[0].latitude']", notNullValue()));
    }

    @Test
    void rejectsOutOfRangeCoordinatesAndInvalidPriority() throws Exception {
        String invalidLatitude = """
                {"orders":[{"orderId":"O-1","latitude":91,"longitude":77,"address":"MG Road","packageWeight":2,"priority":"HIGH"}]}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(invalidLatitude))
                .andExpect(status().isBadRequest());

        String invalidPriority = """
                {"orders":[{"orderId":"O-1","latitude":12,"longitude":77,"address":"MG Road","packageWeight":2,"priority":"URGENT"}]}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(invalidPriority))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Priority must be HIGH, MEDIUM, or LOW")));
    }

    @Test
    void rejectsZeroWeightAndZeroVehicleCapacity() throws Exception {
        String zeroWeight = """
                {"orders":[{"orderId":"O-1","latitude":12,"longitude":77,"address":"MG Road","packageWeight":0,"priority":"HIGH"}]}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(zeroWeight))
                .andExpect(status().isBadRequest());

        String zeroCapacity = """
                [{"vehicleId":"V-1","capacity":0,"currentLatitude":12,"currentLongitude":77,"currentAddress":"Depot"}]
                """;
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content(zeroCapacity))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingVehicleCoordinates() throws Exception {
        String body = """
                [{"vehicleId":"V-1","capacity":10,"currentLongitude":77.5,"currentAddress":"Indiranagar"}]
                """;
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", aMapWithSize(greaterThan(0))));
    }

    @Test
    void persistsUpsertsAndReturnsACompleteDispatchPlan() throws Exception {
        String vehicles = """
                [{"vehicleId":"V-1","capacity":10,"currentLatitude":12.9,"currentLongitude":77.6,"currentAddress":"Indiranagar, Bangalore, Karnataka, India"}]
                """;
        String orders = """
                {"orders":[{"orderId":"O-1","latitude":12.91,"longitude":77.61,"address":"MG Road, Bangalore, Karnataka, India","packageWeight":4,"priority":"HIGH"}]}
                """;
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content(vehicles))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(orders))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/dispatch/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatchPlan[0].vehicleId").value("V-1"))
                .andExpect(jsonPath("$.dispatchPlan[0].currentAddress").value("Indiranagar, Bangalore, Karnataka, India"))
                .andExpect(jsonPath("$.dispatchPlan[0].assignedOrders[0].address").value("MG Road, Bangalore, Karnataka, India"))
                .andExpect(jsonPath("$.dispatchPlan[0].totalLoad").value(4.0))
                .andExpect(jsonPath("$.unassignedOrders", hasSize(0)));
    }

    @Test
    void repeatedBusinessIdUpdatesExistingDatabaseRow() throws Exception {
        String first = """
                {"orders":[{"orderId":"O-1","latitude":12,"longitude":77,"address":"Old address","packageWeight":2,"priority":"LOW"}]}
                """;
        String update = """
                {"orders":[{"orderId":"O-1","latitude":13,"longitude":78,"address":"New address","packageWeight":5,"priority":"HIGH"}]}
                """;
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isAccepted());
        org.junit.jupiter.api.Assertions.assertEquals(1, orderRepository.count());
        org.junit.jupiter.api.Assertions.assertEquals("New address", orderRepository.findAll().get(0).getAddress());
        org.junit.jupiter.api.Assertions.assertEquals(5.0, orderRepository.findAll().get(0).getPackageWeight());
    }

    @Test
    void emptyUpsertIsAValidNoOpAndDoesNotDeleteExistingRows() throws Exception {
        String vehicle = """
                [{"vehicleId":"V-1","capacity":10,"currentLatitude":0,"currentLongitude":0,"currentAddress":"Depot"}]
                """;
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content(vehicle))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isAccepted());
        org.junit.jupiter.api.Assertions.assertEquals(1, vehicleRepository.count());
    }

    @Test
    void exposesOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/dispatch/plan']", notNullValue()));
    }

    private static final String REPORTED_VEHICLES = """
            [
              {"vehicleId":"VEH001","capacity":100,"currentLatitude":28.7041,"currentLongitude":77.1025,"currentAddress":"Karol Bagh, Delhi, India"},
              {"vehicleId":"VEH002","capacity":80,"currentLatitude":28.5355,"currentLongitude":77.3910,"currentAddress":"Sector 18, Noida, Uttar Pradesh, India"},
              {"vehicleId":"VEH003","capacity":120,"currentLatitude":28.4595,"currentLongitude":77.0266,"currentAddress":"Cyber Hub, Gurgaon, Haryana, India"},
              {"vehicleId":"VEH004","capacity":90,"currentLatitude":28.6139,"currentLongitude":77.2090,"currentAddress":"Connaught Place, Delhi, India"},
              {"vehicleId":"VEH005","capacity":110,"currentLatitude":28.7041,"currentLongitude":77.1025,"currentAddress":"Karol Bagh, Delhi, India"}
            ]""";

    @Test
    void acceptsTheFiveVehicleArrayFromTheBugReport() throws Exception {
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content(REPORTED_VEHICLES))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("success"));
        org.junit.jupiter.api.Assertions.assertEquals(5, vehicleRepository.count());
    }

    @Test
    void acceptsVehiclesAsTextPlainAndWithoutContentType() throws Exception {
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.TEXT_PLAIN).content(REPORTED_VEHICLES))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/dispatch/vehicles").content(REPORTED_VEHICLES))
                .andExpect(status().isAccepted());
    }

    @Test
    void acceptsVehicleWrapperAndOrdersAsBareArray() throws Exception {
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicles\":" + REPORTED_VEHICLES + "}"))
                .andExpect(status().isAccepted());
        String orders = "[{\"orderId\":\"O-1\",\"latitude\":12,\"longitude\":77,\"address\":\"MG Road\",\"packageWeight\":2,\"priority\":\"HIGH\"}]";
        mockMvc.perform(post("/api/dispatch/orders").contentType(MediaType.APPLICATION_JSON).content(orders))
                .andExpect(status().isAccepted());
        org.junit.jupiter.api.Assertions.assertEquals(1, orderRepository.count());
    }

    @Test
    void acceptsJsonPastedWithNonBreakingSpacesAndSmartQuotes() throws Exception {
        String pasted = "\uFEFF[\u00A0{\u201CvehicleId\u201D:\u201CV-9\u201D,\u201Ccapacity\u201D:50,\u201CcurrentLatitude\u201D:1,"
                + "\u201CcurrentLongitude\u201D:2,\u201CcurrentAddress\u201D:\u201CDepot\u201D}]";
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content(pasted))
                .andExpect(status().isAccepted());
    }

    @Test
    void malformedJsonExplainsWhereItFailed() throws Exception {
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content("[{\"vehicleId\": ]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Malformed JSON at line 1")));
    }

    @Test
    void duplicateVehicleIdsAreRejected() throws Exception {
        String dup = "[" + REPORTED_VEHICLES.substring(1, REPORTED_VEHICLES.indexOf("},") + 1) + ","
                + REPORTED_VEHICLES.substring(1, REPORTED_VEHICLES.indexOf("},") + 1) + "]";
        mockMvc.perform(post("/api/dispatch/vehicles").contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Duplicate vehicleId")));
    }

    @Test
    void unknownRouteKeepsItsHttpStatusInsteadOfBecoming500() throws Exception {
        mockMvc.perform(delete("/api/dispatch/vehicles"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void openApiDocumentsErrorAndSuccessResponsesForVehicles() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/dispatch/vehicles'].post.responses['202']", notNullValue()))
                .andExpect(jsonPath("$.paths['/api/dispatch/vehicles'].post.responses['400']", notNullValue()))
                .andExpect(jsonPath("$.paths['/api/dispatch/vehicles'].post.requestBody.content['application/json']", notNullValue()));
    }
}
