/*
 * This file is part of LibrePlan
 *
 * Copyright (C) 2013 St. Antoniusziekenhuis
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.libreplan.importers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.LocalDate;
import org.libreplan.business.common.IAdHocTransactionService;
import org.libreplan.business.common.IOnTransaction;
import org.libreplan.business.common.daos.IConfigurationDAO;
import org.libreplan.business.common.daos.IConnectorDAO;
import org.libreplan.business.common.entities.Connector;
import org.libreplan.business.common.entities.PredefinedConnectorProperties;
import org.libreplan.business.common.entities.PredefinedConnectors;
import org.libreplan.business.common.exceptions.InstanceNotFoundException;
import org.libreplan.business.orders.daos.IOrderDAO;
import org.libreplan.business.orders.daos.IOrderSyncInfoDAO;
import org.libreplan.business.orders.entities.Order;
import org.libreplan.business.orders.entities.OrderSyncInfo;
import org.libreplan.business.resources.daos.IWorkerDAO;
import org.libreplan.business.resources.entities.Worker;
import org.libreplan.business.workreports.daos.IWorkReportLineDAO;
import org.libreplan.business.workreports.entities.WorkReportLine;
import org.libreplan.importers.tim.DurationDTO;
import org.libreplan.importers.tim.PersonDTO;
import org.libreplan.importers.tim.ProductDTO;
import org.libreplan.importers.tim.RegistrationDateDTO;
import org.libreplan.importers.tim.TimOptions;
import org.libreplan.importers.tim.TimeRegistrationDTO;
import org.libreplan.importers.tim.TimeRegistrationRequestDTO;
import org.libreplan.importers.tim.TimeRegistrationResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of export timesheets to tim
 *
 * @author Miciele Ghiorghis <m.ghiorghis@antoniusziekenhuis.nl>
 */
