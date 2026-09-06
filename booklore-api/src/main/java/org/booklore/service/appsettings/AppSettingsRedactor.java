package org.booklore.service.appsettings;

import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.dto.settings.OidcProviderDetails;

/**
 * Strips provider credentials out of {@link AppSettings} before it is handed to a non-admin.
 *
 * <p>{@code GET /api/v1/settings} is readable by any authenticated user (the UI needs the
 * non-secret settings), but the payload also carries the admin's Amazon session cookie, the
 * Google/Hardcover/Comicvine/Bookshelf API keys and the OIDC client secret. Those are
 * write-only as far as a non-admin is concerned.
 *
 * <p>Returns copies - the cached {@link AppSettings} instance held by
 * {@link AppSettingService} must never be mutated.
 */
public final class AppSettingsRedactor {

    private AppSettingsRedactor() {
    }

    public static AppSettings redactSecrets(AppSettings settings) {
        if (settings == null) {
            return null;
        }
        return settings.toBuilder()
                .metadataProviderSettings(redact(settings.getMetadataProviderSettings()))
                .oidcProviderDetails(redact(settings.getOidcProviderDetails()))
                .build();
    }

    private static MetadataProviderSettings redact(MetadataProviderSettings source) {
        if (source == null) {
            return null;
        }
        MetadataProviderSettings copy = new MetadataProviderSettings();
        // Providers with no credential are shared by reference - they hold only flags.
        copy.setGoodReads(source.getGoodReads());
        copy.setOpenLibrary(source.getOpenLibrary());
        copy.setRanobedb(source.getRanobedb());
        copy.setDouban(source.getDouban());
        copy.setLubimyczytac(source.getLubimyczytac());

        if (source.getAmazon() != null) {
            MetadataProviderSettings.Amazon amazon = new MetadataProviderSettings.Amazon();
            amazon.setEnabled(source.getAmazon().isEnabled());
            amazon.setDomain(source.getAmazon().getDomain());
            copy.setAmazon(amazon);
        }
        if (source.getGoogle() != null) {
            MetadataProviderSettings.Google google = new MetadataProviderSettings.Google();
            google.setEnabled(source.getGoogle().isEnabled());
            google.setLanguage(source.getGoogle().getLanguage());
            copy.setGoogle(google);
        }
        if (source.getHardcover() != null) {
            MetadataProviderSettings.Hardcover hardcover = new MetadataProviderSettings.Hardcover();
            hardcover.setEnabled(source.getHardcover().isEnabled());
            copy.setHardcover(hardcover);
        }
        if (source.getComicvine() != null) {
            MetadataProviderSettings.Comicvine comicvine = new MetadataProviderSettings.Comicvine();
            comicvine.setEnabled(source.getComicvine().isEnabled());
            copy.setComicvine(comicvine);
        }
        if (source.getBookshelf() != null) {
            MetadataProviderSettings.Bookshelf bookshelf = new MetadataProviderSettings.Bookshelf();
            bookshelf.setEnabled(source.getBookshelf().isEnabled());
            copy.setBookshelf(bookshelf);
        }
        if (source.getAudible() != null) {
            MetadataProviderSettings.Audible audible = new MetadataProviderSettings.Audible();
            audible.setEnabled(source.getAudible().isEnabled());
            audible.setDomain(source.getAudible().getDomain());
            copy.setAudible(audible);
        }
        return copy;
    }

    private static OidcProviderDetails redact(OidcProviderDetails source) {
        if (source == null) {
            return null;
        }
        OidcProviderDetails copy = new OidcProviderDetails();
        copy.setProviderName(source.getProviderName());
        copy.setClientId(source.getClientId());
        copy.setIssuerUri(source.getIssuerUri());
        copy.setScopes(source.getScopes());
        copy.setClaimMapping(source.getClaimMapping());
        return copy;
    }
}
