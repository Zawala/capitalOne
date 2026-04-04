package capital.one.capital;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
class CapitalApplicationTests {

	@MockBean
	KafkaTemplate<String, String> kafkaTemplate;

	@Test
	void contextLoads() {
	}

}
