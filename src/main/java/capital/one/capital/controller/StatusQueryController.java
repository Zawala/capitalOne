package capital.one.capital.controller;

import capital.one.capital.config.InstitutionProperties;
import capital.one.capital.repository.TransferLogRepository;
import capital.one.capital.service.Iso20022MarshallingService;
import capital.one.capital.service.Pacs028BuilderService;
import capital.one.capital.service.Pacs028BuilderService.BuildResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Payments", description = "Credit transfers — send and receive ISO 20022 pacs.008 payments")
@RestController
@RequestMapping("/api/v1/payments")
public class StatusQueryController {

    private static final Logger log = LoggerFactory.getLogger(StatusQueryController.class);

    private final Pacs028BuilderService pacs028Builder;
    private final Iso20022MarshallingService marshaller;
    private final TransferLogRepository transferLogRepository;
    private final InstitutionProperties institution;
    private final RestTemplate restTemplate;

    public StatusQueryController(
            Pacs028BuilderService pacs028Builder,
            Iso20022MarshallingService marshaller,
            TransferLogRepository transferLogRepository,
            InstitutionProperties institution,
            RestTemplate restTemplate) {
        this.pacs028Builder = pacs028Builder;
        this.marshaller = marshaller;
        this.transferLogRepository = transferLogRepository;
        this.institution = institution;
        this.restTemplate = restTemplate;
    }

    /**
     * Queries the status of an existing payment.
     * <ol>
     *   <li>Validates the original transfer exists in the log.</li>
     *   <li>Builds a pacs.028.001.07 FIToFIPaymentStatusRequest.</li>
     *   <li>Marshals to XML and POSTs to the status endpoint.</li>
     *   <li>Returns the pacs.002 status response.</li>
     * </ol>
     *
     * @param msgId the messageId of the original pacs.008 transfer to query
     */
    @Operation(summary = "Query payment status",
               description = "Builds a pacs.028 FIToFIPaymentStatusRequest for the given message ID and returns the pacs.002 status response.")
    @ApiResponse(responseCode = "200", description = "Status response received")
    @ApiResponse(responseCode = "404", description = "No transfer found with the given message ID")
    @ApiResponse(responseCode = "500", description = "Failed to build or marshal the pacs.028 message")
    @ApiResponse(responseCode = "502", description = "Status endpoint unreachable or returned an error")
    @GetMapping("/status")
    public ResponseEntity<String> queryStatus(
            @Parameter(description = "Message ID of the original pacs.008 transfer", required = true)
            @RequestParam String msgId) {

        // ── 1. Validate original transfer exists ──────────────────────────────
        if (transferLogRepository.findByMessageId(msgId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No transfer found with msgId: " + msgId);
        }

        // ── 2. Build pacs.028 ─────────────────────────────────────────────────
        BuildResult built;
        String xml;
        try {
            built = pacs028Builder.build(msgId);
            xml   = marshaller.marshalStatusRequest(built.document());
            log.info("pacs.028 XML:\n{}", xml);
        } catch (JAXBException e) {
            log.error("Failed to build/marshal pacs.028", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to build status request: " + e.getMessage());
        }

        log.info("Status query — stsReqMsgId={} orgnlMsgId={}", built.messageId(), msgId);

        // ── 3. POST XML to status endpoint ────────────────────────────────────
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> httpRequest = new HttpEntity<>(xml, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    institution.getStatusUrl(), httpRequest, String.class);

            log.info("Status response received — stsReqMsgId={} httpStatus={}",
                    built.messageId(), response.getStatusCode());
            return ResponseEntity.ok(response.getBody());

        } catch (RestClientException e) {
            log.error("Status query failed — stsReqMsgId={} error={}", built.messageId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Status query failed: " + e.getMessage());
        }
    }
}
