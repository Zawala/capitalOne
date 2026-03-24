package capital.one.capital.controller;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.dto.TransferRequestDTO;
import capital.one.capital.model.TransferLog;
import capital.one.capital.model.TransferStatus;
import capital.one.capital.repository.TransferLogRepository;
import capital.one.capital.service.Iso20022MarshallingService;
import capital.one.capital.service.Pacs008BuilderService;
import capital.one.capital.service.Pacs008BuilderService.BuildResult;

import jakarta.xml.bind.JAXBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final Pacs008BuilderService pacs008Builder;
    private final Iso20022MarshallingService marshaller;
    private final TransferLogRepository transferLogRepository;
    private final InstitutionProperties institution;
    private final RestTemplate restTemplate;

    public PaymentController(
            Pacs008BuilderService pacs008Builder,
            Iso20022MarshallingService marshaller,
            TransferLogRepository transferLogRepository,
            InstitutionProperties institution,
            RestTemplate restTemplate) {
        this.pacs008Builder = pacs008Builder;
        this.marshaller = marshaller;
        this.transferLogRepository = transferLogRepository;
        this.institution = institution;
        this.restTemplate = restTemplate;
    }

    /**
     * Initiates a credit transfer.
     * <ol>
     *   <li>Builds a pacs.008.001.14 Document from the request.</li>
     *   <li>Persists a PENDING log entry.</li>
     *   <li>Marshals to XML and POSTs to the creditor agent.</li>
     *   <li>Updates the log to SENT or FAILED.</li>
     * </ol>
     */
    @PostMapping("/transfer")
    public ResponseEntity<String> transfer(@RequestBody TransferRequestDTO request) {

        // ── 1. Build pacs.008 ─────────────────────────────────────────────────
        BuildResult built;
        String xml;
        try {
            built = pacs008Builder.build(request);
            xml   = marshaller.marshalPayment(built.document());
        } catch (JAXBException e) {
            log.error("Failed to build/marshal pacs.008", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to build payment message: " + e.getMessage());
        }

        // ── 2. Persist PENDING log ────────────────────────────────────────────
        TransferLog transferLog = new TransferLog();
        transferLog.setMessageId(built.messageId());
        transferLog.setEndToEndId(built.endToEndId());
        transferLog.setDebtorName(request.getFirstName() + " " + request.getLastName());
        transferLog.setDebtorAccount(request.getDebtorAccountNumber());
        transferLog.setCreditorName(request.getCreditorName());
        transferLog.setCreditorAccount(request.getCreditorAccountNumber());
        transferLog.setAmount(request.getAmount());
        transferLog.setCurrency(request.getCurrency());
        transferLog.setStatus(TransferStatus.PENDING);
        transferLog.setRawXmlSent(xml);
        transferLogRepository.save(transferLog);

        log.info("Transfer PENDING — msgId={} endToEndId={} debtor={} amount={} {}",
                built.messageId(), built.endToEndId(),
                transferLog.getDebtorName(), request.getAmount(), request.getCurrency());

        // ── 3. POST XML to creditor agent ─────────────────────────────────────
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> httpRequest = new HttpEntity<>(xml, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    institution.getCreditorAgentUrl(), httpRequest, String.class);

            transferLog.setStatus(TransferStatus.SENT);
            transferLog.setResponseBody(response.getBody());
            transferLogRepository.save(transferLog);

            log.info("Transfer SENT — msgId={} httpStatus={}", built.messageId(), response.getStatusCode());
            return ResponseEntity.ok(response.getBody());

        } catch (RestClientException e) {
            transferLog.setStatus(TransferStatus.FAILED);
            transferLog.setResponseBody(e.getMessage());
            transferLogRepository.save(transferLog);

            log.error("Transfer FAILED — msgId={} error={}", built.messageId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Payment submission failed: " + e.getMessage());
        }
    }
}
