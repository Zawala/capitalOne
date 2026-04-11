package capital.one.capital.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI capitalOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Capital Payments API")
                        .version("1.0.0")
                        .description("""
                                ISO 20022 payment processing API for Capital One Bank.

                                **Supported message types:**
                                - **pacs.008** — Credit transfers (send/receive)
                                - **pacs.004** — Payment returns
                                - **pacs.028** — Payment status queries
                                - **acmt.023 / acmt.024** — Account verification (AVS)

                                All payment messages are marshalled to ISO 20022 XML and forwarded to the downstream institution.""")
                        .contact(new Contact()
                                .name("Capital One Bank")
                                .email("dev@capital.one")));
    }
}
