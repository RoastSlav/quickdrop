package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rostislav.quickdrop.repository.ShortLinkRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortCodeServiceTest {

    @Mock
    private ShortLinkRepository shortLinkRepository;

    private ShortCodeService newService() {
        return new ShortCodeService(shortLinkRepository);
    }

    @Test
    void generateUniqueCodeReturnsCorrectLength() {
        when(shortLinkRepository.existsByShareToken(any())).thenReturn(false);
        String code = newService().generateUniqueCode(5);
        assertEquals(5, code.length());
    }

    @Test
    void generateUniqueCodeUsesOnlyBase62Characters() {
        when(shortLinkRepository.existsByShareToken(any())).thenReturn(false);
        String code = newService().generateUniqueCode(7);
        assertTrue(code.matches("[0-9A-Za-z]{7}"));
    }

    @Test
    void generateUniqueCodeRetriesOnCollision() {
        when(shortLinkRepository.existsByShareToken(any())).thenReturn(true, true, false);
        String code = newService().generateUniqueCode(5);
        assertNotNull(code);
        verify(shortLinkRepository, times(3)).existsByShareToken(any());
    }

    @Test
    void generateUniqueCodeGivesUpAfterBoundedRetries() {
        when(shortLinkRepository.existsByShareToken(any())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> newService().generateUniqueCode(5));
        verify(shortLinkRepository, times(5)).existsByShareToken(any());
    }

    @Test
    void validAliasIsAccepted() {
        when(shortLinkRepository.existsByCodeIgnoreCase("my-link")).thenReturn(false);
        var verdict = newService().validateAlias("my-link");
        assertTrue(verdict.ok());
    }

    @Test
    void tooShortAliasIsRejected() {
        var verdict = newService().validateAlias("ab");
        assertFalse(verdict.ok());
    }

    @Test
    void aliasWithIllegalCharactersIsRejected() {
        var verdict = newService().validateAlias("has spaces");
        assertFalse(verdict.ok());
    }

    @Test
    void reservedWordAliasIsRejected() {
        var verdict = newService().validateAlias("admin");
        assertFalse(verdict.ok());
    }

    @Test
    void reservedWordCheckIsCaseInsensitive() {
        var verdict = newService().validateAlias("Admin");
        assertFalse(verdict.ok());
    }

    @Test
    void duplicateAliasIsRejectedCaseInsensitively() {
        when(shortLinkRepository.existsByCodeIgnoreCase("PayPal")).thenReturn(true);
        var verdict = newService().validateAlias("PayPal");
        assertFalse(verdict.ok());
    }

    @Test
    void isReservedIsCaseInsensitive() {
        ShortCodeService service = newService();
        assertTrue(service.isReserved("ADMIN"));
        assertFalse(service.isReserved("my-custom-prefix"));
    }
}
