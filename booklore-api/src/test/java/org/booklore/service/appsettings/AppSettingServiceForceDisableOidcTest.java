package org.booklore.service.appsettings;

import org.booklore.config.AppProperties;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.settings.AppSettingKey;
import org.booklore.model.entity.AppSettingEntity;
import org.booklore.repository.AppSettingsRepository;
import org.booklore.service.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FORCE_DISABLE_OIDC must be a working way back in when the identity provider is down. */
class AppSettingServiceForceDisableOidcTest {

    private final AppSettingsRepository repository = mock(AppSettingsRepository.class);
    private final AppProperties appProperties = new AppProperties();
    private AppSettingService service;

    @BeforeEach
    void setUp() {
        // OIDC is on and set to force-only in the database.
        Set<String> enabledKeys = Set.of(AppSettingKey.OIDC_ENABLED.toString(), AppSettingKey.OIDC_FORCE_ONLY_MODE.toString());
        when(repository.findByName(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            if (!enabledKeys.contains(name)) {
                return null;
            }
            AppSettingEntity entity = new AppSettingEntity();
            entity.setName(name);
            entity.setVal("true");
            return entity;
        });
        when(repository.findAll()).thenReturn(List.of());

        appProperties.setRemoteAuth(new AppProperties.RemoteAuth());
        SettingPersistenceHelper helper = new SettingPersistenceHelper(repository, JsonMapper.builder().build());
        service = new AppSettingService(appProperties, helper, mock(AuthenticationService.class), mock(AuditService.class));
    }

    @Test
    void withoutTheOverride_oidcAndForceOnlyComeFromTheDatabase() {
        assertThat(service.getPublicSettings().isOidcEnabled()).isTrue();
        assertThat(service.getPublicSettings().isOidcForceOnlyMode()).isTrue();
    }

    @Test
    void forceDisableOidc_clearsForceOnlyToo_inBothPublicAndPrivateSettings() {
        appProperties.setForceDisableOidc(true);

        assertThat(service.getPublicSettings().isOidcEnabled()).isFalse();
        assertThat(service.getPublicSettings().isOidcForceOnlyMode()).isFalse();
        // The private settings are what AuthenticationService checks before refusing a password login.
        assertThat(service.getAppSettings().isOidcEnabled()).isFalse();
        assertThat(service.getAppSettings().isOidcForceOnlyMode()).isFalse();
    }
}
