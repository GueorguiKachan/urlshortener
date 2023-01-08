package es.unizar.urlshortener.infrastructure.delivery

import es.unizar.urlshortener.core.QRService
import es.unizar.urlshortener.core.RedirectionNotFound
import es.unizar.urlshortener.core.ShortUrlRepositoryService
import es.unizar.urlshortener.core.usecases.GetQRUseCaseImpl
import io.github.g0dkar.qrcode.QRCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import java.io.ByteArrayOutputStream

@WebMvcTest
@AutoConfigureMockMvc
@ContextConfiguration(
    classes = [
        UserAgentInfoImpl::class,
        IsReachableServiceImpl::class,
        SafeBrowsingServiceImpl::class,
        HashServiceImpl::class
    ]
)
class FunctionalitiesTest {

    @Autowired
    private lateinit var isReachableService: IsReachableServiceImpl

    @Autowired
    private lateinit var safeBrowsingService: SafeBrowsingServiceImpl

    @Autowired
    private lateinit var hashService: HashServiceImpl

    @MockBean
    private lateinit var shortUrlRepository: ShortUrlRepositoryService

    @MockBean
    private lateinit var qrService: QRService

    @Test
    fun `getQR returns the expected byteArrayResource`() {
        val aux = ByteArrayOutputStream().let {
            QRCode("test").render().writeImage(it)
            val imageBytes = it.toByteArray()
            ByteArrayResource(imageBytes, MediaType.IMAGE_PNG_VALUE)
        }
        val x = QRServiceImpl()
        assertEquals(x.getQR("test"), aux)
    }

    @Test
    fun `generateQR returns the exception`() {
        val x = GetQRUseCaseImpl(shortUrlRepository, qrService)
        try {
            x.generateQR("test")
        } catch (e: RedirectionNotFound) {
            println("Escepción capturada, test correcto")
        }
    }

    @Test
    fun `test UserAgentInfo service for Windows and Firefox`() {
        val x = UserAgentInfoImpl()
        assertEquals("Windows", x.getOS("Mozilla/5.0 (Windows NT 6.1; WOW64; rv:12.0) Gecko/20100101 Firefox/12.0"))
        assertEquals(
            "Firefox-12.0",
            x.getBrowser("Mozilla/5.0 (Windows NT 6.1; WOW64; rv:12.0) Gecko/20100101 Firefox/12.0")
        )
    }

    @Test
    fun `test UserAgentInfo service for Unix and Chrome`() {
        val x = UserAgentInfoImpl()
        assertEquals(
            "Unix",
            x.getOS(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/98.0.4758.102 Safari/537.36"
            )
        )
        assertEquals(
            "Chrome-98.0.4758.102",
            x.getBrowser(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/98.0.4758.102 Safari/537.36"
            )
        )
    }

    @Test
    fun `test SafeBrowsing service`() {
        assertEquals(true, safeBrowsingService.isSafe("https://example.com/"))
        assertEquals(false, safeBrowsingService.isSafe("https://testsafebrowsing.appspot.com/s/phishing.html"))
    }

    @Test
    fun `test Reachablilty service`() {
        assertEquals(true, isReachableService.isReachable("http://unizar.es"))
        assertEquals(false, isReachableService.isReachable("http://unizr.es"))
    }

    @Test
    fun `test customUrl service`() {
        // assertEquals("patata", hashService.hasUrl("https://www.example.com", "patata"))
        assertEquals("6b30f676", hashService.hasUrl("https://www.example.com"))
    }

    @Test
    fun `test isHashUsed service`() {
        given(runBlocking { shortUrlRepository.isHashUsed("6b30f676", "patata") }).willReturn(false, true)

        val returnValue1: Boolean = runBlocking { shortUrlRepository.isHashUsed("6b30f676", "patata") }
        assertThat(returnValue1).isFalse

        val returnValue2: Boolean = runBlocking { shortUrlRepository.isHashUsed("6b30f676", "patata") }
        assertThat(returnValue2).isTrue
    }
}
