package capital.one.capital.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.model.ReturnReasonCode;
import capital.one.capital.model.TransferLog;
import capital.one.capital.repository.TransferLogRepository;
import capital.one.capital.service.IdempotencyService;
import capital.one.capital.service.Iso20022MarshallingService;
import capital.one.capital.service.Pacs004BuilderService;
import capital.one.capital.service.Pacs004BuilderService.BuildResult;
import capital.one.capital.service.PaymentKafkaProducer;
import capital.one.capital.service.WalletPaymentEvent;
import jakarta.xml.bind.JAXBException;

@Tag(name = "Payments", description = "Credit transfers — send and receive ISO 20022 pacs.008 payments")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentReturnController {

    private static final Logger log = LoggerFactory.getLogger(PaymentReturnController.class);

    private final Pacs004BuilderService pacs004Builder;
    private final Iso20022MarshallingService marshaller;
    private final TransferLogRepository transferLogRepository;
    private final InstitutionProperties institution;
    private final RestTemplate restTemplate;
    private final PaymentKafkaProducer kafkaProducer;
    private final IdempotencyService idempotencyService;

    public PaymentReturnController(
            Pacs004BuilderService pacs004Builder,
            Iso20022MarshallingService marshaller,
            TransferLogRepository transferLogRepository,
            InstitutionProperties institution,
            RestTemplate restTemplate,
            PaymentKafkaProducer kafkaProducer,
            IdempotencyService idempotencyService) {
        this.pacs004Builder = pacs004Builder;
        this.marshaller = marshaller;
        this.transferLogRepository = transferLogRepository;
        this.institution = institution;
        this.restTemplate = restTemplate;
        this.kafkaProducer = kafkaProducer;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Initiates a return of an existing payment.
     * <ol>
     *   <li>Looks up the original transfer by msgId.</li>
     *   <li>Builds a pacs.004.001.15 PaymentReturn using the original transfer details.</li>
     *   <li>Marshals to XML and POSTs to the return endpoint.</li>
     *   <li>Returns the pacs.002 return response.</li>
     * </ol>
     *
     * @param msgId      the messageId of the original pacs.008 transfer to return
     * @param reasonCode ISO 20022 external return reason code (e.g. DUPL, FRAD, CUST); defaults to DUPL
     */
    @Operation(summary = "Return a payment",
               description = "Builds a pacs.004 PaymentReturn for the given original transfer and sends it to the return endpoint.")
    @ApiResponse(responseCode = "200", description = "Return response received")
    @ApiResponse(responseCode = "400", description = "Unknown return reason code")
    @ApiResponse(responseCode = "404", description = "No transfer found with the given message ID")
    @ApiResponse(responseCode = "409", description = "A return for this message ID/reason is already in progress")
    @ApiResponse(responseCode = "500", description = "Failed to build or marshal the pacs.004 message")
    @ApiResponse(responseCode = "502", description = "Return endpoint unreachable or returned an error")
    @GetMapping("/return")
    public ResponseEntity<String> returnPayment(
            @Parameter(description = "Message ID of the original pacs.008 transfer to return", required = true)
            @RequestParam String msgId,
            @Parameter(description = "ISO 20022 return reason code (e.g. DUPL, FRAD, CUST). Defaults to DUPL")
            @RequestParam(required = false) String reasonCode,
            @Parameter(description = "Optional client idempotency key; defaults to the original msgId + reason")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        // ── 1. Validate reason code (if supplied) ─────────────────────────────
        if (reasonCode != null && !reasonCode.isBlank()
                && ReturnReasonCode.fromCode(reasonCode) == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Unknown return reason code: " + reasonCode);
        }

        // ── 2. Look up the original transfer ──────────────────────────────────
        TransferLog original = transferLogRepository.findByMessageId(msgId)
                .orElse(null);
        if (original == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No transfer found with msgId: " + msgId);
        }

        // ── 3. Guard the send behind idempotency so a duplicate call does not ──
        //    dispatch a second return to the counterparty. Natural key is the
        //    original msgId + reason unless the client supplies its own.
        String effectiveReason = (reasonCode == null || reasonCode.isBlank()) ? "DUPL" : reasonCode;
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? msgId + ":" + effectiveReason
                : idempotencyKey;
        String fingerprint = msgId + ":" + effectiveReason;

        return idempotencyService.execute("payments.return", key, fingerprint,
                () -> doReturn(original, msgId, reasonCode));
    }

    private ResponseEntity<String> doReturn(TransferLog original, String msgId, String reasonCode) {

        // ── 3. Build pacs.004 ─────────────────────────────────────────────────
        BuildResult built;
        String xml;
        try {
            built = pacs004Builder.build(original, reasonCode);
            xml   = marshaller.marshalPaymentReturn(built.document());
            log.info("pacs.004 XML:\n{}", xml);
        } catch (JAXBException e) {
            log.error("Failed to build/marshal pacs.004", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to build payment return: " + e.getMessage());
        }

        log.info("Payment return — rtrMsgId={} orgnlMsgId={} reason={}",
                built.messageId(), msgId, reasonCode != null ? reasonCode : "DUPL");

        // ── 4. POST XML to return endpoint ────────────────────────────────────
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> httpRequest = new HttpEntity<>(xml, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    institution.getReturnUrl(), httpRequest, String.class);

            log.info("Return response received — rtrMsgId={} httpStatus={}",
                    built.messageId(), response.getStatusCode());

            kafkaProducer.publishReturn(new WalletPaymentEvent(
                    built.messageId(),
                    institution.getBic(),
                    original.getCreditorName(),
                    original.getCreditorAccount(),
                    original.getAmount(),
                    original.getCurrency(),
                    "",
                    original.getDebtorName(),
                    original.getDebtorAccount()));

            return ResponseEntity.ok(response.getBody());

        } catch (RestClientException e) {
            log.error("Payment return failed — rtrMsgId={} error={}", built.messageId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Payment return failed: " + e.getMessage());
        }
    }

    
}
