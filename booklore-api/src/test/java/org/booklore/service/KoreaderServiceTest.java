package org.booklore.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.exception.APIException;
import org.booklore.model.dto.progress.KoreaderProgress;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.KoreaderUserEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.UserBookFileProgressEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.UserBookFileProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserRepository;
import org.booklore.repository.KoreaderUserRepository;
import org.booklore.service.hardcover.HardcoverSyncService;
import org.booklore.service.koreader.KoreaderService;
import org.booklore.util.koreader.EpubCfiService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KoreaderServiceTest {

    @Mock
    UserBookProgressRepository progressRepo;
    @Mock
    UserBookFileProgressRepository fileProgressRepo;
    @Mock
    BookFileRepository bookFileRepo;
    @Mock
    UserRepository userRepo;
    @Mock
    KoreaderUserRepository koreaderUserRepo;
    @Mock
    HardcoverSyncService hardcoverSyncService;
    @Mock
    EpubCfiService epubCfiService;

    @InjectMocks
    KoreaderService service;

    private KoreaderUserDetails details;

    @BeforeEach
    void setUpAuth() {
        details = mock(KoreaderUserDetails.class);
        when(details.getUsername()).thenReturn("u");
        when(details.getPassword()).thenReturn("md5pwd");
        when(details.getBookLoreUserId()).thenReturn(42L);
        Authentication auth = mock(Authentication.class);
        SecurityContext context = new SecurityContextImpl();
        when(auth.getPrincipal()).thenReturn(details);
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private BookFileEntity bookFile(BookEntity book, Long fileId, BookFileType type) {
        var bf = new BookFileEntity();
        bf.setId(fileId);
        bf.setBook(book);
        bf.setBookType(type);
        return bf;
    }

    @Test
    void authorizeUser_success() {
        when(details.isSyncEnabled()).thenReturn(true);
        var userEntity = new KoreaderUserEntity();
        userEntity.setPasswordMD5("5F4DCC3B5AA765D61D8327DEB882CF99");
        when(koreaderUserRepo.findByUsername("u"))
                .thenReturn(Optional.of(userEntity));
        when(details.getPassword()).thenReturn("5f4dcc3b5aa765d61d8327deb882cf99");

        ResponseEntity<Map<String, String>> resp = service.authorizeUser();
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("u", resp.getBody().get("username"));
    }

    @Test
    void authorizeUser_notFound() {
        when(details.isSyncEnabled()).thenReturn(true);
        when(koreaderUserRepo.findByUsername("u")).thenReturn(Optional.empty());
        APIException ex = assertThrows(APIException.class, () -> service.authorizeUser());
        assertTrue(ex.getStatus().is4xxClientError());
    }

    @Test
    void authorizeUser_badPassword() {
        when(details.isSyncEnabled()).thenReturn(true);
        var userEntity = new KoreaderUserEntity();
        userEntity.setPasswordMD5("OTHER");
        when(koreaderUserRepo.findByUsername("u"))
                .thenReturn(Optional.of(userEntity));
        assertThrows(APIException.class, () -> service.authorizeUser());
    }

    @Test
    void authorizeUser_syncDisabled() {
        when(details.isSyncEnabled()).thenReturn(false);
        APIException ex = assertThrows(APIException.class, () -> service.authorizeUser());
        assertTrue(ex.getStatus().is4xxClientError());
    }

    @Test
    void getProgress_success() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(99L);
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("h")).thenReturn(Optional.of(file));
        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("p");
        prog.setKoreaderProgressPercent(0.5F);
        when(progressRepo.findByUserIdAndBookId(42L, 99L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out = service.getProgress("h");
        assertEquals("h", out.getDocument());
        assertEquals("p", out.getProgress());
        assertEquals(0.5F, out.getPercentage());
    }

    @Test
    void getProgress_bookNotFound() {
        when(details.isSyncEnabled()).thenReturn(true);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("h")).thenReturn(Optional.empty());
        assertThrows(APIException.class, () -> service.getProgress("h"));
    }

    @Test
    void getProgress_noProgress() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("h"))
                .thenReturn(Optional.of(file));
        when(progressRepo.findByUserIdAndBookId(anyLong(), isNull()))
                .thenReturn(Optional.empty());
        assertThrows(APIException.class, () -> service.getProgress("h"));
    }

    @Test
    void getProgress_syncDisabled() {
        when(details.isSyncEnabled()).thenReturn(false);
        assertThrows(APIException.class, () -> service.getProgress("h"));
    }

    @Test
    void getProgress_includesTimestamp() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(100L);
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("hash123")).thenReturn(Optional.of(file));

        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("progress/path");
        prog.setKoreaderProgressPercent(0.75F);
        Instant syncTime = Instant.ofEpochSecond(1762209924L);
        prog.setKoreaderLastSyncTime(syncTime);
        when(progressRepo.findByUserIdAndBookId(42L, 100L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out = service.getProgress("hash123");
        assertEquals("hash123", out.getDocument());
        assertEquals("progress/path", out.getProgress());
        assertEquals(0.75F, out.getPercentage());
        assertEquals(1762209924L, out.getTimestamp());
    }

    @Test
    void getProgress_nullTimestamp() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(101L);
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("hash456")).thenReturn(Optional.of(file));

        var prog = new UserBookProgressEntity();
        prog.setKoreaderProgress("progress/path2");
        prog.setKoreaderProgressPercent(0.25F);
        prog.setKoreaderLastSyncTime(null);
        when(progressRepo.findByUserIdAndBookId(42L, 101L))
                .thenReturn(Optional.of(prog));

        KoreaderProgress out = service.getProgress("hash456");
        assertEquals("hash456", out.getDocument());
        assertEquals("progress/path2", out.getProgress());
        assertEquals(0.25F, out.getPercentage());
        assertNull(out.getTimestamp());
    }

    @Test
    void getProgress_prefersBookloreReader_pdfPage_whenNoPriorKoreaderSync() {
        // A user who has only ever read via BookLore's web reader opens the book in
        // KOReader for the first time - there's no koreaderLastSyncTime to compare
        // against, so the web reader's position should win outright.
        when(details.isSyncEnabled()).thenReturn(true);
        when(details.isSyncWithBookloreReader()).thenReturn(true);

        var book = new BookEntity();
        book.setId(200L);
        var pdf = bookFile(book, 5L, BookFileType.PDF);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("pdfHash")).thenReturn(Optional.of(pdf));

        var progress = new UserBookProgressEntity();
        progress.setKoreaderLastSyncTime(null);
        when(progressRepo.findByUserIdAndBookId(42L, 200L)).thenReturn(Optional.of(progress));

        var fileProgress = new UserBookFileProgressEntity();
        fileProgress.setPositionData("57");
        fileProgress.setProgressPercent(65.0F);
        fileProgress.setLastReadTime(Instant.now());
        when(fileProgressRepo.findByUserIdAndBookFileId(42L, 5L)).thenReturn(Optional.of(fileProgress));

        KoreaderProgress out = service.getProgress("pdfHash");
        assertEquals("57", out.getProgress());
        assertEquals(0.65f, out.getPercentage(), 0.001f);
    }

    @Test
    void getProgress_ignoresBookloreReader_whenSyncWithBookloreReaderOff() {
        when(details.isSyncEnabled()).thenReturn(true);
        when(details.isSyncWithBookloreReader()).thenReturn(false);

        var book = new BookEntity();
        book.setId(201L);
        var pdf = bookFile(book, 6L, BookFileType.PDF);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("pdfHash2")).thenReturn(Optional.of(pdf));

        var progress = new UserBookProgressEntity();
        progress.setKoreaderProgress("12");
        progress.setKoreaderProgressPercent(0.2F);
        when(progressRepo.findByUserIdAndBookId(42L, 201L)).thenReturn(Optional.of(progress));

        KoreaderProgress out = service.getProgress("pdfHash2");
        assertEquals("12", out.getProgress());
        assertEquals(0.2f, out.getPercentage(), 0.001f);
        verifyNoInteractions(fileProgressRepo);
    }

    @Test
    void getProgress_ignoresBookloreReader_whenNotMeaningfullyNewer() {
        // The dual-write in saveProgress() means a KOReader push leaves the file-level
        // lastReadTime only microseconds behind koreaderLastSyncTime. Without the recency
        // buffer this would round-trip KOReader's own push back through a CFI conversion
        // for no reason.
        when(details.isSyncEnabled()).thenReturn(true);
        when(details.isSyncWithBookloreReader()).thenReturn(true);

        var book = new BookEntity();
        book.setId(202L);
        var pdf = bookFile(book, 7L, BookFileType.PDF);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("pdfHash3")).thenReturn(Optional.of(pdf));

        Instant syncTime = Instant.now();
        var progress = new UserBookProgressEntity();
        progress.setKoreaderProgress("99");
        progress.setKoreaderProgressPercent(0.5F);
        progress.setKoreaderLastSyncTime(syncTime);
        when(progressRepo.findByUserIdAndBookId(42L, 202L)).thenReturn(Optional.of(progress));

        var fileProgress = new UserBookFileProgressEntity();
        fileProgress.setPositionData("99");
        fileProgress.setProgressPercent(50.0F);
        fileProgress.setLastReadTime(syncTime.plusMillis(50));
        when(fileProgressRepo.findByUserIdAndBookFileId(42L, 7L)).thenReturn(Optional.of(fileProgress));

        KoreaderProgress out = service.getProgress("pdfHash3");
        assertEquals("99", out.getProgress());
        assertEquals(0.5f, out.getPercentage(), 0.001f);
    }

    @Test
    void getProgress_prefersBookloreReader_epub_whenMeaningfullyNewer() {
        when(details.isSyncEnabled()).thenReturn(true);
        when(details.isSyncWithBookloreReader()).thenReturn(true);

        var libraryPath = new LibraryPathEntity();
        libraryPath.setPath("/library");
        var book = new BookEntity();
        book.setId(203L);
        book.setLibraryPath(libraryPath);
        var epub = bookFile(book, 8L, BookFileType.EPUB);
        epub.setFileSubPath("sub");
        epub.setFileName("book.epub");
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("epubHash2")).thenReturn(Optional.of(epub));

        var progress = new UserBookProgressEntity();
        progress.setKoreaderLastSyncTime(Instant.now().minusSeconds(3600));
        when(progressRepo.findByUserIdAndBookId(42L, 203L)).thenReturn(Optional.of(progress));

        var fileProgress = new UserBookFileProgressEntity();
        fileProgress.setPositionData("epubcfi(/6/8!/4/2/1:0)");
        fileProgress.setProgressPercent(80.0F);
        fileProgress.setLastReadTime(Instant.now());
        when(fileProgressRepo.findByUserIdAndBookFileId(42L, 8L)).thenReturn(Optional.of(fileProgress));

        when(epubCfiService.convertCfiToProgressXPointer(epub.getFullFilePath(), "epubcfi(/6/8!/4/2/1:0)"))
                .thenReturn("/body/DocFragment[4]/body/p[1]/text().0");

        KoreaderProgress out = service.getProgress("epubHash2");
        assertEquals("/body/DocFragment[4]/body/p[1]/text().0", out.getProgress());
        assertEquals(0.8f, out.getPercentage(), 0.001f);
    }

    @Test
    void saveProgress_createsNew() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(7L);
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("h")).thenReturn(Optional.of(file));
        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 7L))
                .thenReturn(Optional.empty());

        var dto = KoreaderProgress.builder()
                .document("h").progress("x").percentage(0.6F).device("d").device_id("id").build();
        service.saveProgress("h", dto);

        ArgumentCaptor<UserBookProgressEntity> cap = ArgumentCaptor.forClass(UserBookProgressEntity.class);
        verify(progressRepo).save(cap.capture());
        var saved = cap.getValue();
        assertEquals("x", saved.getKoreaderProgress());
        assertEquals(0.6F, saved.getKoreaderProgressPercent());
        assertEquals("d", saved.getKoreaderDevice());
        assertEquals("id", saved.getKoreaderDeviceId());
        assertEquals(Instant.class, saved.getKoreaderLastSyncTime().getClass());
    }

    @Test
    void saveProgress_updatesExisting() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(8L);
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("h")).thenReturn(Optional.of(file));
        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        var existing = new UserBookProgressEntity();
        when(progressRepo.findByUserIdAndBookId(42L, 8L))
                .thenReturn(Optional.of(existing));

        var dto = KoreaderProgress.builder()
                .document("h").progress("y").percentage(0.4F).device("d").device_id("id").build();
        service.saveProgress("h", dto);

        verify(progressRepo).save(existing);
        assertEquals("y", existing.getKoreaderProgress());
        assertEquals(0.4F, existing.getKoreaderProgressPercent());
    }

    @Test
    void saveProgress_updatesExistingNoProgressChange_noHardcoverUpdate() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(8L);
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("h")).thenReturn(Optional.of(file));
        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        var existing = new UserBookProgressEntity();
        existing.setKoreaderProgressPercent(0.4F);
        existing.setReadStatus(ReadStatus.READING);
        when(progressRepo.findByUserIdAndBookId(42L, 8L))
                .thenReturn(Optional.of(existing));

        var dto = KoreaderProgress.builder()
                .document("h").progress("y").percentage(0.4F).device("d").device_id("id").build();
        service.saveProgress("h", dto);

        verify(progressRepo).save(existing);
        assertEquals("y", existing.getKoreaderProgress());
        assertEquals(0.4F, existing.getKoreaderProgressPercent());
        verify(hardcoverSyncService, never()).syncProgressToHardcover(any(), any(), any());
    }

    @Test
    void saveProgress_staleClientTimestamp_isIgnored() {
        // A second device, having synced later than this push's own clock claims, must not
        // have its newer position overwritten by a late-arriving stale push.
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(11L);
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("h")).thenReturn(Optional.of(file));
        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));

        var existing = new UserBookProgressEntity();
        existing.setKoreaderProgress("newer-position");
        existing.setKoreaderProgressPercent(0.7F);
        Instant lastSync = Instant.ofEpochSecond(2_000_000L);
        existing.setKoreaderLastSyncTime(lastSync);
        when(progressRepo.findByUserIdAndBookId(42L, 11L)).thenReturn(Optional.of(existing));

        var dto = KoreaderProgress.builder()
                .document("h").progress("stale-position").percentage(0.1F)
                .timestamp(1_000_000L).device("d").device_id("id").build();
        Instant result = service.saveProgress("h", dto);

        assertEquals(lastSync, result);
        assertEquals("newer-position", existing.getKoreaderProgress());
        assertEquals(0.7F, existing.getKoreaderProgressPercent());
        verify(progressRepo, never()).save(any());
        verifyNoInteractions(fileProgressRepo, hardcoverSyncService);
    }

    @Test
    void saveProgress_syncDisabled() {
        when(details.isSyncEnabled()).thenReturn(false);
        var dto = KoreaderProgress.builder().document("h").build();
        assertThrows(APIException.class, () -> service.saveProgress("h", dto));
    }

    @Test
    void saveProgress_nullPercentage_doesNotThrow() {
        when(details.isSyncEnabled()).thenReturn(true);
        var book = new BookEntity();
        book.setId(9L);
        var file = bookFile(book, 1L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("h")).thenReturn(Optional.of(file));
        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 9L))
                .thenReturn(Optional.empty());

        var dto = KoreaderProgress.builder().document("h").progress("z").percentage(null).build();

        assertDoesNotThrow(() -> service.saveProgress("h", dto));

        ArgumentCaptor<UserBookProgressEntity> cap = ArgumentCaptor.forClass(UserBookProgressEntity.class);
        verify(progressRepo).save(cap.capture());
        assertNull(cap.getValue().getKoreaderProgressPercent());
        verify(hardcoverSyncService, never()).syncProgressToHardcover(any(), any(), any());
    }

    @Test
    void saveProgress_usesMatchedFile_notBookPrimaryFile() {
        // Regression test: a book can have multiple formats (e.g. a primary EPUB plus an
        // alternate PDF). If KOReader is syncing progress against the hash of the
        // non-primary file, that file - not book.getPrimaryBookFile() - must be what
        // receives the file-level progress write.
        when(details.isSyncEnabled()).thenReturn(true);

        var book = new BookEntity();
        book.setId(20L);
        var primaryEpub = bookFile(book, 1L, BookFileType.EPUB);
        var altPdf = bookFile(book, 2L, BookFileType.PDF);
        book.setBookFiles(List.of(primaryEpub, altPdf));

        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("pdfHash")).thenReturn(Optional.of(altPdf));

        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 20L)).thenReturn(Optional.empty());
        when(fileProgressRepo.findByUserIdAndBookFileId(42L, 2L)).thenReturn(Optional.empty());

        var dto = KoreaderProgress.builder()
                .document("pdfHash").progress("120").percentage(0.3F).device("d").device_id("id").build();
        service.saveProgress("pdfHash", dto);

        ArgumentCaptor<UserBookFileProgressEntity> cap = ArgumentCaptor.forClass(UserBookFileProgressEntity.class);
        verify(fileProgressRepo).save(cap.capture());
        assertEquals(2L, cap.getValue().getBookFile().getId());
        assertEquals("120", cap.getValue().getPositionData());
        assertEquals(30.0f, cap.getValue().getProgressPercent(), 0.001f);
    }

    @Test
    void saveProgress_epubWithoutBookloreReaderSync_doesNotClobberExistingPosition() {
        // Regression test: pushing KOReader progress for an EPUB while "sync with BookLore
        // reader" is off must not erase a previously-recorded CFI position/percent in the
        // file-level progress table - only the percentage (which KOReader always sends)
        // should update.
        when(details.isSyncEnabled()).thenReturn(true);
        when(details.isSyncWithBookloreReader()).thenReturn(false);

        var book = new BookEntity();
        book.setId(30L);
        var epub = bookFile(book, 3L, BookFileType.EPUB);
        when(bookFileRepo.findByCurrentHashAndIsBookFormatTrue("epubHash")).thenReturn(Optional.of(epub));

        var user = new BookLoreUserEntity();
        user.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(progressRepo.findByUserIdAndBookId(42L, 30L)).thenReturn(Optional.empty());

        var existingFileProgress = new UserBookFileProgressEntity();
        existingFileProgress.setPositionData("epubcfi(/6/4!/4/2/1:0)");
        existingFileProgress.setPositionHref("chapter1.xhtml");
        when(fileProgressRepo.findByUserIdAndBookFileId(42L, 3L)).thenReturn(Optional.of(existingFileProgress));

        var dto = KoreaderProgress.builder()
                .document("epubHash").progress("/body/DocFragment[5]/body/p[12]/text().0")
                .percentage(0.42F).device("d").device_id("id").build();
        service.saveProgress("epubHash", dto);

        ArgumentCaptor<UserBookFileProgressEntity> cap = ArgumentCaptor.forClass(UserBookFileProgressEntity.class);
        verify(fileProgressRepo).save(cap.capture());
        assertEquals("epubcfi(/6/4!/4/2/1:0)", cap.getValue().getPositionData());
        assertEquals("chapter1.xhtml", cap.getValue().getPositionHref());
        assertEquals(42.0f, cap.getValue().getProgressPercent(), 0.001f);
    }

    @Test
    void normalizeProgressPercent_handlesNullAndRanges() throws Exception {
        Method method = KoreaderService.class.getDeclaredMethod("normalizeProgressPercent", Float.class);
        method.setAccessible(true);

        assertNull(method.invoke(service, new Object[]{null}));
        assertEquals(50.0f, (Float) method.invoke(service, 0.5f));
        assertEquals(100.0f, (Float) method.invoke(service, 1.0f));
        assertEquals(42.0f, (Float) method.invoke(service, 42.0f));
    }
}
