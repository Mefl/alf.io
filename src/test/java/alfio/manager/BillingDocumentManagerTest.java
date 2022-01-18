/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.manager.payment.PaymentSpecification;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.testSupport.MaybeConfigurationBuilder;
import alfio.model.Audit;
import alfio.model.BillingDetails;
import alfio.model.PurchaseContext;
import alfio.model.TotalPrice;
import alfio.model.extension.InvoiceGeneration;
import alfio.model.system.ConfigurationKeys;
import alfio.model.user.Organization;
import alfio.repository.*;
import alfio.repository.user.OrganizationRepository;
import alfio.repository.user.UserRepository;
import alfio.test.util.TestUtil;
import alfio.util.ClockProvider;
import alfio.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static alfio.model.Audit.EntityType.RESERVATION;
import static alfio.model.Audit.EventType.EXTERNAL_INVOICE_NUMBER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BillingDocumentManagerTest {

    private static final String RESERVATION_ID = "RESERVATION_ID";
    private static final int ORG_ID = 1;
    private TicketReservationRepository ticketReservationRepository;
    private ExtensionManager extensionManager;
    private AuditingRepository auditingRepository;
    private InvoiceSequencesRepository invoiceSequencesRepository;
    private ConfigurationManager configurationManager;
    private PaymentSpecification spec;
    private TotalPrice totalPrice;
    private PurchaseContext purchaseContext;
    private BillingDetails billingDetails;
    private BillingDocumentManager billingDocumentManager;
    private final ConfigurationLevel configurationLevel = ConfigurationLevel.organization(ORG_ID);

    @BeforeEach
    void setUp() {
        ticketReservationRepository = mock(TicketReservationRepository.class);
        extensionManager = mock(ExtensionManager.class);
        auditingRepository = mock(AuditingRepository.class);
        invoiceSequencesRepository = mock(InvoiceSequencesRepository.class);
        configurationManager = mock(ConfigurationManager.class);
        spec = mock(PaymentSpecification.class);
        totalPrice = mock(TotalPrice.class);
        purchaseContext = mock(PurchaseContext.class);
        when(purchaseContext.getOrganizationId()).thenReturn(ORG_ID);
        when(purchaseContext.getConfigurationLevel()).thenReturn(configurationLevel);
        when(spec.getPurchaseContext()).thenReturn(purchaseContext);
        when(spec.getReservationId()).thenReturn(RESERVATION_ID);
        billingDetails = mock(BillingDetails.class);
        when(ticketReservationRepository.getBillingDetailsForReservation(RESERVATION_ID))
            .thenReturn(billingDetails);
        var organizationRepository = mock(OrganizationRepository.class);
        when(organizationRepository.getById(ORG_ID)).thenReturn(mock(Organization.class));
        billingDocumentManager = new BillingDocumentManager(
            mock(BillingDocumentRepository.class),
            mock(Json.class),
            configurationManager,
            mock(TicketRepository.class),
            mock(TicketCategoryRepository.class),
            organizationRepository,
            mock(UserRepository.class),
            auditingRepository,
            ticketReservationRepository,
            TestUtil.FIXED_TIME_CLOCK,
            extensionManager,
            invoiceSequencesRepository
        );
    }

    @ParameterizedTest
    @CsvSource({
        "0,0,0",
        "0,0,1",
        "0,1,0",
        "0,1,1",
        "1,0,0",
        "1,0,1",
        "1,1,0",
    })
    void doNotGenerateInvoiceNumber(String hasAllConfiguration, String requiresPayment, String invoiceRequested) {
        when(configurationManager.hasAllConfigurationsForInvoice(purchaseContext)).thenReturn("1".equals(hasAllConfiguration));
        when(totalPrice.requiresPayment()).thenReturn("1".equals(requiresPayment));
        when(spec.isInvoiceRequested()).thenReturn("1".equals(invoiceRequested));
        assertTrue(billingDocumentManager.generateInvoiceNumber(spec, totalPrice).isEmpty());
        verify(configurationManager, never()).getFor(ConfigurationKeys.INVOICE_NUMBER_PATTERN, configurationLevel);
        verify(invoiceSequencesRepository, never()).lockReservationForUpdate(ORG_ID);
        verify(invoiceSequencesRepository, never()).incrementSequenceFor(ORG_ID);
    }

    @Test
    void generateInvoiceNumberFromSequence() {
        when(configurationManager.hasAllConfigurationsForInvoice(purchaseContext)).thenReturn(true);
        when(totalPrice.requiresPayment()).thenReturn(true);
        when(spec.isInvoiceRequested()).thenReturn(true);
        when(extensionManager.handleInvoiceGeneration(eq(spec), eq(totalPrice), eq(billingDetails), any()))
            .thenReturn(Optional.empty());
        when(invoiceSequencesRepository.lockReservationForUpdate(ORG_ID)).thenReturn(123);
        when(configurationManager.getFor(ConfigurationKeys.INVOICE_NUMBER_PATTERN, configurationLevel))
            .thenReturn(MaybeConfigurationBuilder.missing(ConfigurationKeys.INVOICE_NUMBER_PATTERN));
        var invoiceNumberOptional = billingDocumentManager.generateInvoiceNumber(spec, totalPrice);
        assertTrue(invoiceNumberOptional.isPresent());
        assertEquals("123", invoiceNumberOptional.get());
        verify(invoiceSequencesRepository).incrementSequenceFor(ORG_ID);
        verify(auditingRepository, never()).insert(eq(RESERVATION_ID), any(), eq(purchaseContext), eq(EXTERNAL_INVOICE_NUMBER), any(), eq(RESERVATION), eq(RESERVATION_ID), any());
    }

    @ParameterizedTest
    @CsvSource({
        "TEST-%d,TEST-123",
        ",123"
    })
    void generateInvoiceNumberFromSequenceWithPattern(String pattern, String expected) {
        when(configurationManager.hasAllConfigurationsForInvoice(purchaseContext)).thenReturn(true);
        when(totalPrice.requiresPayment()).thenReturn(true);
        when(spec.isInvoiceRequested()).thenReturn(true);
        when(extensionManager.handleInvoiceGeneration(eq(spec), eq(totalPrice), eq(billingDetails), any()))
            .thenReturn(Optional.empty());
        when(invoiceSequencesRepository.lockReservationForUpdate(ORG_ID)).thenReturn(123);
        when(configurationManager.getFor(ConfigurationKeys.INVOICE_NUMBER_PATTERN, configurationLevel))
            .thenReturn(MaybeConfigurationBuilder.existing(ConfigurationKeys.INVOICE_NUMBER_PATTERN, pattern));
        var invoiceNumberOptional = billingDocumentManager.generateInvoiceNumber(spec, totalPrice);
        assertTrue(invoiceNumberOptional.isPresent());
        assertEquals(expected, invoiceNumberOptional.get());
        verify(invoiceSequencesRepository).incrementSequenceFor(ORG_ID);
        verify(auditingRepository, never()).insert(eq(RESERVATION_ID), any(), eq(purchaseContext), eq(EXTERNAL_INVOICE_NUMBER), any(), eq(RESERVATION), eq(RESERVATION_ID), any());
    }

    @Test
    void generateInvoiceNumberFromExtension() {
        when(configurationManager.hasAllConfigurationsForInvoice(purchaseContext)).thenReturn(true);
        when(totalPrice.requiresPayment()).thenReturn(true);
        when(spec.isInvoiceRequested()).thenReturn(true);
        var invoiceGeneration = new InvoiceGeneration();
        invoiceGeneration.setInvoiceNumber("external");
        when(extensionManager.handleInvoiceGeneration(eq(spec), eq(totalPrice), eq(billingDetails), any()))
            .thenReturn(Optional.of(invoiceGeneration));
        var invoiceNumberOptional = billingDocumentManager.generateInvoiceNumber(spec, totalPrice);
        assertTrue(invoiceNumberOptional.isPresent());
        assertEquals("external", invoiceNumberOptional.get());
        verify(configurationManager, never()).getFor(ConfigurationKeys.INVOICE_NUMBER_PATTERN, configurationLevel);
        verify(invoiceSequencesRepository, never()).lockReservationForUpdate(ORG_ID);
        verify(invoiceSequencesRepository, never()).incrementSequenceFor(ORG_ID);
        verify(auditingRepository).insert(eq(RESERVATION_ID), any(), eq(purchaseContext), eq(EXTERNAL_INVOICE_NUMBER), any(), eq(RESERVATION), eq(RESERVATION_ID), eq(List.of(Map.of("invoiceNumber", "external"))));
    }


}