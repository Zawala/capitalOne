package capital.one.capital.controller;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.model.ReturnReasonCode;
import capital.one.capital.model.TransferLog;
import capital.one.capital.repository.TransferLogRepository;
import capital.one.capital.service.Iso20022MarshallingService;
import capital.one.capital.service.Pacs004BuilderService;
import capital.one.capital.service.Pacs004BuilderService.BuildResult;

import jakarta.xml.bind.JAXBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentReturnController {

    private static final Logger log = LoggerFactory.getLogger(PaymentReturnController.class);

    private final Pacs004BuilderService pacs004Builder;
    private final Iso20022MarshallingService marshaller;
    private final TransferLogRepository transferLogRepository;
    private final InstitutionProperties institution;
    private final RestTemplate restTemplate;

    public PaymentReturnController(
            Pacs004BuilderService pacs004Builder,
            Iso20022MarshallingService marshaller,
            TransferLogRepository transferLogRepository,
            InstitutionProperties institution,
            RestTemplate restTemplate) {
        this.pacs004Builder = pacs004Builder;
        this.marshaller = marshaller;
        this.transferLogRepository = transferLogRepository;
        this.institution = institution;
        this.restTemplate = restTemplate;
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
    @GetMapping("/return")
    public ResponseEntity<String> returnPayment(
            @RequestParam String msgId,
            @RequestParam(required = false) String reasonCode) {

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
            return ResponseEntity.ok(response.getBody());

        } catch (RestClientException e) {
            log.error("Payment return failed — rtrMsgId={} error={}", built.messageId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Payment return failed: " + e.getMessage());
        }
    }
}
