/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.ds.server.controller;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.dynamicsimulation.DynamicSimulationParameters;
import com.powsybl.dynawo.DynawoSimulationParameters;
import com.powsybl.dynawo.DynawoSimulationParameters.SolverType;
import com.powsybl.dynawo.mappings.UniversalSynchronousGeneratorMapping;
import com.powsybl.dynawo.networks.Ieee14EnergySources;
import com.powsybl.iidm.network.ImportConfig;
import com.powsybl.iidm.network.ImportersServiceLoader;
import com.powsybl.iidm.network.Network;
import com.powsybl.network.store.client.PreloadingStrategy;
import com.powsybl.network.store.iidm.impl.NetworkFactoryImpl;
import com.powsybl.timeseries.DoubleTimeSeries;
import com.powsybl.timeseries.StringTimeSeries;
import com.powsybl.timeseries.TimeSeries;
import com.powsybl.timeseries.TimeSeriesDataType;
import org.apache.commons.collections4.CollectionUtils;
import org.gridsuite.ds.server.controller.utils.ParameterTestUtils;
import org.gridsuite.ds.server.dto.DynamicSimulationParametersInfos;
import org.gridsuite.ds.server.dto.DynamicSimulationStatus;
import org.gridsuite.ds.server.dto.curve.CurveInfos;
import org.gridsuite.ds.server.dto.event.EventInfos;
import org.gridsuite.ds.server.dto.timeseries.TimeSeriesGroupInfos;
import org.gridsuite.ds.server.entities.parameters.DynamicSimulationParametersEntity;
import org.gridsuite.ds.server.service.client.timeseries.TimeSeriesClientTest;
import org.gridsuite.ds.server.service.parameters.ParameterUtils;
import org.gridsuite.ds.server.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gridsuite.computation.service.NotificationService.HEADER_RESULT_UUID;
import static org.gridsuite.computation.service.NotificationService.HEADER_USER_ID;
import static org.gridsuite.ds.server.utils.Utils.RESOURCE_PATH_DELIMITER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dynamic simulation of IEEE 14 run by the server with Dynawo through JNI, on the network itself as the network store
 * gives it, its dynamic models given by the universal mapping of powsybl-dynawo instead of a mapping of the dynamic
 * mapping server. Run in the jni profile, from the installation of Dynawo of the {@code DYNAWO_INSTALL_DIR} environment
 * variable, that needs the models of the universal mapping of IEEE 14 (GeneratorSynchronousFourWindingsGoverPropVRPropInt,
 * GeneratorSynchronousThreeWindingsGoverPropVRPropInt, LoadAlphaBeta, StaticVarCompensator) and of its events
 * (EventQuadripoleDisconnection, NodeFault).
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
@EnabledIfEnvironmentVariable(named = "DYNAWO_INSTALL_DIR", matches = ".+")
class DynamicSimulationControllerIEEE14JniTest extends AbstractDynamicSimulationControllerTest {

    private static final String DATA_IEEE14_BASE_DIR = RESOURCE_PATH_DELIMITER + "data" + RESOURCE_PATH_DELIMITER + "ieee14";
    private static final String NETWORK_FILE = "IEEE14.iidm";
    private static final String NETWORK_UUID_STRING = "11111111-0000-0000-0000-000000000000";
    private static final UUID PARAMETERS_UUID = UUID.fromString("5170a122-ebe5-4fa2-9cd9-d8f76ce04db3");
    private static final String DISCONNECTED_LINE_ID = "_BUS____1-BUS____5-1_AC";

    private final Map<UUID, List<TimeSeries<?, ?>>> timeSeriesMockBd = new HashMap<>();

    private Network network;

    @Autowired
    private OutputDestination output;

    @Override
    public OutputDestination getOutputDestination() {
        return output;
    }

