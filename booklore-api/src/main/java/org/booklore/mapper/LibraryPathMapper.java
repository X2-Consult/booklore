package org.booklore.mapper;

import org.booklore.model.dto.LibraryPath;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.util.MountInfo;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface LibraryPathMapper {

    LibraryPath toLibraryPath(LibraryPathEntity libraryPathEntity);

    @AfterMapping
    default void addNetworkFilesystem(@MappingTarget LibraryPath libraryPath) {
        if (libraryPath.getPath() == null) {
            return;
        }
        try {
            MountInfo.find(Path.of(libraryPath.getPath()))
                    .filter(MountInfo.Mount::isNetwork)
                    .ifPresent(mount -> libraryPath.setNetworkFilesystem(mount.fsType()));
        } catch (InvalidPathException ignored) {
            // not a path this system can check; leave it as local
        }
    }
}
