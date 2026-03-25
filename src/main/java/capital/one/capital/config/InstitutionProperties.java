package capital.one.capital.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "institution")
public class InstitutionProperties {

    private String name;
    private String bic;
    private String country;
    private String street;
    private String city;
    private String postalCode;
    private String creditorAgentUrl;
    private String avsUrl;
    private String statusUrl;
    private String returnUrl;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBic() { return bic; }
    public void setBic(String bic) { this.bic = bic; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }

    public String getCreditorAgentUrl() { return creditorAgentUrl; }
    public void setCreditorAgentUrl(String creditorAgentUrl) { this.creditorAgentUrl = creditorAgentUrl; }

    public String getAvsUrl() { return avsUrl; }
    public void setAvsUrl(String avsUrl) { this.avsUrl = avsUrl; }

    public String getStatusUrl() { return statusUrl; }
    public void setStatusUrl(String statusUrl) { this.statusUrl = statusUrl; }

    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
}