@Component
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class ExportTimesheetsToTim implements IExportTimesheetsToTim {

    private static final Log LOG = LogFactory
            .getLog(ExportTimesheetsToTim.class);

    @Autowired
    private IWorkerDAO workerDAO;

    @Autowired
    private IWorkReportLineDAO workReportLineDAO;

    @Autowired
    private IConfigurationDAO configurationDAO;

    @Autowired
    IOrderSyncInfoDAO orderSyncInfoDAO;

    @Autowired
    private IAdHocTransactionService adHocTransactionService;

    @Autowired
    private IConnectorDAO connectorDAO;

    @Autowired
    private IOrderDAO orderDAO;

    @Override
    @Transactional(readOnly = true)
    public void exportTimesheets() {
        String majorId = PredefinedConnectors.TIM.getMajorId();
        Connector connector = connectorDAO.findUniqueByMajorId(majorId);
        if (connector == null) {
            return;
        }

        List<Order> orders = orderDAO.getOrders();
        for (Order order : orders) {
            OrderSyncInfo orderSyncInfo = orderSyncInfoDAO
                    .findLastSynchronizedInfoByOrderAndConnectorId(order,
                            majorId);
            if (orderSyncInfo == null) {
                LOG.warn("Order '" + order.getName()
                        + "' is not yet synchronized");
            } else {
                boolean result = exportTimesheets(orderSyncInfo.getKey(),
                        orderSyncInfo.getOrder(), connector);
                LOG.info("Export successful: " + result);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exportTimesheets(String productCode, Order order) {
        if (productCode == null || productCode.isEmpty()) {
            throw new RuntimeException("Product code should not be empty");
        }
        if (order == null) {
            throw new RuntimeException("Order should not be empty");
        }
        Connector connector = connectorDAO
                .findUniqueByMajorId(PredefinedConnectors.TIM.getMajorId());
        if (connector == null) {
            throw new RuntimeException("Tim connector not found");
        }

        return exportTimesheets(productCode, order, connector);
    }

    /**
     * exports time sheets to Tim
     *
     * @param productCode
     *            the product code
     * @param order
     *            the order
     * @param connector
     *            the connector
     *
     * @return true if export is succeeded, false otherwise
     */
    private boolean exportTimesheets(String productCode, Order order,
            Connector connector) {
        Map<String, String> properties = connector.getPropertiesAsMap();

        String url = properties.get(PredefinedConnectorProperties.SERVER_URL);
        String userName = properties
                .get(PredefinedConnectorProperties.USERNAME);
        String password = properties
                .get(PredefinedConnectorProperties.PASSWORD);
        int nrDaysTimesheetToTim = Integer.parseInt(properties
                .get(PredefinedConnectorProperties.TIM_NR_DAYS_TIMESHEET));

        LocalDate dateNrOfDaysBack = new LocalDate()
                .minusDays(nrDaysTimesheetToTim);

        List<WorkReportLine> workReportLines = order.getWorkReportLines(
                dateNrOfDaysBack.toDateTimeAtStartOfDay().toDate(), new Date(),
                true);
        if (workReportLines == null || workReportLines.isEmpty()) {
            LOG.warn("No work reportlines are found");
            return false;
        }

        List<TimeRegistrationDTO> timeRegistrationDTOs = new ArrayList<TimeRegistrationDTO>();

        for (WorkReportLine workReportLine : workReportLines) {
            TimeRegistrationDTO timeRegistrationDTO = createExportTimeRegistration(
                    productCode, workReportLine);
            if (timeRegistrationDTO != null) {
                timeRegistrationDTOs.add(timeRegistrationDTO);
            }
        }

        if (timeRegistrationDTOs.isEmpty()) {
            LOG.warn("Unable to crate timeregistration for request");
            return false;
        }

        TimeRegistrationRequestDTO timeRegistrationRequestDTO = new TimeRegistrationRequestDTO();
        timeRegistrationRequestDTO.setTimeRegistrations(timeRegistrationDTOs);

        TimeRegistrationResponseDTO timeRegistrationResponseDTO = TimSoapClient
                .sendRequestReceiveResponse(url, userName, password,
                        timeRegistrationRequestDTO, TimeRegistrationResponseDTO.class);

        if (isRefsListEmpty(timeRegistrationResponseDTO.getRefs())) {
            LOG.warn("Registration response with empty refs");
            return false;
        }
        saveSyncInfoOnAnotherTransaction(productCode, order);
        return true;
    }

    /**
     * checks if list of refs is empty
     *
     * @param refs
     *            the list of refs
     * @return true if list is empty otherwise false
     */
    private boolean isRefsListEmpty(List<Integer> refs) {
        if (refs == null) {
            return true;
        }
        refs.removeAll(Collections.singleton(0));
        return refs.isEmpty();
    }

    /**
     * Saves synchronization info
     *
     * @param productCode
     *            the productcode
     * @param order
     *            the order
     */
    private void saveSyncInfoOnAnotherTransaction(final String productCode,
            final Order order) {
        adHocTransactionService
                .runOnAnotherTransaction(new IOnTransaction<Void>() {
                    @Override
                    public Void execute() {
                        OrderSyncInfo orderSyncInfo = OrderSyncInfo.create(
                                order, PredefinedConnectors.TIM.getMajorId());
                        orderSyncInfo.setKey(productCode);
                        orderSyncInfoDAO.save(orderSyncInfo);
                        return null;
                    }
                });
    }

    /**
     * Creates export time registration
     *
     * @param productCode
     *            the product code
     * @param workReportLine
     *            the workreportLine
     * @return timeRegistration DTO
     */
    private TimeRegistrationDTO createExportTimeRegistration(String productCode,
            WorkReportLine workReportLine) {
        Worker worker;
        String workerCode = workReportLine.getResource().getCode();
        try {
            worker = workerDAO.findByCode(workerCode);
        } catch (InstanceNotFoundException e) {
            LOG.warn("Worker \"" + workerCode + "\" not found!");
            return null;
        }

        PersonDTO personDTO = new PersonDTO();
        personDTO.setName(worker.getName());
        personDTO.setOptions(TimOptions.UPDATE_OR_INSERT);

        ProductDTO productDTO = new ProductDTO();
        productDTO.setOptions(TimOptions.UPDATE_OR_INSERT);
        productDTO.setCode(productCode);

        RegistrationDateDTO registrationDTO = new RegistrationDateDTO();
        registrationDTO.setOptions(TimOptions.UPDATE_OR_INSERT);
        registrationDTO.setDate(workReportLine.getLocalDate());

        DurationDTO durationDTO = new DurationDTO();
        durationDTO.setOptions(TimOptions.DECIMAL);
        durationDTO.setDuration(workReportLine.getEffort()
                .toHoursAsDecimalWithScale(2).doubleValue());

        TimeRegistrationDTO timeRegistrationDTO = new TimeRegistrationDTO();
        timeRegistrationDTO.setPerson(personDTO);
        timeRegistrationDTO.setProduct(productDTO);
        timeRegistrationDTO.setRegistrationDate(registrationDTO);
        timeRegistrationDTO.setDuration(durationDTO);
        return timeRegistrationDTO;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderSyncInfo getOrderLastSyncInfo(Order order) {
        return orderSyncInfoDAO.findLastSynchronizedInfoByOrderAndConnectorId(
                order, PredefinedConnectors.TIM.getMajorId());
    }

}