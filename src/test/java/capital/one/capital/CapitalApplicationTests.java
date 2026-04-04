package capital.one.capital;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
class CapitalApplicationTests {

	@MockitoBean
	KafkaTemplate<String, String> kafkaTemplate;

	@Test
	void contextLoads() {
	}

}
