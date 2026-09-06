package org.booklore.service.appsettings;

import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.dto.settings.OidcProviderDetails;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppSettingsRedactorTest {

    private AppSettings settingsWithSecrets() {
        MetadataProviderSettings providers = new MetadataProviderSettings();

        MetadataProviderSettings.Amazon amazon = new MetadataProviderSettings.Amazon();
        amazon.setEnabled(true);
        amazon.setDomain("amazon.co.uk");
        amazon.setCookie("session-id=super-secret");
        providers.setAmazon(amazon);

        MetadataProviderSettings.Hardcover hardcover = new MetadataProviderSettings.Hardcover();
        hardcover.setEnabled(true);
        hardcover.setApiKey("hc-key");
        providers.setHardcover(hardcover);

        MetadataProviderSettings.Comicvine comicvine = new MetadataProviderSettings.Comicvine();
        comicvine.setEnabled(true);
        comicvine.setApiKey("cv-key");
        providers.setComicvine(comicvine);

        MetadataProviderSettings.Bookshelf bookshelf = new MetadataProviderSettings.Bookshelf();
        bookshelf.setEnabled(true);
        bookshelf.setApiKey("bs-key");
        providers.setBookshelf(bookshelf);

        MetadataProviderSettings.Google google = new MetadataProviderSettings.Google();
        google.setEnabled(true);
        google.setLanguage("en");
        google.setApiKey("g-key");
        providers.setGoogle(google);

        OidcProviderDetails oidc = new OidcProviderDetails();
        oidc.setProviderName("authelia");
        oidc.setClientId("booklore");
        oidc.setClientSecret("oidc-secret");
        oidc.setIssuerUri("https://auth.example.com");

        return AppSettings.builder()
                .metadataProviderSettings(providers)
                .oidcProviderDetails(oidc)
                .uploadPattern("{title}")
                .opdsServerEnabled(true)
                .build();
    }

    @Test
    void stripsEveryProviderCredentialAndTheOidcClientSecret() {
        AppSettings redacted = AppSettingsRedactor.redactSecrets(settingsWithSecrets());

        MetadataProviderSettings p = redacted.getMetadataProviderSettings();
        assertThat(p.getAmazon().getCookie()).isNull();
        assertThat(p.getGoogle().getApiKey()).isNull();
        assertThat(p.getHardcover().getApiKey()).isNull();
        assertThat(p.getComicvine().getApiKey()).isNull();
        assertThat(p.getBookshelf().getApiKey()).isNull();
        assertThat(redacted.getOidcProviderDetails().getClientSecret()).isNull();
    }

    @Test
    void keepsTheNonSecretSettingsIntact() {
        AppSettings redacted = AppSettingsRedactor.redactSecrets(settingsWithSecrets());

        assertThat(redacted.getUploadPattern()).isEqualTo("{title}");
        assertThat(redacted.isOpdsServerEnabled()).isTrue();
        assertThat(redacted.getMetadataProviderSettings().getAmazon().isEnabled()).isTrue();
        assertThat(redacted.getMetadataProviderSettings().getAmazon().getDomain()).isEqualTo("amazon.co.uk");
        assertThat(redacted.getMetadataProviderSettings().getGoogle().getLanguage()).isEqualTo("en");
        assertThat(redacted.getOidcProviderDetails().getClientId()).isEqualTo("booklore");
        assertThat(redacted.getOidcProviderDetails().getIssuerUri()).isEqualTo("https://auth.example.com");
    }

    @Test
    void doesNotMutateTheCachedInstance() {
        AppSettings original = settingsWithSecrets();

        AppSettingsRedactor.redactSecrets(original);

        assertThat(original.getMetadataProviderSettings().getAmazon().getCookie()).isEqualTo("session-id=super-secret");
        assertThat(original.getMetadataProviderSettings().getHardcover().getApiKey()).isEqualTo("hc-key");
        assertThat(original.getOidcProviderDetails().getClientSecret()).isEqualTo("oidc-secret");
    }

    @Test
    void toleratesNulls() {
        assertThat(AppSettingsRedactor.redactSecrets(null)).isNull();
        AppSettings sparse = AppSettingsRedactor.redactSecrets(AppSettings.builder().build());
        assertThat(sparse.getMetadataProviderSettings()).isNull();
        assertThat(sparse.getOidcProviderDetails()).isNull();
    }
}
