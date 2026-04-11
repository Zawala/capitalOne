package capital.one.capital.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes structured {@link WalletPaymentEvent} JSON to Kafka topics.
 *
 * <pre>
 * Endpoint        Topic
 * ─────────────────────────────
 * POST /receive   wallet-deposits
 * POST /transfer  wallet-credits
 * </pre>
 *
 * The message key is the ISO 20022 MessageId, which enables Kafka partition
 * affinity — all messages for the same transaction land on the same partition.
 */
@Service
public class PaymentKafkaProducer {

    public static final String TOPIC_DEPOSITS = "wallet-deposits";
    public static final String TOPIC_CREDITS  = "wallet-credits";
    public static final String TOPIC_RETURNS  = "wallet-returns";

    private static final Logger log = LoggerFactory.getLogger(PaymentKafkaProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentKafkaProducer(KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper  = objectMapper;
    }

    /**
     * Publishes an inbound deposit event to {@value #TOPIC_DEPOSITS}.
     */
    public void publishDeposit(WalletPaymentEvent event) {
        publish(TOPIC_DEPOSITS, event);
    }

    /**
     * Publishes an outbound credit transfer event to {@value #TOPIC_CREDITS}.
     */
    public void publishCredit(WalletPaymentEvent event) {
        publish(TOPIC_CREDITS, event);
    }

    /**
     * Publishes a payment return event to {@value #TOPIC_RETURNS}.
     */
    public void publishReturn(WalletPaymentEvent event) {
        publish(TOPIC_RETURNS, event);
    }

    private void publish(String topic, WalletPaymentEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize WalletPaymentEvent for topic={} msgId={}", topic, event.messageId(), e);
            return;
        }

        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(topic, event.messageId(), json);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to topic={} key={} error={}", topic, event.messageId(), ex.getMessage());
            } else {
                log.info("Published to topic={} key={} partition={} offset={}",
                        topic, event.messageId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