    @Override
    protected void initNetworkStoreServiceMock() {
        // IEEE 14 in the network store implementation of IIDM, as the network store gives it to the server
        try (InputStream is = Objects.requireNonNull(getClass().getResourceAsStream(DATA_IEEE14_BASE_DIR + RESOURCE_PATH_DELIMITER + NETWORK_FILE))) {
            network = Network.read(NETWORK_FILE, is, LocalComputationManager.getDefault(), new ImportConfig(), null,
                    new NetworkFactoryImpl(), new ImportersServiceLoader(), ReportNode.NO_OP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // the universal mapping models the generators from their energy source
        Ieee14EnergySources.apply(network);
        given(networkStoreClient.getNetwork(UUID.fromString(NETWORK_UUID_STRING), PreloadingStrategy.COLLECTION)).willReturn(network);
    }

    @Override
    protected void initDynamicMappingServiceMock() {
        // no mapping of the dynamic mapping server: the universal mapping is applied to the network
    }

    @Override
    protected void initTimeSeriesServiceMock() {
        Mockito.doAnswer(invocation -> {
            List<TimeSeries<?, ?>> data = invocation.getArgument(0);
            if (CollectionUtils.isEmpty(data)) {
                return null;
            }
            UUID seriesUuid = data.getFirst().getMetadata().getDataType() == TimeSeriesDataType.STRING
                    ? TimeSeriesClientTest.TIME_LINE_UUID
                    : TimeSeriesClientTest.TIME_SERIES_UUID;
            timeSeriesMockBd.put(seriesUuid, data);
            return new TimeSeriesGroupInfos(seriesUuid);
        }).when(timeSeriesClient).sendTimeSeries(any());
    }

    @AfterEach
    @Override
    void tearDown() throws Exception {
        super.tearDown();
        mockMvc.perform(delete("/v1/results")).andExpect(status().isOk());
        timeSeriesMockBd.clear();
    }

    /**
     * The curves of the IEEE 14 test, with the names of the variables of Dynawo 2.0: without the _value of the former
     * Modelica pins
     */
    private static List<CurveInfos> getCurveInfosList() {
        return ParameterTestUtils.getCurveInfosList().stream()
                .map(curveInfos -> curveInfos.getVariableId().endsWith("_mode_value")
                        ? new CurveInfos(curveInfos.getId(), curveInfos.getEquipmentType(), curveInfos.getEquipmentId(),
                                curveInfos.getVariableId().substring(0, curveInfos.getVariableId().length() - "_value".length()))
                        : curveInfos)
                .toList();
    }

    @Test
    void testUniversalMappingThroughJni() throws Exception {
        DynamicSimulationParametersInfos parameters = ParameterUtils.getDefaultParametersValues();
        parameters.setStartTime(0d);
        parameters.setStopTime(50d);
        parameters.setSolver(SolverType.SIM);
        List<CurveInfos> curveInfosList = getCurveInfosList();
        parameters.setCurves(curveInfosList);
        // the universal mapping of powsybl-dynawo, no mapping of the dynamic mapping server
        parameters.setMappingName(UniversalSynchronousGeneratorMapping.DYNAWALTZ_NAME);
        given(dynamicSimulationParametersRepository.findById(PARAMETERS_UUID)).willReturn(Optional.of(new DynamicSimulationParametersEntity(parameters)));
        // the line between the buses 1 and 5 opened on its side 2 at 10 s, a fault on the bus 1 at 20 s
        List<EventInfos> eventInfosList = ParameterTestUtils.getEventInfosList();
        assertThat(network.getLine(DISCONNECTED_LINE_ID).getTerminal2().isConnected()).isTrue();

        MvcResult result = mockMvc.perform(
                post("/v1/networks/{networkUuid}/run", NETWORK_UUID_STRING)
                        .param("parametersUuid", PARAMETERS_UUID.toString())
                        .contentType(APPLICATION_JSON)
                        .header(HEADER_USER_ID, "testUserId")
                        .content(objectMapper.writeValueAsString(eventInfosList)))
                .andExpect(status().isOk())
                .andReturn();
        UUID runUuid = objectMapper.readValue(result.getResponse().getContentAsString(), UUID.class);

        Message<byte[]> message = output.receive(1000 * 60, dsResultDestination);
        assertThat(message.getHeaders()).containsEntry(HEADER_RESULT_UUID, runUuid.toString());
        assertResultStatus(runUuid, DynamicSimulationStatus.CONVERGED);
        verifyNoInteractions(dynamicMappingClient);

        // the curves of Dynawo, read from memory, sent to the time series server
        List<TimeSeries<?, ?>> curves = timeSeriesMockBd.get(TimeSeriesClientTest.TIME_SERIES_UUID);
        assertThat(curves.stream().map(timeSeries -> timeSeries.getMetadata().getName()))
                .containsExactlyInAnyOrderElementsOf(curveInfosList.stream()
                        .map(curveInfos -> curveInfos.getEquipmentId() + "_" + curveInfos.getVariableId()).toList());
        curves.forEach(curve -> assertThat(((DoubleTimeSeries) curve).toArray()).isNotEmpty());
        // the generator, out of its steady state after the events
        double[] omega = ((DoubleTimeSeries) curves.stream()
                .filter(curve -> curve.getMetadata().getName().equals("_GEN____3_SM_generator_omegaPu")).findFirst().orElseThrow()).toArray();
        assertThat(Arrays.stream(omega).max().orElseThrow() - Arrays.stream(omega).min().orElseThrow()).isGreaterThan(1e-6);
        // its timeline
        List<TimeSeries<?, ?>> timeline = timeSeriesMockBd.get(TimeSeriesClientTest.TIME_LINE_UUID);
        assertThat(timeline).hasSize(1);
        assertThat(((StringTimeSeries) timeline.getFirst()).toArray()).isNotEmpty();

        // the final state written by Dynawo into the network of the store: the line open on its side 2
        assertThat(network.getLine(DISCONNECTED_LINE_ID).getTerminal1().isConnected()).isTrue();
        assertThat(network.getLine(DISCONNECTED_LINE_ID).getTerminal2().isConnected()).isFalse();

        // the dump of the final state, written by Dynawo on disk
        result = mockMvc.perform(get("/v1/results/{resultUuid}/output-state", runUuid))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();

        // the parameters of the result: those of the models of the mapping
        result = mockMvc.perform(get("/v1/results/{resultUuid}/parameters", runUuid))
                .andExpect(status().isOk())
                .andReturn();
        DynamicSimulationParameters resultParameters = Utils.unzip(result.getResponse().getContentAsByteArray(), objectMapper, DynamicSimulationParameters.class);
        assertThat(resultParameters.getExtension(DynawoSimulationParameters.class).getModelParameters()).isNotEmpty();
    }
}
