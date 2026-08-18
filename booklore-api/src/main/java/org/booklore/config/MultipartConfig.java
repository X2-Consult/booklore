package org.booklore.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
public class MultipartConfig {

    /**
     * Hard ceiling enforced by the servlet container itself, in MB. This must stay
     * comfortably above anything an admin can configure via the MAX_FILE_UPLOAD_SIZE_IN_MB
     * app setting (see AppSettingService#validateMaxFileUploadSize), otherwise uploads for
     * files between the two limits get rejected by Tomcat before the app's own, friendlier
     * FILE_TOO_LARGE validation ever runs - surfacing as an opaque 500 instead of a clear
     * "file too large" error. Large audiobook files in particular can easily exceed the
     * previous 1024 MB default.
     */
    public static final long MAX_UPLOAD_SIZE_MB = 10240;

    /**
     * Provides a MultipartConfigElement with a generous default max upload size.
     * The actual user-configured limit from app_settings is enforced at the service layer.
     * This bean is created during servlet container initialization (before Flyway migrations run),
     * so it must NOT query the database.
     */
    @Bean
    public MultipartConfigElement multipartConfigElement() {
        long maxSizeBytes = DataSize.ofMegabytes(MAX_UPLOAD_SIZE_MB).toBytes();
        return new MultipartConfigElement("", maxSizeBytes, maxSizeBytes, 0);
    }
}
